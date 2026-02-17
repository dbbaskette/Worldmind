package com.worldmind.starblaster;

import com.worldmind.core.model.Directive;
import com.worldmind.core.model.FileRecord;
import com.worldmind.core.model.ProjectContext;
import com.worldmind.core.model.TestResult;

import java.util.List;

/**
 * Converts a Directive and ProjectContext into a Goose-readable instruction string.
 * Pure function — no Spring dependencies.
 */
public final class InstructionBuilder {

    private InstructionBuilder() {}

    public static String build(Directive directive, ProjectContext context) {
        var sb = new StringBuilder();

        sb.append("# Directive: ").append(directive.id()).append("\n\n");

        sb.append("## Objective\n\n");
        sb.append(directive.description()).append("\n\n");

        if (directive.inputContext() != null && !directive.inputContext().isBlank()) {
            sb.append("## Additional Context\n\n");
            sb.append(directive.inputContext()).append("\n\n");
        }

        if (context != null) {
            sb.append("## Project Context\n\n");
            sb.append("- **Language:** ").append(context.language()).append("\n");
            sb.append("- **Framework:** ").append(context.framework()).append("\n");
            if (context.summary() != null && !context.summary().isBlank()) {
                sb.append("- **Summary:** ").append(context.summary()).append("\n");
            }
            if (context.dependencies() != null && !context.dependencies().isEmpty()) {
                sb.append("- **Dependencies:** ").append(formatDependencies(context)).append("\n");
            }
            if (context.fileTree() != null && !context.fileTree().isEmpty()) {
                sb.append("\n### File Structure\n\n```\n");
                var tree = context.fileTree();
                if (tree.size() > MAX_FILE_TREE_ENTRIES) {
                    sb.append(String.join("\n", tree.subList(0, MAX_FILE_TREE_ENTRIES)));
                    sb.append("\n... and ").append(tree.size() - MAX_FILE_TREE_ENTRIES).append(" more files\n");
                } else {
                    sb.append(String.join("\n", tree));
                }
                sb.append("\n```\n");
            }
            sb.append("\n");
        }

        sb.append("## Success Criteria\n\n");
        sb.append(directive.successCriteria()).append("\n\n");

        sb.append("## Constraints\n\n");
        sb.append("- **CRITICAL**: You MUST create/modify the files described in the objective. ");
        sb.append("Do NOT exit until you have actually written the code. ");
        sb.append("If you only explore the codebase without creating files, this directive will FAIL.\n");
        sb.append("- Only modify files related to this directive\n");
        sb.append("- Do not modify test files (Gauntlet handles tests)\n");
        sb.append("- When finished, stage and commit all changes: `git add -A && git commit -m 'done'`\n");
        sb.append("- If you encounter an error, attempt to fix it before reporting failure\n");

        return sb.toString();
    }

    /**
     * Builds a test-focused instruction for the GAUNTLET centurion.
     * Gauntlet runs tests to verify code changes made by a Forge directive.
     */
    public static String buildGauntletInstruction(Directive forgeDirective, ProjectContext context,
                                                   List<FileRecord> fileChanges) {
        var sb = new StringBuilder();

        sb.append("# Test Directive for: ").append(forgeDirective.id()).append("\n\n");

        sb.append("## Objective\n\n");
        sb.append("Run tests to verify the code changes made by directive ")
          .append(forgeDirective.id()).append(".\n\n");

        sb.append("## What Was Changed\n\n");
        sb.append(forgeDirective.description()).append("\n\n");

        sb.append("## Files Modified\n\n");
        sb.append(formatFileChanges(fileChanges)).append("\n");

        appendProjectContext(sb, context);

        sb.append("## Test Instructions\n\n");
        sb.append("- Run all existing tests in the project\n");
        sb.append("- If the project has a build tool (Maven, Gradle, npm), use it to run tests\n");
        sb.append("- Report the test results clearly: number of tests run, passed, failed\n");
        sb.append("- If tests fail, include the failure messages\n");
        sb.append("- Do NOT fix any code — only run tests and report results\n\n");

        sb.append("## Output Format\n\n");
        sb.append("Report results as:\n");
        sb.append("Tests run: X, Failures: Y, Errors: Z\n");
        sb.append("{failure details if any}\n");

        return sb.toString();
    }

