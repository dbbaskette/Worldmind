package com.worldmind.core.nodes;

import com.worldmind.core.events.EventBus;
import com.worldmind.core.events.WorldmindEvent;
import com.worldmind.core.logging.MdcContext;
import com.worldmind.core.metrics.WorldmindMetrics;
import com.worldmind.core.model.*;
import com.worldmind.core.state.WorldmindState;
import com.worldmind.sandbox.AgentDispatcher;
import com.worldmind.sandbox.SandboxProperties;
import com.worldmind.sandbox.WorktreeExecutionContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;

/**
 * Dispatches all tasks in the current wave concurrently using virtual threads.
 * Bounded by a semaphore at maxParallel. Collects per-task results into
 * {@code waveDispatchResults} and appends sandbox infos and errors.
 *
 * <p>Optionally integrates with {@link WorktreeExecutionContext} for worktree-based
 * parallel execution, providing each task with an isolated working directory.
 */
@Component
public class ParallelDispatchNode {

    private static final Logger log = LoggerFactory.getLogger(ParallelDispatchNode.class);

    private final AgentDispatcher bridge;
    private final int maxParallel;
    private final EventBus eventBus;
    private final WorldmindMetrics metrics;
    private final WorktreeExecutionContext worktreeContext;
    private final boolean worktreesEnabled;

    @Autowired
    public ParallelDispatchNode(AgentDispatcher bridge, SandboxProperties properties,
                                EventBus eventBus, WorldmindMetrics metrics,
                                @Autowired(required = false) WorktreeExecutionContext worktreeContext) {
        this(bridge, properties.getMaxParallel(), eventBus, metrics, worktreeContext, properties.isWorktreesEnabled());
    }

    ParallelDispatchNode(AgentDispatcher bridge, int maxParallel) {
        this(bridge, maxParallel, new EventBus(), null, null, false);
    }

    ParallelDispatchNode(AgentDispatcher bridge, int maxParallel, EventBus eventBus, WorldmindMetrics metrics,
                        WorktreeExecutionContext worktreeContext, boolean worktreesEnabled) {
        this.bridge = bridge;
        this.maxParallel = maxParallel;
        this.eventBus = eventBus;
        this.metrics = metrics;
        this.worktreeContext = worktreeContext;
        this.worktreesEnabled = worktreesEnabled && worktreeContext != null;
    }

