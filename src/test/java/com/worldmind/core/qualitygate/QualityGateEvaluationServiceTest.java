package com.worldmind.core.quality_gate;

import com.worldmind.core.llm.LlmService;
import com.worldmind.core.model.Task;
import com.worldmind.core.model.TaskStatus;
import com.worldmind.core.model.FailureStrategy;
import com.worldmind.core.model.ReviewFeedback;
import com.worldmind.core.model.QualityGateDecision;
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
 * Unit tests for {@link QualityGateEvaluationService}.
 * <p>
 * Mocks {@link LlmService} so no real LLM calls are made.
 */
class QualityGateEvaluationServiceTest {

    private LlmService mockLlmService;
    private QualityGateEvaluationService service;

    @BeforeEach
    void setUp() {
        mockLlmService = mock(LlmService.class);
        service = new QualityGateEvaluationService(mockLlmService, null);
    }

    // ── parseTestOutput tests ───────────────────────────────────────────

    @Nested
    @DisplayName("parseTestOutput")
    class ParseTestOutputTests {

        @Test
        @DisplayName("parses Maven/JUnit style output with failures")
        void parsesMavenStyleOutput() {
            String output = "Tests run: 10, Failures: 2, Errors: 0";
            TestResult result = service.parseTestOutput("TASK-001", output, 5000);

            assertEquals("TASK-001", result.taskId());
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
            TestResult result = service.parseTestOutput("TASK-002", output, 3000);

            assertTrue(result.passed());
            assertEquals(15, result.totalTests());
            assertEquals(0, result.failedTests());
        }

        @Test
        @DisplayName("parses pytest style output with all passing")
        void parsesPytestStyleOutput() {
            String output = "8 passed, 0 failed";
            TestResult result = service.parseTestOutput("TASK-003", output, 2000);

            assertEquals("TASK-003", result.taskId());
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
            TestResult result = service.parseTestOutput("TASK-004", output, 4000);

            assertFalse(result.passed());
            assertEquals(8, result.totalTests());
            assertEquals(3, result.failedTests());
        }

        @Test
        @DisplayName("parses pytest style output with only passed count")
        void parsesPytestStyleOutputOnlyPassed() {
            String output = "12 passed";
            TestResult result = service.parseTestOutput("TASK-005", output, 1000);

            assertTrue(result.passed());
            assertEquals(12, result.totalTests());
            assertEquals(0, result.failedTests());
        }

        @Test
        @DisplayName("fallback to passed when no pattern found and no error keywords")
        void fallbackToPassedWhenNoPattern() {
            String output = "All good";
            TestResult result = service.parseTestOutput("TASK-006", output, 500);

            assertTrue(result.passed());
            assertEquals(0, result.totalTests());
            assertEquals(0, result.failedTests());
        }

        @Test
        @DisplayName("fallback to failed when output contains BUILD FAILURE")
        void fallbackToFailedWhenBuildFailure() {
            String output = "BUILD FAILURE — compilation errors in module";
            TestResult result = service.parseTestOutput("TASK-007", output, 1500);

            assertFalse(result.passed());
            assertEquals(0, result.totalTests());
            assertEquals(0, result.failedTests());
        }

        @Test
        @DisplayName("generic FAILED/Error keywords treated as passed (not build failures)")
        void genericErrorKeywordsTreatedAsPassed() {
            String output = "An Error occurred while running tests";
            TestResult result = service.parseTestOutput("TASK-008", output, 1500);

            assertTrue(result.passed());
        }

        @Test
        @DisplayName("handles null output as failed")
        void handlesNullOutput() {
            TestResult result = service.parseTestOutput("TASK-009", null, 0);

            assertFalse(result.passed());
            assertEquals(0, result.totalTests());
            assertEquals(0, result.failedTests());
            assertEquals("", result.output());
        }

