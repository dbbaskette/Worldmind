package com.worldmind.core.seal;

import com.worldmind.core.llm.LlmService;
import com.worldmind.core.model.Directive;
import com.worldmind.core.model.DirectiveStatus;
import com.worldmind.core.model.FailureStrategy;
import com.worldmind.core.model.ReviewFeedback;
import com.worldmind.core.model.SealDecision;
import com.worldmind.core.model.TestResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link SealEvaluationService}.
 * <p>
 * Mocks {@link LlmService} so no real LLM calls are made.
 */
class SealEvaluationServiceTest {

    private LlmService mockLlmService;
    private SealEvaluationService service;

    @BeforeEach
    void setUp() {
        mockLlmService = mock(LlmService.class);
        service = new SealEvaluationService(mockLlmService, null);
    }

    // ── parseTestOutput tests ───────────────────────────────────────────

    @Nested
    @DisplayName("parseTestOutput")
    class ParseTestOutputTests {

        @Test
        @DisplayName("parses Maven/JUnit style output with failures")
        void parsesMavenStyleOutput() {
            String output = "Tests run: 10, Failures: 2, Errors: 0";
            TestResult result = service.parseTestOutput("DIR-001", output, 5000);

            assertEquals("DIR-001", result.directiveId());
            assertFalse(result.passed());
            assertEquals(10, result.totalTests());
            assertEquals(2, result.failedTests());
            assertEquals(output, result.output());
            assertEquals(5000, result.durationMs());
        }

        @Test
        @DisplayName("parses Maven/JUnit style output with zero failures")
        void parsesMavenStyleOutputAllPassing() {
            String output = "Tests run: 15, Failures: 0, Errors: 0";
            TestResult result = service.parseTestOutput("DIR-002", output, 3000);

            assertTrue(result.passed());
            assertEquals(15, result.totalTests());
            assertEquals(0, result.failedTests());
        }

        @Test
        @DisplayName("parses pytest style output with all passing")
        void parsesPytestStyleOutput() {
            String output = "8 passed, 0 failed";
            TestResult result = service.parseTestOutput("DIR-003", output, 2000);

            assertEquals("DIR-003", result.directiveId());
            assertTrue(result.passed());
            assertEquals(8, result.totalTests());
            assertEquals(0, result.failedTests());
            assertEquals(output, result.output());
            assertEquals(2000, result.durationMs());
        }

        @Test
        @DisplayName("parses pytest style output with failures")
        void parsesPytestStyleOutputWithFailures() {
            String output = "5 passed, 3 failed";
            TestResult result = service.parseTestOutput("DIR-004", output, 4000);

            assertFalse(result.passed());
            assertEquals(8, result.totalTests());
            assertEquals(3, result.failedTests());
        }

        @Test
        @DisplayName("parses pytest style output with only passed count")
        void parsesPytestStyleOutputOnlyPassed() {
            String output = "12 passed";
            TestResult result = service.parseTestOutput("DIR-005", output, 1000);

            assertTrue(result.passed());
            assertEquals(12, result.totalTests());
            assertEquals(0, result.failedTests());
        }

        @Test
        @DisplayName("fallback to passed when no pattern found and no error keywords")
        void fallbackToPassedWhenNoPattern() {
            String output = "All good";
            TestResult result = service.parseTestOutput("DIR-006", output, 500);

            assertTrue(result.passed());
            assertEquals(0, result.totalTests());
            assertEquals(0, result.failedTests());
        }

        @Test
        @DisplayName("fallback to failed when output contains BUILD FAILURE")
        void fallbackToFailedWhenBuildFailure() {
            String output = "BUILD FAILURE — compilation errors in module";
            TestResult result = service.parseTestOutput("DIR-007", output, 1500);

            assertFalse(result.passed());
            assertEquals(0, result.totalTests());
            assertEquals(0, result.failedTests());
        }

        @Test
        @DisplayName("generic FAILED/Error keywords treated as passed (not build failures)")
        void genericErrorKeywordsTreatedAsPassed() {
            String output = "An Error occurred while running tests";
            TestResult result = service.parseTestOutput("DIR-008", output, 1500);

            assertTrue(result.passed());
        }

        @Test
        @DisplayName("handles null output as failed")
        void handlesNullOutput() {
            TestResult result = service.parseTestOutput("DIR-009", null, 0);

            assertFalse(result.passed());
            assertEquals(0, result.totalTests());
            assertEquals(0, result.failedTests());
            assertEquals("", result.output());
        }

        @Test
        @DisplayName("handles empty output as failed")
        void handlesEmptyOutput() {
            TestResult result = service.parseTestOutput("DIR-010", "", 0);

            assertFalse(result.passed());
            assertEquals(0, result.totalTests());
            assertEquals(0, result.failedTests());
        }
    }