    /**
     * Builds a review-focused instruction for the VIGIL centurion.
     * Vigil reviews code changes for correctness, security, and quality.
     */
    public static String buildVigilInstruction(Directive forgeDirective, ProjectContext context,
                                                List<FileRecord> fileChanges, TestResult testResult) {
        var sb = new StringBuilder();

        sb.append("# Code Review for: ").append(forgeDirective.id()).append("\n\n");

        sb.append("## Objective\n\n");
        sb.append("Review the code changes made by directive ")
          .append(forgeDirective.id()).append(".\n\n");

        sb.append("## What Was Changed\n\n");
        sb.append(forgeDirective.description()).append("\n\n");

        sb.append("## Files Modified\n\n");
        sb.append(formatFileChanges(fileChanges)).append("\n");

        sb.append("## Test Results\n\n");
        if (testResult != null) {
            sb.append("- Tests passed: ").append(testResult.passed()).append("\n");
            sb.append("- Total: ").append(testResult.totalTests())
              .append(", Failed: ").append(testResult.failedTests()).append("\n");
            sb.append("- Duration: ").append(testResult.durationMs()).append("ms\n");
        } else {
            sb.append("- No test results available\n");
        }
        sb.append("\n");

        appendProjectContext(sb, context);

        sb.append("## Review Instructions\n\n");
        sb.append("- Review each modified file for correctness, security, and code quality\n");
        sb.append("- Check that the implementation matches the directive objective\n");
        sb.append("- Look for: bugs, security vulnerabilities, performance issues, code style problems\n");
        sb.append("- Provide a quality score from 1-10\n");
        sb.append("- List specific issues found\n");
        sb.append("- List improvement suggestions\n\n");

        sb.append("## Output Format\n\n");
        sb.append("Provide your review as:\n");
        sb.append("Score: X/10\n");
        sb.append("Approved: yes/no\n");
        sb.append("Summary: {brief summary}\n");
        sb.append("Issues: {bullet list}\n");
        sb.append("Suggestions: {bullet list}\n");

        return sb.toString();
    }

    /**
     * Builds a read-only research instruction for the PULSE centurion.
     * Pulse centurions analyze code, gather information, and produce reports
     * without modifying any files.
     */
    public static String buildPulseInstruction(Directive directive, ProjectContext context) {
        var sb = new StringBuilder();

        sb.append("# Research Directive: ").append(directive.id()).append("\n\n");

        sb.append("## Objective\n\n");
        sb.append(directive.description()).append("\n\n");

        if (directive.inputContext() != null && !directive.inputContext().isBlank()) {
            sb.append("## Additional Context\n\n");
            sb.append(directive.inputContext()).append("\n\n");
        }

        appendProjectContext(sb, context);

        sb.append("## Success Criteria\n\n");
        sb.append(directive.successCriteria()).append("\n\n");

        sb.append("## Constraints\n\n");
        sb.append("- READ-ONLY: Do NOT create, modify, or delete any files\n");
        sb.append("- Do NOT run any commands that modify state (no git commit, no file writes)\n");
        sb.append("- Analyze existing code, configurations, and documentation only\n");
        sb.append("- Report findings as structured output\n");
        sb.append("- If you need to suggest changes, describe them in your output — do not apply them\n");

        return sb.toString();
    }

    /**
     * Builds a refactoring instruction for the PRISM centurion.
     * Prism performs refactoring with before/after test verification to ensure
     * behavioral equivalence.
     */
    public static String buildPrismInstruction(Directive directive, ProjectContext context,
                                                TestResult baselineTests) {
        var sb = new StringBuilder();
        sb.append("# Refactoring Directive: ").append(directive.id()).append("\n\n");
        sb.append("## Objective\n\n");
        sb.append(directive.description()).append("\n\n");

        if (directive.inputContext() != null && !directive.inputContext().isBlank()) {
            sb.append("## Additional Context\n\n");
            sb.append(directive.inputContext()).append("\n\n");
        }

        appendProjectContext(sb, context);

        sb.append("## Baseline Test Results\n\n");
        if (baselineTests != null) {
            sb.append("- Tests passed: ").append(baselineTests.passed()).append("\n");
            sb.append("- Total: ").append(baselineTests.totalTests())
              .append(", Failed: ").append(baselineTests.failedTests()).append("\n");
        } else {
            sb.append("- No baseline tests available — run tests after refactoring to verify\n");
        }
        sb.append("\n");

        sb.append("## Success Criteria\n\n");
        sb.append(directive.successCriteria()).append("\n\n");

        sb.append("## Constraints\n\n");
        sb.append("- **CRITICAL**: You MUST make refactoring changes to the code. ");
        sb.append("Do NOT exit until you have actually modified files. ");
        sb.append("If you only explore the codebase without making changes, this directive will FAIL.\n");
        sb.append("- BEHAVIORAL EQUIVALENCE: Tests must pass identically before and after refactoring\n");
        sb.append("- Do NOT change any public APIs, method signatures, or external behavior\n");
        sb.append("- Focus on internal code quality: extract methods, reduce duplication, improve naming\n");
        sb.append("- Run tests after each refactoring step to ensure nothing breaks\n");
        sb.append("- If any test fails after refactoring, revert that specific change\n");
        sb.append("- When finished, stage and commit all changes: `git add -A && git commit -m 'done'`\n");
        return sb.toString();
    }