        @Test
        @DisplayName("handles empty output as failed")
        void handlesEmptyOutput() {
            TestResult result = service.parseTestOutput("TASK-010", "", 0);

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
        @DisplayName("parses review output via LLM and preserves taskId")
        void parsesReviewViaLlm() {
            var llmResult = new ReviewFeedback(
                    "ignored-id", true, "Code looks good",
                    List.of("Minor formatting issue"), List.of("Add more comments"), 8
            );
            when(mockLlmService.structuredCall(anyString(), anyString(), eq(ReviewFeedback.class)))
                    .thenReturn(llmResult);

            ReviewFeedback result = service.parseReviewOutput("TASK-001", "The code is well structured...");

            assertEquals("TASK-001", result.taskId());
            assertTrue(result.approved());
            assertEquals("Code looks good", result.summary());
            assertEquals(List.of("Minor formatting issue"), result.issues());
            assertEquals(List.of("Add more comments"), result.suggestions());
            assertEquals(8, result.score());

            // Verify LLM was called
            verify(mockLlmService).structuredCall(anyString(), contains("TASK-001"), eq(ReviewFeedback.class));
        }

        @Test
        @DisplayName("returns default failed feedback for null review output without calling LLM")
        void handlesNullReviewOutput() {
            ReviewFeedback result = service.parseReviewOutput("TASK-002", null);

            assertEquals("TASK-002", result.taskId());
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
            ReviewFeedback result = service.parseReviewOutput("TASK-003", "   ");

            assertEquals("TASK-003", result.taskId());
            assertFalse(result.approved());
            assertEquals("No review output", result.summary());
            assertEquals(0, result.score());

            verifyNoInteractions(mockLlmService);
        }
    }

    // ── evaluateQualityGate tests ──────────────────────────────────────────────

    @Nested
    @DisplayName("evaluateQualityGate")
    class EvaluateQualityGateTests {

        @Test
        @DisplayName("grants quality_gate when tests pass and review score is above threshold")
        void grantsWhenTestsPassAndReviewAboveThreshold() {
            var testResult = new TestResult("TASK-001", true, 10, 0, "All passed", 5000);
            var reviewFeedback = new ReviewFeedback("TASK-001", true, "Good code", List.of(), List.of(), 8);
            var task = createTask("TASK-001", 0, 3, FailureStrategy.RETRY);

            QualityGateDecision decision = service.evaluateQualityGate(testResult, reviewFeedback, task);

            assertTrue(decision.quality_gateGranted());
            assertNull(decision.action());
            assertTrue(decision.reason().contains("QualityGate granted"));
            assertTrue(decision.reason().contains("8/10"));
        }

        @Test
        @DisplayName("grants quality_gate when review score is exactly at threshold (7)")
        void grantsWhenReviewScoreExactlyAtThreshold() {
            var testResult = new TestResult("TASK-001", true, 5, 0, "OK", 1000);
            var reviewFeedback = new ReviewFeedback("TASK-001", true, "Acceptable", List.of(), List.of(), 7);
            var task = createTask("TASK-001", 0, 3, FailureStrategy.RETRY);

            QualityGateDecision decision = service.evaluateQualityGate(testResult, reviewFeedback, task);

            assertTrue(decision.quality_gateGranted());
        }

        @Test
        @DisplayName("denies quality_gate when tests fail")
        void deniesWhenTestsFail() {
            var testResult = new TestResult("TASK-001", false, 10, 3, "3 failures", 5000);
            var reviewFeedback = new ReviewFeedback("TASK-001", true, "Good code", List.of(), List.of(), 9);
            var task = createTask("TASK-001", 0, 3, FailureStrategy.RETRY);

            QualityGateDecision decision = service.evaluateQualityGate(testResult, reviewFeedback, task);

            assertFalse(decision.quality_gateGranted());
            assertEquals(FailureStrategy.RETRY, decision.action());
            assertTrue(decision.reason().contains("Tests failed"));
        }

        @Test
        @DisplayName("denies quality_gate when review score is below threshold")
        void deniesWhenReviewBelowThreshold() {
            var testResult = new TestResult("TASK-001", true, 10, 0, "All passed", 5000);
            var reviewFeedback = new ReviewFeedback("TASK-001", false, "Needs work", List.of("Bug"), List.of(), 5);
            var task = createTask("TASK-001", 0, 3, FailureStrategy.RETRY);

            QualityGateDecision decision = service.evaluateQualityGate(testResult, reviewFeedback, task);

            assertFalse(decision.quality_gateGranted());
            assertEquals(FailureStrategy.RETRY, decision.action());
            assertTrue(decision.reason().contains("Review score 5/10"));
            assertTrue(decision.reason().contains("minimum 7"));
        }

