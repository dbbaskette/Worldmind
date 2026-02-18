package com.worldmind.core.nodes;

import com.worldmind.core.model.*;
import com.worldmind.core.seal.SealEvaluationService;
import com.worldmind.core.state.WorldmindState;
import com.worldmind.starblaster.InstructionBuilder;
import com.worldmind.starblaster.StarblasterBridge;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.*;

/**
 * LangGraph4j node that acts as the quality gate in the build-test-fix loop.
 *
 * <p>After a FORGE centurion dispatches a directive, this node dispatches
 * GAUNTLET (test runner) and VIGIL (code reviewer) centurions to evaluate
 * the output, then decides whether the Seal of Approval is granted.
 *
 * <p>If the seal is denied, the directive's {@link FailureStrategy} is applied:
 * <ul>
 *   <li>{@link FailureStrategy#RETRY} — decrement the directive index so dispatch re-runs it</li>
 *   <li>{@link FailureStrategy#SKIP} — leave the index alone and move to the next directive</li>
 *   <li>{@link FailureStrategy#ESCALATE} — fail the mission</li>
 *   <li>{@link FailureStrategy#REPLAN} — fail the mission (replanning not yet implemented)</li>
 * </ul>
 */
@Component
public class EvaluateSealNode {

    private static final Logger log = LoggerFactory.getLogger(EvaluateSealNode.class);

    private final StarblasterBridge bridge;
    private final SealEvaluationService sealService;

    public EvaluateSealNode(StarblasterBridge bridge, SealEvaluationService sealService) {
        this.bridge = bridge;
        this.sealService = sealService;
    }

    public Map<String, Object> apply(WorldmindState state) {
        var directives = state.directives();
        int currentIndex = state.currentDirectiveIndex();

        // The dispatch node already advanced the index, so last dispatched is index - 1
        int lastIndex = currentIndex - 1;
        if (lastIndex < 0 || lastIndex >= directives.size()) {
            log.warn("No directive to evaluate at index {}", lastIndex);
            return Map.of("sealGranted", true);
        }

        var directive = directives.get(lastIndex);

        // Only evaluate FORGE directives — GAUNTLET/VIGIL don't need their own seal
        if (!"FORGE".equalsIgnoreCase(directive.centurion())) {
            log.info("Skipping seal evaluation for non-FORGE directive {} [{}]",
                    directive.id(), directive.centurion());
            return Map.of("sealGranted", true);
        }

        // If the FORGE directive already failed, apply its failure strategy directly
        if (directive.status() == DirectiveStatus.FAILED) {
            log.info("FORGE directive {} failed — applying failure strategy", directive.id());
            FailureStrategy action = directive.onFailure() != null ? directive.onFailure() : FailureStrategy.RETRY;
            if (action == FailureStrategy.RETRY && directive.iteration() >= directive.maxIterations()) {
                action = FailureStrategy.ESCALATE;
            }
            return handleSealDenied(state, directive, action,
                    "FORGE directive failed during execution");
        }

        var projectContext = state.projectContext().orElse(null);
        String projectPath = projectContext != null ? projectContext.rootPath() : ".";
        var fileChanges = directive.filesAffected() != null ? directive.filesAffected() : List.<FileRecord>of();

        var updates = new HashMap<String, Object>();
        var starblasterInfos = new ArrayList<StarblasterInfo>();

        // ── Step 1: Dispatch GAUNTLET (test runner) ────────────────
        TestResult testResult;
        try {
            var gauntletDirective = createGauntletDirective(directive);
            log.info("Dispatching GAUNTLET for directive {}", directive.id());
            var gauntletResult = bridge.executeDirective(
                    gauntletDirective, projectContext, Path.of(projectPath), state.gitRemoteUrl(), state.runtimeTag(), state.reasoningLevel());
            starblasterInfos.add(gauntletResult.starblasterInfo());
            testResult = sealService.parseTestOutput(
                    directive.id(), gauntletResult.output(),
                    gauntletResult.directive().elapsedMs() != null ? gauntletResult.directive().elapsedMs() : 0L);
        } catch (Exception e) {
            log.error("GAUNTLET dispatch failed for {}: {}", directive.id(), e.getMessage());
            testResult = new TestResult(directive.id(), false, 0, 0,
                    "GAUNTLET infrastructure error: " + e.getMessage(), 0L);
        }

        // ── Step 2: Dispatch VIGIL (code reviewer) ─────────────────
        ReviewFeedback reviewFeedback;
        try {
            var vigilDirective = createVigilDirective(directive);
            log.info("Dispatching VIGIL for directive {}", directive.id());
            var vigilResult = bridge.executeDirective(
                    vigilDirective, projectContext, Path.of(projectPath), state.gitRemoteUrl(), state.runtimeTag(), state.reasoningLevel());
            starblasterInfos.add(vigilResult.starblasterInfo());
            reviewFeedback = sealService.parseReviewOutput(
                    directive.id(), vigilResult.output());
        } catch (Exception e) {
            log.error("VIGIL dispatch failed for {}: {}", directive.id(), e.getMessage());
            reviewFeedback = new ReviewFeedback(directive.id(), false,
                    "VIGIL infrastructure error: " + e.getMessage(),
                    List.of(e.getMessage()), List.of(), 0);
        }

        // ── Step 3: Evaluate seal ──────────────────────────────────
        var sealDecision = sealService.evaluateSeal(testResult, reviewFeedback, directive);

        log.info("Seal evaluation for {}: {} — {}", directive.id(),
                sealDecision.sealGranted() ? "GRANTED" : "DENIED", sealDecision.reason());

        updates.put("testResults", List.of(testResult));
        updates.put("reviewFeedback", List.of(reviewFeedback));
        updates.put("starblasters", starblasterInfos);
        updates.put("sealGranted", sealDecision.sealGranted());

        if (sealDecision.sealGranted()) {
            return updates;
        } else {
            updates.putAll(handleSealDenied(state, directive, sealDecision.action(),
                    sealDecision.reason()));
            return updates;
        }
    }

