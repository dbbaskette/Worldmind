package com.worldmind.core.nodes;

import com.worldmind.core.events.EventBus;
import com.worldmind.core.metrics.WorldmindMetrics;
import com.worldmind.core.model.*;
import com.worldmind.core.scheduler.OscillationDetector;
import com.worldmind.core.seal.SealEvaluationService;
import com.worldmind.core.state.WorldmindState;
import com.worldmind.starblaster.StarblasterBridge;
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

    private StarblasterBridge mockBridge;
    private SealEvaluationService mockSealService;
    private EvaluateWaveNode node;

    @BeforeEach
    void setUp() {
        mockBridge = mock(StarblasterBridge.class);
        mockSealService = mock(SealEvaluationService.class);
        node = new EvaluateWaveNode(mockBridge, mockSealService, new EventBus(), mock(WorldmindMetrics.class), new OscillationDetector());
    }

    private Directive forgeDirective(String id, int iteration, int maxIterations, FailureStrategy onFailure) {
        return new Directive(id, "FORGE", "Do " + id, "", "Done", List.of(),
                DirectiveStatus.PENDING, iteration, maxIterations, onFailure, List.of(), null);
    }

    private Directive nonForgeDirective(String id) {
        return new Directive(id, "PULSE", "Research " + id, "", "Done", List.of(),
                DirectiveStatus.PENDING, 0, 1, FailureStrategy.SKIP, List.of(), null);
    }

    private WaveDispatchResult passedResult(String id) {
        return new WaveDispatchResult(id, DirectiveStatus.PASSED,
                List.of(new FileRecord("test.java", "created", 10)), "Success", 500L);
    }

    private WaveDispatchResult failedResult(String id) {
        return new WaveDispatchResult(id, DirectiveStatus.FAILED, List.of(), "Failed output", 300L);
    }

    private StarblasterBridge.BridgeResult bridgeResult(String id, String centurion) {
        var d = new Directive(id, centurion, "sub", "", "", List.of(),
                DirectiveStatus.PASSED, 1, 1, FailureStrategy.SKIP, List.of(), 100L);
        return new StarblasterBridge.BridgeResult(d,
                new StarblasterInfo("c-" + id, centurion, id, "completed", Instant.now(), Instant.now()),
                "output");
    }

    @Test
    @DisplayName("Single FORGE, seal granted -> completedIds updated")
    @SuppressWarnings("unchecked")
    void singleForgeSealGranted() {
        var d = forgeDirective("DIR-001", 0, 3, FailureStrategy.RETRY);
        when(mockBridge.executeDirective(any(), any(), any())).thenReturn(bridgeResult("DIR-001", "GAUNTLET"));
        when(mockSealService.parseTestOutput(anyString(), anyString(), anyLong()))
                .thenReturn(new TestResult("DIR-001", true, 10, 0, "OK", 500L));
        when(mockSealService.parseReviewOutput(anyString(), anyString()))
                .thenReturn(new ReviewFeedback("DIR-001", true, "Good", List.of(), List.of(), 8));
        when(mockSealService.evaluateSeal(any(), any(), any()))
                .thenReturn(new SealDecision(true, null, "All good"));

        var state = new WorldmindState(Map.of(
                "waveDirectiveIds", List.of("DIR-001"),
                "directives", List.of(d),
                "waveDispatchResults", List.of(passedResult("DIR-001"))
        ));

        var result = node.apply(state);

        var completedIds = (List<String>) result.get("completedDirectiveIds");
        assertTrue(completedIds.contains("DIR-001"));
    }

    @Test
    @DisplayName("Single FORGE, seal denied + RETRY -> NOT in completedIds, retryContext set")
    @SuppressWarnings("unchecked")
    void singleForgeSealDeniedRetry() {
        var d = forgeDirective("DIR-001", 0, 3, FailureStrategy.RETRY);
        when(mockBridge.executeDirective(any(), any(), any())).thenReturn(bridgeResult("DIR-001", "GAUNTLET"));
        when(mockSealService.parseTestOutput(anyString(), anyString(), anyLong()))
                .thenReturn(new TestResult("DIR-001", false, 10, 3, "FAIL", 500L));
        when(mockSealService.parseReviewOutput(anyString(), anyString()))
                .thenReturn(new ReviewFeedback("DIR-001", false, "Issues", List.of("bug"), List.of(), 4));
        when(mockSealService.evaluateSeal(any(), any(), any()))
                .thenReturn(new SealDecision(false, FailureStrategy.RETRY, "Tests failed"));

        var state = new WorldmindState(Map.of(
                "waveDirectiveIds", List.of("DIR-001"),
                "directives", List.of(d),
                "waveDispatchResults", List.of(passedResult("DIR-001"))
        ));

        var result = node.apply(state);

        var completedIds = (List<String>) result.get("completedDirectiveIds");
        assertFalse(completedIds.contains("DIR-001"));
        assertTrue(((String) result.get("retryContext")).contains("DIR-001"));
    }

    @Test
    @DisplayName("Single FORGE, seal denied + SKIP -> in completedIds")
    @SuppressWarnings("unchecked")
    void singleForgeSealDeniedSkip() {
        var d = forgeDirective("DIR-001", 0, 3, FailureStrategy.SKIP);
        when(mockBridge.executeDirective(any(), any(), any())).thenReturn(bridgeResult("DIR-001", "GAUNTLET"));
        when(mockSealService.parseTestOutput(anyString(), anyString(), anyLong()))
                .thenReturn(new TestResult("DIR-001", false, 10, 3, "FAIL", 500L));
        when(mockSealService.parseReviewOutput(anyString(), anyString()))
                .thenReturn(new ReviewFeedback("DIR-001", false, "Issues", List.of("bug"), List.of(), 4));
        when(mockSealService.evaluateSeal(any(), any(), any()))
                .thenReturn(new SealDecision(false, FailureStrategy.SKIP, "Skipping"));

        var state = new WorldmindState(Map.of(
                "waveDirectiveIds", List.of("DIR-001"),
                "directives", List.of(d),
                "waveDispatchResults", List.of(passedResult("DIR-001"))
        ));

        var result = node.apply(state);

        var completedIds = (List<String>) result.get("completedDirectiveIds");
        assertTrue(completedIds.contains("DIR-001"));
    }

    @Test
    @DisplayName("ESCALATE -> mission FAILED")
    void escalateMissionFailed() {
        var d = forgeDirective("DIR-001", 3, 3, FailureStrategy.ESCALATE);
        when(mockBridge.executeDirective(any(), any(), any())).thenReturn(bridgeResult("DIR-001", "GAUNTLET"));
        when(mockSealService.parseTestOutput(anyString(), anyString(), anyLong()))
                .thenReturn(new TestResult("DIR-001", false, 10, 5, "FAIL", 500L));
        when(mockSealService.parseReviewOutput(anyString(), anyString()))
                .thenReturn(new ReviewFeedback("DIR-001", false, "Bad", List.of("critical"), List.of(), 2));
        when(mockSealService.evaluateSeal(any(), any(), any()))
                .thenReturn(new SealDecision(false, FailureStrategy.ESCALATE, "Critical failure"));

        var state = new WorldmindState(Map.of(
                "waveDirectiveIds", List.of("DIR-001"),
                "directives", List.of(d),
                "waveDispatchResults", List.of(passedResult("DIR-001"))
        ));

        var result = node.apply(state);

        assertEquals(MissionStatus.FAILED.name(), result.get("status"));
    }

    @Test
    @DisplayName("Non-FORGE directive -> auto-pass, added to completedIds")
    @SuppressWarnings("unchecked")
    void nonForgeAutoPass() {
        var d = nonForgeDirective("DIR-001");

        var state = new WorldmindState(Map.of(
                "waveDirectiveIds", List.of("DIR-001"),
                "directives", List.of(d),
                "waveDispatchResults", List.of(passedResult("DIR-001"))
        ));

        var result = node.apply(state);

        var completedIds = (List<String>) result.get("completedDirectiveIds");
        assertTrue(completedIds.contains("DIR-001"));
        // No bridge calls for non-FORGE (GAUNTLET/VIGIL not dispatched)
        verify(mockBridge, never()).executeDirective(any(), any(), any());
    }

    @Test
    @DisplayName("Multiple directives in wave, mixed results")
    @SuppressWarnings("unchecked")
    void multipleDirectivesMixedResults() {
        var d1 = forgeDirective("DIR-001", 0, 3, FailureStrategy.RETRY);
        var d2 = nonForgeDirective("DIR-002");

        when(mockBridge.executeDirective(any(), any(), any())).thenReturn(bridgeResult("DIR-001", "GAUNTLET"));
        when(mockSealService.parseTestOutput(anyString(), anyString(), anyLong()))
                .thenReturn(new TestResult("DIR-001", true, 5, 0, "OK", 200L));
        when(mockSealService.parseReviewOutput(anyString(), anyString()))
                .thenReturn(new ReviewFeedback("DIR-001", true, "Good", List.of(), List.of(), 9));
        when(mockSealService.evaluateSeal(any(), any(), any()))
                .thenReturn(new SealDecision(true, null, "Seal granted"));

        var state = new WorldmindState(Map.of(
                "waveDirectiveIds", List.of("DIR-001", "DIR-002"),
                "directives", List.of(d1, d2),
                "waveDispatchResults", List.of(passedResult("DIR-001"), passedResult("DIR-002"))
        ));

        var result = node.apply(state);

        var completedIds = (List<String>) result.get("completedDirectiveIds");
        assertTrue(completedIds.contains("DIR-001"));
        assertTrue(completedIds.contains("DIR-002"));
    }

    @Test
    @DisplayName("FORGE that failed at dispatch -> apply failure strategy directly")
    @SuppressWarnings("unchecked")
    void forgeFailedAtDispatch() {
        var d = forgeDirective("DIR-001", 0, 3, FailureStrategy.RETRY);

        var state = new WorldmindState(Map.of(
                "waveDirectiveIds", List.of("DIR-001"),
                "directives", List.of(d),
                "waveDispatchResults", List.of(failedResult("DIR-001"))
        ));

        var result = node.apply(state);

        // RETRY: should NOT be in completedIds, and retryContext should be set
        var completedIds = (List<String>) result.get("completedDirectiveIds");
        assertFalse(completedIds.contains("DIR-001"));
        assertTrue(((String) result.get("retryContext")).contains("DIR-001"));
        // No GAUNTLET/VIGIL dispatch for a failed directive
        verify(mockBridge, never()).executeDirective(any(), any(), any());
    }

    @Test
    @DisplayName("GAUNTLET infrastructure error handled gracefully")
    @SuppressWarnings("unchecked")
    void gauntletInfrastructureError() {
        var d = forgeDirective("DIR-001", 0, 3, FailureStrategy.RETRY);

        // GAUNTLET throws, VIGIL succeeds
        when(mockBridge.executeDirective(
                argThat(dir -> dir != null && dir.id().contains("GAUNTLET")), any(), any()))
                .thenThrow(new RuntimeException("Docker down"));
        when(mockBridge.executeDirective(
                argThat(dir -> dir != null && dir.id().contains("VIGIL")), any(), any()))
                .thenReturn(bridgeResult("DIR-001-VIGIL", "VIGIL"));
        when(mockSealService.parseReviewOutput(anyString(), anyString()))
                .thenReturn(new ReviewFeedback("DIR-001", true, "OK", List.of(), List.of(), 8));
        when(mockSealService.evaluateSeal(any(), any(), any()))
                .thenReturn(new SealDecision(false, FailureStrategy.RETRY, "Tests failed due to infra error"));

        var state = new WorldmindState(Map.of(
                "waveDirectiveIds", List.of("DIR-001"),
                "directives", List.of(d),
                "waveDispatchResults", List.of(passedResult("DIR-001"))
        ));

        var result = node.apply(state);

        // Should still produce a result (not crash)
        assertNotNull(result.get("completedDirectiveIds"));
    }

    @Test
    @DisplayName("Retries exhausted -> escalation to FAILED")
    void retriesExhaustedEscalation() {
        // iteration >= maxIterations means retries are exhausted
        var d = forgeDirective("DIR-001", 3, 3, FailureStrategy.RETRY);

        when(mockBridge.executeDirective(any(), any(), any())).thenReturn(bridgeResult("DIR-001", "GAUNTLET"));
        when(mockSealService.parseTestOutput(anyString(), anyString(), anyLong()))
                .thenReturn(new TestResult("DIR-001", false, 10, 3, "FAIL", 500L));
        when(mockSealService.parseReviewOutput(anyString(), anyString()))
                .thenReturn(new ReviewFeedback("DIR-001", false, "Bad", List.of("bug"), List.of(), 3));
        // SealEvaluationService should escalate when retries exhausted
        when(mockSealService.evaluateSeal(any(), any(), any()))
                .thenReturn(new SealDecision(false, FailureStrategy.ESCALATE, "Retries exhausted"));

        var state = new WorldmindState(Map.of(
                "waveDirectiveIds", List.of("DIR-001"),
                "directives", List.of(d),
                "waveDispatchResults", List.of(passedResult("DIR-001"))
        ));

        var result = node.apply(state);

        assertEquals(MissionStatus.FAILED.name(), result.get("status"));
    }
}
