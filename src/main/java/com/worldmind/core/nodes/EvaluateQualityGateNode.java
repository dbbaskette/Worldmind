package com.worldmind.core.nodes;

import com.worldmind.core.model.*;
import com.worldmind.core.quality_gate.QualityGateEvaluationService;
import com.worldmind.core.state.WorldmindState;
import com.worldmind.sandbox.InstructionBuilder;
import com.worldmind.sandbox.AgentDispatcher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.*;

/**
 * LangGraph4j node that acts as the quality gate in the build-test-fix loop.
 *
 * <p>After a CODER agent dispatches a task, this node dispatches
 * TESTER (test runner) and REVIEWER (code reviewer) agents to evaluate
 * the output, then decides whether the QualityGate of Approval is granted.
 *
 * <p>If the quality_gate is denied, the task's {@link FailureStrategy} is applied:
 * <ul>
 *   <li>{@link FailureStrategy#RETRY} — decrement the task index so dispatch re-runs it</li>
 *   <li>{@link FailureStrategy#SKIP} — leave the index alone and move to the next task</li>
 *   <li>{@link FailureStrategy#ESCALATE} — fail the mission</li>
 *   <li>{@link FailureStrategy#REPLAN} — fail the mission (replanning not yet implemented)</li>
 * </ul>
 */
@Component
public class EvaluateQualityGateNode {

    private static final Logger log = LoggerFactory.getLogger(EvaluateQualityGateNode.class);

    private final AgentDispatcher bridge;
    private final QualityGateEvaluationService quality_gateService;

    public EvaluateQualityGateNode(AgentDispatcher bridge, QualityGateEvaluationService quality_gateService) {
        this.bridge = bridge;
        this.quality_gateService = quality_gateService;
    }

    public Map<String, Object> apply(WorldmindState state) {
        var tasks = state.tasks();
        int currentIndex = state.currentTaskIndex();

        // The dispatch node already advanced the index, so last dispatched is index - 1
        int lastIndex = currentIndex - 1;
        if (lastIndex < 0 || lastIndex >= tasks.size()) {
            log.warn("No task to evaluate at index {}", lastIndex);
            return Map.of("quality_gateGranted", true);
        }

        var task = tasks.get(lastIndex);

        // Only evaluate CODER tasks — TESTER/REVIEWER don't need their own quality_gate
        if (!"CODER".equalsIgnoreCase(task.agent())) {
            log.info("Skipping quality_gate evaluation for non-CODER task {} [{}]",
                    task.id(), task.agent());
            return Map.of("quality_gateGranted", true);
        }

        // If the CODER task already failed, apply its failure strategy directly
        if (task.status() == TaskStatus.FAILED) {
            log.info("CODER task {} failed — applying failure strategy", task.id());
            FailureStrategy action = task.onFailure() != null ? task.onFailure() : FailureStrategy.RETRY;
            if (action == FailureStrategy.RETRY && task.iteration() >= task.maxIterations()) {
                action = FailureStrategy.ESCALATE;
            }
            return handleQualityGateDenied(state, task, action,
                    "CODER task failed during execution");
        }

        var projectContext = state.projectContext().orElse(null);
        String projectPath = projectContext != null ? projectContext.rootPath() : ".";
        var fileChanges = task.filesAffected() != null ? task.filesAffected() : List.<FileRecord>of();

        var updates = new HashMap<String, Object>();
        var sandboxInfos = new ArrayList<SandboxInfo>();

        // ── Step 1: Dispatch TESTER (test runner) ────────────────
        TestResult testResult;
        try {
            var testerTask = createTesterTask(task);
            log.info("Dispatching TESTER for task {}", task.id());
            var testerResult = bridge.executeTask(
                    testerTask, projectContext, Path.of(projectPath), state.gitRemoteUrl(), state.runtimeTag(), state.reasoningLevel());
            sandboxInfos.add(testerResult.sandboxInfo());
            testResult = quality_gateService.parseTestOutput(
                    task.id(), testerResult.output(),
                    testerResult.task().elapsedMs() != null ? testerResult.task().elapsedMs() : 0L);
        } catch (Exception e) {
            log.error("TESTER dispatch failed for {}: {}", task.id(), e.getMessage());
            testResult = new TestResult(task.id(), false, 0, 0,
                    "TESTER infrastructure error: " + e.getMessage(), 0L);
        }

        // ── Step 2: Dispatch REVIEWER (code reviewer) ─────────────────
        ReviewFeedback reviewFeedback;
        try {
            var reviewerTask = createReviewerTask(task);
            log.info("Dispatching REVIEWER for task {}", task.id());
            var reviewerResult = bridge.executeTask(
                    reviewerTask, projectContext, Path.of(projectPath), state.gitRemoteUrl(), state.runtimeTag(), state.reasoningLevel());
            sandboxInfos.add(reviewerResult.sandboxInfo());
            reviewFeedback = quality_gateService.parseReviewOutput(
                    task.id(), reviewerResult.output());
        } catch (Exception e) {
            log.error("REVIEWER dispatch failed for {}: {}", task.id(), e.getMessage());
            reviewFeedback = new ReviewFeedback(task.id(), false,
                    "REVIEWER infrastructure error: " + e.getMessage(),
                    List.of(e.getMessage()), List.of(), 0);
        }

        // ── Step 3: Evaluate quality_gate ──────────────────────────────────
        var quality_gateDecision = quality_gateService.evaluateQualityGate(testResult, reviewFeedback, task);

        log.info("QualityGate evaluation for {}: {} — {}", task.id(),
                quality_gateDecision.quality_gateGranted() ? "GRANTED" : "DENIED", quality_gateDecision.reason());

        updates.put("testResults", List.of(testResult));
        updates.put("reviewFeedback", List.of(reviewFeedback));
        updates.put("sandboxes", sandboxInfos);
        updates.put("quality_gateGranted", quality_gateDecision.quality_gateGranted());

        if (quality_gateDecision.quality_gateGranted()) {
            return updates;
        } else {
            updates.putAll(handleQualityGateDenied(state, task, quality_gateDecision.action(),
                    quality_gateDecision.reason()));
            return updates;
        }
    }

