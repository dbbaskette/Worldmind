package com.worldmind.core.seal;

import com.worldmind.core.llm.LlmService;
import com.worldmind.core.model.Directive;
import com.worldmind.core.model.FailureStrategy;
import com.worldmind.core.model.ReviewFeedback;
import com.worldmind.core.model.SealDecision;
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
 * Evaluates the Seal of Approval quality gate for completed directives.
 * <p>
 * Responsibilities:
 * <ul>
 *   <li>Parse raw Gauntlet (test runner) output into structured {@link TestResult}</li>
 *   <li>Parse raw Vigil (code reviewer) output into structured {@link ReviewFeedback} via LLM</li>
 *   <li>Evaluate whether a directive earns the Seal of Approval based on test results and review feedback</li>
 * </ul>
 */
@Service
public class SealEvaluationService {

    private static final Logger log = LoggerFactory.getLogger(SealEvaluationService.class);

    /** Minimum review score (inclusive) required for seal approval. */
    private static final int REVIEW_SCORE_THRESHOLD = 7;

    /** Maven/JUnit style: "Tests run: 10, Failures: 2" */
    private static final Pattern MAVEN_PATTERN =
            Pattern.compile("Tests run:\\s*(\\d+),\\s*Failures:\\s*(\\d+)");

    /** pytest style: "8 passed, 2 failed" or "8 passed" */
    private static final Pattern PYTEST_PASSED_PATTERN = Pattern.compile("(\\d+)\\s+passed");
    private static final Pattern PYTEST_FAILED_PATTERN = Pattern.compile("(\\d+)\\s+failed");

    /** Fallback failure indicators (case-insensitive). */
    private static final Pattern FAILURE_INDICATOR =
            Pattern.compile("(?i)(FAILED|Error)");

    private static final String REVIEW_PARSE_SYSTEM_PROMPT =
            "You are a code review parser. Extract structured feedback from the following code review output. " +
            "Extract the reviewer's approval decision and score exactly as stated in the output. " +
            "If the review contains an explicit score (e.g. 'Score: 8/10'), use that exact number. " +
            "Do NOT re-evaluate or override the reviewer's score — extract it faithfully. " +
            "Also extract the summary, list of issues, and list of suggestions from the review.";

    private final LlmService llmService;
    private final McpToolProvider mcpToolProvider;

    public SealEvaluationService(LlmService llmService,
                                 @Autowired(required = false) McpToolProvider mcpToolProvider) {
        this.llmService = llmService;
        this.mcpToolProvider = mcpToolProvider;
    }

    /**
     * Parse raw Gauntlet (test runner) output into a structured {@link TestResult}.
     * <p>
     * Looks for patterns like "Tests run: X, Failures: Y" (Maven/JUnit) or
     * "X passed, Y failed" (pytest). If no pattern is found, falls back to
     * checking for error/failure keywords in the output.
     *
     * @param directiveId   the directive that was tested
     * @param gauntletOutput raw output from the test runner
     * @param durationMs    how long the test run took
     * @return a structured {@link TestResult}
     */
    public TestResult parseTestOutput(String directiveId, String gauntletOutput, long durationMs) {
        if (gauntletOutput == null || gauntletOutput.isBlank()) {
            log.warn("Empty or null test output for directive {}", directiveId);
            return new TestResult(directiveId, false, 0, 0, gauntletOutput != null ? gauntletOutput : "", durationMs);
        }

        // Try Maven/JUnit style: "Tests run: 10, Failures: 2"
        Matcher mavenMatcher = MAVEN_PATTERN.matcher(gauntletOutput);
        if (mavenMatcher.find()) {
            int totalTests = Integer.parseInt(mavenMatcher.group(1));
            int failedTests = Integer.parseInt(mavenMatcher.group(2));
            boolean passed = failedTests == 0;
            log.info("Parsed Maven-style output for {}: {}/{} tests passed", directiveId, totalTests - failedTests, totalTests);
            return new TestResult(directiveId, passed, totalTests, failedTests, gauntletOutput, durationMs);
        }

        // Try pytest style: "8 passed, 2 failed"
        Matcher passedMatcher = PYTEST_PASSED_PATTERN.matcher(gauntletOutput);
        Matcher failedMatcher = PYTEST_FAILED_PATTERN.matcher(gauntletOutput);
        boolean foundPassed = passedMatcher.find();
        boolean foundFailed = failedMatcher.find();
        if (foundPassed || foundFailed) {
            int passedCount = foundPassed ? Integer.parseInt(passedMatcher.group(1)) : 0;
            int failedCount = foundFailed ? Integer.parseInt(failedMatcher.group(1)) : 0;
            int totalTests = passedCount + failedCount;
            boolean passed = failedCount == 0;
            log.info("Parsed pytest-style output for {}: {}/{} tests passed", directiveId, passedCount, totalTests);
            return new TestResult(directiveId, passed, totalTests, failedCount, gauntletOutput, durationMs);
        }

        // Fallback: check for failure/error keywords
        boolean containsFailure = FAILURE_INDICATOR.matcher(gauntletOutput).find();
        if (containsFailure) {
            log.info("Fallback: failure indicator found in output for {}", directiveId);
            return new TestResult(directiveId, false, 0, 0, gauntletOutput, durationMs);
        }

        log.info("Fallback: no failure indicators found in output for {}, treating as passed", directiveId);
        return new TestResult(directiveId, true, 0, 0, gauntletOutput, durationMs);
    }