    public Map<String, Object> apply(WorldmindState state) {
        var waveIds = state.waveTaskIds();
        var tasks = state.tasks();
        String retryContext = state.retryContext();
        var projectContext = state.projectContext().orElse(null);
        // Use the original user-supplied host path for agent bind mounts.
        // projectContext.rootPath() may be /workspace (container-internal) when running in Docker.
        String userPath = state.projectPath();
        String projectPath = (userPath != null && !userPath.isBlank())
                ? userPath
                : (projectContext != null ? projectContext.rootPath() : ".");
        String missionId = state.missionId();

        if (waveIds.isEmpty()) {
            return Map.of(
                    "waveDispatchResults", List.of(),
                    "sandboxes", List.of(),
                    "status", MissionStatus.EXECUTING.name()
            );
        }

        // Initialize mission workspace if worktrees are enabled and this is the first wave
        if (worktreesEnabled && state.waveCount() == 1) {
            String gitUrl = state.gitRemoteUrl();
            if (gitUrl != null && !gitUrl.isBlank()) {
                Path workspace = worktreeContext.createMissionWorkspace(missionId, gitUrl);
                if (workspace != null) {
                    log.info("Created mission workspace for {} at {}", missionId, workspace);
                } else {
                    log.warn("Failed to create mission workspace — falling back to standard execution");
                }
            }
        }

        // Build a lookup map for task IDs
        var taskMap = new HashMap<String, Task>();
        for (var d : tasks) {
            taskMap.put(d.id(), d);
        }

        var semaphore = new Semaphore(maxParallel);
        var futures = new ArrayList<CompletableFuture<DispatchOutcome>>();

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (var id : waveIds) {
                var task = taskMap.get(id);
                if (task == null) {
                    log.warn("Task {} not found in tasks list, skipping", id);
                    continue;
                }

                var taskToDispatch = applyRetryContext(task, retryContext);

                futures.add(CompletableFuture.supplyAsync(() -> {
                    MdcContext.setTask(missionId, taskToDispatch.id(),
                            taskToDispatch.agent());
                    
                    // Acquire worktree for this task if enabled
                    Path effectiveProjectPath = Path.of(projectPath);
                    if (worktreesEnabled) {
                        Path worktreePath = worktreeContext.acquireWorktree(missionId, taskToDispatch.id(), "main");
                        if (worktreePath != null) {
                            effectiveProjectPath = worktreePath;
                            log.info("Using worktree {} for task {}", worktreePath, taskToDispatch.id());
                        } else {
                            log.warn("Could not acquire worktree for {} — using shared project path", taskToDispatch.id());
                        }
                    }
                    
                    final Path finalProjectPath = effectiveProjectPath;
                    
                    try {
                        semaphore.acquire();
                        try {
                            log.info("Dispatching task {} [{}]: {}",
                                    taskToDispatch.id(), taskToDispatch.agent(),
                                    taskToDispatch.description());

                            eventBus.publish(new WorldmindEvent("task.started",
                                    missionId, taskToDispatch.id(),
                                    Map.of("agent", taskToDispatch.agent(),
                                           "description", taskToDispatch.description()),
                                    Instant.now()));

                            long startMs = System.currentTimeMillis();
                            var result = bridge.executeTask(
                                    taskToDispatch, projectContext, finalProjectPath, state.gitRemoteUrl(), state.runtimeTag(), state.reasoningLevel());
                            long elapsedMs = System.currentTimeMillis() - startMs;
                            
                            // Commit and push worktree changes if enabled and task succeeded
                            if (worktreesEnabled && result.task().status() != TaskStatus.FAILED) {
                                boolean pushed = worktreeContext.commitAndPush(taskToDispatch.id());
                                if (pushed) {
                                    log.info("Committed and pushed changes for task {}", taskToDispatch.id());
                                }
                            }
                            if (metrics != null) {
                                metrics.recordTaskExecution(taskToDispatch.agent(), elapsedMs);
                            }

                            eventBus.publish(new WorldmindEvent(
                                    result.task().status() == TaskStatus.FAILED
                                            ? "task.progress" : "task.fulfilled",
                                    missionId, task.id(),
                                    Map.of("status", result.task().status().name(),
                                           "agent", taskToDispatch.agent()),
                                    Instant.now()));

                            if (result.sandboxInfo() != null) {
                                eventBus.publish(new WorldmindEvent("sandbox.opened",
                                        missionId, task.id(),
                                        Map.of("containerId", result.sandboxInfo().containerId(),
                                               "agent", taskToDispatch.agent()),
                                        Instant.now()));
                            }

                            return new DispatchOutcome(
                                    new WaveDispatchResult(
                                            task.id(), result.task().status(),
                                            result.task().filesAffected(),
                                            result.output(),
                                            result.task().elapsedMs() != null ? result.task().elapsedMs() : 0L),
                                    result.sandboxInfo(),
                                    result.task().status() == TaskStatus.FAILED
                                            ? "Task " + task.id() + " failed: " + result.output()
                                            : null
                            );
                        } finally {
                            semaphore.release();
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return errorOutcome(task.id(), "Interrupted: " + e.getMessage());
                    } catch (Exception e) {
                        log.error("Infrastructure error dispatching task {}: {}",
                                task.id(), e.getMessage());
                        return errorOutcome(task.id(), e.getMessage());
                    } finally {
                        MdcContext.clear();
                    }
                }, executor));
            }

            // Collect all results
            var waveResults = new ArrayList<WaveDispatchResult>();
            var sandboxInfos = new ArrayList<SandboxInfo>();
            var errors = new ArrayList<String>();

            for (var future : futures) {
                try {
                    var outcome = future.join();
                    waveResults.add(outcome.result);
                    if (outcome.sandboxInfo != null) {
                        sandboxInfos.add(outcome.sandboxInfo);
                    }
                    if (outcome.error != null) {
                        errors.add(outcome.error);
                    }
                } catch (CompletionException e) {
                    log.error("Unexpected error collecting dispatch result", e);
                }
            }

            var updates = new HashMap<String, Object>();
            updates.put("waveDispatchResults", waveResults);
            updates.put("sandboxes", sandboxInfos);
            updates.put("status", MissionStatus.EXECUTING.name());
            if (!errors.isEmpty()) {
                updates.put("errors", errors);
            }
            // Clear retryContext after consuming
            if (retryContext != null && !retryContext.isEmpty()) {
                updates.put("retryContext", "");
            }
            return updates;
        }
    }

    private Task applyRetryContext(Task task, String retryContext) {
        if (retryContext == null || retryContext.isEmpty()) return task;
        String augmentedContext = (task.inputContext() != null ? task.inputContext() + "\n\n" : "") +
                "## Retry Context (from previous attempt)\n\n" + retryContext;
        return new Task(
                task.id(), task.agent(), task.description(),
                augmentedContext, task.successCriteria(), task.dependencies(),
                TaskStatus.PENDING, task.iteration(), task.maxIterations(),
                task.onFailure(), task.targetFiles(), task.filesAffected(), task.elapsedMs()
        );
    }

    private DispatchOutcome errorOutcome(String taskId, String errorMsg) {
        return new DispatchOutcome(
                new WaveDispatchResult(taskId, TaskStatus.FAILED, List.of(), errorMsg, 0L),
                null,
                "Task " + taskId + " infrastructure error: " + errorMsg
        );
    }

    private record DispatchOutcome(WaveDispatchResult result, SandboxInfo sandboxInfo, String error) {}
}
