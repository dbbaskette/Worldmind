package com.worldmind.core.nodes;

import com.worldmind.core.events.EventBus;
import com.worldmind.core.events.WorldmindEvent;
import com.worldmind.core.logging.MdcContext;
import com.worldmind.core.metrics.WorldmindMetrics;
import com.worldmind.core.model.*;
import com.worldmind.core.state.WorldmindState;
import com.worldmind.starblaster.StarblasterBridge;
import com.worldmind.starblaster.StarblasterProperties;
import com.worldmind.starblaster.WorktreeExecutionContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;

/**
 * Dispatches all directives in the current wave concurrently using virtual threads.
 * Bounded by a semaphore at maxParallel. Collects per-directive results into
 * {@code waveDispatchResults} and appends starblaster infos and errors.
 *
 * <p>Optionally integrates with {@link WorktreeExecutionContext} for worktree-based
 * parallel execution, providing each directive with an isolated working directory.
 */
@Component
public class ParallelDispatchNode {

    private static final Logger log = LoggerFactory.getLogger(ParallelDispatchNode.class);

    private final StarblasterBridge bridge;
    private final int maxParallel;
    private final EventBus eventBus;
    private final WorldmindMetrics metrics;
    private final WorktreeExecutionContext worktreeContext;
    private final boolean worktreesEnabled;

    @Autowired
    public ParallelDispatchNode(StarblasterBridge bridge, StarblasterProperties properties,
                                EventBus eventBus, WorldmindMetrics metrics,
                                @Autowired(required = false) WorktreeExecutionContext worktreeContext) {
        this(bridge, properties.getMaxParallel(), eventBus, metrics, worktreeContext, properties.isWorktreesEnabled());
    }

    ParallelDispatchNode(StarblasterBridge bridge, int maxParallel) {
        this(bridge, maxParallel, new EventBus(), null, null, false);
    }

    ParallelDispatchNode(StarblasterBridge bridge, int maxParallel, EventBus eventBus, WorldmindMetrics metrics,
                        WorktreeExecutionContext worktreeContext, boolean worktreesEnabled) {
        this.bridge = bridge;
        this.maxParallel = maxParallel;
        this.eventBus = eventBus;
        this.metrics = metrics;
        this.worktreeContext = worktreeContext;
        this.worktreesEnabled = worktreesEnabled && worktreeContext != null;
    }

