package com.worldmind.core.nodes;

import com.worldmind.core.events.EventBus;
import com.worldmind.core.metrics.WorldmindMetrics;
import com.worldmind.core.model.*;
import com.worldmind.core.scheduler.OscillationDetector;
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

class EvaluateWaveNodeTest {

    private AgentDispatcher mockBridge;
    private QualityGateEvaluationService mockQualityGateService;
    private EvaluateWaveNode node;

    @BeforeEach
    void setUp() {
        mockBridge = mock(AgentDispatcher.class);
        mockQualityGateService = mock(QualityGateEvaluationService.class);
        node = new EvaluateWaveNode(mockBridge, mockQualityGateService, new EventBus(), mock(WorldmindMetrics.class), new OscillationDetector(), null, null);
    }

    private Task coderTask(String id, int iteration, int maxIterations, FailureStrategy onFailure) {
        return new Task(id, "CODER", "Do " + id, "", "Done", List.of(),
                TaskStatus.PENDING, iteration, maxIterations, onFailure, List.of(), List.of(), null);
    }

    private Task nonCoderTask(String id) {
        return new Task(id, "RESEARCHER", "Research " + id, "", "Done", List.of(),
                TaskStatus.PENDING, 0, 1, FailureStrategy.SKIP, List.of(), List.of(), null);
    }

    private WaveDispatchResult passedResult(String id) {
        return new WaveDispatchResult(id, TaskStatus.PASSED,
                List.of(new FileRecord("test.java", "created", 10)), "Success", 500L);
    }

    private WaveDispatchResult passedResultNoFiles(String id) {
        return new WaveDispatchResult(id, TaskStatus.PASSED, List.of(), "Success", 500L);
    }

    private WaveDispatchResult failedResult(String id) {
        return new WaveDispatchResult(id, TaskStatus.FAILED, List.of(), "Failed output", 300L);
    }

    private AgentDispatcher.BridgeResult bridgeResult(String id, String agent) {
        var d = new Task(id, agent, "sub", "", "", List.of(),
                TaskStatus.PASSED, 1, 1, FailureStrategy.SKIP, List.of(), List.of(), 100L);
        return new AgentDispatcher.BridgeResult(d,
                new SandboxInfo("c-" + id, agent, id, "completed", Instant.now(), Instant.now()),
                "output");
    }

    @Test
    @DisplayName("Single CODER, quality_gate granted -> completedIds updated")
    @SuppressWarnings("unchecked")
    void singleCoderQualityGateGranted() {
        var d = coderTask("TASK-001", 0, 3, FailureStrategy.RETRY);
        when(mockBridge.executeTask(any(), any(), any(), any(), any())).thenReturn(bridgeResult("TASK-001", "TESTER"));
        when(mockQualityGateService.parseTestOutput(anyString(), anyString(), anyLong()))
                .thenReturn(new TestResult("TASK-001", true, 10, 0, "OK", 500L));
        when(mockQualityGateService.parseReviewOutput(anyString(), anyString()))
                .thenReturn(new ReviewFeedback("TASK-001", true, "Good", List.of(), List.of(), 8));
        when(mockQualityGateService.evaluateQualityGate(any(), any(), any()))
                .thenReturn(new QualityGateDecision(true, null, "All good"));

        var state = new WorldmindState(Map.of(
                "waveTaskIds", List.of("TASK-001"),
                "tasks", List.of(d),
                "waveDispatchResults", List.of(passedResult("TASK-001"))
        ));

        var result = node.apply(state);

        var completedIds = (List<String>) result.get("completedTaskIds");
        assertTrue(completedIds.contains("TASK-001"));
    }

