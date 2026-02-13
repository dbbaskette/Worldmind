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

    /** Specific test failure patterns (not generic "Error" which matches Goose session noise). */
    private static final Pattern BUILD_FAILURE_PATTERN =
            Pattern.compile("(?i)(BUILD FAILURE|BUILD FAILED|COMPILATION ERROR|test.*failed|npm ERR!)");

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

        // Fallback: check for specific build/test failure patterns (not generic "Error"
        // which false-positives on Goose session logs containing error handling code)
        boolean containsBuildFailure = BUILD_FAILURE_PATTERN.matcher(gauntletOutput).find();
        if (containsBuildFailure) {
            log.info("Fallback: build/test failure pattern found in output for {}", directiveId);
            return new TestResult(directiveId, false, 0, 0, gauntletOutput, durationMs);
        }

        log.info("No test framework output found for {}, treating as passed", directiveId);
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

        log.info("Parsing review output for directive {} via LLM ({} chars)", directiveId, vigilOutput.length());

        // Try regex extraction first — if Goose included "Score: X/10" in its output,
        // we can use it directly without relying on the LLM parser
        Matcher scoreMatcher = SCORE_PATTERN.matcher(vigilOutput);
        int regexScore = scoreMatcher.find() ? Integer.parseInt(scoreMatcher.group(1)) : -1;
        if (regexScore >= 0) {
            log.info("Extracted review score {} via regex for directive {}", regexScore, directiveId);
        }

        String userPrompt = "Directive: " + directiveId + "\n\nReview Output:\n" + vigilOutput;
        var parsed = (mcpToolProvider != null && mcpToolProvider.hasTools())
                ? llmService.structuredCallWithTools(REVIEW_PARSE_SYSTEM_PROMPT, userPrompt, ReviewFeedback.class, mcpToolProvider.getToolsFor("seal"))
                : llmService.structuredCall(REVIEW_PARSE_SYSTEM_PROMPT, userPrompt, ReviewFeedback.class);

        // Use regex-extracted score if LLM returned 0 (likely parsing failure)
        int finalScore = parsed.score();
        if (finalScore == 0 && regexScore > 0) {
            log.info("LLM returned score 0 but regex found {}, using regex score for {}", regexScore, directiveId);
            finalScore = regexScore;
        }
        boolean approved = finalScore >= REVIEW_SCORE_THRESHOLD;

        return new ReviewFeedback(directiveId, approved, parsed.summary(),
                parsed.issues(), parsed.suggestions(), finalScore);
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
