package com.worldmind.core.nodes;

import com.worldmind.core.events.EventBus;
import com.worldmind.core.events.WorldmindEvent;
import com.worldmind.core.logging.MdcContext;
import com.worldmind.core.metrics.WorldmindMetrics;
import com.worldmind.core.model.*;
import com.worldmind.core.scheduler.OscillationDetector;
import com.worldmind.core.seal.SealEvaluationService;
import com.worldmind.core.state.WorldmindState;
import com.worldmind.starblaster.InstructionBuilder;
import com.worldmind.starblaster.StarblasterBridge;
import com.worldmind.starblaster.cf.CloudFoundryProperties;
import com.worldmind.starblaster.cf.GitWorkspaceManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.time.Instant;
import java.util.*;

/**
 * Evaluates all directives in the current wave after dispatch.
 * For FORGE directives: runs GAUNTLET + VIGIL quality gates and evaluates the seal.
 * For non-FORGE directives: auto-passes them.
 * 
 * <p>After evaluating all directives in a wave, merges passed FORGE branches into main
 * so the next wave can build on prior work. This prevents merge conflicts when
 * multiple directives touch the same files across waves.
 */
@Component
public class EvaluateWaveNode {

    private static final Logger log = LoggerFactory.getLogger(EvaluateWaveNode.class);

    private final StarblasterBridge bridge;
    private final SealEvaluationService sealService;
    private final EventBus eventBus;
    private final WorldmindMetrics metrics;
    private final OscillationDetector oscillationDetector;
    
    // Optional dependencies for per-wave merge (only available in CF mode)
    private final GitWorkspaceManager gitWorkspaceManager;
    private final CloudFoundryProperties cfProperties;

    @Autowired
    public EvaluateWaveNode(StarblasterBridge bridge, SealEvaluationService sealService,
                            EventBus eventBus, WorldmindMetrics metrics,
                            OscillationDetector oscillationDetector,
                            @Autowired(required = false) GitWorkspaceManager gitWorkspaceManager,
                            @Autowired(required = false) CloudFoundryProperties cfProperties) {
        this.bridge = bridge;
        this.sealService = sealService;
        this.eventBus = eventBus;
        this.metrics = metrics;
        this.oscillationDetector = oscillationDetector;
        this.gitWorkspaceManager = gitWorkspaceManager;
        this.cfProperties = cfProperties;
    }