    @Test
    @DisplayName("Single CODER, quality_gate denied + RETRY -> NOT in completedIds, retryContext set")
    @SuppressWarnings("unchecked")
    void singleCoderQualityGateDeniedRetry() {
        var d = coderTask("TASK-001", 0, 3, FailureStrategy.RETRY);
        when(mockBridge.executeTask(any(), any(), any(), any(), any())).thenReturn(bridgeResult("TASK-001", "TESTER"));
        when(mockQualityGateService.parseTestOutput(anyString(), anyString(), anyLong()))
                .thenReturn(new TestResult("TASK-001", false, 10, 3, "FAIL", 500L));
        when(mockQualityGateService.parseReviewOutput(anyString(), anyString()))
                .thenReturn(new ReviewFeedback("TASK-001", false, "Issues", List.of("bug"), List.of(), 4));
        when(mockQualityGateService.evaluateQualityGate(any(), any(), any()))
                .thenReturn(new QualityGateDecision(false, FailureStrategy.RETRY, "Tests failed"));

        var state = new WorldmindState(Map.of(
                "waveTaskIds", List.of("TASK-001"),
                "tasks", List.of(d),
                "waveDispatchResults", List.of(passedResult("TASK-001"))
        ));

        var result = node.apply(state);

        var completedIds = (List<String>) result.get("completedTaskIds");
        assertFalse(completedIds.contains("TASK-001"));
        assertTrue(((String) result.get("retryContext")).contains("TASK-001"));
    }

    @Test
    @DisplayName("Single CODER, quality_gate denied + SKIP -> in completedIds")
    @SuppressWarnings("unchecked")
    void singleCoderQualityGateDeniedSkip() {
        var d = coderTask("TASK-001", 0, 3, FailureStrategy.SKIP);
        when(mockBridge.executeTask(any(), any(), any(), any(), any())).thenReturn(bridgeResult("TASK-001", "TESTER"));
        when(mockQualityGateService.parseTestOutput(anyString(), anyString(), anyLong()))
                .thenReturn(new TestResult("TASK-001", false, 10, 3, "FAIL", 500L));
        when(mockQualityGateService.parseReviewOutput(anyString(), anyString()))
                .thenReturn(new ReviewFeedback("TASK-001", false, "Issues", List.of("bug"), List.of(), 4));
        when(mockQualityGateService.evaluateQualityGate(any(), any(), any()))
                .thenReturn(new QualityGateDecision(false, FailureStrategy.SKIP, "Skipping"));

        var state = new WorldmindState(Map.of(
                "waveTaskIds", List.of("TASK-001"),
                "tasks", List.of(d),
                "waveDispatchResults", List.of(passedResult("TASK-001"))
        ));

        var result = node.apply(state);

        var completedIds = (List<String>) result.get("completedTaskIds");
        assertTrue(completedIds.contains("TASK-001"));
    }

    @Test
    @DisplayName("ESCALATE -> mission FAILED")
    void escalateMissionFailed() {
        var d = coderTask("TASK-001", 3, 3, FailureStrategy.ESCALATE);
        when(mockBridge.executeTask(any(), any(), any(), any(), any())).thenReturn(bridgeResult("TASK-001", "TESTER"));
        when(mockQualityGateService.parseTestOutput(anyString(), anyString(), anyLong()))
                .thenReturn(new TestResult("TASK-001", false, 10, 5, "FAIL", 500L));
        when(mockQualityGateService.parseReviewOutput(anyString(), anyString()))
                .thenReturn(new ReviewFeedback("TASK-001", false, "Bad", List.of("critical"), List.of(), 2));
        when(mockQualityGateService.evaluateQualityGate(any(), any(), any()))
                .thenReturn(new QualityGateDecision(false, FailureStrategy.ESCALATE, "Critical failure"));

        var state = new WorldmindState(Map.of(
                "waveTaskIds", List.of("TASK-001"),
                "tasks", List.of(d),
                "waveDispatchResults", List.of(passedResult("TASK-001"))
        ));

        var result = node.apply(state);

        assertEquals(MissionStatus.FAILED.name(), result.get("status"));
    }

