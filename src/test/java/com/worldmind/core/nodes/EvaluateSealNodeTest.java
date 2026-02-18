package com.worldmind.core.nodes;

import com.worldmind.core.model.*;
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

/**
 * Unit tests for {@link EvaluateSealNode}.
 * <p>
 * Uses Mockito to mock {@link StarblasterBridge} and {@link SealEvaluationService}
 * so that no real Docker containers are launched and no LLM calls are made.
 */
class EvaluateSealNodeTest {

    private StarblasterBridge bridge;
    private SealEvaluationService sealService;
    private EvaluateSealNode node;

    @BeforeEach
    void setUp() {
        bridge = mock(StarblasterBridge.class);
        sealService = mock(SealEvaluationService.class);
        node = new EvaluateSealNode(bridge, sealService);
    }

    // ── Helper methods ──────────────────────────────────────────────

    private Directive forgeDirective(DirectiveStatus status, int iteration, int maxIterations,
                                      FailureStrategy onFailure) {
        return new Directive(
                "DIR-001", "FORGE", "Implement feature",
                "context", "Feature works",
                List.of(), status, iteration, maxIterations,
                onFailure, List.of(),
                List.of(new FileRecord("src/Main.java", "modified", 50)),
                5000L
        );
    }

    private Directive forgeDirective(DirectiveStatus status) {
        return forgeDirective(status, 1, 3, FailureStrategy.RETRY);
    }

    private StarblasterBridge.BridgeResult bridgeResult(String centurionType, String directiveId,
                                                      String output) {
        var updatedDirective = new Directive(
                directiveId, centurionType, "desc", "", "criteria",
                List.of(), DirectiveStatus.PASSED, 1, 1,
                FailureStrategy.SKIP, List.of(), List.of(), 1000L
        );
        var starblasterInfo = new StarblasterInfo(
                "c-" + centurionType.toLowerCase(), centurionType, directiveId,
                "completed", Instant.now(), Instant.now()
        );
        return new StarblasterBridge.BridgeResult(updatedDirective, starblasterInfo, output);
    }

    private WorldmindState stateWithDirective(Directive directive) {
        return new WorldmindState(Map.of(
                "directives", List.of(directive),
                "currentDirectiveIndex", 1,  // dispatch already advanced, so lastIndex = 0
                "testResults", List.of(),
                "reviewFeedback", List.of(),
                "starblasters", List.of()
        ));
    }

    private void mockSuccessfulBridgeAndSeal() {
        // Mock GAUNTLET bridge call (first call)
        var gauntletResult = bridgeResult("GAUNTLET", "DIR-001-GAUNTLET",
                "Tests run: 10, Failures: 0");
        // Mock VIGIL bridge call (second call)
        var vigilResult = bridgeResult("VIGIL", "DIR-001-VIGIL",
                "Score: 9/10\nApproved: yes");

        when(bridge.executeDirective(any(), any(), any(), any(), any()))
                .thenReturn(gauntletResult)
                .thenReturn(vigilResult);

        when(sealService.parseTestOutput(anyString(), anyString(), anyLong()))
                .thenReturn(new TestResult("DIR-001", true, 10, 0, "All passed", 1000L));
        when(sealService.parseReviewOutput(anyString(), anyString()))
                .thenReturn(new ReviewFeedback("DIR-001", true, "Good code",
                        List.of(), List.of(), 9));
    }

    // ── Tests ───────────────────────────────────────────────────────

    @Test
    @DisplayName("skips seal evaluation for non-FORGE directive")
    void skipsNonForgeDirective() {
        var gauntletDirective = new Directive(
                "DIR-002", "GAUNTLET", "Run tests",
                "", "Tests pass",
                List.of(), DirectiveStatus.PASSED, 1, 3,
                FailureStrategy.RETRY, List.of(), List.of(), 2000L
        );
        var state = stateWithDirective(gauntletDirective);

        var result = node.apply(state);

        assertEquals(true, result.get("sealGranted"));
        verifyNoInteractions(bridge);
        verifyNoInteractions(sealService);
    }

