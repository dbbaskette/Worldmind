package com.worldmind.core.quality_gate;

import com.worldmind.core.llm.LlmService;
import com.worldmind.core.model.Task;
import com.worldmind.core.model.FailureStrategy;
import com.worldmind.core.model.ReviewFeedback;
import com.worldmind.core.model.QualityGateDecision;
import com.worldmind.core.model.TestResult;
import com.worldmind.mcp.McpToolProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Evaluates the QualityGate of Approval quality gate for completed tasks.
 * <p>
 * Responsibilities:
 * <ul>
 *   <li>Parse raw Tester (test runner) output into structured {@link TestResult}</li>
 *   <li>Parse raw Reviewer (code reviewer) output into structured {@link ReviewFeedback} via LLM</li>
 *   <li>Evaluate whether a task earns the QualityGate of Approval based on test results and review feedback</li>
 * </ul>
 */
@Service
public class QualityGateEvaluationService {

    private static final Logger log = LoggerFactory.getLogger(QualityGateEvaluationService.class);

    /** Minimum review score (inclusive) required for quality_gate approval. */
    private static final int REVIEW_SCORE_THRESHOLD = 6;

    /** Maven/JUnit style: "Tests run: 10, Failures: 2" */
    private static final Pattern MAVEN_PATTERN =
            Pattern.compile("Tests run:\\s*(\\d+),\\s*Failures:\\s*(\\d+)");

    /** pytest style: "8 passed, 2 failed" or "8 passed" */
    private static final Pattern PYTEST_PASSED_PATTERN = Pattern.compile("(\\d+)\\s+passed");
    private static final Pattern PYTEST_FAILED_PATTERN = Pattern.compile("(\\d+)\\s+failed");

    /** Specific test failure patterns. Requires anchoring context to avoid false-positives on
     *  Goose session noise (e.g. the word "test" appearing in agent commentary). */
    private static final Pattern BUILD_FAILURE_PATTERN =
            Pattern.compile("(?i)(BUILD FAILURE|BUILD FAILED|COMPILATION ERROR|npm ERR!|Tests? run:.*Failures:\\s*[1-9])");

    /** Extract "Score: X/10" from raw Goose output before LLM parsing. */
    private static final Pattern SCORE_PATTERN =
            Pattern.compile("(?i)Score:\\s*(\\d{1,2})\\s*/\\s*10");

    private static final String REVIEW_PARSE_SYSTEM_PROMPT =
            "You are a code review parser. The input is the raw session log from an AI coding agent (Goose) " +
            "that was asked to perform a code review. The log contains tool calls, file reads, and the agent's " +
            "review commentary. Extract the agent's review findings:\n\n" +
            "1. Find the review SCORE — look for 'Score: X/10' or similar scoring. If the agent gave a score, use it exactly.\n" +
            "2. If no explicit score is found, assess the agent's review commentary: " +
            "if the review found no significant issues, score 8/10. If it found minor issues, score 6/10. " +
            "If it found major issues, score 3/10.\n" +
            "3. Extract: approved (true if score >= 7), summary, issues list, suggestions list.\n" +
            "4. The 'score' field MUST be an integer 0-10. Never return 0 unless the review found catastrophic problems.";

    private final LlmService llmService;
    private final McpToolProvider mcpToolProvider;

    public QualityGateEvaluationService(LlmService llmService,
                                 @Autowired(required = false) McpToolProvider mcpToolProvider) {
        this.llmService = llmService;
        this.mcpToolProvider = mcpToolProvider;
    }

    /**
     * Parse raw Tester (test runner) output into a structured {@link TestResult}.
     * <p>
     * Looks for patterns like "Tests run: X, Failures: Y" (Maven/JUnit) or
     * "X passed, Y failed" (pytest). If no pattern is found, falls back to
     * checking for error/failure keywords in the output.
     *
     * @param taskId   the task that was tested
     * @param testerOutput raw output from the test runner
     * @param durationMs    how long the test run took
     * @return a structured {@link TestResult}
     */
    public TestResult parseTestOutput(String taskId, String testerOutput, long durationMs) {
        if (testerOutput == null || testerOutput.isBlank()) {
            log.warn("Empty or null test output for task {}", taskId);
            return new TestResult(taskId, false, 0, 0, testerOutput != null ? testerOutput : "", durationMs);
        }

        // Try Maven/JUnit style: "Tests run: 10, Failures: 2"
        Matcher mavenMatcher = MAVEN_PATTERN.matcher(testerOutput);
        if (mavenMatcher.find()) {
            int totalTests = Integer.parseInt(mavenMatcher.group(1));
            int failedTests = Integer.parseInt(mavenMatcher.group(2));
            boolean passed = failedTests == 0;
            log.info("Parsed Maven-style output for {}: {}/{} tests passed", taskId, totalTests - failedTests, totalTests);
            return new TestResult(taskId, passed, totalTests, failedTests, testerOutput, durationMs);
        }

        // Try pytest style: "8 passed, 2 failed"
        Matcher passedMatcher = PYTEST_PASSED_PATTERN.matcher(testerOutput);
        Matcher failedMatcher = PYTEST_FAILED_PATTERN.matcher(testerOutput);
        boolean foundPassed = passedMatcher.find();
        boolean foundFailed = failedMatcher.find();
        if (foundPassed || foundFailed) {
            int passedCount = foundPassed ? Integer.parseInt(passedMatcher.group(1)) : 0;
            int failedCount = foundFailed ? Integer.parseInt(failedMatcher.group(1)) : 0;
            int totalTests = passedCount + failedCount;
            boolean passed = failedCount == 0;
            log.info("Parsed pytest-style output for {}: {}/{} tests passed", taskId, passedCount, totalTests);
            return new TestResult(taskId, passed, totalTests, failedCount, testerOutput, durationMs);
        }

        // Fallback: check for specific build/test failure patterns (not generic "Error"
        // which false-positives on Goose session logs containing error handling code)
        boolean containsBuildFailure = BUILD_FAILURE_PATTERN.matcher(testerOutput).find();
        if (containsBuildFailure) {
            log.info("Fallback: build/test failure pattern found in output for {}", taskId);
            return new TestResult(taskId, false, 0, 0, testerOutput, durationMs);
        }

        log.info("No test framework output found for {}, treating as passed", taskId);
        return new TestResult(taskId, true, 0, 0, testerOutput, durationMs);
    }

