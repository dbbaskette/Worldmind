package com.worldmind.core.nodes;

import com.worldmind.core.events.EventBus;
import com.worldmind.core.events.WorldmindEvent;
import com.worldmind.core.logging.MdcContext;
import com.worldmind.core.metrics.WorldmindMetrics;
import com.worldmind.core.model.*;
import com.worldmind.core.scheduler.OscillationDetector;
import com.worldmind.core.quality_gate.QualityGateEvaluationService;
import com.worldmind.core.state.WorldmindState;
import com.worldmind.sandbox.InstructionBuilder;
import com.worldmind.sandbox.AgentDispatcher;
import com.worldmind.sandbox.cf.CloudFoundryProperties;
import com.worldmind.sandbox.cf.GitWorkspaceManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.regex.Pattern;

/**
 * Evaluates all tasks in the current wave after dispatch.
 * For CODER tasks: runs TESTER + REVIEWER quality gates and evaluates the quality_gate.
 * For non-CODER tasks: auto-passes them.
 * 
 * <p>After evaluating all tasks in a wave, merges passed CODER branches into main
 * so the next wave can build on prior work. This prevents merge conflicts when
 * multiple tasks touch the same files across waves.
 */
@Component
public class EvaluateWaveNode {

    private static final Logger log = LoggerFactory.getLogger(EvaluateWaveNode.class);

    // Pre-compiled patterns for extractServiceName (avoid recompiling on every call)
    private static final Pattern SERVICE_NOT_FOUND_PATTERN =
            Pattern.compile("(?i)could not find service\\s+(\\S+)");
    private static final Pattern SERVICE_INSTANCE_NOT_FOUND_PATTERN =
            Pattern.compile("(?i)service instance\\s+'?(\\S+?)'?\\s+not found");

    private final AgentDispatcher bridge;
    private final QualityGateEvaluationService quality_gateService;
    private final EventBus eventBus;
    private final WorldmindMetrics metrics;
    private final OscillationDetector oscillationDetector;
    
    // Optional dependencies for per-wave merge (only available in CF mode)
    private final GitWorkspaceManager gitWorkspaceManager;
    private final CloudFoundryProperties cfProperties;

    @Autowired
    public EvaluateWaveNode(AgentDispatcher bridge, QualityGateEvaluationService quality_gateService,
                            EventBus eventBus, WorldmindMetrics metrics,
                            OscillationDetector oscillationDetector,
                            @Autowired(required = false) GitWorkspaceManager gitWorkspaceManager,
                            @Autowired(required = false) CloudFoundryProperties cfProperties) {
        this.bridge = bridge;
        this.quality_gateService = quality_gateService;
        this.eventBus = eventBus;
        this.metrics = metrics;
        this.oscillationDetector = oscillationDetector;
        this.gitWorkspaceManager = gitWorkspaceManager;
        this.cfProperties = cfProperties;
    }