    @Test
    @DisplayName("grants seal when tests pass and review is approved")
    void grantsWhenTestsPassAndReviewApproved() {
        var directive = forgeDirective(DirectiveStatus.PASSED);
        var state = stateWithDirective(directive);

        mockSuccessfulBridgeAndSeal();
        when(sealService.evaluateSeal(any(), any(), any()))
                .thenReturn(new SealDecision(true, null, "Seal granted"));

        var result = node.apply(state);

        assertEquals(true, result.get("sealGranted"));
        assertTrue(result.containsKey("testResults"));
        assertTrue(result.containsKey("reviewFeedback"));
        assertTrue(result.containsKey("starblasters"));

        @SuppressWarnings("unchecked")
        var testResults = (List<TestResult>) result.get("testResults");
        assertEquals(1, testResults.size());
        assertTrue(testResults.get(0).passed());

        @SuppressWarnings("unchecked")
        var reviewFeedbacks = (List<ReviewFeedback>) result.get("reviewFeedback");
        assertEquals(1, reviewFeedbacks.size());
        assertTrue(reviewFeedbacks.get(0).approved());

        // Verify bridge was called exactly twice (GAUNTLET + VIGIL)
        verify(bridge, times(2)).executeDirective(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("denies seal and retries when seal is denied with RETRY strategy")
    void deniesAndRetriesWhenSealDenied() {
        var directive = forgeDirective(DirectiveStatus.PASSED, 1, 3, FailureStrategy.RETRY);
        var state = stateWithDirective(directive);

        mockSuccessfulBridgeAndSeal();
        when(sealService.evaluateSeal(any(), any(), any()))
                .thenReturn(new SealDecision(false, FailureStrategy.RETRY, "Tests failed"));

        var result = node.apply(state);

        assertEquals(false, result.get("sealGranted"));
        // currentDirectiveIndex should be decremented from 1 to 0
        assertEquals(0, result.get("currentDirectiveIndex"));
        assertNotNull(result.get("retryContext"));
        String retryContext = (String) result.get("retryContext");
        assertTrue(retryContext.contains("DIR-001"));
        assertTrue(retryContext.contains("Tests failed"));
    }

    @Test
    @DisplayName("denies seal and skips when strategy is SKIP")
    void deniesAndSkipsWhenStrategyIsSkip() {
        var directive = forgeDirective(DirectiveStatus.PASSED, 1, 3, FailureStrategy.SKIP);
        var state = stateWithDirective(directive);

        mockSuccessfulBridgeAndSeal();
        when(sealService.evaluateSeal(any(), any(), any()))
                .thenReturn(new SealDecision(false, FailureStrategy.SKIP, "Review score too low"));

        var result = node.apply(state);

        assertEquals(false, result.get("sealGranted"));
        // Index should NOT be changed for SKIP — dispatch already advanced it
        assertFalse(result.containsKey("currentDirectiveIndex"));
    }

    @Test
    @DisplayName("escalates and fails mission when strategy is ESCALATE")
    void escalatesAndFailsMission() {
        var directive = forgeDirective(DirectiveStatus.PASSED, 3, 3, FailureStrategy.ESCALATE);
        var state = stateWithDirective(directive);

        mockSuccessfulBridgeAndSeal();
        when(sealService.evaluateSeal(any(), any(), any()))
                .thenReturn(new SealDecision(false, FailureStrategy.ESCALATE, "Critical failure"));

        var result = node.apply(state);

        assertEquals(false, result.get("sealGranted"));
        assertEquals(MissionStatus.FAILED.name(), result.get("status"));
        assertTrue(result.containsKey("errors"));
        @SuppressWarnings("unchecked")
        var errors = (List<String>) result.get("errors");
        assertEquals(1, errors.size());
        assertTrue(errors.get(0).contains("DIR-001"));
        assertTrue(errors.get(0).contains("escalated"));
    }

    @Test
    @DisplayName("handles failed FORGE directive by applying failure strategy directly")
    void handlesFailedForgeDirective() {
        var directive = forgeDirective(DirectiveStatus.FAILED, 1, 3, FailureStrategy.RETRY);
        var state = stateWithDirective(directive);

        var result = node.apply(state);

        assertEquals(false, result.get("sealGranted"));
        // Should decrement index for RETRY
        assertEquals(0, result.get("currentDirectiveIndex"));
        assertNotNull(result.get("retryContext"));
        String retryContext = (String) result.get("retryContext");
        assertTrue(retryContext.contains("DIR-001"));

        // No bridge calls should be made for already-failed directives
        verifyNoInteractions(bridge);
        verifyNoInteractions(sealService);
    }

    @Test
    @DisplayName("handles GAUNTLET infrastructure error gracefully")
    void handlesGauntletInfrastructureError() {
        var directive = forgeDirective(DirectiveStatus.PASSED);
        var state = stateWithDirective(directive);

        // GAUNTLET throws, VIGIL succeeds
        var vigilResult = bridgeResult("VIGIL", "DIR-001-VIGIL", "Score: 8/10\nApproved: yes");
        when(bridge.executeDirective(any(), any(), any(), any(), any()))
                .thenThrow(new RuntimeException("Docker connection refused"))
                .thenReturn(vigilResult);

        when(sealService.parseReviewOutput(anyString(), anyString()))
                .thenReturn(new ReviewFeedback("DIR-001", true, "Good code",
                        List.of(), List.of(), 8));
        when(sealService.evaluateSeal(any(), any(), any()))
                .thenReturn(new SealDecision(false, FailureStrategy.RETRY, "Tests failed due to infra error"));

        var result = node.apply(state);

        // Should still have dispatched VIGIL (second call)
        verify(bridge, times(2)).executeDirective(any(), any(), any(), any(), any());

        // testResults should contain the error info
        assertTrue(result.containsKey("testResults"));
        @SuppressWarnings("unchecked")
        var testResults = (List<TestResult>) result.get("testResults");
        assertEquals(1, testResults.size());
        assertFalse(testResults.get(0).passed());
        assertTrue(testResults.get(0).output().contains("GAUNTLET infrastructure error"));

        // evaluateSeal should still have been called
        verify(sealService).evaluateSeal(any(), any(), any());
    }

    @Test
    @DisplayName("handles VIGIL infrastructure error gracefully")
    void handlesVigilInfrastructureError() {
        var directive = forgeDirective(DirectiveStatus.PASSED);
        var state = stateWithDirective(directive);

        // GAUNTLET succeeds, VIGIL throws
        var gauntletResult = bridgeResult("GAUNTLET", "DIR-001-GAUNTLET",
                "Tests run: 10, Failures: 0");
        when(bridge.executeDirective(any(), any(), any(), any(), any()))
                .thenReturn(gauntletResult)
                .thenThrow(new RuntimeException("VIGIL container timeout"));

        when(sealService.parseTestOutput(anyString(), anyString(), anyLong()))
                .thenReturn(new TestResult("DIR-001", true, 10, 0, "All passed", 1000L));
        when(sealService.evaluateSeal(any(), any(), any()))
                .thenReturn(new SealDecision(false, FailureStrategy.RETRY, "Review failed due to infra error"));

        var result = node.apply(state);

        // Should have tried both bridge calls
        verify(bridge, times(2)).executeDirective(any(), any(), any(), any(), any());

        // reviewFeedback should contain the error info
        assertTrue(result.containsKey("reviewFeedback"));
        @SuppressWarnings("unchecked")
        var feedbacks = (List<ReviewFeedback>) result.get("reviewFeedback");
        assertEquals(1, feedbacks.size());
        assertFalse(feedbacks.get(0).approved());
        assertTrue(feedbacks.get(0).summary().contains("VIGIL infrastructure error"));
        assertEquals(0, feedbacks.get(0).score());

        // evaluateSeal should still have been called
        verify(sealService).evaluateSeal(any(), any(), any());
    }

    @Test
    @DisplayName("handles out-of-bounds index gracefully")
    void handlesOutOfBoundsIndex() {
        // currentDirectiveIndex = 0 means lastIndex = -1 (nothing dispatched yet)
        var state = new WorldmindState(Map.of(
                "directives", List.of(forgeDirective(DirectiveStatus.PENDING)),
                "currentDirectiveIndex", 0
        ));

        var result = node.apply(state);

        assertEquals(true, result.get("sealGranted"));
        verifyNoInteractions(bridge);
        verifyNoInteractions(sealService);
    }

    @Test
    @DisplayName("failed FORGE directive with exhausted retries escalates")
    void failedForgeWithExhaustedRetriesEscalates() {
        // iteration=3, maxIterations=3 -> retries exhausted, should escalate even if onFailure=RETRY
        var directive = forgeDirective(DirectiveStatus.FAILED, 3, 3, FailureStrategy.RETRY);
        var state = stateWithDirective(directive);

        var result = node.apply(state);

        assertEquals(false, result.get("sealGranted"));
        assertEquals(MissionStatus.FAILED.name(), result.get("status"));
        assertTrue(result.containsKey("errors"));
        @SuppressWarnings("unchecked")
        var errors = (List<String>) result.get("errors");
        assertTrue(errors.get(0).contains("DIR-001"));
        assertTrue(errors.get(0).contains("escalated"));

        verifyNoInteractions(bridge);
        verifyNoInteractions(sealService);
    }

    @Test
    @DisplayName("REPLAN strategy fails mission with replanning message")
    void replanStrategyFailsMission() {
        var directive = forgeDirective(DirectiveStatus.PASSED, 1, 3, FailureStrategy.REPLAN);
        var state = stateWithDirective(directive);

        mockSuccessfulBridgeAndSeal();
        when(sealService.evaluateSeal(any(), any(), any()))
                .thenReturn(new SealDecision(false, FailureStrategy.REPLAN, "Architecture needs rethinking"));

        var result = node.apply(state);

        assertEquals(false, result.get("sealGranted"));
        assertEquals(MissionStatus.FAILED.name(), result.get("status"));
        @SuppressWarnings("unchecked")
        var errors = (List<String>) result.get("errors");
        assertTrue(errors.get(0).contains("replanning"));
    }
}
