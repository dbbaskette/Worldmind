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
        assertTrue(completedIds == null || !completedIds.contains("TASK-001"));
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
        assertTrue(completedIds == null || !completedIds.contains("TASK-001"));
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

        // Should still produce a result (not crash) — retryContext is set for RETRY
        assertNotNull(result);
        assertTrue(result.containsKey("retryContext") || result.containsKey("completedTaskIds"),
                "Result should contain either retryContext or completedTaskIds");
    }

    @Test
    @DisplayName("CODER with no file changes -> retries (TESTER/REVIEWER not dispatched)")
    void coderNoFileChangesRetries() {
        var d = coderTask("TASK-001", 0, 3, FailureStrategy.RETRY);

        var state = new WorldmindState(Map.of(
                "waveTaskIds", List.of("TASK-001"),
                "tasks", List.of(d),
                "waveDispatchResults", List.of(passedResultNoFiles("TASK-001"))
        ));

        var result = node.apply(state);

        // Should retry — gives the agent another chance to produce code
        assertTrue(((String) result.get("retryContext")).contains("TASK-001"));
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

    private WaveDispatchResult deployerBuildFailureResult(String id) {
        String output = "Running mvn package...\n"
                + "[ERROR] Failed to execute goal org.apache.maven.plugins:maven-compiler-plugin\n"
                + "[ERROR] BUILD FAILURE\n"
                + "[ERROR] Total time: 5.2 s\n"
                + "[ERROR] Compilation failure: cannot find symbol";
        return new WaveDispatchResult(id, TaskStatus.PASSED, List.of(), output, 15000L);
    }

    private WaveDispatchResult deployerStagingFailureResult(String id) {
        String output = "Pushing app wmnd-2026-0001...\n"
                + "Staging app...\n"
                + "Staging error: Unable to detect buildpack\n"
                + "Error staging application: StagingError";
        return new WaveDispatchResult(id, TaskStatus.PASSED, List.of(), output, 20000L);
    }

    private WaveDispatchResult deployerCrashedResult(String id) {
        String output = "Pushing app wmnd-2026-0001...\n"
                + "Staging app...\n"
                + "Build succeeded\n"
                + "Waiting for app to start...\n"
                + "App instance exited with CRASHED status\n"
                + "Out of memory: Java heap space";
        return new WaveDispatchResult(id, TaskStatus.PASSED, List.of(), output, 30000L);
    }

    private WaveDispatchResult deployerHealthCheckTimeoutResult(String id) {
        String output = "Pushing app wmnd-2026-0001...\n"
                + "Staging app...\n"
                + "Build succeeded\n"
                + "Waiting for app to start...\n"
                + "health check timeout after 300 seconds";
        return new WaveDispatchResult(id, TaskStatus.PASSED, List.of(), output, 300000L);
    }

    private WaveDispatchResult deployerServiceBindingFailureResult(String id) {
        String output = "Pushing app wmnd-2026-0001...\n"
                + "Binding service my-db...\n"
                + "FAILED\n"
                + "Could not find service my-db in org my-org / space my-space";
        return new WaveDispatchResult(id, TaskStatus.PASSED, List.of(), output, 10000L);
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
        // Deployment URL is captured in task output on success
        String url = (String) result.get("deploymentUrl");
        assertNotNull(url, "Deployment URL should be captured on DEPLOYER success");
        assertTrue(url.contains("wmnd-2026-0001"), "Deployment URL should contain the app route");
    }

    @Test
    @DisplayName("DEPLOYER app crash failure -> retry with enriched context")
    void deployerAppCrashFailureRetry() {
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

    @Test
    @DisplayName("DEPLOYER success -> deployment URL captured in result")
    void deployerSuccessCapturesUrl() {
        var d = deployerTask("TASK-DEPLOY", 0, 3, FailureStrategy.RETRY);

        var state = new WorldmindState(Map.of(
                "waveTaskIds", List.of("TASK-DEPLOY"),
                "tasks", List.of(d),
                "waveDispatchResults", List.of(deployerSuccessResult("TASK-DEPLOY"))
        ));

        var result = node.apply(state);

        String url = (String) result.get("deploymentUrl");
        assertNotNull(url, "Deployment URL should be captured on success");
        assertTrue(url.contains("wmnd-2026-0001.apps.tas-tdc.kuhn-labs.com"));
    }

    @Test
    @DisplayName("DEPLOYER Maven BUILD FAILURE -> retry with build failure diagnosis")
    @SuppressWarnings("unchecked")
    void deployerBuildFailureRetry() {
        var d = deployerTask("TASK-DEPLOY", 0, 3, FailureStrategy.RETRY);

        var state = new WorldmindState(Map.of(
                "waveTaskIds", List.of("TASK-DEPLOY"),
                "tasks", List.of(d),
                "waveDispatchResults", List.of(deployerBuildFailureResult("TASK-DEPLOY"))
        ));

        var result = node.apply(state);

        var completedIds = (List<String>) result.get("completedTaskIds");
        assertTrue(completedIds == null || !completedIds.contains("TASK-DEPLOY"));
        String ctx = (String) result.get("retryContext");
        assertTrue(ctx.contains("TASK-DEPLOY"));

        // Verify updated task has enriched context mentioning build failure
        var tasks = (List<Task>) result.get("tasks");
        assertNotNull(tasks);
        Task retryTask = tasks.stream().filter(t -> t.id().equals("TASK-DEPLOY")).findFirst().orElse(null);
        assertNotNull(retryTask);
        assertTrue(retryTask.inputContext().contains("BUILD_FAILURE"),
                "Retry context should include BUILD_FAILURE diagnosis");
        assertTrue(retryTask.inputContext().contains("pom.xml"),
                "Retry context should suggest fixing dependencies");
    }

    @Test
    @DisplayName("DEPLOYER staging failure -> retry with staging failure diagnosis")
    @SuppressWarnings("unchecked")
    void deployerStagingFailureRetry() {
        var d = deployerTask("TASK-DEPLOY", 0, 3, FailureStrategy.RETRY);

        var state = new WorldmindState(Map.of(
                "waveTaskIds", List.of("TASK-DEPLOY"),
                "tasks", List.of(d),
                "waveDispatchResults", List.of(deployerStagingFailureResult("TASK-DEPLOY"))
        ));

        var result = node.apply(state);

        var tasks = (List<Task>) result.get("tasks");
        assertNotNull(tasks);
        Task retryTask = tasks.stream().filter(t -> t.id().equals("TASK-DEPLOY")).findFirst().orElse(null);
        assertNotNull(retryTask);
        assertTrue(retryTask.inputContext().contains("STAGING_FAILURE"),
                "Retry context should include STAGING_FAILURE diagnosis");
        assertTrue(retryTask.inputContext().contains("buildpack"),
                "Retry context should mention buildpack");
    }

    @Test
    @DisplayName("DEPLOYER app CRASHED with OOM -> retry with memory increase suggestion")
    @SuppressWarnings("unchecked")
    void deployerCrashedWithOomRetry() {
        var d = deployerTask("TASK-DEPLOY", 0, 3, FailureStrategy.RETRY);

        var state = new WorldmindState(Map.of(
                "waveTaskIds", List.of("TASK-DEPLOY"),
                "tasks", List.of(d),
                "waveDispatchResults", List.of(deployerCrashedResult("TASK-DEPLOY"))
        ));

        var result = node.apply(state);

        var tasks = (List<Task>) result.get("tasks");
        assertNotNull(tasks);
        Task retryTask = tasks.stream().filter(t -> t.id().equals("TASK-DEPLOY")).findFirst().orElse(null);
        assertNotNull(retryTask);
        assertTrue(retryTask.inputContext().contains("APP_CRASHED"),
                "Retry context should include APP_CRASHED diagnosis");
        assertTrue(retryTask.inputContext().contains("memory"),
                "Retry context should suggest increasing memory");
    }

    @Test
    @DisplayName("DEPLOYER health check timeout -> retry with timeout suggestion")
    @SuppressWarnings("unchecked")
    void deployerHealthCheckTimeoutRetry() {
        var d = deployerTask("TASK-DEPLOY", 0, 3, FailureStrategy.RETRY);

        var state = new WorldmindState(Map.of(
                "waveTaskIds", List.of("TASK-DEPLOY"),
                "tasks", List.of(d),
                "waveDispatchResults", List.of(deployerHealthCheckTimeoutResult("TASK-DEPLOY"))
        ));

        var result = node.apply(state);

        var tasks = (List<Task>) result.get("tasks");
        assertNotNull(tasks);
        Task retryTask = tasks.stream().filter(t -> t.id().equals("TASK-DEPLOY")).findFirst().orElse(null);
        assertNotNull(retryTask);
        assertTrue(retryTask.inputContext().contains("HEALTH_CHECK_TIMEOUT"),
                "Retry context should include HEALTH_CHECK_TIMEOUT diagnosis");
        assertTrue(retryTask.inputContext().contains("health-check-timeout"),
                "Retry context should suggest increasing timeout");
    }

    @Test
    @DisplayName("DEPLOYER service binding failure -> retry with pre-create service suggestion")
    @SuppressWarnings("unchecked")
    void deployerServiceBindingFailureRetry() {
        var d = deployerTask("TASK-DEPLOY", 0, 3, FailureStrategy.RETRY);

        var state = new WorldmindState(Map.of(
                "waveTaskIds", List.of("TASK-DEPLOY"),
                "tasks", List.of(d),
                "waveDispatchResults", List.of(deployerServiceBindingFailureResult("TASK-DEPLOY"))
        ));

        var result = node.apply(state);

        var tasks = (List<Task>) result.get("tasks");
        assertNotNull(tasks);
        Task retryTask = tasks.stream().filter(t -> t.id().equals("TASK-DEPLOY")).findFirst().orElse(null);
        assertNotNull(retryTask);
        assertTrue(retryTask.inputContext().contains("SERVICE_BINDING_FAILURE"),
                "Retry context should include SERVICE_BINDING_FAILURE diagnosis");
        assertTrue(retryTask.inputContext().contains("cf create-service"),
                "Retry context should suggest pre-creating the service instance");
    }

    @Test
    @DisplayName("DEPLOYER retries exhausted -> error message includes actionable failure reason")
    @SuppressWarnings("unchecked")
    void deployerRetriesExhaustedIncludesActionableError() {
        var d = deployerTask("TASK-DEPLOY", 3, 3, FailureStrategy.RETRY);

        var state = new WorldmindState(Map.of(
                "waveTaskIds", List.of("TASK-DEPLOY"),
                "tasks", List.of(d),
                "waveDispatchResults", List.of(deployerBuildFailureResult("TASK-DEPLOY"))
        ));

        var result = node.apply(state);

        assertEquals(MissionStatus.FAILED.name(), result.get("status"));
        var errors = (List<String>) result.get("errors");
        assertNotNull(errors);
        assertFalse(errors.isEmpty());
        // Should include "Deployment failed" with actionable context
        assertTrue(errors.stream().anyMatch(e -> e.contains("Deployment failed")),
                "Error should mention deployment failure");
        assertTrue(errors.stream().anyMatch(e -> e.contains("pom.xml")),
                "Build failure error should mention pom.xml dependencies");
    }

    @Test
    @DisplayName("DEPLOYER service binding exhausted -> error includes service name")
    @SuppressWarnings("unchecked")
    void deployerServiceBindingExhaustedIncludesServiceName() {
        var d = deployerTask("TASK-DEPLOY", 3, 3, FailureStrategy.RETRY);

        var state = new WorldmindState(Map.of(
                "waveTaskIds", List.of("TASK-DEPLOY"),
                "tasks", List.of(d),
                "waveDispatchResults", List.of(deployerServiceBindingFailureResult("TASK-DEPLOY"))
        ));

        var result = node.apply(state);

        assertEquals(MissionStatus.FAILED.name(), result.get("status"));
        var errors = (List<String>) result.get("errors");
        assertNotNull(errors);
        assertTrue(errors.stream().anyMatch(e -> e.contains("my-db")),
                "Service binding error should include the actual service name 'my-db'");
    }

    @Test
    @DisplayName("DEPLOYER health check exhausted -> error mentions timeout and endpoint")
    @SuppressWarnings("unchecked")
    void deployerHealthCheckExhaustedMentionsTimeout() {
        var d = deployerTask("TASK-DEPLOY", 3, 3, FailureStrategy.RETRY);

        var state = new WorldmindState(Map.of(
                "waveTaskIds", List.of("TASK-DEPLOY"),
                "tasks", List.of(d),
                "waveDispatchResults", List.of(deployerHealthCheckTimeoutResult("TASK-DEPLOY"))
        ));

        var result = node.apply(state);

        assertEquals(MissionStatus.FAILED.name(), result.get("status"));
        var errors = (List<String>) result.get("errors");
        assertNotNull(errors);
        assertTrue(errors.stream().anyMatch(e -> e.contains("Health check timeout")),
                "Health check error should mention health check timeout");
        assertTrue(errors.stream().anyMatch(e -> e.contains("/actuator/health")),
                "Health check error should mention the health check endpoint");
    }

    @Test
    @DisplayName("DEPLOYER crash with OOM exhausted -> error suggests memory increase")
    @SuppressWarnings("unchecked")
    void deployerCrashOomExhaustedSuggestsMemory() {
        var d = deployerTask("TASK-DEPLOY", 3, 3, FailureStrategy.RETRY);

        var state = new WorldmindState(Map.of(
                "waveTaskIds", List.of("TASK-DEPLOY"),
                "tasks", List.of(d),
                "waveDispatchResults", List.of(deployerCrashedResult("TASK-DEPLOY"))
        ));

        var result = node.apply(state);

        assertEquals(MissionStatus.FAILED.name(), result.get("status"));
        var errors = (List<String>) result.get("errors");
        assertNotNull(errors);
        assertTrue(errors.stream().anyMatch(e -> e.contains("memory")),
                "Crash error with OOM should mention memory");
    }

    @Test
    @DisplayName("DEPLOYER crash without OOM -> error suggests checking crash logs, not memory")
    @SuppressWarnings("unchecked")
    void deployerCrashWithoutOomSuggestsCrashLogs() {
        var d = deployerTask("TASK-DEPLOY", 0, 3, FailureStrategy.RETRY);

        // Output contains CRASHED but no memory/OOM keywords
        String output = "Pushing app wmnd-2026-0001...\n"
                + "Staging app...\n"
                + "Build succeeded\n"
                + "Waiting for app to start...\n"
                + "App instance exited with CRASHED status\n"
                + "exit status 1";
        var crashedNoOomResult = new WaveDispatchResult("TASK-DEPLOY", TaskStatus.PASSED, List.of(), output, 30000L);

        var state = new WorldmindState(Map.of(
                "waveTaskIds", List.of("TASK-DEPLOY"),
                "tasks", List.of(d),
                "waveDispatchResults", List.of(crashedNoOomResult)
        ));

        var result = node.apply(state);

        var tasks = (List<Task>) result.get("tasks");
        assertNotNull(tasks);
        Task retryTask = tasks.stream().filter(t -> t.id().equals("TASK-DEPLOY")).findFirst().orElse(null);
        assertNotNull(retryTask);
        assertTrue(retryTask.inputContext().contains("crash logs"),
                "Non-OOM crash context should mention crash logs");
        assertFalse(retryTask.inputContext().contains("memory"),
                "Non-OOM crash context should NOT mention memory");
    }

    @Test
    @DisplayName("'timed out' without health context -> not matched as HEALTH_CHECK_TIMEOUT")
    @SuppressWarnings("unchecked")
    void timedOutWithoutHealthContextNotMatchedAsHealthCheck() {
        var d = deployerTask("TASK-DEPLOY", 0, 3, FailureStrategy.RETRY);

        // Output contains "timed out" but NOT in a health check context
        String output = "Pushing app wmnd-2026-0001...\n"
                + "Running mvn package...\n"
                + "Connection timed out\n"
                + "Build succeeded\n"
                + "App started\n"
                + "status: running";
        var timedOutResult = new WaveDispatchResult("TASK-DEPLOY", TaskStatus.PASSED, List.of(), output, 30000L);

        var state = new WorldmindState(Map.of(
                "waveTaskIds", List.of("TASK-DEPLOY"),
                "tasks", List.of(d),
                "waveDispatchResults", List.of(timedOutResult)
        ));

        var result = node.apply(state);

        // "timed out" without "health" should NOT trigger HEALTH_CHECK_TIMEOUT diagnosis;
        // since the output also contains "App started" + "status: running", deployment is successful
        var completedIds = (List<String>) result.get("completedTaskIds");
        assertTrue(completedIds != null && completedIds.contains("TASK-DEPLOY"),
                "Deployment with non-health 'timed out' and success markers should be successful");
    }

    @Test
    @DisplayName("DEPLOYER service binding failure with unrecognized format -> null serviceName handled gracefully")
    @SuppressWarnings("unchecked")
    void deployerServiceBindingFailureNullServiceName() {
        var d = deployerTask("TASK-DEPLOY", 0, 3, FailureStrategy.RETRY);

        // Output triggers the service binding condition ("service binding failed")
        // but does NOT match either extractServiceName regex pattern, so
        // extractServiceName returns null. The diagnosis must not NPE or interpolate "null".
        String output = "Pushing app wmnd-2026-0001...\n"
                + "Binding services...\n"
                + "FAILED\n"
                + "Server error, status code: 502, error code: 10001, "
                + "message: Service binding failed due to internal platform error";
        var bindingFailedResult = new WaveDispatchResult("TASK-DEPLOY", TaskStatus.PASSED, List.of(), output, 10000L);

        var state = new WorldmindState(Map.of(
                "waveTaskIds", List.of("TASK-DEPLOY"),
                "tasks", List.of(d),
                "waveDispatchResults", List.of(bindingFailedResult)
        ));

        var result = node.apply(state);

        // Should retry (not crash with NPE)
        var completedIds = (List<String>) result.get("completedTaskIds");
        assertTrue(completedIds == null || !completedIds.contains("TASK-DEPLOY"),
                "Service binding failure should not be completed");
        assertTrue(((String) result.get("retryContext")).contains("TASK-DEPLOY"));

        // Verify the retry context uses the generic fallback message (no "null" interpolation)
        var tasks = (List<Task>) result.get("tasks");
        assertNotNull(tasks);
        Task retryTask = tasks.stream().filter(t -> t.id().equals("TASK-DEPLOY")).findFirst().orElse(null);
        assertNotNull(retryTask);
        assertTrue(retryTask.inputContext().contains("SERVICE_BINDING_FAILURE"),
                "Retry context should include SERVICE_BINDING_FAILURE diagnosis");
        assertFalse(retryTask.inputContext().contains("Service 'null'"),
                "Null service name must not produce 'Service 'null'' in the message");
        assertTrue(retryTask.inputContext().contains("CF service binding failed"),
                "Should use the generic fallback message when service name is null");
    }

    @Test
    @DisplayName("Realistic CF CLI health-check timeout -> HEALTH_CHECK_TIMEOUT diagnosis")
    @SuppressWarnings("unchecked")
    void deployerRealisticCfHealthCheckTimeout() {
        var d = deployerTask("TASK-DEPLOY", 0, 3, FailureStrategy.RETRY);

        // Realistic CF CLI output for a health-check timeout — modeled on actual
        // 'cf push' output when the app starts but the health check never passes.
        String output = "Pushing app my-java-app to org myorg / space dev as admin...\n"
                + "Mapping routes...\n"
                + "Comparing local files to remote cache...\n"
                + "Packaging files to upload...\n"
                + "Uploading files...\n"
                + " 539.35 KiB / 539.35 KiB [=========================================] 100.00% 1s\n"
                + "Waiting for API to complete processing files...\n"
                + "\n"
                + "Staging app and tracing logs...\n"
                + "   Downloading java_buildpack...\n"
                + "   Downloaded java_buildpack\n"
                + "   Cell abc-123 creating container for instance xyz-456\n"
                + "   Cell abc-123 successfully created container for instance xyz-456\n"
                + "   Downloading app package...\n"
                + "   Downloaded app package (12.3M)\n"
                + "   -----> Java Buildpack v4.50\n"
                + "   -----> Downloading Open JDK JRE 11.0.15_10\n"
                + "   Exit status 0\n"
                + "   Uploading droplet, build artifacts cache...\n"
                + "   Uploading droplet...\n"
                + "   Uploaded droplet (78.2M)\n"
                + "   Uploading complete\n"
                + "\n"
                + "Waiting for app my-java-app to start...\n"
                + "\n"
                + "Start unsuccessful\n"
                + "\n"
                + "TIP: use 'cf logs my-java-app --recent' for more information\n"
                + "\n"
                + "FAILED\n"
                + "\n"
                + "Error: Start app timeout\n"
                + "\n"
                + "Timed out waiting for health check to pass for app instance\n";
        var realisticHealthCheckResult = new WaveDispatchResult("TASK-DEPLOY", TaskStatus.PASSED, List.of(), output, 300000L);

        var state = new WorldmindState(Map.of(
                "waveTaskIds", List.of("TASK-DEPLOY"),
                "tasks", List.of(d),
                "waveDispatchResults", List.of(realisticHealthCheckResult)
        ));

        var result = node.apply(state);

        // Should be diagnosed as HEALTH_CHECK_TIMEOUT
        var tasks = (List<Task>) result.get("tasks");
        assertNotNull(tasks);
        Task retryTask = tasks.stream().filter(t -> t.id().equals("TASK-DEPLOY")).findFirst().orElse(null);
        assertNotNull(retryTask);
        assertTrue(retryTask.inputContext().contains("HEALTH_CHECK_TIMEOUT"),
                "Realistic CF health check timeout output should be diagnosed as HEALTH_CHECK_TIMEOUT");
        assertTrue(retryTask.inputContext().contains("health-check-timeout"),
                "Retry context should suggest increasing health-check-timeout in manifest.yml");
    }

    @Test
    @DisplayName("CF health check failure variant -> HEALTH_CHECK_TIMEOUT diagnosis")
    @SuppressWarnings("unchecked")
    void deployerCfHealthCheckFailureVariant() {
        var d = deployerTask("TASK-DEPLOY", 0, 3, FailureStrategy.RETRY);

        // Another realistic variant: CF reports health check explicitly failed
        String output = "Pushing app my-spring-app to org prod / space production...\n"
                + "Staging app...\n"
                + "Build succeeded\n"
                + "Waiting for app to start...\n"
                + "   instances starting...\n"
                + "   instances starting...\n"
                + "   instances starting...\n"
                + "health check for process \"web\" did not pass: fail\n"
                + "\n"
                + "TIP: use 'cf logs my-spring-app --recent'\n"
                + "FAILED\n";
        var healthCheckFailResult = new WaveDispatchResult("TASK-DEPLOY", TaskStatus.PASSED, List.of(), output, 180000L);

        var state = new WorldmindState(Map.of(
                "waveTaskIds", List.of("TASK-DEPLOY"),
                "tasks", List.of(d),
                "waveDispatchResults", List.of(healthCheckFailResult)
        ));

        var result = node.apply(state);

        // Should be diagnosed as HEALTH_CHECK_TIMEOUT via the (health check + fail) condition
        var tasks = (List<Task>) result.get("tasks");
        assertNotNull(tasks);
        Task retryTask = tasks.stream().filter(t -> t.id().equals("TASK-DEPLOY")).findFirst().orElse(null);
        assertNotNull(retryTask);
        assertTrue(retryTask.inputContext().contains("HEALTH_CHECK_TIMEOUT"),
                "Health check failure variant should be diagnosed as HEALTH_CHECK_TIMEOUT");
    }

    @Test
    @DisplayName("DEPLOYER staging failure exhausted -> error mentions staging logs")
    @SuppressWarnings("unchecked")
    void deployerStagingFailureExhaustedMentionsStagingLogs() {
        var d = deployerTask("TASK-DEPLOY", 3, 3, FailureStrategy.RETRY);

        var state = new WorldmindState(Map.of(
                "waveTaskIds", List.of("TASK-DEPLOY"),
                "tasks", List.of(d),
                "waveDispatchResults", List.of(deployerStagingFailureResult("TASK-DEPLOY"))
        ));

        var result = node.apply(state);

        assertEquals(MissionStatus.FAILED.name(), result.get("status"));
        var errors = (List<String>) result.get("errors");
        assertNotNull(errors);
        assertTrue(errors.stream().anyMatch(e -> e.contains("staging")),
                "Staging failure error should mention staging");
    }

    @Test
    @DisplayName("DEPLOYER success with single-subdomain route -> URL captured")
    void deployerSuccessSingleSubdomainRoute() {
        var d = deployerTask("TASK-DEPLOY", 0, 3, FailureStrategy.RETRY);

        // Single-subdomain route (e.g., myapp.example.com — only one dot)
        String output = "Pushing app myapp...\n"
                + "Staging app...\n"
                + "Build succeeded\n"
                + "routes: myapp.example.com\n"
                + "status: running\n"
                + "App started";
        var singleSubdomainResult = new WaveDispatchResult("TASK-DEPLOY", TaskStatus.PASSED, List.of(), output, 30000L);

        var state = new WorldmindState(Map.of(
                "waveTaskIds", List.of("TASK-DEPLOY"),
                "tasks", List.of(d),
                "waveDispatchResults", List.of(singleSubdomainResult)
        ));

        var result = node.apply(state);

        String url = (String) result.get("deploymentUrl");
        assertNotNull(url, "Single-subdomain route should be captured");
        assertTrue(url.contains("myapp.example.com"), "URL should contain the single-subdomain route");
    }

    @Test
    @DisplayName("DEPLOYER with null output -> UNKNOWN diagnosis and retry")
    @SuppressWarnings("unchecked")
    void deployerNullOutputRetry() {
        var d = deployerTask("TASK-DEPLOY", 0, 3, FailureStrategy.RETRY);

        var nullOutputResult = new WaveDispatchResult("TASK-DEPLOY", TaskStatus.PASSED, List.of(), null, 30000L);

        var state = new WorldmindState(Map.of(
                "waveTaskIds", List.of("TASK-DEPLOY"),
                "tasks", List.of(d),
                "waveDispatchResults", List.of(nullOutputResult)
        ));

        var result = node.apply(state);

        // Null output -> isDeploymentSuccessful returns false -> diagnoseDeploymentFailure returns UNKNOWN
        var completedIds = (List<String>) result.get("completedTaskIds");
        assertTrue(completedIds == null || !completedIds.contains("TASK-DEPLOY"),
                "DEPLOYER with null output should not be completed");
        assertTrue(((String) result.get("retryContext")).contains("TASK-DEPLOY"));

        var tasks = (List<Task>) result.get("tasks");
        assertNotNull(tasks);
        Task retryTask = tasks.stream().filter(t -> t.id().equals("TASK-DEPLOY")).findFirst().orElse(null);
        assertNotNull(retryTask);
        assertTrue(retryTask.inputContext().contains("UNKNOWN"),
                "Null output should produce UNKNOWN diagnosis");
    }

    @Test
    @DisplayName("DEPLOYER with empty output -> UNKNOWN diagnosis and retry")
    @SuppressWarnings("unchecked")
    void deployerEmptyOutputRetry() {
        var d = deployerTask("TASK-DEPLOY", 0, 3, FailureStrategy.RETRY);

        var emptyOutputResult = new WaveDispatchResult("TASK-DEPLOY", TaskStatus.PASSED, List.of(), "   ", 30000L);

        var state = new WorldmindState(Map.of(
                "waveTaskIds", List.of("TASK-DEPLOY"),
                "tasks", List.of(d),
                "waveDispatchResults", List.of(emptyOutputResult)
        ));

        var result = node.apply(state);

        var completedIds = (List<String>) result.get("completedTaskIds");
        assertTrue(completedIds == null || !completedIds.contains("TASK-DEPLOY"),
                "DEPLOYER with empty output should not be completed");
        assertTrue(((String) result.get("retryContext")).contains("TASK-DEPLOY"));

        var tasks = (List<Task>) result.get("tasks");
        assertNotNull(tasks);
        Task retryTask = tasks.stream().filter(t -> t.id().equals("TASK-DEPLOY")).findFirst().orElse(null);
        assertNotNull(retryTask);
        assertTrue(retryTask.inputContext().contains("UNKNOWN"),
                "Empty output should produce UNKNOWN diagnosis");
    }

    @Test
    @DisplayName("Benign 'exit status 0' does not trigger APP_CRASHED")
    @SuppressWarnings("unchecked")
    void benignExitStatusZeroNotAppCrashed() {
        var d = deployerTask("TASK-DEPLOY", 0, 3, FailureStrategy.RETRY);

        // Output contains "exit status" in a benign context (exit status 0 = success)
        String output = "Pushing app wmnd-2026-0001...\n"
                + "Staging app...\n"
                + "Build succeeded\n"
                + "checking exit status: 0\n"
                + "exit status code verified\n"
                + "App started\n"
                + "status: running";
        var benignExitResult = new WaveDispatchResult("TASK-DEPLOY", TaskStatus.PASSED, List.of(), output, 30000L);

        var state = new WorldmindState(Map.of(
                "waveTaskIds", List.of("TASK-DEPLOY"),
                "tasks", List.of(d),
                "waveDispatchResults", List.of(benignExitResult)
        ));

        var result = node.apply(state);

        // Should succeed — benign "exit status" mentions should not trigger APP_CRASHED
        var completedIds = (List<String>) result.get("completedTaskIds");
        assertTrue(completedIds != null && completedIds.contains("TASK-DEPLOY"),
                "Benign 'exit status 0' should not prevent successful deployment");
    }

    @Test
    @DisplayName("DEPLOYER success via 'OK' status line from cf push")
    @SuppressWarnings("unchecked")
    void deployerSuccessViaOkStatus() {
        var d = deployerTask("TASK-DEPLOY", 0, 3, FailureStrategy.RETRY);

        // cf push output with bare "OK" status line
        String output = "Pushing app wmnd-2026-0001...\n"
                + "Staging app...\n"
                + "OK\n"
                + "routes: wmnd-2026-0001.apps.example.com";
        var okResult = new WaveDispatchResult("TASK-DEPLOY", TaskStatus.PASSED, List.of(), output, 30000L);

        var state = new WorldmindState(Map.of(
                "waveTaskIds", List.of("TASK-DEPLOY"),
                "tasks", List.of(d),
                "waveDispatchResults", List.of(okResult)
        ));

        var result = node.apply(state);

        var completedIds = (List<String>) result.get("completedTaskIds");
        assertTrue(completedIds != null && completedIds.contains("TASK-DEPLOY"),
                "Deployment with 'OK' status line should be successful");
        String url = (String) result.get("deploymentUrl");
        assertNotNull(url, "Deployment URL should be captured");
    }

    @Test
    @DisplayName("DEPLOYER failure with success+failure markers -> failure takes precedence")
    @SuppressWarnings("unchecked")
    void deployerFailureMarkersTakePrecedence() {
        var d = deployerTask("TASK-DEPLOY", 0, 3, FailureStrategy.RETRY);

        // Output contains both "app started" AND "CRASHED" — failure should win
        String output = "Pushing app wmnd-2026-0001...\n"
                + "App started\n"
                + "instances: 1/1\n"
                + "status: running\n"
                + "App instance exited with CRASHED status\n"
                + "Process has crashed with type: web";
        var mixedResult = new WaveDispatchResult("TASK-DEPLOY", TaskStatus.PASSED, List.of(), output, 30000L);

        var state = new WorldmindState(Map.of(
                "waveTaskIds", List.of("TASK-DEPLOY"),
                "tasks", List.of(d),
                "waveDispatchResults", List.of(mixedResult)
        ));

        var result = node.apply(state);

        // Should NOT be in completedIds — failure markers override success
        var completedIds = (List<String>) result.get("completedTaskIds");
        assertTrue(completedIds == null || !completedIds.contains("TASK-DEPLOY"),
                "Task should not be completed when failure markers are present");
        assertNull(result.get("deploymentUrl"),
                "No deployment URL on failure");
    }
}
