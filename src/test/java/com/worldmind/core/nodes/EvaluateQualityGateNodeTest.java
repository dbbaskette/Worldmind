package com.worldmind.core.nodes;

import com.worldmind.core.model.*;
import com.worldmind.core.quality_gate.QualityGateEvaluationService;
import com.worldmind.core.state.WorldmindState;
import com.worldmind.sandbox.AgentDispatcher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link EvaluateQualityGateNode}.
 * <p>
 * Uses Mockito to mock {@link AgentDispatcher} and {@link QualityGateEvaluationService}
 * so that no real Docker containers are launched and no LLM calls are made.
 */
class EvaluateQualityGateNodeTest {

    private AgentDispatcher bridge;
    private QualityGateEvaluationService quality_gateService;
    private EvaluateQualityGateNode node;

    @BeforeEach
    void setUp() {
        bridge = mock(AgentDispatcher.class);
        quality_gateService = mock(QualityGateEvaluationService.class);
        node = new EvaluateQualityGateNode(bridge, quality_gateService);
    }

    // ── Helper methods ──────────────────────────────────────────────

    private Task coderTask(TaskStatus status, int iteration, int maxIterations,
                                      FailureStrategy onFailure) {
        return new Task(
                "TASK-001", "CODER", "Implement feature",
                "context", "Feature works",
                List.of(), status, iteration, maxIterations,
                onFailure, List.of(),
                List.of(new FileRecord("src/Main.java", "modified", 50)),
                5000L
        );
    }

    private Task coderTask(TaskStatus status) {
        return coderTask(status, 1, 3, FailureStrategy.RETRY);
    }

    private AgentDispatcher.BridgeResult bridgeResult(String agentType, String taskId,
                                                      String output) {
        var updatedTask = new Task(
                taskId, agentType, "desc", "", "criteria",
                List.of(), TaskStatus.PASSED, 1, 1,
                FailureStrategy.SKIP, List.of(), List.of(), 1000L
        );
        var sandboxInfo = new SandboxInfo(
                "c-" + agentType.toLowerCase(), agentType, taskId,
                "completed", Instant.now(), Instant.now()
        );
        return new AgentDispatcher.BridgeResult(updatedTask, sandboxInfo, output);
    }

    private WorldmindState stateWithTask(Task task) {
        return new WorldmindState(Map.of(
                "tasks", List.of(task),
                "currentTaskIndex", 1,  // dispatch already advanced, so lastIndex = 0
                "testResults", List.of(),
                "reviewFeedback", List.of(),
                "sandboxes", List.of()
        ));
    }

    private void mockSuccessfulBridgeAndQualityGate() {
        // Mock TESTER bridge call (first call)
        var testerResult = bridgeResult("TESTER", "TASK-001-TESTER",
                "Tests run: 10, Failures: 0");
        // Mock REVIEWER bridge call (second call)
        var reviewerResult = bridgeResult("REVIEWER", "TASK-001-REVIEWER",
                "Score: 9/10\nApproved: yes");

        when(bridge.executeTask(any(), any(), any(), any(), any()))
                .thenReturn(testerResult)
                .thenReturn(reviewerResult);

        when(quality_gateService.parseTestOutput(anyString(), anyString(), anyLong()))
                .thenReturn(new TestResult("TASK-001", true, 10, 0, "All passed", 1000L));
        when(quality_gateService.parseReviewOutput(anyString(), anyString()))
                .thenReturn(new ReviewFeedback("TASK-001", true, "Good code",
                        List.of(), List.of(), 9));
    }

    // ── Tests ───────────────────────────────────────────────────────

    @Test
    @DisplayName("skips quality_gate evaluation for non-CODER task")
    void skipsNonCoderTask() {
        var testerTask = new Task(
                "TASK-002", "TESTER", "Run tests",
                "", "Tests pass",
                List.of(), TaskStatus.PASSED, 1, 3,
                FailureStrategy.RETRY, List.of(), List.of(), 2000L
        );
        var state = stateWithTask(testerTask);

        var result = node.apply(state);

        assertEquals(true, result.get("quality_gateGranted"));
        verifyNoInteractions(bridge);
        verifyNoInteractions(quality_gateService);
    }