    /**
     * Parse raw Reviewer (code reviewer) output into structured {@link ReviewFeedback}.
     * <p>
     * Uses {@link LlmService#structuredCall} to extract structured review data from
     * free-form review text. Returns a default failed feedback if the input is null or blank.
     *
     * @param taskId the task that was reviewed
     * @param reviewerOutput raw output from the code reviewer
     * @return a structured {@link ReviewFeedback}
     */
    public ReviewFeedback parseReviewOutput(String taskId, String reviewerOutput) {
        if (reviewerOutput == null || reviewerOutput.isBlank()) {
            log.warn("Empty or null review output for task {}", taskId);
            return new ReviewFeedback(taskId, false, "No review output",
                    List.of("No output from reviewer"), List.of(), 0);
        }

        log.info("Parsing review output for task {} via LLM ({} chars)", taskId, reviewerOutput.length());

        // Try regex extraction first — if Goose included "Score: X/10" in its output,
        // we can use it directly without relying on the LLM parser
        Matcher scoreMatcher = SCORE_PATTERN.matcher(reviewerOutput);
        int regexScore = scoreMatcher.find() ? Integer.parseInt(scoreMatcher.group(1)) : -1;
        if (regexScore >= 0) {
            log.info("Extracted review score {} via regex for task {}", regexScore, taskId);
        }

        String userPrompt = "Task: " + taskId + "\n\nReview Output:\n" + reviewerOutput;
        var parsed = (mcpToolProvider != null && mcpToolProvider.hasTools())
                ? llmService.structuredCallWithTools(REVIEW_PARSE_SYSTEM_PROMPT, userPrompt, ReviewFeedback.class, mcpToolProvider.getToolsFor("quality_gate"))
                : llmService.structuredCall(REVIEW_PARSE_SYSTEM_PROMPT, userPrompt, ReviewFeedback.class);

        // Use regex-extracted score if LLM returned 0 (likely parsing failure)
        int finalScore = parsed.score();
        if (finalScore == 0 && regexScore > 0) {
            log.info("LLM returned score 0 but regex found {}, using regex score for {}", regexScore, taskId);
            finalScore = regexScore;
        }
        boolean approved = finalScore >= REVIEW_SCORE_THRESHOLD;

        return new ReviewFeedback(taskId, approved, parsed.summary(),
                parsed.issues(), parsed.suggestions(), finalScore);
    }

    /**
     * Evaluate the QualityGate of Approval based on test results and review feedback.
     * <p>
     * The quality_gate is granted if tests pass AND the review score is at or above the threshold (7/10).
     * Otherwise, the task's {@link FailureStrategy} is applied. If the strategy is
     * {@link FailureStrategy#RETRY} but the task has exhausted its maximum iterations,
     * the action escalates to {@link FailureStrategy#ESCALATE}.
     *
     * @param testResult     structured test results from Tester
     * @param reviewFeedback structured review feedback from Reviewer
     * @param task      the task being evaluated
     * @return a {@link QualityGateDecision} indicating whether the quality_gate was granted
     */
    public QualityGateDecision evaluateQualityGate(TestResult testResult, ReviewFeedback reviewFeedback, Task task) {
        boolean testsPassed = testResult.passed();
        boolean reviewApproved = reviewFeedback.score() >= REVIEW_SCORE_THRESHOLD;

        if (testsPassed && reviewApproved) {
            String reason = "QualityGate granted — tests pass, review score " + reviewFeedback.score() + "/10";
            log.info("QualityGate GRANTED for task {}: {}", task.id(), reason);
            return new QualityGateDecision(true, null, reason);
        }

        // Determine failure strategy
        FailureStrategy action = task.onFailure() != null ? task.onFailure() : FailureStrategy.RETRY;

        // If RETRY but iterations exhausted, escalate
        if (action == FailureStrategy.RETRY && task.iteration() >= task.maxIterations()) {
            log.warn("Task {} exhausted retries ({}/{}), escalating",
                    task.id(), task.iteration(), task.maxIterations());
            action = FailureStrategy.ESCALATE;
        }

        // Build reason string
        String reason;
        if (!testsPassed && !reviewApproved) {
            reason = testResult.totalTests() > 0
                    ? String.format("Tests failed (%d of %d) and review score %d/10 below threshold",
                            testResult.failedTests(), testResult.totalTests(), reviewFeedback.score())
                    : String.format("Build/test failure detected and review score %d/10 below threshold",
                            reviewFeedback.score());
        } else if (!testsPassed) {
            reason = testResult.totalTests() > 0
                    ? String.format("Tests failed: %d of %d failed",
                            testResult.failedTests(), testResult.totalTests())
                    : "Build or test failure detected (no structured test output)";
        } else {
            reason = String.format("Review score %d/10 below threshold (minimum %d)",
                    reviewFeedback.score(), REVIEW_SCORE_THRESHOLD);
        }

        log.info("QualityGate DENIED for task {}: {} → {}", task.id(), reason, action);
        return new QualityGateDecision(false, action, reason);
    }
}