    public Map<String, Object> apply(WorldmindState state) {
        MdcContext.setWave(state.missionId(), state.waveCount());
        try {
            var waveIds = state.waveTaskIds();
            var tasks = state.tasks();
            var waveResults = state.waveDispatchResults();
            var projectContext = state.projectContext().orElse(null);
            // Use the original user-supplied host path for agent bind mounts.
            String userPath = state.projectPath();
            String projectPath = (userPath != null && !userPath.isBlank())
                    ? userPath
                    : (projectContext != null ? projectContext.rootPath() : ".");

            // Build lookup maps
            var taskMap = new HashMap<String, Task>();
            for (var d : tasks) taskMap.put(d.id(), d);

            var resultMap = new HashMap<String, WaveDispatchResult>();
            for (var r : waveResults) resultMap.put(r.taskId(), r);

            var completedIds = new ArrayList<String>();
            var retryingIds = new ArrayList<String>();  // Tasks to retry after merge conflict
            var updatedTasks = new ArrayList<Task>();
            var testResultsList = new ArrayList<TestResult>();
            var reviewFeedbackList = new ArrayList<ReviewFeedback>();
            var sandboxInfos = new ArrayList<SandboxInfo>();
            var errors = new ArrayList<String>();
            String retryContext = null;
            MissionStatus missionStatus = null;
            String deploymentUrl = null;

            for (var id : waveIds) {
                var task = taskMap.get(id);
                var dispatchResult = resultMap.get(id);

                if (task == null || dispatchResult == null) {
                    log.warn("Missing task or dispatch result for {}, skipping evaluation", id);
                    continue;
                }

                // DEPLOYER task — run pre-deploy build verification, then evaluate deployment
                if ("DEPLOYER".equalsIgnoreCase(task.agent())) {
                    // Pre-deploy build verification: compile the merged code on main before deploying
                    boolean buildVerified = runPreDeployVerification(state, projectContext, projectPath, sandboxInfos);
                    if (!buildVerified) {
                        log.error("Pre-deploy build verification FAILED for mission {} — skipping deployment", state.missionId());
                        errors.add("Pre-deploy build verification failed: merged code does not compile or manifest is invalid");
                        updatedTasks.add(withResult(task, dispatchResult, TaskStatus.FAILED));
                        missionStatus = MissionStatus.FAILED;
                        continue;
                    }

                    var deployResult = evaluateDeployerResult(id, task, dispatchResult, state,
                            completedIds, updatedTasks, errors);
                    if (deployResult.retryContext != null) retryContext = deployResult.retryContext;
                    if (deployResult.missionFailed) missionStatus = MissionStatus.FAILED;
                    if (deployResult.deploymentUrl != null) deploymentUrl = deployResult.deploymentUrl;
                    continue;
                }

                // Non-CODER tasks auto-pass (RESEARCHER, TESTER, REVIEWER, etc.)
                if (!"CODER".equalsIgnoreCase(task.agent())) {
                    log.info("Auto-passing non-CODER task {} [{}]", id, task.agent());
                    completedIds.add(id);
                    updatedTasks.add(withResult(task, dispatchResult, TaskStatus.PASSED));
                    continue;
                }

                // CODER task that failed at dispatch — apply failure strategy directly
                if (dispatchResult.status() == TaskStatus.FAILED) {
                    log.info("CODER task {} failed at dispatch — applying failure strategy", id);
                    String reason = "CODER task failed during execution";
                    FailureStrategy action = task.onFailure() != null ? task.onFailure() : FailureStrategy.RETRY;
                    if (action == FailureStrategy.RETRY && task.iteration() >= task.maxIterations()) {
                        action = FailureStrategy.ESCALATE;
                    }
                    var result = handleFailure(id, task, dispatchResult, action, reason,
                            enrichFailureContext(task.inputContext(), reason),
                            completedIds, updatedTasks, errors);
                    if (result.retryContext != null) retryContext = result.retryContext;
                    if (result.missionFailed) missionStatus = MissionStatus.FAILED;
                    continue;
                }

                // CODER task that passed dispatch — run TESTER + REVIEWER
                var allFileChanges = dispatchResult.filesAffected() != null ? dispatchResult.filesAffected() : List.<FileRecord>of();
                
                // Filter out Worldmind diagnostic files — only count actual code changes
                var fileChanges = allFileChanges.stream()
                        .filter(f -> !f.path().startsWith(".worldmind"))
                        .filter(f -> !f.path().contains("/goose-logs/"))
                        .filter(f -> !f.path().endsWith(".log"))
                        .filter(f -> !f.path().endsWith(".jsonl"))
                        .toList();

                // CODER must produce code — if no real files were affected, retry.
                if (fileChanges.isEmpty()) {
                    String outputSnippet = summarizeAgentOutput(dispatchResult.output());
                    log.warn("CODER task {} produced no code files (only logs) — will retry. Agent output: {}",
                            id, outputSnippet);

                    eventBus.publish(new WorldmindEvent("task.failed",
                            state.missionId(), id,
                            Map.of("reason", "CODER produced no code changes",
                                   "agentOutput", outputSnippet),
                            Instant.now()));

                    String reason = "CODER task produced no code files. You MUST create/modify actual source files. Agent output:\n" + outputSnippet;
                    var result = handleFailure(id, task, dispatchResult, FailureStrategy.RETRY, reason,
                            enrichFailureContext(task.inputContext(), reason),
                            completedIds, updatedTasks, errors);
                    if (result.retryContext != null) retryContext = result.retryContext;
                    if (result.missionFailed) missionStatus = MissionStatus.FAILED;
                    continue;
                }

                // Step 1: TESTER (skipped when skipPerTaskTests is enabled)
                TestResult testResult;
                if (state.skipPerTaskTests()) {
                    log.info("Skipping per-task TESTER for {} (skipPerTaskTests=true)", id);
                    testResult = new TestResult(id, true, 0, 0, "Per-task tests skipped by mission config", 0L);
                } else {
                    eventBus.publish(new WorldmindEvent("task.phase",
                            state.missionId(), id,
                            Map.of("phase", "TESTER"), Instant.now()));
                    try {
                        var testerTask = createTesterTask(task, fileChanges);
                        log.info("Dispatching TESTER for task {} ({} file changes)", id, fileChanges.size());
                        var testerResult = bridge.executeTask(testerTask, projectContext, Path.of(projectPath), state.gitRemoteUrl(), state.runtimeTag(), state.reasoningLevel());
                        sandboxInfos.add(testerResult.sandboxInfo());
                        if (testerResult.task().status() == TaskStatus.FAILED) {
                            log.warn("TESTER for {} failed ({}ms): {}", id,
                                    testerResult.task().elapsedMs(), testerResult.output());
                        }
                        testResult = quality_gateService.parseTestOutput(id, testerResult.output(),
                                testerResult.task().elapsedMs() != null ? testerResult.task().elapsedMs() : 0L);
                    } catch (Exception e) {
                        log.error("TESTER dispatch failed for {}: {}", id, e.getMessage());
                        testResult = new TestResult(id, false, 0, 0,
                                "TESTER infrastructure error: " + e.getMessage(), 0L);
                    }
                }

                // Step 2: REVIEWER
                eventBus.publish(new WorldmindEvent("task.phase",
                        state.missionId(), id,
                        Map.of("phase", "REVIEWER"), Instant.now()));
                ReviewFeedback reviewFeedback;
                try {
                    var reviewerTask = createReviewerTask(task, fileChanges);
                    log.info("Dispatching REVIEWER for task {} ({} file changes)", id, fileChanges.size());
                    var reviewerResult = bridge.executeTask(reviewerTask, projectContext, Path.of(projectPath), state.gitRemoteUrl(), state.runtimeTag(), state.reasoningLevel());
                    sandboxInfos.add(reviewerResult.sandboxInfo());
                    if (reviewerResult.task().status() == TaskStatus.FAILED) {
                        log.warn("REVIEWER for {} failed ({}ms): {}", id,
                                reviewerResult.task().elapsedMs(), reviewerResult.output());
                    }
                    reviewFeedback = quality_gateService.parseReviewOutput(id, reviewerResult.output());
                } catch (Exception e) {
                    log.error("REVIEWER dispatch failed for {}: {}", id, e.getMessage());
                    reviewFeedback = new ReviewFeedback(id, false,
                            "REVIEWER infrastructure error: " + e.getMessage(),
                            List.of(e.getMessage()), List.of(), 0);
                }

                testResultsList.add(testResult);
                reviewFeedbackList.add(reviewFeedback);

                // Step 3: Evaluate quality_gate
                eventBus.publish(new WorldmindEvent("task.phase",
                        state.missionId(), id,
                        Map.of("phase", "QUALITY_GATE"), Instant.now()));
                var quality_gateDecision = quality_gateService.evaluateQualityGate(testResult, reviewFeedback, task);
                log.info("QualityGate evaluation for {}: {} — {}", id,
                        quality_gateDecision.quality_gateGranted() ? "GRANTED" : "DENIED", quality_gateDecision.reason());
                metrics.recordQualityGateResult(quality_gateDecision.quality_gateGranted());

                if (quality_gateDecision.quality_gateGranted()) {
                    completedIds.add(id);
                    updatedTasks.add(withResult(task, dispatchResult, TaskStatus.PASSED));
                    eventBus.publish(new WorldmindEvent("quality_gate.granted",
                            state.missionId(), id,
                            Map.of("reason", quality_gateDecision.reason(),
                                   "score", reviewFeedback.score(),
                                   "summary", reviewFeedback.summary() != null ? reviewFeedback.summary() : ""),
                            Instant.now()));
                } else {
                    eventBus.publish(new WorldmindEvent("quality_gate.denied",
                            state.missionId(), id,
                            Map.of("reason", quality_gateDecision.reason(),
                                   "action", quality_gateDecision.action() != null ? quality_gateDecision.action().name() : "UNKNOWN",
                                   "score", reviewFeedback.score(),
                                   "summary", reviewFeedback.summary() != null ? reviewFeedback.summary() : ""),
                            Instant.now()));
                    var result = handleFailure(id, task, dispatchResult,
                            quality_gateDecision.action(), quality_gateDecision.reason(),
                            enrichRetryContext(task.inputContext(), reviewFeedback),
                            completedIds, updatedTasks, errors);
                    if (result.retryContext != null) retryContext = result.retryContext;
                    if (result.missionFailed) missionStatus = MissionStatus.FAILED;
                }
            }

            // Per-wave merge: merge passed CODER/REFACTORER branches into main
            List<String> passedCoderIds = updatedTasks.stream()
                    .filter(d -> d.status() == TaskStatus.PASSED)
                    .filter(d -> "CODER".equalsIgnoreCase(d.agent()) || "REFACTORER".equalsIgnoreCase(d.agent()))
                    .map(Task::id)
                    .sorted()
                    .toList();
            
            if (!passedCoderIds.isEmpty() && gitWorkspaceManager != null && cfProperties != null) {
                var mergeOutcome = mergeWaveAndHandleConflicts(
                        state, passedCoderIds, taskMap, updatedTasks, completedIds, retryingIds, errors);
                if (mergeOutcome.missionFailed) missionStatus = MissionStatus.FAILED;
            }

            // Build state updates
            var updates = new HashMap<String, Object>();
            if (!completedIds.isEmpty()) updates.put("completedTaskIds", completedIds);
            if (!retryingIds.isEmpty()) updates.put("retryingTaskIds", retryingIds);
            if (!updatedTasks.isEmpty()) updates.put("tasks", updatedTasks);
            if (!testResultsList.isEmpty()) updates.put("testResults", testResultsList);
            if (!reviewFeedbackList.isEmpty()) updates.put("reviewFeedback", reviewFeedbackList);
            if (!sandboxInfos.isEmpty()) updates.put("sandboxes", sandboxInfos);
            if (!errors.isEmpty()) updates.put("errors", errors);
            if (retryContext != null) updates.put("retryContext", retryContext);
            if (deploymentUrl != null) updates.put("deploymentUrl", deploymentUrl);
            if (missionStatus != null) updates.put("status", missionStatus.name());

            eventBus.publish(new WorldmindEvent("wave.completed",
                    state.missionId(), null,
                    Map.of("waveNumber", state.waveCount(),
                           "completed", completedIds.size(),
                           "failed", errors.size(),
                           "retrying", retryingIds.size()),
                    Instant.now()));

            return updates;
        } finally {
            MdcContext.clear();
        }
    }