    /**
     * Parse raw Vigil (code reviewer) output into structured {@link ReviewFeedback}.
     * <p>
     * Uses {@link LlmService#structuredCall} to extract structured review data from
     * free-form review text. Returns a default failed feedback if the input is null or blank.
     *
     * @param directiveId the directive that was reviewed
     * @param vigilOutput raw output from the code reviewer
     * @return a structured {@link ReviewFeedback}
     */
    public ReviewFeedback parseReviewOutput(String directiveId, String vigilOutput) {
        if (vigilOutput == null || vigilOutput.isBlank()) {
            log.warn("Empty or null review output for directive {}", directiveId);
            return new ReviewFeedback(directiveId, false, "No review output",
                    List.of("No output from reviewer"), List.of(), 0);
        }

        log.info("Parsing review output for directive {} via LLM", directiveId);
        String userPrompt = "Directive: " + directiveId + "\n\nReview Output:\n" + vigilOutput;
        var parsed = (mcpToolProvider != null && mcpToolProvider.hasTools())
                ? llmService.structuredCallWithTools(REVIEW_PARSE_SYSTEM_PROMPT, userPrompt, ReviewFeedback.class, mcpToolProvider.getToolsFor("seal"))
                : llmService.structuredCall(REVIEW_PARSE_SYSTEM_PROMPT, userPrompt, ReviewFeedback.class);

        // Reconstruct with the correct directiveId since the LLM may not preserve it
        return new ReviewFeedback(directiveId, parsed.approved(), parsed.summary(),
                parsed.issues(), parsed.suggestions(), parsed.score());
    }

    /**
     * Evaluate the Seal of Approval based on test results and review feedback.
     * <p>
     * The seal is granted if tests pass AND the review score is at or above the threshold (7/10).
     * Otherwise, the directive's {@link FailureStrategy} is applied. If the strategy is
     * {@link FailureStrategy#RETRY} but the directive has exhausted its maximum iterations,
     * the action escalates to {@link FailureStrategy#ESCALATE}.
     *
     * @param testResult     structured test results from Gauntlet
     * @param reviewFeedback structured review feedback from Vigil
     * @param directive      the directive being evaluated
     * @return a {@link SealDecision} indicating whether the seal was granted
     */
    public SealDecision evaluateSeal(TestResult testResult, ReviewFeedback reviewFeedback, Directive directive) {
        boolean testsPassed = testResult.passed();
        boolean reviewApproved = reviewFeedback.score() >= REVIEW_SCORE_THRESHOLD;

        if (testsPassed && reviewApproved) {
            String reason = "Seal granted — tests pass, review score " + reviewFeedback.score() + "/10";
            log.info("Seal GRANTED for directive {}: {}", directive.id(), reason);
            return new SealDecision(true, null, reason);
        }

        // Determine failure strategy
        FailureStrategy action = directive.onFailure() != null ? directive.onFailure() : FailureStrategy.RETRY;

        // If RETRY but iterations exhausted, escalate
        if (action == FailureStrategy.RETRY && directive.iteration() >= directive.maxIterations()) {
            log.warn("Directive {} exhausted retries ({}/{}), escalating",
                    directive.id(), directive.iteration(), directive.maxIterations());
            action = FailureStrategy.ESCALATE;
        }

        // Build reason string
        String reason;
        if (!testsPassed && !reviewApproved) {
            reason = String.format("Tests failed (%d of %d) and review score %d/10 below threshold",
                    testResult.failedTests(), testResult.totalTests(), reviewFeedback.score());
        } else if (!testsPassed) {
            reason = String.format("Tests failed: %d of %d failed",
                    testResult.failedTests(), testResult.totalTests());
        } else {
            reason = String.format("Review score %d/10 below threshold (minimum %d)",
                    reviewFeedback.score(), REVIEW_SCORE_THRESHOLD);
        }

        log.info("Seal DENIED for directive {}: {} → {}", directive.id(), reason, action);
        return new SealDecision(false, action, reason);
    }
}