    @Test
    @DisplayName("grants quality_gate when tests pass and review is approved")
    void grantsWhenTestsPassAndReviewApproved() {
        var task = coderTask(TaskStatus.PASSED);
        var state = stateWithTask(task);

        mockSuccessfulBridgeAndQualityGate();
        when(quality_gateService.evaluateQualityGate(any(), any(), any()))
                .thenReturn(new QualityGateDecision(true, null, "QualityGate granted"));

        var result = node.apply(state);

        assertEquals(true, result.get("quality_gateGranted"));
        assertTrue(result.containsKey("testResults"));
        assertTrue(result.containsKey("reviewFeedback"));
        assertTrue(result.containsKey("sandboxes"));

        @SuppressWarnings("unchecked")
        var testResults = (List<TestResult>) result.get("testResults");
        assertEquals(1, testResults.size());
        assertTrue(testResults.get(0).passed());

        @SuppressWarnings("unchecked")
        var reviewFeedbacks = (List<ReviewFeedback>) result.get("reviewFeedback");
        assertEquals(1, reviewFeedbacks.size());
        assertTrue(reviewFeedbacks.get(0).approved());

        // Verify bridge was called exactly twice (TESTER + REVIEWER)
        verify(bridge, times(2)).executeTask(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("denies quality_gate and retries when quality_gate is denied with RETRY strategy")
    void deniesAndRetriesWhenQualityGateDenied() {
        var task = coderTask(TaskStatus.PASSED, 1, 3, FailureStrategy.RETRY);
        var state = stateWithTask(task);

        mockSuccessfulBridgeAndQualityGate();
        when(quality_gateService.evaluateQualityGate(any(), any(), any()))
                .thenReturn(new QualityGateDecision(false, FailureStrategy.RETRY, "Tests failed"));

        var result = node.apply(state);

        assertEquals(false, result.get("quality_gateGranted"));
        // currentTaskIndex should be decremented from 1 to 0
        assertEquals(0, result.get("currentTaskIndex"));
        assertNotNull(result.get("retryContext"));
        String retryContext = (String) result.get("retryContext");
        assertTrue(retryContext.contains("TASK-001"));
        assertTrue(retryContext.contains("Tests failed"));
    }

    @Test
    @DisplayName("denies quality_gate and skips when strategy is SKIP")
    void deniesAndSkipsWhenStrategyIsSkip() {
        var task = coderTask(TaskStatus.PASSED, 1, 3, FailureStrategy.SKIP);
        var state = stateWithTask(task);

        mockSuccessfulBridgeAndQualityGate();
        when(quality_gateService.evaluateQualityGate(any(), any(), any()))
                .thenReturn(new QualityGateDecision(false, FailureStrategy.SKIP, "Review score too low"));

        var result = node.apply(state);

        assertEquals(false, result.get("quality_gateGranted"));
        // Index should NOT be changed for SKIP — dispatch already advanced it
        assertFalse(result.containsKey("currentTaskIndex"));
    }

    @Test
    @DisplayName("escalates and fails mission when strategy is ESCALATE")
    void escalatesAndFailsMission() {
        var task = coderTask(TaskStatus.PASSED, 3, 3, FailureStrategy.ESCALATE);
        var state = stateWithTask(task);

        mockSuccessfulBridgeAndQualityGate();
        when(quality_gateService.evaluateQualityGate(any(), any(), any()))
                .thenReturn(new QualityGateDecision(false, FailureStrategy.ESCALATE, "Critical failure"));

        var result = node.apply(state);

        assertEquals(false, result.get("quality_gateGranted"));
        assertEquals(MissionStatus.FAILED.name(), result.get("status"));
        assertTrue(result.containsKey("errors"));
        @SuppressWarnings("unchecked")
        var errors = (List<String>) result.get("errors");
        assertEquals(1, errors.size());
        assertTrue(errors.get(0).contains("TASK-001"));
        assertTrue(errors.get(0).contains("escalated"));
    }

    @Test
    @DisplayName("handles failed CODER task by applying failure strategy directly")
    void handlesFailedCoderTask() {
        var task = coderTask(TaskStatus.FAILED, 1, 3, FailureStrategy.RETRY);
        var state = stateWithTask(task);

        var result = node.apply(state);

        assertEquals(false, result.get("quality_gateGranted"));
        // Should decrement index for RETRY
        assertEquals(0, result.get("currentTaskIndex"));
        assertNotNull(result.get("retryContext"));
        String retryContext = (String) result.get("retryContext");
        assertTrue(retryContext.contains("TASK-001"));

        // No bridge calls should be made for already-failed tasks
        verifyNoInteractions(bridge);
        verifyNoInteractions(quality_gateService);
    }

    @Test
    @DisplayName("handles TESTER infrastructure error gracefully")
    void handlesTesterInfrastructureError() {
        var task = coderTask(TaskStatus.PASSED);
        var state = stateWithTask(task);

        // TESTER throws, REVIEWER succeeds
        var reviewerResult = bridgeResult("REVIEWER", "TASK-001-REVIEWER", "Score: 8/10\nApproved: yes");
        when(bridge.executeTask(any(), any(), any(), any(), any()))
                .thenThrow(new RuntimeException("Docker connection refused"))
                .thenReturn(reviewerResult);

        when(quality_gateService.parseReviewOutput(anyString(), anyString()))
                .thenReturn(new ReviewFeedback("TASK-001", true, "Good code",
                        List.of(), List.of(), 8));
        when(quality_gateService.evaluateQualityGate(any(), any(), any()))
                .thenReturn(new QualityGateDecision(false, FailureStrategy.RETRY, "Tests failed due to infra error"));

        var result = node.apply(state);

        // Should still have dispatched REVIEWER (second call)
        verify(bridge, times(2)).executeTask(any(), any(), any(), any(), any());

        // testResults should contain the error info
        assertTrue(result.containsKey("testResults"));
        @SuppressWarnings("unchecked")
        var testResults = (List<TestResult>) result.get("testResults");
        assertEquals(1, testResults.size());
        assertFalse(testResults.get(0).passed());
        assertTrue(testResults.get(0).output().contains("TESTER infrastructure error"));

        // evaluateQualityGate should still have been called
        verify(quality_gateService).evaluateQualityGate(any(), any(), any());
    }

    @Test
    @DisplayName("handles REVIEWER infrastructure error gracefully")
    void handlesReviewerInfrastructureError() {
        var task = coderTask(TaskStatus.PASSED);
        var state = stateWithTask(task);

        // TESTER succeeds, REVIEWER throws
        var testerResult = bridgeResult("TESTER", "TASK-001-TESTER",
                "Tests run: 10, Failures: 0");
        when(bridge.executeTask(any(), any(), any(), any(), any()))
                .thenReturn(testerResult)
                .thenThrow(new RuntimeException("REVIEWER container timeout"));

        when(quality_gateService.parseTestOutput(anyString(), anyString(), anyLong()))
                .thenReturn(new TestResult("TASK-001", true, 10, 0, "All passed", 1000L));
        when(quality_gateService.evaluateQualityGate(any(), any(), any()))
                .thenReturn(new QualityGateDecision(false, FailureStrategy.RETRY, "Review failed due to infra error"));

        var result = node.apply(state);

        // Should have tried both bridge calls
        verify(bridge, times(2)).executeTask(any(), any(), any(), any(), any());

        // reviewFeedback should contain the error info
        assertTrue(result.containsKey("reviewFeedback"));
        @SuppressWarnings("unchecked")
        var feedbacks = (List<ReviewFeedback>) result.get("reviewFeedback");
        assertEquals(1, feedbacks.size());
        assertFalse(feedbacks.get(0).approved());
        assertTrue(feedbacks.get(0).summary().contains("REVIEWER infrastructure error"));
        assertEquals(0, feedbacks.get(0).score());

        // evaluateQualityGate should still have been called
        verify(quality_gateService).evaluateQualityGate(any(), any(), any());
    }

    @Test
    @DisplayName("handles out-of-bounds index gracefully")
    void handlesOutOfBoundsIndex() {
        // currentTaskIndex = 0 means lastIndex = -1 (nothing dispatched yet)
        var state = new WorldmindState(Map.of(
                "tasks", List.of(coderTask(TaskStatus.PENDING)),
                "currentTaskIndex", 0
        ));

        var result = node.apply(state);

        assertEquals(true, result.get("quality_gateGranted"));
        verifyNoInteractions(bridge);
        verifyNoInteractions(quality_gateService);
    }

    @Test
    @DisplayName("failed CODER task with exhausted retries escalates")
    void failedCoderWithExhaustedRetriesEscalates() {
        // iteration=3, maxIterations=3 -> retries exhausted, should escalate even if onFailure=RETRY
        var task = coderTask(TaskStatus.FAILED, 3, 3, FailureStrategy.RETRY);
        var state = stateWithTask(task);

        var result = node.apply(state);

        assertEquals(false, result.get("quality_gateGranted"));
        assertEquals(MissionStatus.FAILED.name(), result.get("status"));
        assertTrue(result.containsKey("errors"));
        @SuppressWarnings("unchecked")
        var errors = (List<String>) result.get("errors");
        assertTrue(errors.get(0).contains("TASK-001"));
        assertTrue(errors.get(0).contains("escalated"));

        verifyNoInteractions(bridge);
        verifyNoInteractions(quality_gateService);
    }

    @Test
    @DisplayName("REPLAN strategy fails mission with replanning message")
    void replanStrategyFailsMission() {
        var task = coderTask(TaskStatus.PASSED, 1, 3, FailureStrategy.REPLAN);
        var state = stateWithTask(task);

        mockSuccessfulBridgeAndQualityGate();
        when(quality_gateService.evaluateQualityGate(any(), any(), any()))
                .thenReturn(new QualityGateDecision(false, FailureStrategy.REPLAN, "Architecture needs rethinking"));

        var result = node.apply(state);

        assertEquals(false, result.get("quality_gateGranted"));
        assertEquals(MissionStatus.FAILED.name(), result.get("status"));
        @SuppressWarnings("unchecked")
        var errors = (List<String>) result.get("errors");
        assertTrue(errors.get(0).contains("replanning"));
    }
}