    public Map<String, Object> apply(WorldmindState state) {
        var waveIds = state.waveDirectiveIds();
        var directives = state.directives();
        String retryContext = state.retryContext();
        var projectContext = state.projectContext().orElse(null);
        // Use the original user-supplied host path for centurion bind mounts.
        // projectContext.rootPath() may be /workspace (container-internal) when running in Docker.
        String userPath = state.projectPath();
        String projectPath = (userPath != null && !userPath.isBlank())
                ? userPath
                : (projectContext != null ? projectContext.rootPath() : ".");
        String missionId = state.missionId();

        if (waveIds.isEmpty()) {
            return Map.of(
                    "waveDispatchResults", List.of(),
                    "starblasters", List.of(),
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

        // Build a lookup map for directive IDs
        var directiveMap = new HashMap<String, Directive>();
        for (var d : directives) {
            directiveMap.put(d.id(), d);
        }

        var semaphore = new Semaphore(maxParallel);
        var futures = new ArrayList<CompletableFuture<DispatchOutcome>>();

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (var id : waveIds) {
                var directive = directiveMap.get(id);
                if (directive == null) {
                    log.warn("Directive {} not found in directives list, skipping", id);
                    continue;
                }

                var directiveToDispatch = applyRetryContext(directive, retryContext);

                futures.add(CompletableFuture.supplyAsync(() -> {
                    MdcContext.setDirective(missionId, directiveToDispatch.id(),
                            directiveToDispatch.centurion());
                    
                    // Acquire worktree for this directive if enabled
                    Path effectiveProjectPath = Path.of(projectPath);
                    if (worktreesEnabled) {
                        Path worktreePath = worktreeContext.acquireWorktree(missionId, directiveToDispatch.id(), "main");
                        if (worktreePath != null) {
                            effectiveProjectPath = worktreePath;
                            log.info("Using worktree {} for directive {}", worktreePath, directiveToDispatch.id());
                        } else {
                            log.warn("Could not acquire worktree for {} — using shared project path", directiveToDispatch.id());
                        }
                    }
                    
                    final Path finalProjectPath = effectiveProjectPath;
                    
                    try {
                        semaphore.acquire();
                        try {
                            log.info("Dispatching directive {} [{}]: {}",
                                    directiveToDispatch.id(), directiveToDispatch.centurion(),
                                    directiveToDispatch.description());

                            eventBus.publish(new WorldmindEvent("directive.started",
                                    missionId, directiveToDispatch.id(),
                                    Map.of("centurion", directiveToDispatch.centurion(),
                                           "description", directiveToDispatch.description()),
                                    Instant.now()));

                            long startMs = System.currentTimeMillis();
                            var result = bridge.executeDirective(
                                    directiveToDispatch, projectContext, finalProjectPath, state.gitRemoteUrl(), state.runtimeTag(), state.reasoningLevel());
                            long elapsedMs = System.currentTimeMillis() - startMs;
                            
                            // Commit and push worktree changes if enabled and directive succeeded
                            if (worktreesEnabled && result.directive().status() != DirectiveStatus.FAILED) {
                                boolean pushed = worktreeContext.commitAndPush(directiveToDispatch.id());
                                if (pushed) {
                                    log.info("Committed and pushed changes for directive {}", directiveToDispatch.id());
                                }
                            }
                            if (metrics != null) {
                                metrics.recordDirectiveExecution(directiveToDispatch.centurion(), elapsedMs);
                            }

                            eventBus.publish(new WorldmindEvent(
                                    result.directive().status() == DirectiveStatus.FAILED
                                            ? "directive.progress" : "directive.fulfilled",
                                    missionId, directive.id(),
                                    Map.of("status", result.directive().status().name(),
                                           "centurion", directiveToDispatch.centurion()),
                                    Instant.now()));

                            if (result.starblasterInfo() != null) {
                                eventBus.publish(new WorldmindEvent("starblaster.opened",
                                        missionId, directive.id(),
                                        Map.of("containerId", result.starblasterInfo().containerId(),
                                               "centurion", directiveToDispatch.centurion()),
                                        Instant.now()));
                            }

                            return new DispatchOutcome(
                                    new WaveDispatchResult(
                                            directive.id(), result.directive().status(),
                                            result.directive().filesAffected(),
                                            result.output(),
                                            result.directive().elapsedMs() != null ? result.directive().elapsedMs() : 0L),
                                    result.starblasterInfo(),
                                    result.directive().status() == DirectiveStatus.FAILED
                                            ? "Directive " + directive.id() + " failed: " + result.output()
                                            : null
                            );
                        } finally {
                            semaphore.release();
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return errorOutcome(directive.id(), "Interrupted: " + e.getMessage());
                    } catch (Exception e) {
                        log.error("Infrastructure error dispatching directive {}: {}",
                                directive.id(), e.getMessage());
                        return errorOutcome(directive.id(), e.getMessage());
                    } finally {
                        MdcContext.clear();
                    }
                }, executor));
            }

            // Collect all results
            var waveResults = new ArrayList<WaveDispatchResult>();
            var starblasterInfos = new ArrayList<StarblasterInfo>();
            var errors = new ArrayList<String>();

            for (var future : futures) {
                try {
                    var outcome = future.join();
                    waveResults.add(outcome.result);
                    if (outcome.starblasterInfo != null) {
                        starblasterInfos.add(outcome.starblasterInfo);
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
            updates.put("starblasters", starblasterInfos);
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

    private Directive applyRetryContext(Directive directive, String retryContext) {
        if (retryContext == null || retryContext.isEmpty()) return directive;
        String augmentedContext = (directive.inputContext() != null ? directive.inputContext() + "\n\n" : "") +
                "## Retry Context (from previous attempt)\n\n" + retryContext;
        return new Directive(
                directive.id(), directive.centurion(), directive.description(),
                augmentedContext, directive.successCriteria(), directive.dependencies(),
                DirectiveStatus.PENDING, directive.iteration(), directive.maxIterations(),
                directive.onFailure(), directive.targetFiles(), directive.filesAffected(), directive.elapsedMs()
        );
    }

    private DispatchOutcome errorOutcome(String directiveId, String errorMsg) {
        return new DispatchOutcome(
                new WaveDispatchResult(directiveId, DirectiveStatus.FAILED, List.of(), errorMsg, 0L),
                null,
                "Directive " + directiveId + " infrastructure error: " + errorMsg
        );
    }

    private record DispatchOutcome(WaveDispatchResult result, StarblasterInfo starblasterInfo, String error) {}
}