    /**
     * Prepends a runtime environment preamble when runtimeTag is "base",
     * informing the centurion that it may need to install tools at runtime.
     */
    public static String withRuntimePreamble(String instruction, String runtimeTag) {
        if (!"base".equals(runtimeTag)) return instruction;
        return """
                ## Runtime Environment Note
                Your container has basic build tools (git, curl, shell) but may not have the specific
                language runtime needed. If a required tool is missing, install it using apt-get, curl,
                or the appropriate package manager before proceeding.

                """ + instruction;
    }

    /**
     * Appends a brief note reminding the centurion that MCP extension tools
     * are available and should be used when relevant.
     *
     * @param instruction    the base instruction text
     * @param centurionType  the centurion type (unused for now, kept for future scoping)
     * @param serverNames    names of configured MCP servers (e.g. ["nexus"])
     * @return instruction with MCP tools note appended
     */
    public static String withMcpTools(String instruction, String centurionType, List<String> serverNames) {
        if (serverNames == null || serverNames.isEmpty()) return instruction;
        return instruction
                + "\n\n## Tools\n\n"
                + "You have MCP extension tools available for GitHub operations, code search, "
                + "and other services. Use them whenever they can help — especially for git "
                + "operations, reading repository files, or searching code.\n";
    }

    private static String formatFileChanges(List<FileRecord> fileChanges) {
        if (fileChanges == null || fileChanges.isEmpty()) {
            return "- No file changes recorded\n";
        }
        var sb = new StringBuilder();
        for (var fc : fileChanges) {
            sb.append("- ").append(fc.action()).append(": ").append(fc.path())
              .append(" (").append(fc.linesChanged()).append(" lines)\n");
        }
        return sb.toString();
    }

    private static final int MAX_FILE_TREE_ENTRIES = 200;
    private static final int MAX_DEPENDENCIES = 50;

    private static void appendProjectContext(StringBuilder sb, ProjectContext context) {
        if (context != null) {
            sb.append("## Project Context\n\n");
            sb.append("- **Language:** ").append(context.language()).append("\n");
            sb.append("- **Framework:** ").append(context.framework()).append("\n");
            if (context.dependencies() != null && !context.dependencies().isEmpty()) {
                sb.append("- **Dependencies:** ").append(formatDependencies(context)).append("\n");
            }
            if (context.fileTree() != null && !context.fileTree().isEmpty()) {
                sb.append("\n### File Structure\n\n```\n");
                var tree = context.fileTree();
                if (tree.size() > MAX_FILE_TREE_ENTRIES) {
                    sb.append(String.join("\n", tree.subList(0, MAX_FILE_TREE_ENTRIES)));
                    sb.append("\n... and ").append(tree.size() - MAX_FILE_TREE_ENTRIES).append(" more files\n");
                } else {
                    sb.append(String.join("\n", tree));
                }
                sb.append("\n```\n");
            }
            sb.append("\n");
        }
    }

    private static String formatDependencies(ProjectContext context) {
        var entries = context.dependencies().entrySet().stream()
            .map(e -> e.getKey() + ":" + e.getValue())
            .sorted()
            .toList();
        if (entries.size() > MAX_DEPENDENCIES) {
            entries = entries.subList(0, MAX_DEPENDENCIES);
            return String.join(", ", entries) + " ... and more";
        }
        return String.join(", ", entries);
    }
}