    @Test
    @DisplayName("Non-CODER task -> auto-pass, added to completedIds")
    @SuppressWarnings("unchecked")
    void nonCoderAutoPass() {
        var d = nonCoderTask("TASK-001");

        var state = new WorldmindState(Map.of(
                "waveTaskIds", List.of("TASK-001"),
                "tasks", List.of(d),
                "waveDispatchResults", List.of(passedResult("TASK-001"))
        ));

        var result = node.apply(state);

        var completedIds = (List<String>) result.get("completedTaskIds");
        assertTrue(completedIds.contains("TASK-001"));
        // No bridge calls for non-CODER (TESTER/REVIEWER not dispatched)
        verify(mockBridge, never()).executeTask(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("Multiple tasks in wave, mixed results")
    @SuppressWarnings("unchecked")
    void multipleTasksMixedResults() {
        var d1 = coderTask("TASK-001", 0, 3, FailureStrategy.RETRY);
        var d2 = nonCoderTask("TASK-002");

        when(mockBridge.executeTask(any(), any(), any(), any(), any())).thenReturn(bridgeResult("TASK-001", "TESTER"));
        when(mockQualityGateService.parseTestOutput(anyString(), anyString(), anyLong()))
                .thenReturn(new TestResult("TASK-001", true, 5, 0, "OK", 200L));
        when(mockQualityGateService.parseReviewOutput(anyString(), anyString()))
                .thenReturn(new ReviewFeedback("TASK-001", true, "Good", List.of(), List.of(), 9));
        when(mockQualityGateService.evaluateQualityGate(any(), any(), any()))
                .thenReturn(new QualityGateDecision(true, null, "QualityGate granted"));

        var state = new WorldmindState(Map.of(
                "waveTaskIds", List.of("TASK-001", "TASK-002"),
                "tasks", List.of(d1, d2),
                "waveDispatchResults", List.of(passedResult("TASK-001"), passedResult("TASK-002"))
        ));

        var result = node.apply(state);

        var completedIds = (List<String>) result.get("completedTaskIds");
        assertTrue(completedIds.contains("TASK-001"));
        assertTrue(completedIds.contains("TASK-002"));
    }

    @Test
    @DisplayName("CODER that failed at dispatch -> apply failure strategy directly")
    @SuppressWarnings("unchecked")
    void coderFailedAtDispatch() {
        var d = coderTask("TASK-001", 0, 3, FailureStrategy.RETRY);

        var state = new WorldmindState(Map.of(
                "waveTaskIds", List.of("TASK-001"),
                "tasks", List.of(d),
                "waveDispatchResults", List.of(failedResult("TASK-001"))
        ));

        var result = node.apply(state);

        // RETRY: should NOT be in completedIds, and retryContext should be set
        var completedIds = (List<String>) result.get("completedTaskIds");
        assertFalse(completedIds.contains("TASK-001"));
        assertTrue(((String) result.get("retryContext")).contains("TASK-001"));
        // No TESTER/REVIEWER dispatch for a failed task
        verify(mockBridge, never()).executeTask(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("TESTER infrastructure error handled gracefully")
    @SuppressWarnings("unchecked")
    void testerInfrastructureError() {
        var d = coderTask("TASK-001", 0, 3, FailureStrategy.RETRY);

        // TESTER throws, REVIEWER succeeds
        when(mockBridge.executeTask(
                argThat(dir -> dir != null && dir.id().contains("TESTER")), any(), any(), any(), any()))
                .thenThrow(new RuntimeException("Docker down"));
        when(mockBridge.executeTask(
                argThat(dir -> dir != null && dir.id().contains("REVIEWER")), any(), any(), any(), any()))
                .thenReturn(bridgeResult("TASK-001-REVIEWER", "REVIEWER"));
        when(mockQualityGateService.parseReviewOutput(anyString(), anyString()))
                .thenReturn(new ReviewFeedback("TASK-001", true, "OK", List.of(), List.of(), 8));
        when(mockQualityGateService.evaluateQualityGate(any(), any(), any()))
                .thenReturn(new QualityGateDecision(false, FailureStrategy.RETRY, "Tests failed due to infra error"));

        var state = new WorldmindState(Map.of(
                "waveTaskIds", List.of("TASK-001"),
                "tasks", List.of(d),
                "waveDispatchResults", List.of(passedResult("TASK-001"))
        ));

        var result = node.apply(state);

        // Should still produce a result (not crash)
        assertNotNull(result.get("completedTaskIds"));
    }

    @Test
    @DisplayName("CODER with no file changes -> immediately ESCALATES to FAILED (no retry)")
    void coderNoFileChangesEscalatesImmediately() {
        // Even on first iteration with RETRY strategy, empty CODER should escalate
        var d = coderTask("TASK-001", 0, 3, FailureStrategy.RETRY);

        var state = new WorldmindState(Map.of(
                "waveTaskIds", List.of("TASK-001"),
                "tasks", List.of(d),
                "waveDispatchResults", List.of(passedResultNoFiles("TASK-001"))
        ));

        var result = node.apply(state);

        // Should immediately FAIL the mission — no point retrying when agent wrote nothing
        assertEquals(MissionStatus.FAILED.name(), result.get("status"));
        // Should NOT run TESTER/REVIEWER on empty output
        verify(mockBridge, never()).executeTask(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("Retries exhausted -> escalation to FAILED")
    void retriesExhaustedEscalation() {
        // iteration >= maxIterations means retries are exhausted
        var d = coderTask("TASK-001", 3, 3, FailureStrategy.RETRY);

        when(mockBridge.executeTask(any(), any(), any(), any(), any())).thenReturn(bridgeResult("TASK-001", "TESTER"));
        when(mockQualityGateService.parseTestOutput(anyString(), anyString(), anyLong()))
                .thenReturn(new TestResult("TASK-001", false, 10, 3, "FAIL", 500L));
        when(mockQualityGateService.parseReviewOutput(anyString(), anyString()))
                .thenReturn(new ReviewFeedback("TASK-001", false, "Bad", List.of("bug"), List.of(), 3));
        // QualityGateEvaluationService should escalate when retries exhausted
        when(mockQualityGateService.evaluateQualityGate(any(), any(), any()))
                .thenReturn(new QualityGateDecision(false, FailureStrategy.ESCALATE, "Retries exhausted"));

        var state = new WorldmindState(Map.of(
                "waveTaskIds", List.of("TASK-001"),
                "tasks", List.of(d),
                "waveDispatchResults", List.of(passedResult("TASK-001"))
        ));

        var result = node.apply(state);

        assertEquals(MissionStatus.FAILED.name(), result.get("status"));
    }

    // --- DEPLOYER Tests ---

    private Task deployerTask(String id, int iteration, int maxIterations, FailureStrategy onFailure) {
        return new Task(id, "DEPLOYER", "Deploy app", "", "App running", List.of(),
                TaskStatus.PENDING, iteration, maxIterations, onFailure, List.of("manifest.yml"), List.of(), null);
    }

    private WaveDispatchResult deployerSuccessResult(String id) {
        String output = "Pushing app wmnd-2026-0001...\n"
                + "Staging app...\n"
                + "Build succeeded\n"
                + "requested state: started\n"
                + "instances: 1/1\n"
                + "routes: wmnd-2026-0001.apps.tas-tdc.kuhn-labs.com\n"
                + "status: running\n"
                + "App started";
        return new WaveDispatchResult(id, TaskStatus.PASSED, List.of(), output, 30000L);
    }

    private WaveDispatchResult deployerFailureResult(String id) {
        String output = "Pushing app wmnd-2026-0001...\n"
                + "Staging app...\n"
                + "Build succeeded\n"
                + "Start unsuccessful\n"
                + "Error: App crashed during startup";
        return new WaveDispatchResult(id, TaskStatus.PASSED, List.of(), output, 30000L);
    }

    @Test
    @DisplayName("DEPLOYER success -> completedIds updated, no TESTER/REVIEWER dispatched")
    @SuppressWarnings("unchecked")
    void deployerSuccessNoQualityGate() {
        var d = deployerTask("TASK-DEPLOY", 0, 3, FailureStrategy.RETRY);

        var state = new WorldmindState(Map.of(
                "waveTaskIds", List.of("TASK-DEPLOY"),
                "tasks", List.of(d),
                "waveDispatchResults", List.of(deployerSuccessResult("TASK-DEPLOY"))
        ));

        var result = node.apply(state);

        var completedIds = (List<String>) result.get("completedTaskIds");
        assertTrue(completedIds.contains("TASK-DEPLOY"));
        // DEPLOYER does NOT trigger TESTER/REVIEWER
        verify(mockBridge, never()).executeTask(any(), any(), any(), any(), any());
        // No mission failure
        assertNull(result.get("status"));
    }

    @Test
    @DisplayName("DEPLOYER health check failure -> retry with enriched context")
    void deployerHealthCheckFailureRetry() {
        var d = deployerTask("TASK-DEPLOY", 0, 3, FailureStrategy.RETRY);

        var state = new WorldmindState(Map.of(
                "waveTaskIds", List.of("TASK-DEPLOY"),
                "tasks", List.of(d),
                "waveDispatchResults", List.of(deployerFailureResult("TASK-DEPLOY"))
        ));

        var result = node.apply(state);

        // Retry: completedTaskIds may be absent (empty list not written to state)
        @SuppressWarnings("unchecked")
        var completedIds = (List<String>) result.get("completedTaskIds");
        assertTrue(completedIds == null || !completedIds.contains("TASK-DEPLOY"));
        assertTrue(((String) result.get("retryContext")).contains("TASK-DEPLOY"));
        // No TESTER/REVIEWER dispatched
        verify(mockBridge, never()).executeTask(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("DEPLOYER dispatch failure -> retry")
    void deployerDispatchFailureRetry() {
        var d = deployerTask("TASK-DEPLOY", 0, 3, FailureStrategy.RETRY);

        var state = new WorldmindState(Map.of(
                "waveTaskIds", List.of("TASK-DEPLOY"),
                "tasks", List.of(d),
                "waveDispatchResults", List.of(failedResult("TASK-DEPLOY"))
        ));

        var result = node.apply(state);

        // Retry: completedTaskIds may be absent (empty list not written to state)
        @SuppressWarnings("unchecked")
        var completedIds = (List<String>) result.get("completedTaskIds");
        assertTrue(completedIds == null || !completedIds.contains("TASK-DEPLOY"));
        assertTrue(((String) result.get("retryContext")).contains("TASK-DEPLOY"));
    }

    @Test
    @DisplayName("DEPLOYER retries exhausted -> mission FAILED")
    void deployerRetriesExhaustedEscalation() {
        var d = deployerTask("TASK-DEPLOY", 3, 3, FailureStrategy.RETRY);

        var state = new WorldmindState(Map.of(
                "waveTaskIds", List.of("TASK-DEPLOY"),
                "tasks", List.of(d),
                "waveDispatchResults", List.of(deployerFailureResult("TASK-DEPLOY"))
        ));

        var result = node.apply(state);

        assertEquals(MissionStatus.FAILED.name(), result.get("status"));
    }

    @Test
    @DisplayName("DEPLOYER does NOT trigger REVIEWER (skip quality gate entirely)")
    void deployerSkipsReviewer() {
        var d = deployerTask("TASK-DEPLOY", 0, 3, FailureStrategy.RETRY);

        var state = new WorldmindState(Map.of(
                "waveTaskIds", List.of("TASK-DEPLOY"),
                "tasks", List.of(d),
                "waveDispatchResults", List.of(deployerSuccessResult("TASK-DEPLOY"))
        ));

        node.apply(state);

        // Verify NO bridge calls at all — DEPLOYER skips TESTER and REVIEWER
        verify(mockBridge, never()).executeTask(any(), any(), any(), any(), any());
        verify(mockQualityGateService, never()).evaluateQualityGate(any(), any(), any());
    }
}