        @Test
        @DisplayName("provides combined reason when both tests fail and review is below threshold")
        void deniesBothTestsAndReview() {
            var testResult = new TestResult("TASK-001", false, 10, 4, "4 failures", 5000);
            var reviewFeedback = new ReviewFeedback("TASK-001", false, "Poor code", List.of("Bugs"), List.of(), 3);
            var task = createTask("TASK-001", 0, 3, FailureStrategy.RETRY);

            QualityGateDecision decision = service.evaluateQualityGate(testResult, reviewFeedback, task);

            assertFalse(decision.quality_gateGranted());
            assertTrue(decision.reason().contains("Tests failed"));
            assertTrue(decision.reason().contains("review score"));
        }

        @Test
        @DisplayName("escalates when retry is exhausted (iteration >= maxIterations)")
        void escalatesWhenRetryExhausted() {
            var testResult = new TestResult("TASK-001", false, 10, 2, "2 failures", 5000);
            var reviewFeedback = new ReviewFeedback("TASK-001", false, "Issues", List.of(), List.of(), 5);
            var task = createTask("TASK-001", 3, 3, FailureStrategy.RETRY);

            QualityGateDecision decision = service.evaluateQualityGate(testResult, reviewFeedback, task);

            assertFalse(decision.quality_gateGranted());
            assertEquals(FailureStrategy.ESCALATE, decision.action());
        }

        @Test
        @DisplayName("uses task's failure strategy when set to SKIP")
        void usesTaskFailureStrategy() {
            var testResult = new TestResult("TASK-001", false, 10, 5, "5 failures", 5000);
            var reviewFeedback = new ReviewFeedback("TASK-001", false, "Bad", List.of(), List.of(), 3);
            var task = createTask("TASK-001", 0, 3, FailureStrategy.SKIP);

            QualityGateDecision decision = service.evaluateQualityGate(testResult, reviewFeedback, task);

            assertFalse(decision.quality_gateGranted());
            assertEquals(FailureStrategy.SKIP, decision.action());
        }

        @Test
        @DisplayName("uses task's failure strategy when set to REPLAN")
        void usesReplanFailureStrategy() {
            var testResult = new TestResult("TASK-001", false, 10, 5, "5 failures", 5000);
            var reviewFeedback = new ReviewFeedback("TASK-001", false, "Bad", List.of(), List.of(), 3);
            var task = createTask("TASK-001", 0, 3, FailureStrategy.REPLAN);

            QualityGateDecision decision = service.evaluateQualityGate(testResult, reviewFeedback, task);

            assertFalse(decision.quality_gateGranted());
            assertEquals(FailureStrategy.REPLAN, decision.action());
        }

        @Test
        @DisplayName("defaults to RETRY when task has null failure strategy")
        void handlesNullFailureStrategy() {
            var testResult = new TestResult("TASK-001", false, 10, 1, "1 failure", 5000);
            var reviewFeedback = new ReviewFeedback("TASK-001", true, "OK", List.of(), List.of(), 8);
            var task = createTask("TASK-001", 0, 3, null);

            QualityGateDecision decision = service.evaluateQualityGate(testResult, reviewFeedback, task);

            assertFalse(decision.quality_gateGranted());
            assertEquals(FailureStrategy.RETRY, decision.action());
        }

        @Test
        @DisplayName("escalates when null failure strategy defaults to RETRY and iterations exhausted")
        void handlesNullFailureStrategyWithExhaustedRetries() {
            var testResult = new TestResult("TASK-001", false, 10, 1, "1 failure", 5000);
            var reviewFeedback = new ReviewFeedback("TASK-001", true, "OK", List.of(), List.of(), 8);
            var task = createTask("TASK-001", 3, 3, null);

            QualityGateDecision decision = service.evaluateQualityGate(testResult, reviewFeedback, task);

            assertFalse(decision.quality_gateGranted());
            assertEquals(FailureStrategy.ESCALATE, decision.action());
        }
    }

    // ── Helper methods ──────────────────────────────────────────────────

    /**
     * Create a minimal Task for testing quality_gate evaluation.
     */
    private Task createTask(String id, int iteration, int maxIterations, FailureStrategy onFailure) {
        return new Task(
                id,
                "CODER",
                "Test task",
                "input context",
                "success criteria",
                List.of(),
                TaskStatus.PENDING,
                iteration,
                maxIterations,
                onFailure,
                List.of(),
                List.of(),
                null
        );
    }
}