    private Task withResult(Task d, WaveDispatchResult result, TaskStatus status) {
        // Increment iteration count since this task was executed
        int newIteration = d.iteration() + 1;
        return new Task(
                d.id(), d.agent(), d.description(),
                d.inputContext(), d.successCriteria(), d.dependencies(),
                status, newIteration, d.maxIterations(),
                d.onFailure(), d.targetFiles(),
                result != null && result.filesAffected() != null ? result.filesAffected() : d.filesAffected(),
                result != null ? result.elapsedMs() : d.elapsedMs()
        );
    }

    /**
     * Merges passed CODER/REFACTORER branches into main after the wave completes.
     * If merge conflicts occur, marks conflicted tasks for retry with enriched context.
     */
    private FailureOutcome mergeWaveAndHandleConflicts(
            WorldmindState state, List<String> passedCoderIds,
            Map<String, Task> taskMap, List<Task> updatedTasks,
            List<String> completedIds, List<String> retryingIds, List<String> errors) {

        var outcome = new FailureOutcome();
        log.info("Wave {} complete: merging {} passed CODER/REFACTORER branches into main",
                state.waveCount(), passedCoderIds.size());
        try {
            var mergeResult = gitWorkspaceManager.mergeWaveBranches(
                    passedCoderIds, cfProperties.getGitToken(), state.gitRemoteUrl());

            if (mergeResult.hasConflicts()) {
                log.warn("Wave merge had {} conflicts: {} — will retry on updated main",
                        mergeResult.conflictedIds().size(), mergeResult.conflictedIds());

                String mergedFilesContext = buildMergedFilesContext(mergeResult.mergedIds(), taskMap);
                outcome.missionFailed = resetConflictedTasks(
                        mergeResult.conflictedIds(), mergedFilesContext,
                        updatedTasks, completedIds, retryingIds, errors);

                errors.add("Merge conflicts on " + mergeResult.conflictedIds()
                        + " — retrying on updated main in next wave");
            }

            eventBus.publish(new WorldmindEvent("wave.merged",
                    state.missionId(), null,
                    Map.of("merged", mergeResult.mergedIds(),
                           "conflicted", mergeResult.conflictedIds(),
                           "waveNumber", state.waveCount()),
                    Instant.now()));
        } catch (Exception e) {
            log.error("Failed to merge wave branches: {}", e.getMessage(), e);
            errors.add("Wave merge failed: " + e.getMessage());
        }
        return outcome;
    }