    /**
     * Handle a denied quality_gate by applying the failure strategy.
     * For RETRY: decrement currentTaskIndex so dispatch re-runs this task,
     * and set retryContext with the feedback.
     */
    private Map<String, Object> handleQualityGateDenied(WorldmindState state, Task task,
                                                   FailureStrategy action, String reason) {
        var updates = new HashMap<String, Object>();
        updates.put("quality_gateGranted", false);

        switch (action) {
            case RETRY -> {
                // Decrement index so dispatch_agent re-runs this task
                updates.put("currentTaskIndex", state.currentTaskIndex() - 1);
                updates.put("retryContext", "Retry for " + task.id() + ": " + reason);
                log.info("Retrying task {} — index decremented", task.id());
            }
            case SKIP -> {
                log.info("Skipping task {} — moving to next", task.id());
                // Don't change index — dispatch already advanced it
            }
            case ESCALATE -> {
                updates.put("status", MissionStatus.FAILED.name());
                updates.put("errors", List.of("Task " + task.id() + " escalated: " + reason));
                log.warn("Task {} ESCALATED — mission failed", task.id());
            }
            case REPLAN -> {
                updates.put("status", MissionStatus.FAILED.name());
                updates.put("errors", List.of("Task " + task.id() + " requires replanning: " + reason));
                log.warn("Task {} requires REPLAN — mission failed", task.id());
            }
        }
        return updates;
    }

    private Task createTesterTask(Task coderTask) {
        return new Task(
                coderTask.id() + "-TESTER",
                "TESTER",
                "Run tests for task " + coderTask.id(),
                InstructionBuilder.buildTesterInstruction(
                        coderTask, null, coderTask.filesAffected()),
                "All tests pass",
                List.of(),
                TaskStatus.PENDING,
                0, 1,
                FailureStrategy.SKIP,
                List.of(),
                List.of(),
                null
        );
    }

    private Task createReviewerTask(Task coderTask) {
        return new Task(
                coderTask.id() + "-REVIEWER",
                "REVIEWER",
                "Review code for task " + coderTask.id(),
                InstructionBuilder.buildReviewerInstruction(
                        coderTask, null, coderTask.filesAffected(), null),
                "Code review complete with score >= 7",
                List.of(),
                TaskStatus.PENDING,
                0, 1,
                FailureStrategy.SKIP,
                List.of(),
                List.of(),
                null
        );
    }
}
