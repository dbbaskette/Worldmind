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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.time.Instant;
import java.util.*;

/**
 * Evaluates all directives in the current wave after dispatch.
 * For FORGE directives: runs GAUNTLET + VIGIL quality gates and evaluates the seal.
 * For non-FORGE directives: auto-passes them.
 */
@Component
public class EvaluateWaveNode {

    private static final Logger log = LoggerFactory.getLogger(EvaluateWaveNode.class);

    private final StarblasterBridge bridge;
    private final SealEvaluationService sealService;
    private final EventBus eventBus;
    private final WorldmindMetrics metrics;
    private final OscillationDetector oscillationDetector;

    public EvaluateWaveNode(StarblasterBridge bridge, SealEvaluationService sealService,
                            EventBus eventBus, WorldmindMetrics metrics,
                            OscillationDetector oscillationDetector) {
        this.bridge = bridge;
        this.sealService = sealService;
        this.eventBus = eventBus;
        this.metrics = metrics;
        this.oscillationDetector = oscillationDetector;
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
                    if (outcome.retryContext != null) retryContext = outcome.retryContext;
                    if (outcome.missionFailed) missionStatus = MissionStatus.FAILED;
                    errors.addAll(outcome.errors);
                    continue;
                }

                // FORGE directive that passed dispatch — run GAUNTLET + VIGIL
                var fileChanges = dispatchResult.filesAffected() != null ? dispatchResult.filesAffected() : List.<FileRecord>of();

                // Step 1: GAUNTLET
                TestResult testResult;
                try {
                    var gauntletDirective = createGauntletDirective(directive);
                    log.info("Dispatching GAUNTLET for directive {}", id);
                    var gauntletResult = bridge.executeDirective(gauntletDirective, projectContext, Path.of(projectPath), state.gitRemoteUrl());
                    starblasterInfos.add(gauntletResult.starblasterInfo());
                    testResult = sealService.parseTestOutput(id, gauntletResult.output(),
                            gauntletResult.directive().elapsedMs() != null ? gauntletResult.directive().elapsedMs() : 0L);
                } catch (Exception e) {
                    log.error("GAUNTLET dispatch failed for {}: {}", id, e.getMessage());
                    testResult = new TestResult(id, false, 0, 0,
                            "GAUNTLET infrastructure error: " + e.getMessage(), 0L);
                }

                // Step 2: VIGIL
                ReviewFeedback reviewFeedback;
                try {
                    var vigilDirective = createVigilDirective(directive);
                    log.info("Dispatching VIGIL for directive {}", id);
                    var vigilResult = bridge.executeDirective(vigilDirective, projectContext, Path.of(projectPath), state.gitRemoteUrl());
                    starblasterInfos.add(vigilResult.starblasterInfo());
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
                var sealDecision = sealService.evaluateSeal(testResult, reviewFeedback, directive);
                log.info("Seal evaluation for {}: {} — {}", id,
                        sealDecision.sealGranted() ? "GRANTED" : "DENIED", sealDecision.reason());
                metrics.recordSealResult(sealDecision.sealGranted());

                if (sealDecision.sealGranted()) {
                    completedIds.add(id);
                } else {
                    eventBus.publish(new WorldmindEvent("seal.denied",
                            state.missionId(), id,
                            Map.of("reason", sealDecision.reason(),
                                   "action", sealDecision.action() != null ? sealDecision.action().name() : "UNKNOWN"),
                            Instant.now()));
                    var outcome = applyFailureStrategy(id, directive, sealDecision.action(),
                            sealDecision.reason());
                    completedIds.addAll(outcome.completedIds);
                    if (outcome.retryContext != null) retryContext = outcome.retryContext;
                    if (outcome.missionFailed) missionStatus = MissionStatus.FAILED;
                    errors.addAll(outcome.errors);
                }
            }

            // Build state updates
            var updates = new HashMap<String, Object>();
            updates.put("completedDirectiveIds", completedIds);
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

    private Directive createGauntletDirective(Directive forgeDirective) {
        return new Directive(
                forgeDirective.id() + "-GAUNTLET", "GAUNTLET",
                "Run tests for directive " + forgeDirective.id(),
                InstructionBuilder.buildGauntletInstruction(
                        forgeDirective, null, forgeDirective.filesAffected()),
                "All tests pass", List.of(), DirectiveStatus.PENDING,
                0, 1, FailureStrategy.SKIP, List.of(), null
        );
    }

    private Directive createVigilDirective(Directive forgeDirective) {
        return new Directive(
                forgeDirective.id() + "-VIGIL", "VIGIL",
                "Review code for directive " + forgeDirective.id(),
                InstructionBuilder.buildVigilInstruction(
                        forgeDirective, null, forgeDirective.filesAffected(), null),
                "Code review complete with score >= 7", List.of(), DirectiveStatus.PENDING,
                0, 1, FailureStrategy.SKIP, List.of(), null
        );
    }

    private static class FailureOutcome {
        List<String> completedIds = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        String retryContext;
        boolean missionFailed;
    }
}