    private String buildMergedFilesContext(List<String> mergedIds, Map<String, Task> taskMap) {
        if (mergedIds.isEmpty()) return "";
        var sb = new StringBuilder("\n\nFILES ALREADY IN MAIN from merged tasks:\n");
        for (String mergedId : mergedIds) {
            Task mergedTask = taskMap.get(mergedId);
            if (mergedTask == null || mergedTask.filesAffected() == null || mergedTask.filesAffected().isEmpty()) {
                continue;
            }
            sb.append("- ").append(mergedId).append(": ");
            sb.append(mergedTask.filesAffected().stream()
                    .map(FileRecord::path)
                    .collect(java.util.stream.Collectors.joining(", ")));
            sb.append("\n");
        }
        sb.append("\nDO NOT recreate these files from scratch. ");
        sb.append("Read them from the filesystem and extend/modify as needed.");
        return sb.toString();
    }

    /**
     * Resets conflicted tasks to PENDING for retry, or marks them FAILED if retries exhausted.
     * @return true if any task was escalated (mission should fail)
     */
    private boolean resetConflictedTasks(List<String> conflictedIds, String mergedFilesContext,
                                         List<Task> updatedTasks, List<String> completedIds,
                                         List<String> retryingIds, List<String> errors) {
        boolean anyEscalated = false;
        for (String conflictedId : conflictedIds) {
            for (int i = 0; i < updatedTasks.size(); i++) {
                Task d = updatedTasks.get(i);
                if (!d.id().equals(conflictedId)) continue;

                int nextIteration = d.iteration() + 1;
                if (nextIteration > d.maxIterations()) {
                    log.warn("Task {} exhausted merge conflict retries ({}/{}), escalating",
                            conflictedId, d.iteration(), d.maxIterations());
                    anyEscalated = true;
                    errors.add("Task " + conflictedId + " escalated: merge conflicts after "
                            + d.iteration() + " attempts");
                    updatedTasks.set(i, new Task(
                            d.id(), d.agent(), d.description(),
                            d.inputContext(), d.successCriteria(), d.dependencies(),
                            TaskStatus.FAILED, d.iteration(), d.maxIterations(),
                            d.onFailure(), d.targetFiles(), d.filesAffected(), d.elapsedMs()));
                    break;
                }

                retryingIds.add(conflictedId);
                completedIds.remove(conflictedId);

                List<String> actualTargets = (d.filesAffected() != null && !d.filesAffected().isEmpty())
                        ? d.filesAffected().stream().map(FileRecord::path).toList()
                        : d.targetFiles();

                String base = d.inputContext() != null ? d.inputContext() : "";
                String enhancedContext = "MERGE CONFLICT RETRY: Your previous branch conflicted with main.\n"
                        + "You are starting fresh from the CURRENT main branch (your old branch was deleted).\n"
                        + "Review existing files before creating new ones to avoid duplicating work."
                        + mergedFilesContext + "\n\n" + base;

                updatedTasks.set(i, new Task(
                        d.id(), d.agent(), d.description(),
                        enhancedContext, d.successCriteria(), d.dependencies(),
                        TaskStatus.PENDING, nextIteration, d.maxIterations(),
                        d.onFailure(), actualTargets, List.of(), null));
                log.info("Reset {} to PENDING (iteration {}) for retry on updated main (target files: {})",
                        conflictedId, nextIteration, actualTargets);
                break;
            }
        }
        return anyEscalated;
    }