    /**
     * Handle a denied seal by applying the failure strategy.
     * For RETRY: decrement currentDirectiveIndex so dispatch re-runs this directive,
     * and set retryContext with the feedback.
     */
    private Map<String, Object> handleSealDenied(WorldmindState state, Directive directive,
                                                   FailureStrategy action, String reason) {
        var updates = new HashMap<String, Object>();
        updates.put("sealGranted", false);

        switch (action) {
            case RETRY -> {
                // Decrement index so dispatch_centurion re-runs this directive
                updates.put("currentDirectiveIndex", state.currentDirectiveIndex() - 1);
                updates.put("retryContext", "Retry for " + directive.id() + ": " + reason);
                log.info("Retrying directive {} — index decremented", directive.id());
            }
            case SKIP -> {
                log.info("Skipping directive {} — moving to next", directive.id());
                // Don't change index — dispatch already advanced it
            }
            case ESCALATE -> {
                updates.put("status", MissionStatus.FAILED.name());
                updates.put("errors", List.of("Directive " + directive.id() + " escalated: " + reason));
                log.warn("Directive {} ESCALATED — mission failed", directive.id());
            }
            case REPLAN -> {
                updates.put("status", MissionStatus.FAILED.name());
                updates.put("errors", List.of("Directive " + directive.id() + " requires replanning: " + reason));
                log.warn("Directive {} requires REPLAN — mission failed", directive.id());
            }
        }
        return updates;
    }

    private Directive createGauntletDirective(Directive forgeDirective) {
        return new Directive(
                forgeDirective.id() + "-GAUNTLET",
                "GAUNTLET",
                "Run tests for directive " + forgeDirective.id(),
                InstructionBuilder.buildGauntletInstruction(
                        forgeDirective, null, forgeDirective.filesAffected()),
                "All tests pass",
                List.of(),
                DirectiveStatus.PENDING,
                0, 1,
                FailureStrategy.SKIP,
                List.of(),
                null
        );
    }

    private Directive createVigilDirective(Directive forgeDirective) {
        return new Directive(
                forgeDirective.id() + "-VIGIL",
                "VIGIL",
                "Review code for directive " + forgeDirective.id(),
                InstructionBuilder.buildVigilInstruction(
                        forgeDirective, null, forgeDirective.filesAffected(), null),
                "Code review complete with score >= 7",
                List.of(),
                DirectiveStatus.PENDING,
                0, 1,
                FailureStrategy.SKIP,
                List.of(),
                null
        );
    }
}