    // ── parseReviewOutput tests ─────────────────────────────────────────

    @Nested
    @DisplayName("parseReviewOutput")
    class ParseReviewOutputTests {

        @Test
        @DisplayName("parses review output via LLM and preserves directiveId")
        void parsesReviewViaLlm() {
            var llmResult = new ReviewFeedback(
                    "ignored-id", true, "Code looks good",
                    List.of("Minor formatting issue"), List.of("Add more comments"), 8
            );
            when(mockLlmService.structuredCall(anyString(), anyString(), eq(ReviewFeedback.class)))
                    .thenReturn(llmResult);

            ReviewFeedback result = service.parseReviewOutput("DIR-001", "The code is well structured...");

            assertEquals("DIR-001", result.directiveId());
            assertTrue(result.approved());
            assertEquals("Code looks good", result.summary());
            assertEquals(List.of("Minor formatting issue"), result.issues());
            assertEquals(List.of("Add more comments"), result.suggestions());
            assertEquals(8, result.score());

            // Verify LLM was called
            verify(mockLlmService).structuredCall(anyString(), contains("DIR-001"), eq(ReviewFeedback.class));
        }

        @Test
        @DisplayName("returns default failed feedback for null review output without calling LLM")
        void handlesNullReviewOutput() {
            ReviewFeedback result = service.parseReviewOutput("DIR-002", null);

            assertEquals("DIR-002", result.directiveId());
            assertFalse(result.approved());
            assertEquals("No review output", result.summary());
            assertEquals(List.of("No output from reviewer"), result.issues());
            assertEquals(List.of(), result.suggestions());
            assertEquals(0, result.score());

            // LLM should NOT be called
            verifyNoInteractions(mockLlmService);
        }

        @Test
        @DisplayName("returns default failed feedback for blank review output without calling LLM")
        void handlesBlankReviewOutput() {
            ReviewFeedback result = service.parseReviewOutput("DIR-003", "   ");

            assertEquals("DIR-003", result.directiveId());
            assertFalse(result.approved());
            assertEquals("No review output", result.summary());
            assertEquals(0, result.score());

            verifyNoInteractions(mockLlmService);
        }
    }

    // ── evaluateSeal tests ──────────────────────────────────────────────

    @Nested
    @DisplayName("evaluateSeal")
    class EvaluateSealTests {

        @Test
        @DisplayName("grants seal when tests pass and review score is above threshold")
        void grantsWhenTestsPassAndReviewAboveThreshold() {
            var testResult = new TestResult("DIR-001", true, 10, 0, "All passed", 5000);
            var reviewFeedback = new ReviewFeedback("DIR-001", true, "Good code", List.of(), List.of(), 8);
            var directive = createDirective("DIR-001", 0, 3, FailureStrategy.RETRY);

            SealDecision decision = service.evaluateSeal(testResult, reviewFeedback, directive);

            assertTrue(decision.sealGranted());
            assertNull(decision.action());
            assertTrue(decision.reason().contains("Seal granted"));
            assertTrue(decision.reason().contains("8/10"));
        }

        @Test
        @DisplayName("grants seal when review score is exactly at threshold (7)")
        void grantsWhenReviewScoreExactlyAtThreshold() {
            var testResult = new TestResult("DIR-001", true, 5, 0, "OK", 1000);
            var reviewFeedback = new ReviewFeedback("DIR-001", true, "Acceptable", List.of(), List.of(), 7);
            var directive = createDirective("DIR-001", 0, 3, FailureStrategy.RETRY);

            SealDecision decision = service.evaluateSeal(testResult, reviewFeedback, directive);

            assertTrue(decision.sealGranted());
        }

        @Test
        @DisplayName("denies seal when tests fail")
        void deniesWhenTestsFail() {
            var testResult = new TestResult("DIR-001", false, 10, 3, "3 failures", 5000);
            var reviewFeedback = new ReviewFeedback("DIR-001", true, "Good code", List.of(), List.of(), 9);
            var directive = createDirective("DIR-001", 0, 3, FailureStrategy.RETRY);

            SealDecision decision = service.evaluateSeal(testResult, reviewFeedback, directive);

            assertFalse(decision.sealGranted());
            assertEquals(FailureStrategy.RETRY, decision.action());
            assertTrue(decision.reason().contains("Tests failed"));
        }

        @Test
        @DisplayName("denies seal when review score is below threshold")
        void deniesWhenReviewBelowThreshold() {
            var testResult = new TestResult("DIR-001", true, 10, 0, "All passed", 5000);
            var reviewFeedback = new ReviewFeedback("DIR-001", false, "Needs work", List.of("Bug"), List.of(), 5);
            var directive = createDirective("DIR-001", 0, 3, FailureStrategy.RETRY);

            SealDecision decision = service.evaluateSeal(testResult, reviewFeedback, directive);

            assertFalse(decision.sealGranted());
            assertEquals(FailureStrategy.RETRY, decision.action());
            assertTrue(decision.reason().contains("Review score 5/10"));
            assertTrue(decision.reason().contains("minimum 7"));
        }