    /**
     * Applies failure strategy and updates the shared collections (completedIds, updatedTasks, errors).
     * Returns the outcome so the caller can propagate retryContext and missionFailed.
     *
     * @param enhancedContext pre-built context for the retry attempt (failure-specific or review-based)
     */
    private FailureOutcome handleFailure(String id, Task task, WaveDispatchResult dispatchResult,
                                         FailureStrategy action, String reason, String enhancedContext,
                                         List<String> completedIds, List<Task> updatedTasks, List<String> errors) {
        var outcome = applyFailureStrategy(id, task, action, reason);
        completedIds.addAll(outcome.completedIds);

        if (!outcome.completedIds.isEmpty()) {
            updatedTasks.add(withResult(task, dispatchResult, TaskStatus.SKIPPED));
        } else if (outcome.missionFailed) {
            updatedTasks.add(withResult(task, dispatchResult, TaskStatus.FAILED));
        } else if (outcome.retryContext != null) {
            int nextIteration = task.iteration() + 1;
            updatedTasks.add(new Task(
                    task.id(), task.agent(), task.description(),
                    enhancedContext, task.successCriteria(), task.dependencies(),
                    TaskStatus.PENDING, nextIteration, task.maxIterations(),
                    task.onFailure(), task.targetFiles(), List.of(), null));
            log.info("Reset {} to PENDING (iteration {}/{}) for retry", id, nextIteration, task.maxIterations());
        }

        errors.addAll(outcome.errors);
        return outcome;
    }

    private FailureOutcome applyFailureStrategy(String id, Task task,
                                                  FailureStrategy action, String reason) {
        if (action == FailureStrategy.RETRY) {
            oscillationDetector.recordFailure(id, reason);
            int failureCount = oscillationDetector.failureCount(id);
            if (failureCount >= task.maxIterations() || task.iteration() >= task.maxIterations()) {
                log.warn("Max retries ({}) exhausted for task {} — escalating", task.maxIterations(), id);
                action = FailureStrategy.ESCALATE;
            } else if (oscillationDetector.isOscillating(id)) {
                log.warn("Oscillation detected for task {} — overriding RETRY to ESCALATE", id);
                action = FailureStrategy.ESCALATE;
            }
        }
        var outcome = new FailureOutcome();
        switch (action) {
            case RETRY -> {
                outcome.retryContext = "Retry for " + id + ": " + reason;
                log.info("Retrying task {} — will re-enter next wave", id);
            }
            case SKIP -> {
                outcome.completedIds.add(id);
                log.info("Skipping task {} — marking complete", id);
            }
            case ESCALATE -> {
                outcome.missionFailed = true;
                outcome.errors.add("Task " + id + " escalated: " + reason);
                log.warn("Task {} ESCALATED — mission failed", id);
                metrics.incrementEscalations(reason);
            }
            case REPLAN -> {
                outcome.missionFailed = true;
                outcome.errors.add("Task " + id + " requires replanning: " + reason);
                log.warn("Task {} requires REPLAN — mission failed", id);
            }
        }
        return outcome;
    }

    /**
     * Appends failure details to task context so the agent understands what went wrong.
     * Used when a task fails before reaching the quality gate (e.g., no code files produced).
     */
    private String enrichFailureContext(String baseContext, String failureReason) {
        var sb = new StringBuilder(baseContext != null ? baseContext : "");
        sb.append("\n\n## PREVIOUS ATTEMPT FAILED\n\n");
        sb.append("Your previous attempt failed. You MUST address the following:\n\n");
        sb.append(failureReason).append("\n\n");
        sb.append("**CRITICAL**: You must create or modify actual source code files. ");
        sb.append("Do not just analyze or discuss — write the code.\n");
        return sb.toString();
    }

    /**
     * Appends reviewer review details to retry context so CODER knows what to fix.
     */
    private static final int MAX_RETRY_ISSUES = 3;
    private static final int MAX_RETRY_SUGGESTIONS = 3;

    private String enrichRetryContext(String baseContext, ReviewFeedback feedback) {
        var sb = new StringBuilder(baseContext);
        sb.append("\n\n## Review Feedback (score: ").append(feedback.score()).append("/10)\n\n");
        sb.append("Focus on the most critical issues below. Do NOT try to address every suggestion — ");
        sb.append("fix the core problems first and keep your changes minimal and correct.\n\n");

        if (feedback.summary() != null && !feedback.summary().isBlank()) {
            sb.append("**Summary:** ").append(feedback.summary()).append("\n\n");
        }
        if (feedback.issues() != null && !feedback.issues().isEmpty()) {
            var issues = feedback.issues().size() > MAX_RETRY_ISSUES
                    ? feedback.issues().subList(0, MAX_RETRY_ISSUES) : feedback.issues();
            sb.append("**Top issues to fix:**\n");
            for (var issue : issues) {
                sb.append("- ").append(issue).append("\n");
            }
            sb.append("\n");
        }
        if (feedback.suggestions() != null && !feedback.suggestions().isEmpty()) {
            var suggestions = feedback.suggestions().size() > MAX_RETRY_SUGGESTIONS
                    ? feedback.suggestions().subList(0, MAX_RETRY_SUGGESTIONS) : feedback.suggestions();
            sb.append("**Suggestions (nice-to-have, not required):**\n");
            for (var suggestion : suggestions) {
                sb.append("- ").append(suggestion).append("\n");
            }
        }
        return sb.toString();
    }