    public Map<String, Object> apply(WorldmindState state) {
        MdcContext.setWave(state.missionId(), state.waveCount());
        try {
            var waveIds = state.waveDirectiveIds();
            var directives = state.directives();
            var waveResults = state.waveDispatchResults();
            var projectContext = state.projectContext().orElse(null);
            // Use the original user-supplied host path for centurion bind mounts.
            String userPath = state.projectPath();
            String projectPath = (userPath != null && !userPath.isBlank())
                    ? userPath
                    : (projectContext != null ? projectContext.rootPath() : ".");

            // Build lookup maps
            var directiveMap = new HashMap<String, Directive>();
            for (var d : directives) directiveMap.put(d.id(), d);

            var resultMap = new HashMap<String, WaveDispatchResult>();
            for (var r : waveResults) resultMap.put(r.directiveId(), r);

            var completedIds = new ArrayList<String>();
            var retryingIds = new ArrayList<String>();  // Directives to retry after merge conflict
            var updatedDirectives = new ArrayList<Directive>();
            var testResultsList = new ArrayList<TestResult>();
            var reviewFeedbackList = new ArrayList<ReviewFeedback>();
            var starblasterInfos = new ArrayList<StarblasterInfo>();
            var errors = new ArrayList<String>();
            String retryContext = null;
            MissionStatus missionStatus = null;

            for (var id : waveIds) {
                var directive = directiveMap.get(id);
                var dispatchResult = resultMap.get(id);

                if (directive == null || dispatchResult == null) {
                    log.warn("Missing directive or dispatch result for {}, skipping evaluation", id);
                    continue;
                }

                // Non-FORGE directives auto-pass
                if (!"FORGE".equalsIgnoreCase(directive.centurion())) {
                    log.info("Auto-passing non-FORGE directive {} [{}]", id, directive.centurion());
                    completedIds.add(id);
                    updatedDirectives.add(withResult(directive, dispatchResult, DirectiveStatus.PASSED));
                    continue;
                }

                // FORGE directive that failed at dispatch — apply failure strategy directly
                if (dispatchResult.status() == DirectiveStatus.FAILED) {
                    log.info("FORGE directive {} failed at dispatch — applying failure strategy", id);
                    FailureStrategy action = directive.onFailure() != null ? directive.onFailure() : FailureStrategy.RETRY;
                    if (action == FailureStrategy.RETRY && directive.iteration() >= directive.maxIterations()) {
                        action = FailureStrategy.ESCALATE;
                    }
                    var outcome = applyFailureStrategy(id, directive, action,
                            "FORGE directive failed during execution");
                    completedIds.addAll(outcome.completedIds);
                    if (!outcome.completedIds.isEmpty()) {
                        updatedDirectives.add(withResult(directive, dispatchResult, DirectiveStatus.SKIPPED));
                    } else if (outcome.missionFailed) {
                        updatedDirectives.add(withResult(directive, dispatchResult, DirectiveStatus.FAILED));
                    }
                    if (outcome.retryContext != null) retryContext = outcome.retryContext;
                    if (outcome.missionFailed) missionStatus = MissionStatus.FAILED;
                    errors.addAll(outcome.errors);
                    continue;
                }

                // FORGE directive that passed dispatch — run GAUNTLET + VIGIL
                var allFileChanges = dispatchResult.filesAffected() != null ? dispatchResult.filesAffected() : List.<FileRecord>of();
                
                // Filter out Worldmind diagnostic files — only count actual code changes
                var fileChanges = allFileChanges.stream()
                        .filter(f -> !f.path().startsWith(".worldmind"))
                        .filter(f -> !f.path().contains("/goose-logs/"))
                        .filter(f -> !f.path().endsWith(".log"))
                        .filter(f -> !f.path().endsWith(".jsonl"))
                        .toList();

                // FORGE must produce code — if no real files were affected, retry.
                // The centurion may have explored but not written code; retrying often helps.
                if (fileChanges.isEmpty()) {
                    String outputSnippet = summarizeCenturionOutput(dispatchResult.output());
                    log.warn("FORGE directive {} produced no code files (only logs) — will retry. Centurion output: {}",
                            id, outputSnippet);

                    eventBus.publish(new WorldmindEvent("directive.failed",
                            state.missionId(), id,
                            Map.of("reason", "FORGE produced no code changes",
                                   "centurionOutput", outputSnippet),
                            Instant.now()));

                    String failureReason = "FORGE directive produced no code files. You MUST create/modify actual source files. Centurion output:\n" + outputSnippet;
                    var outcome = applyFailureStrategy(id, directive, FailureStrategy.RETRY, failureReason);
                    completedIds.addAll(outcome.completedIds);
                    if (!outcome.completedIds.isEmpty()) {
                        updatedDirectives.add(withResult(directive, dispatchResult, DirectiveStatus.SKIPPED));
                    } else if (outcome.missionFailed) {
                        updatedDirectives.add(withResult(directive, dispatchResult, DirectiveStatus.FAILED));
                    }
                    if (outcome.retryContext != null) retryContext = outcome.retryContext;
                    if (outcome.missionFailed) missionStatus = MissionStatus.FAILED;
                    errors.addAll(outcome.errors);
                    continue;
                }

                // Step 1: GAUNTLET
                eventBus.publish(new WorldmindEvent("directive.phase",
                        state.missionId(), id,
                        Map.of("phase", "GAUNTLET"), Instant.now()));
                TestResult testResult;
                try {
                    var gauntletDirective = createGauntletDirective(directive, fileChanges);
                    log.info("Dispatching GAUNTLET for directive {} ({} file changes)", id, fileChanges.size());
                    var gauntletResult = bridge.executeDirective(gauntletDirective, projectContext, Path.of(projectPath), state.gitRemoteUrl(), state.runtimeTag(), state.reasoningLevel());
                    starblasterInfos.add(gauntletResult.starblasterInfo());
                    if (gauntletResult.directive().status() == DirectiveStatus.FAILED) {
                        log.warn("GAUNTLET for {} failed ({}ms): {}", id,
                                gauntletResult.directive().elapsedMs(), gauntletResult.output());
                    }
                    testResult = sealService.parseTestOutput(id, gauntletResult.output(),
                            gauntletResult.directive().elapsedMs() != null ? gauntletResult.directive().elapsedMs() : 0L);
                } catch (Exception e) {
                    log.error("GAUNTLET dispatch failed for {}: {}", id, e.getMessage());
                    testResult = new TestResult(id, false, 0, 0,
                            "GAUNTLET infrastructure error: " + e.getMessage(), 0L);
                }

                // Step 2: VIGIL
                eventBus.publish(new WorldmindEvent("directive.phase",
                        state.missionId(), id,
                        Map.of("phase", "VIGIL"), Instant.now()));
                ReviewFeedback reviewFeedback;
                try {
                    var vigilDirective = createVigilDirective(directive, fileChanges);
                    log.info("Dispatching VIGIL for directive {} ({} file changes)", id, fileChanges.size());
                    var vigilResult = bridge.executeDirective(vigilDirective, projectContext, Path.of(projectPath), state.gitRemoteUrl(), state.runtimeTag(), state.reasoningLevel());
                    starblasterInfos.add(vigilResult.starblasterInfo());
                    if (vigilResult.directive().status() == DirectiveStatus.FAILED) {
                        log.warn("VIGIL for {} failed ({}ms): {}", id,
                                vigilResult.directive().elapsedMs(), vigilResult.output());
                    }
                    reviewFeedback = sealService.parseReviewOutput(id, vigilResult.output());
                } catch (Exception e) {
                    log.error("VIGIL dispatch failed for {}: {}", id, e.getMessage());
                    reviewFeedback = new ReviewFeedback(id, false,
                            "VIGIL infrastructure error: " + e.getMessage(),
                            List.of(e.getMessage()), List.of(), 0);
                }

                testResultsList.add(testResult);
                reviewFeedbackList.add(reviewFeedback);

                // Step 3: Evaluate seal
                eventBus.publish(new WorldmindEvent("directive.phase",
                        state.missionId(), id,
                        Map.of("phase", "SEAL"), Instant.now()));
                var sealDecision = sealService.evaluateSeal(testResult, reviewFeedback, directive);
                log.info("Seal evaluation for {}: {} — {}", id,
                        sealDecision.sealGranted() ? "GRANTED" : "DENIED", sealDecision.reason());
                metrics.recordSealResult(sealDecision.sealGranted());

                if (sealDecision.sealGranted()) {
                    completedIds.add(id);
                    updatedDirectives.add(withResult(directive, dispatchResult, DirectiveStatus.PASSED));
                    eventBus.publish(new WorldmindEvent("seal.granted",
                            state.missionId(), id,
                            Map.of("reason", sealDecision.reason(),
                                   "score", reviewFeedback.score(),
                                   "summary", reviewFeedback.summary() != null ? reviewFeedback.summary() : ""),
                            Instant.now()));
                } else {
                    eventBus.publish(new WorldmindEvent("seal.denied",
                            state.missionId(), id,
                            Map.of("reason", sealDecision.reason(),
                                   "action", sealDecision.action() != null ? sealDecision.action().name() : "UNKNOWN",
                                   "score", reviewFeedback.score(),
                                   "summary", reviewFeedback.summary() != null ? reviewFeedback.summary() : ""),
                            Instant.now()));
                    var outcome = applyFailureStrategy(id, directive, sealDecision.action(),
                            sealDecision.reason());
                    completedIds.addAll(outcome.completedIds);
                    if (!outcome.completedIds.isEmpty()) {
                        updatedDirectives.add(withResult(directive, dispatchResult, DirectiveStatus.SKIPPED));
                    } else if (outcome.missionFailed) {
                        updatedDirectives.add(withResult(directive, dispatchResult, DirectiveStatus.FAILED));
                    }
                    if (outcome.retryContext != null) {
                        retryContext = enrichRetryContext(outcome.retryContext, reviewFeedback);
                    }
                    if (outcome.missionFailed) missionStatus = MissionStatus.FAILED;
                    errors.addAll(outcome.errors);
                }
            }

            // Per-wave merge: merge passed FORGE/PRISM branches into main so next wave can build on them.
            // Sort by ID to ensure deterministic merge order (DIR-001 before DIR-002, etc.)
            List<String> passedForgeIds = updatedDirectives.stream()
                    .filter(d -> d.status() == DirectiveStatus.PASSED)
                    .filter(d -> "FORGE".equalsIgnoreCase(d.centurion()) || "PRISM".equalsIgnoreCase(d.centurion()))
                    .map(Directive::id)
                    .sorted()
                    .toList();
            
            if (!passedForgeIds.isEmpty() && gitWorkspaceManager != null && cfProperties != null) {
                log.info("Wave {} complete: merging {} passed FORGE/PRISM branches into main", 
                        state.waveCount(), passedForgeIds.size());
                try {
                    var mergeResult = gitWorkspaceManager.mergeWaveBranches(
                            passedForgeIds, 
                            cfProperties.getGitToken(), 
                            state.gitRemoteUrl());
                    
                    if (mergeResult.hasConflicts()) {
                        log.warn("Wave merge had {} conflicts: {} — will retry on updated main", 
                                mergeResult.conflictedIds().size(), mergeResult.conflictedIds());
                        
                        // Determine which directives merged successfully (for conflict context)
                        String mergedContext = mergeResult.mergedIds().isEmpty() 
                                ? "" 
                                : " The following directives have already merged: " + 
                                  String.join(", ", mergeResult.mergedIds()) + 
                                  ". Check main branch for their changes before creating your files.";
                        
                        // Mark conflicted directives for retry so they get re-scheduled
                        // They'll run again in the next wave, now with access to the merged changes
                        for (String conflictedId : mergeResult.conflictedIds()) {
                            // Reset directive status to PENDING and add merge conflict context
                            for (int i = 0; i < updatedDirectives.size(); i++) {
                                Directive d = updatedDirectives.get(i);
                                if (d.id().equals(conflictedId)) {
                                    int nextIteration = d.iteration() + 1;
                                    
                                    // Check if we've exhausted retries — escalate instead of retrying
                                    if (nextIteration > d.maxIterations()) {
                                        log.warn("Directive {} exhausted merge conflict retries ({}/{}), escalating",
                                                conflictedId, nextIteration - 1, d.maxIterations());
                                        missionStatus = MissionStatus.FAILED;
                                        errors.add("Directive " + conflictedId + " escalated: merge conflicts after " 
                                                + (nextIteration - 1) + " attempts");
                                        updatedDirectives.set(i, new Directive(
                                                d.id(), d.centurion(), d.description(),
                                                d.inputContext(), d.successCriteria(), d.dependencies(),
                                                DirectiveStatus.FAILED, d.iteration(), d.maxIterations(),
                                                d.onFailure(), d.targetFiles(), d.filesAffected(), d.elapsedMs()));
                                        break;
                                    }
                                    
                                    retryingIds.add(conflictedId);  // Add to retry list (excludes from completed)
                                    completedIds.remove(conflictedId);  // Don't add to completed this wave
                                    
                                    // Enhance input context with merge conflict information
                                    String enhancedContext = d.inputContext();
                                    if (enhancedContext == null) enhancedContext = "";
                                    enhancedContext = "MERGE CONFLICT RETRY: Your previous branch had " +
                                            "conflicts with main. Start fresh from the current main branch." +
                                            mergedContext + "\n\n" + enhancedContext;
                                    
                                    updatedDirectives.set(i, new Directive(
                                            d.id(), d.centurion(), d.description(),
                                            enhancedContext, d.successCriteria(), d.dependencies(),
                                            DirectiveStatus.PENDING, nextIteration, d.maxIterations(),
                                            d.onFailure(), d.targetFiles(), List.of(), null));
                                    log.info("Reset {} to PENDING (iteration {}) for retry on updated main", 
                                            conflictedId, nextIteration);
                                    break;
                                }
                            }
                        }
                        
                        errors.add("Merge conflicts on " + mergeResult.conflictedIds() + 
                                " — retrying on updated main in next wave");
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
            }

            // Build state updates
            var updates = new HashMap<String, Object>();
            if (!completedIds.isEmpty()) updates.put("completedDirectiveIds", completedIds);
            if (!retryingIds.isEmpty()) updates.put("retryingDirectiveIds", retryingIds);
            if (!updatedDirectives.isEmpty()) updates.put("directives", updatedDirectives);
            if (!testResultsList.isEmpty()) updates.put("testResults", testResultsList);
            if (!reviewFeedbackList.isEmpty()) updates.put("reviewFeedback", reviewFeedbackList);
            if (!starblasterInfos.isEmpty()) updates.put("starblasters", starblasterInfos);
            if (!errors.isEmpty()) updates.put("errors", errors);
            if (retryContext != null) updates.put("retryContext", retryContext);
            if (missionStatus != null) updates.put("status", missionStatus.name());

            return updates;
        } finally {
            MdcContext.clear();
        }
    }

    private Directive withResult(Directive d, WaveDispatchResult result, DirectiveStatus status) {
        // Increment iteration count since this directive was executed
        int newIteration = d.iteration() + 1;
        return new Directive(
                d.id(), d.centurion(), d.description(),
                d.inputContext(), d.successCriteria(), d.dependencies(),
                status, newIteration, d.maxIterations(),
                d.onFailure(), d.targetFiles(),
                result != null && result.filesAffected() != null ? result.filesAffected() : d.filesAffected(),
                result != null ? result.elapsedMs() : d.elapsedMs()
        );
    }

    private FailureOutcome applyFailureStrategy(String id, Directive directive,
                                                  FailureStrategy action, String reason) {
        if (action == FailureStrategy.RETRY) {
            oscillationDetector.recordFailure(id, reason);
            int failureCount = oscillationDetector.failureCount(id);
            if (failureCount >= directive.maxIterations()) {
                log.warn("Max retries ({}) exhausted for directive {} — escalating", directive.maxIterations(), id);
                action = FailureStrategy.ESCALATE;
            } else if (oscillationDetector.isOscillating(id)) {
                log.warn("Oscillation detected for directive {} — overriding RETRY to ESCALATE", id);
                action = FailureStrategy.ESCALATE;
            }
        }
        var outcome = new FailureOutcome();
        switch (action) {
            case RETRY -> {
                outcome.retryContext = "Retry for " + id + ": " + reason;
                log.info("Retrying directive {} — will re-enter next wave", id);
            }
            case SKIP -> {
                outcome.completedIds.add(id);
                log.info("Skipping directive {} — marking complete", id);
            }
            case ESCALATE -> {
                outcome.missionFailed = true;
                outcome.errors.add("Directive " + id + " escalated: " + reason);
                log.warn("Directive {} ESCALATED — mission failed", id);
                metrics.incrementEscalations(reason);
            }
            case REPLAN -> {
                outcome.missionFailed = true;
                outcome.errors.add("Directive " + id + " requires replanning: " + reason);
                log.warn("Directive {} requires REPLAN — mission failed", id);
            }
        }
        return outcome;
    }

    /**
     * Appends vigil review details to retry context so FORGE knows what to fix.
     */
    private String enrichRetryContext(String baseContext, ReviewFeedback feedback) {
        var sb = new StringBuilder(baseContext);
        sb.append("\n\n## Review Feedback (score: ").append(feedback.score()).append("/10)\n\n");

        if (feedback.summary() != null && !feedback.summary().isBlank()) {
            sb.append("**Summary:** ").append(feedback.summary()).append("\n\n");
        }
        if (feedback.issues() != null && !feedback.issues().isEmpty()) {
            sb.append("**Issues to fix:**\n");
            for (var issue : feedback.issues()) {
                sb.append("- ").append(issue).append("\n");
            }
            sb.append("\n");
        }
        if (feedback.suggestions() != null && !feedback.suggestions().isEmpty()) {
            sb.append("**Suggestions:**\n");
            for (var suggestion : feedback.suggestions()) {
                sb.append("- ").append(suggestion).append("\n");
            }
        }
        return sb.toString();
    }

    private Directive createGauntletDirective(Directive forgeDirective, List<FileRecord> fileChanges) {
        return new Directive(
                forgeDirective.id() + "-GAUNTLET", "GAUNTLET",
                "Run tests for directive " + forgeDirective.id(),
                InstructionBuilder.buildGauntletInstruction(
                        forgeDirective, null, fileChanges),
                "All tests pass", List.of(), DirectiveStatus.PENDING,
                0, 1, FailureStrategy.SKIP, List.of(), List.of(), null
        );
    }

    private Directive createVigilDirective(Directive forgeDirective, List<FileRecord> fileChanges) {
        return new Directive(
                forgeDirective.id() + "-VIGIL", "VIGIL",
                "Review code for directive " + forgeDirective.id(),
                InstructionBuilder.buildVigilInstruction(
                        forgeDirective, null, fileChanges, null),
                "Code review complete with score >= 7", List.of(), DirectiveStatus.PENDING,
                0, 1, FailureStrategy.SKIP, List.of(), List.of(), null
        );
    }

    /**
     * Extracts the last meaningful portion of centurion output for diagnostics.
     * Keeps the tail of the output where the centurion typically explains what it did (or didn't do).
     */
    private static String summarizeCenturionOutput(String output) {
        if (output == null || output.isBlank()) {
            return "(no output from centurion)";
        }
        // Keep last 2000 chars — the end of the session is where the centurion
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
    }
}