        @Test
        @DisplayName("provides combined reason when both tests fail and review is below threshold")
        void deniesBothTestsAndReview() {
            var testResult = new TestResult("DIR-001", false, 10, 4, "4 failures", 5000);
            var reviewFeedback = new ReviewFeedback("DIR-001", false, "Poor code", List.of("Bugs"), List.of(), 3);
            var directive = createDirective("DIR-001", 0, 3, FailureStrategy.RETRY);

            SealDecision decision = service.evaluateSeal(testResult, reviewFeedback, directive);

            assertFalse(decision.sealGranted());
            assertTrue(decision.reason().contains("Tests failed"));
            assertTrue(decision.reason().contains("review score"));
        }

        @Test
        @DisplayName("escalates when retry is exhausted (iteration >= maxIterations)")
        void escalatesWhenRetryExhausted() {
            var testResult = new TestResult("DIR-001", false, 10, 2, "2 failures", 5000);
            var reviewFeedback = new ReviewFeedback("DIR-001", false, "Issues", List.of(), List.of(), 5);
            var directive = createDirective("DIR-001", 3, 3, FailureStrategy.RETRY);

            SealDecision decision = service.evaluateSeal(testResult, reviewFeedback, directive);

            assertFalse(decision.sealGranted());
            assertEquals(FailureStrategy.ESCALATE, decision.action());
        }

        @Test
        @DisplayName("uses directive's failure strategy when set to SKIP")
        void usesDirectiveFailureStrategy() {
            var testResult = new TestResult("DIR-001", false, 10, 5, "5 failures", 5000);
            var reviewFeedback = new ReviewFeedback("DIR-001", false, "Bad", List.of(), List.of(), 3);
            var directive = createDirective("DIR-001", 0, 3, FailureStrategy.SKIP);

            SealDecision decision = service.evaluateSeal(testResult, reviewFeedback, directive);

            assertFalse(decision.sealGranted());
            assertEquals(FailureStrategy.SKIP, decision.action());
        }

        @Test
        @DisplayName("uses directive's failure strategy when set to REPLAN")
        void usesReplanFailureStrategy() {
            var testResult = new TestResult("DIR-001", false, 10, 5, "5 failures", 5000);
            var reviewFeedback = new ReviewFeedback("DIR-001", false, "Bad", List.of(), List.of(), 3);
            var directive = createDirective("DIR-001", 0, 3, FailureStrategy.REPLAN);

            SealDecision decision = service.evaluateSeal(testResult, reviewFeedback, directive);

            assertFalse(decision.sealGranted());
            assertEquals(FailureStrategy.REPLAN, decision.action());
        }

        @Test
        @DisplayName("defaults to RETRY when directive has null failure strategy")
        void handlesNullFailureStrategy() {
            var testResult = new TestResult("DIR-001", false, 10, 1, "1 failure", 5000);
            var reviewFeedback = new ReviewFeedback("DIR-001", true, "OK", List.of(), List.of(), 8);
            var directive = createDirective("DIR-001", 0, 3, null);

            SealDecision decision = service.evaluateSeal(testResult, reviewFeedback, directive);

            assertFalse(decision.sealGranted());
            assertEquals(FailureStrategy.RETRY, decision.action());
        }

        @Test
        @DisplayName("escalates when null failure strategy defaults to RETRY and iterations exhausted")
        void handlesNullFailureStrategyWithExhaustedRetries() {
            var testResult = new TestResult("DIR-001", false, 10, 1, "1 failure", 5000);
            var reviewFeedback = new ReviewFeedback("DIR-001", true, "OK", List.of(), List.of(), 8);
            var directive = createDirective("DIR-001", 3, 3, null);

            SealDecision decision = service.evaluateSeal(testResult, reviewFeedback, directive);

            assertFalse(decision.sealGranted());
            assertEquals(FailureStrategy.ESCALATE, decision.action());
        }
    }

    // ── Helper methods ──────────────────────────────────────────────────

    /**
     * Create a minimal Directive for testing seal evaluation.
     */
    private Directive createDirective(String id, int iteration, int maxIterations, FailureStrategy onFailure) {
        return new Directive(
                id,
                "FORGE",
                "Test directive",
                "input context",
                "success criteria",
                List.of(),
                DirectiveStatus.PENDING,
                iteration,
                maxIterations,
                onFailure,
                List.of(),
                null
        );
    }
}