    /**
     * Runs a pre-deploy build verification on the merged main branch.
     * Dispatches a TESTER agent to compile the project and validate the manifest.
     * Returns true if the build passes, false if it fails.
     */
    private boolean runPreDeployVerification(WorldmindState state, ProjectContext projectContext,
                                             String projectPath, List<SandboxInfo> sandboxInfos) {
        String runtimeTag = state.runtimeTag();
        boolean cfDeploy = state.createCfDeployment();

        log.info("Running pre-deploy build verification (runtime={}, cfDeploy={})", runtimeTag, cfDeploy);
        eventBus.publish(new WorldmindEvent("task.phase",
                state.missionId(), "TASK-DEPLOY",
                Map.of("phase", "VERIFY_BUILD"), Instant.now()));

        String instruction = InstructionBuilder.buildFinalVerificationInstruction(runtimeTag, cfDeploy);
        var verifyTask = new Task(
                "VERIFY-BUILD", "TESTER",
                "Final integration build verification",
                instruction,
                "Project compiles and manifest is valid",
                List.of(), TaskStatus.PENDING,
                0, 1, FailureStrategy.SKIP, List.of(), List.of(), null
        );

        try {
            var result = bridge.executeTask(verifyTask, projectContext,
                    Path.of(projectPath), state.gitRemoteUrl(), runtimeTag, state.reasoningLevel());
            sandboxInfos.add(result.sandboxInfo());

            String output = result.output() != null ? result.output() : "";
            boolean buildPassed = output.contains("BUILD: PASS");
            boolean buildFailed = output.contains("BUILD: FAIL");
            boolean manifestFailed = output.contains("MANIFEST: FAIL") || output.contains("MANIFEST: MISSING");

            if (buildFailed) {
                log.error("Pre-deploy verification: BUILD FAILED\n{}", summarizeAgentOutput(output));
                return false;
            }
            if (manifestFailed) {
                log.error("Pre-deploy verification: MANIFEST INVALID\n{}", summarizeAgentOutput(output));
                return false;
            }
            if (buildPassed) {
                log.info("Pre-deploy verification: BUILD PASSED");
                return true;
            }

            // If structured output wasn't found, check if the task itself failed
            if (result.task().status() == TaskStatus.FAILED) {
                log.warn("Pre-deploy verification task failed (no structured output). Treating as build failure.");
                return false;
            }

            log.info("Pre-deploy verification completed (no explicit BUILD: PASS/FAIL in output). Proceeding with deploy.");
            return true;
        } catch (Exception e) {
            log.error("Pre-deploy verification dispatch failed: {}. Proceeding with deploy anyway.", e.getMessage());
            return true;
        }
    }

    /**
     * Evaluates DEPLOYER task results based on deployment health rather than code quality.
     * DEPLOYER does NOT go through the quality gate (no TESTER/REVIEWER needed).
     */
    private FailureOutcome evaluateDeployerResult(String id, Task task, WaveDispatchResult dispatchResult,
                                                   WorldmindState state,
                                                   List<String> completedIds, List<Task> updatedTasks,
                                                   List<String> errors) {
        // DEPLOYER that failed at dispatch — apply failure strategy
        if (dispatchResult.status() == TaskStatus.FAILED) {
            log.info("DEPLOYER task {} failed at dispatch — applying failure strategy", id);
            var diagnosis = diagnoseDeploymentFailure(dispatchResult.output());
            String reason = "Deployment failed: " + diagnosis.reason();
            FailureStrategy action = task.onFailure() != null ? task.onFailure() : FailureStrategy.RETRY;
            return handleFailure(id, task, dispatchResult, action, reason,
                    enrichDeployerRetryContext(task.inputContext(), diagnosis),
                    completedIds, updatedTasks, errors);
        }

        // Check deployment output for success/failure
        String output = dispatchResult.output() != null ? dispatchResult.output() : "";
        boolean deploymentSuccessful = isDeploymentSuccessful(output);

        if (deploymentSuccessful) {
            String url = extractDeploymentUrl(output);
            log.info("DEPLOYER task {} succeeded. Deployment URL: {}", id, url);
            completedIds.add(id);
            updatedTasks.add(withResult(task, dispatchResult, TaskStatus.PASSED));

            var outcome = new FailureOutcome();
            outcome.deploymentUrl = url;

            eventBus.publish(new WorldmindEvent("deployer.success",
                    state.missionId(), id,
                    Map.of("deploymentUrl", url != null ? url : ""),
                    Instant.now()));

            return outcome;
        } else {
            var diagnosis = diagnoseDeploymentFailure(output);
            log.warn("DEPLOYER task {} failed [{}]: {} — evaluating retry",
                    id, diagnosis.failureType(), diagnosis.reason());
            String reason = "Deployment failed: " + diagnosis.reason();
            FailureStrategy action = task.onFailure() != null ? task.onFailure() : FailureStrategy.RETRY;

            eventBus.publish(new WorldmindEvent("deployer.failed",
                    state.missionId(), id,
                    Map.of("reason", reason,
                           "failureType", diagnosis.failureType(),
                           "suggestion", diagnosis.suggestion(),
                           "output", summarizeAgentOutput(output)),
                    Instant.now()));

            return handleFailure(id, task, dispatchResult, action, reason,
                    enrichDeployerRetryContext(task.inputContext(), diagnosis),
                    completedIds, updatedTasks, errors);
        }
    }

    /**
     * Checks deployment output for success markers indicating the app started and is healthy.
     * Failure markers take precedence — if the output contains both success and failure
     * indicators (e.g., a transient "app started" followed by "CRASHED"), the deployment
     * is considered failed.
     */
    private boolean isDeploymentSuccessful(String output) {
        if (output == null || output.isBlank()) return false;
        String lower = output.toLowerCase();

        // Failure markers take precedence over success markers
        if (lower.contains("crashed")
                || lower.contains("staging error") || lower.contains("staging failed")
                || lower.contains("failed to stage")
                || lower.contains("health check timeout")
                || lower.contains("build failure") || lower.contains("build failed")
                || lower.contains("could not find service") || lower.contains("service binding failed")) {
            return false;
        }

        return lower.contains("app started")
                || lower.contains("instances running")
                || (lower.contains("requested state") && lower.contains("running"))
                || lower.contains("status: running")
                || lower.contains("push successful")
                || lower.contains("\nok\n");
    }

    /**
     * Extracts the deployment URL from cf push output.
     * Looks for route patterns like "routes: url" or "https://..." in the output.
     */
    private String extractDeploymentUrl(String output) {
        if (output == null) return null;
        // Look for route in cf push output (e.g., "routes: myapp.example.com" or "routes: app.apps.domain.com").
        // Requires at least one dot to distinguish routes from plain words.
        var routeMatcher = java.util.regex.Pattern.compile(
                "routes?:\\s*(\\S+\\.\\S+)", java.util.regex.Pattern.CASE_INSENSITIVE
        ).matcher(output);
        if (routeMatcher.find()) {
            String route = routeMatcher.group(1);
            return route.startsWith("http") ? route : "https://" + route;
        }
        // Fallback: look for explicit URL
        var urlMatcher = java.util.regex.Pattern.compile(
                "https?://\\S+\\.apps\\.\\S+", java.util.regex.Pattern.CASE_INSENSITIVE
        ).matcher(output);
        if (urlMatcher.find()) {
            return urlMatcher.group();
        }
        return null;
    }

    private record DeploymentDiagnosis(String failureType, String reason, String suggestion, String relevantLogs) {}

    /**
     * Analyzes deployment output to produce a specific failure diagnosis with actionable suggestions.
     */
    private DeploymentDiagnosis diagnoseDeploymentFailure(String output) {
        if (output == null || output.isBlank()) {
            return new DeploymentDiagnosis("UNKNOWN",
                    "No deployment output available",
                    "Check agent logs for execution errors", "");
        }
        String lower = output.toLowerCase();

        // Maven/Gradle build failures (happen before cf push)
        if (lower.contains("build failure") || lower.contains("build failed")) {
            return new DeploymentDiagnosis("BUILD_FAILURE",
                    "Maven build error — check pom.xml dependencies",
                    "Fix compilation errors or dependency issues in pom.xml/build.gradle",
                    extractRelevantLogs(output, "BUILD FAILURE", "build failed", "ERROR"));
        }

        // Service binding failures — extract service name from output
        if (lower.contains("could not find service") || lower.contains("service binding failed")
                || (lower.contains("service instance") && lower.contains("not found"))) {
            String serviceName = extractServiceName(output);
            String reason = serviceName != null
                    ? "Service '" + serviceName + "' not found — ensure it exists in the target CF space"
                    : "CF service binding failed — required service instance does not exist in the target space";
            return new DeploymentDiagnosis("SERVICE_BINDING_FAILURE",
                    reason,
                    "Pre-create the required service instance using 'cf create-service' before deploying",
                    extractRelevantLogs(output, "service", "binding", "not found"));
        }

        // CF staging failures
        if (lower.contains("staging error") || lower.contains("staging failed")
                || lower.contains("failed to stage")) {
            return new DeploymentDiagnosis("STAGING_FAILURE",
                    "CF staging error — see staging logs",
                    "Check buildpack compatibility and application configuration",
                    extractRelevantLogs(output, "staging", "error", "buildpack"));
        }

        // App crashes — require non-zero exit status to avoid matching benign "exit status: 0" lines
        if (lower.contains("crashed")
                || lower.contains("exit status 1") || lower.contains("exit status 2")
                || lower.contains("exit status 137") || lower.contains("exit status 143")) {
            boolean memoryRelated = lower.contains("out of memory") || lower.contains("oom") || lower.contains("memory");
            String reason = memoryRelated
                    ? "App crashed on start — likely out of memory"
                    : "App crashed on start — review crash logs for missing configuration or port binding issues";
            String suggestion = memoryRelated
                    ? "Increase memory allocation in manifest.yml (e.g., memory: 2G)"
                    : "Review crash logs for missing configuration or port binding issues";
            return new DeploymentDiagnosis("APP_CRASHED",
                    reason,
                    suggestion,
                    extractRelevantLogs(output, "crash", "CRASHED", "exit"));
        }

        // Health check timeout — require "health" context to avoid matching unrelated timeouts
        if (lower.contains("health check timeout")
                || (lower.contains("timed out") && lower.contains("health"))
                || (lower.contains("health check") && lower.contains("fail"))) {
            return new DeploymentDiagnosis("HEALTH_CHECK_TIMEOUT",
                    "Health check timeout — check /actuator/health endpoint",
                    "Increase health-check-timeout in manifest.yml or verify the health check endpoint is correct",
                    extractRelevantLogs(output, "health", "timeout", "check"));
        }

        return new DeploymentDiagnosis("UNKNOWN",
                "Deployment failed for an unrecognized reason",
                "Review the full deployment output for error details",
                summarizeAgentOutput(output));
    }

    /**
     * Extracts the service name from deployment output containing service binding errors.
     * Looks for error-specific patterns like "Could not find service my-db" or
     * "service instance 'my-db' not found". Returns null if no error-specific match is found,
     * so the caller can fall back to a generic CF message.
     */
    private String extractServiceName(String output) {
        if (output == null) return null;
        // Pattern: "Could not find service <name>"
        var matcher = SERVICE_NOT_FOUND_PATTERN.matcher(output);
        if (matcher.find()) return matcher.group(1);
        // Pattern: "service instance '<name>'" or "service instance <name>"
        matcher = SERVICE_INSTANCE_NOT_FOUND_PATTERN.matcher(output);
        if (matcher.find()) return matcher.group(1);
        return null;
    }

    /**
     * Extracts log lines surrounding the first occurrence of any keyword for diagnostics.
     */
    private String extractRelevantLogs(String output, String... keywords) {
        String[] lines = output.split("\n");
        int matchIndex = -1;
        for (int i = 0; i < lines.length && matchIndex < 0; i++) {
            String lineLower = lines[i].toLowerCase();
            for (String kw : keywords) {
                if (lineLower.contains(kw.toLowerCase())) {
                    matchIndex = i;
                    break;
                }
            }
        }
        if (matchIndex < 0) return summarizeAgentOutput(output);

        int start = Math.max(0, matchIndex - 5);
        int end = Math.min(lines.length, matchIndex + 10);
        var sb = new StringBuilder();
        for (int i = start; i < end; i++) {
            sb.append(lines[i]).append("\n");
        }
        return sb.toString().strip();
    }

    /**
     * Enriches DEPLOYER retry context with specific deployment failure diagnosis and suggestions.
     */
    private String enrichDeployerRetryContext(String baseContext, DeploymentDiagnosis diagnosis) {
        var sb = new StringBuilder(baseContext != null ? baseContext : "");
        sb.append("\n\n## PREVIOUS DEPLOYMENT ATTEMPT FAILED\n\n");
        sb.append("**Failure type:** ").append(diagnosis.failureType()).append("\n");
        sb.append("**Reason:** ").append(diagnosis.reason()).append("\n");
        sb.append("**Suggested fix:** ").append(diagnosis.suggestion()).append("\n\n");
        if (diagnosis.relevantLogs() != null && !diagnosis.relevantLogs().isBlank()) {
            sb.append("**Relevant logs:**\n```\n");
            sb.append(diagnosis.relevantLogs());
            sb.append("\n```\n\n");
        }
        sb.append("Address the above issue before retrying deployment.\n");
        return sb.toString();
    }

    private Task createTesterTask(Task coderTask, List<FileRecord> fileChanges) {
        return new Task(
                coderTask.id() + "-TESTER", "TESTER",
                "Run tests for task " + coderTask.id(),
                InstructionBuilder.buildTesterInstruction(
                        coderTask, null, fileChanges),
                "All tests pass", List.of(), TaskStatus.PENDING,
                0, 1, FailureStrategy.SKIP, List.of(), List.of(), null
        );
    }

    private Task createReviewerTask(Task coderTask, List<FileRecord> fileChanges) {
        return new Task(
                coderTask.id() + "-REVIEWER", "REVIEWER",
                "Review code for task " + coderTask.id(),
                InstructionBuilder.buildReviewerInstruction(
                        coderTask, null, fileChanges, null),
                "Code review complete with score >= 6", List.of(), TaskStatus.PENDING,
                0, 1, FailureStrategy.SKIP, List.of(), List.of(), null
        );
    }

    /**
     * Extracts the last meaningful portion of agent output for diagnostics.
     * Keeps the tail of the output where the agent typically explains what it did (or didn't do).
     */
    private static String summarizeAgentOutput(String output) {
        if (output == null || output.isBlank()) {
            return "(no output from agent)";
        }
        // Keep last 2000 chars — the end of the session is where the agent
        // typically summarizes its work or explains why it couldn't proceed
        int maxLen = 2000;
        String trimmed = output.length() > maxLen
                ? "...\n" + output.substring(output.length() - maxLen)
                : output;
        return trimmed;
    }

    private static class FailureOutcome {
        List<String> completedIds = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        String retryContext;
        boolean missionFailed;
        String deploymentUrl;
    }
}
