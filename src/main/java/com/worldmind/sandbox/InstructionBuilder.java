package com.worldmind.sandbox;

import com.worldmind.core.model.Task;
import com.worldmind.core.model.FileRecord;
import com.worldmind.core.model.ProjectContext;
import com.worldmind.core.model.TestResult;

import java.util.List;

/**
 * Converts a Task and ProjectContext into a Goose-readable instruction string.
 * Pure function — no Spring dependencies.
 */
public final class InstructionBuilder {

    private InstructionBuilder() {}

    public static String build(Task task, ProjectContext context) {
        return build(task, context, "medium");
    }

    public static String build(Task task, ProjectContext context, String reasoningLevel) {
        var sb = new StringBuilder();

        // Add reasoning guidance based on level
        if ("high".equalsIgnoreCase(reasoningLevel) || "max".equalsIgnoreCase(reasoningLevel)) {
            sb.append("## Reasoning Approach\n\n");
            sb.append("- Think through this problem step by step before implementing\n");
            sb.append("- Consider edge cases and potential issues\n");
            sb.append("- Evaluate multiple approaches before choosing one\n");
            if ("max".equalsIgnoreCase(reasoningLevel)) {
                sb.append("- Take your time — quality and correctness over speed\n");
                sb.append("- Double-check your implementation before committing\n");
                sb.append("- Consider how this integrates with existing code\n");
            }
            sb.append("\n");
        } else if ("low".equalsIgnoreCase(reasoningLevel)) {
            sb.append("## Approach\n\n");
            sb.append("- Be concise and direct — implement the minimum viable solution\n");
            sb.append("- Don't over-engineer; simple is better\n\n");
        }

        sb.append("# Task: ").append(task.id()).append("\n\n");

        sb.append("## Objective\n\n");
        sb.append(task.description()).append("\n\n");

        if (task.inputContext() != null && !task.inputContext().isBlank()) {
            sb.append("## Additional Context\n\n");
            sb.append(task.inputContext()).append("\n\n");
            // Emphasize file creation constraints if present
            if (task.inputContext().toLowerCase().contains("do not create")) {
                sb.append("**IMPORTANT**: The constraints above about files you should NOT create ");
                sb.append("are STRICT. Another task owns those files. If you create them, ");
                sb.append("you will cause merge conflicts and the mission will fail.\n\n");
            }
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
        sb.append(task.successCriteria()).append("\n\n");

        sb.append("## Workspace Layout\n\n");
        sb.append("- Your working directory is `/workspace` (the project root).\n");
        sb.append("- **ALL code MUST be created under `/workspace`** using standard project paths ");
        sb.append("(e.g., `src/main/java/...`, `public/`, `pom.xml`, `package.json`).\n");
        sb.append("- **NEVER put code in `.worldmind-*` directories.** Those are internal orchestration ");
        sb.append("directories containing instruction files and logs — not part of the project. Ignore them entirely.\n");
        sb.append("- If you see existing code in `.worldmind-*` directories, that is NOT the project code. ");
        sb.append("Do not read, copy, or reference files from those directories.\n\n");

        // File ownership — explicit boundaries for what this task may touch
        if (task.targetFiles() != null && !task.targetFiles().isEmpty()) {
            sb.append("## File Ownership (STRICT)\n\n");
            sb.append("You are assigned the following files. You MUST create/modify ONLY these files:\n\n");
            for (String f : task.targetFiles()) {
                sb.append("- `").append(f).append("`\n");
            }
            sb.append("\n**RULES:**\n");
            sb.append("- Do NOT create or modify any files outside this list.\n");
            sb.append("- Other tasks own other files. If you touch them, you will cause merge conflicts and the mission will fail.\n");
            sb.append("- If you need to READ another file for context (e.g., an interface you implement), that is fine — but do NOT write to it.\n");
            sb.append("- If your task genuinely cannot be completed without modifying an unlisted file, ");
            sb.append("document this in a comment in one of your owned files and proceed with your assigned files only.\n\n");
        }

        sb.append("## Constraints\n\n");
        sb.append("- **NAMING**: Name things according to their function. App names, project names, ");
        sb.append("and config files should describe what the application does (e.g., `snake-game`, `todo-api`), ");
        sb.append("not generic names like `my-app` or `worldmind-app`.\n");
        sb.append("- **CRITICAL**: You MUST create/modify the files described in the objective. ");
        sb.append("Do NOT exit until you have actually written the code. ");
        sb.append("If you only explore the codebase without creating files, this task will FAIL.\n");
        sb.append("- **FUNCTIONAL COMPLETENESS**: Deliver working, functional code — not scaffolding or placeholders. ");
        sb.append("Before committing, verify it actually works: run it, test it, confirm the core functionality operates as expected. ");
        sb.append("If something doesn't work, fix it before finishing.\n");
        sb.append("- Only modify files related to this task\n");
        sb.append("- Do not modify test files (Tester handles tests)\n");
        sb.append("- When finished, stage and commit all changes: `git add -A && git commit -m 'done'`\n");
        sb.append("- If you encounter an error, attempt to fix it before reporting failure\n\n");

        sb.append("## Available Tools\n\n");
        sb.append("- **File Operations**: Read, write, and modify files in the workspace.\n");
        sb.append("- **Shell**: Run shell commands (build, test, etc.).\n\n");

        sb.append("## Cloud Foundry Deployment\n\n");
        sb.append("If you create a `manifest.yml`:\n");
        sb.append("- Use `default-route: true` — NEVER hardcode routes like `route: app.apps.example.com`\n");
        sb.append("- **Staticfile apps**: Create a `Staticfile` config with `root: public` and place HTML/CSS/JS in `public/`\n");
        sb.append("- **Java apps**: Ensure path matches where the JAR is built (usually `target/`)\n");
        sb.append("- **Node apps**: Ensure `package.json` exists with a valid `start` script\n");
        sb.append("- Verify the deployment config matches the actual file structure before committing\n");

        return sb.toString();
    }

    /**
     * Builds a test-focused instruction for the TESTER agent.
     * Tester runs tests to verify code changes made by a Coder task.
     */
    public static String buildTesterInstruction(Task coderTask, ProjectContext context,
                                                   List<FileRecord> fileChanges) {
        var sb = new StringBuilder();

        sb.append("# Test Task for: ").append(coderTask.id()).append("\n\n");

        sb.append("## Objective\n\n");
        sb.append("Run tests to verify the code changes made by task ")
          .append(coderTask.id()).append(".\n\n");

        sb.append("## What Was Changed\n\n");
        sb.append(coderTask.description()).append("\n\n");

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
     * Builds a review-focused instruction for the REVIEWER agent.
     * Reviewer reviews code changes for correctness, security, and quality.
     */
    public static String buildReviewerInstruction(Task coderTask, ProjectContext context,
                                                List<FileRecord> fileChanges, TestResult testResult) {
        var sb = new StringBuilder();

        sb.append("# Code Review for: ").append(coderTask.id()).append("\n\n");

        sb.append("## Objective\n\n");
        sb.append("Review the code changes made by task ")
          .append(coderTask.id()).append(".\n\n");

        sb.append("## What Was Changed\n\n");
        sb.append(coderTask.description()).append("\n\n");

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

        sb.append("## Review Scope (CRITICAL — READ THIS FIRST)\n\n");
        sb.append("You are reviewing ONE task in a multi-task mission. Other tasks handle other parts.\n");
        sb.append("**ONLY evaluate whether THIS task fulfilled ITS described objective.**\n\n");
        sb.append("DO NOT penalize for:\n");
        sb.append("- Missing tests (a separate TESTER task handles testing)\n");
        sb.append("- Missing security config (another task may own that)\n");
        sb.append("- Missing deployment files like manifest.yml (another task may own that)\n");
        sb.append("- Missing configuration files (another task may own that)\n");
        sb.append("- Missing features that are NOT in the task description above\n\n");
        sb.append("DO evaluate:\n");
        sb.append("- Does the code implement what the task description asks for?\n");
        sb.append("- Is the code syntactically correct and complete (no truncated files, missing braces)?\n");
        sb.append("- Are there bugs in the logic THIS task implemented?\n");
        sb.append("- Basic code quality: naming, structure, obvious anti-patterns\n\n");

        sb.append("## Review Instructions\n\n");
        sb.append("- Review ONLY the modified files listed above\n");
        sb.append("- Check that the implementation matches the task objective\n");
        sb.append("- Look for: bugs, compilation errors, logic errors, obvious security issues in THIS code\n\n");
        sb.append("### FUNCTIONAL COMPLETENESS CHECK\n");
        sb.append("- Does the code actually implement what was requested? Not just scaffolding.\n");
        sb.append("- Is the file syntactically complete? (no missing closing braces, imports, etc.)\n");
        sb.append("- **FAIL (score <= 4) if core functionality is missing, broken, or file is truncated.**\n\n");
        sb.append("- Provide a quality score from 1-10\n");
        sb.append("- List at most 3 specific issues found (focus on the most critical)\n");
        sb.append("- List at most 3 improvement suggestions\n\n");

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
     * Builds a read-only research instruction for the RESEARCHER agent.
     * Researcher agents analyze code, gather information, and produce reports
     * without modifying any files.
     */
    public static String buildResearcherInstruction(Task task, ProjectContext context) {
        var sb = new StringBuilder();

        sb.append("# Research Task: ").append(task.id()).append("\n\n");

        sb.append("## Objective\n\n");
        sb.append(task.description()).append("\n\n");

        if (task.inputContext() != null && !task.inputContext().isBlank()) {
            sb.append("## Additional Context\n\n");
            sb.append(task.inputContext()).append("\n\n");
        }

        appendProjectContext(sb, context);

        sb.append("## Success Criteria\n\n");
        sb.append(task.successCriteria()).append("\n\n");

        sb.append("## Available Tools\n\n");
        sb.append("- **File Operations**: Read files in the workspace (but do NOT write).\n");
        sb.append("- **Shell**: Run shell commands (grep, find, ls, etc.).\n\n");

        sb.append("## Constraints\n\n");
        sb.append("- READ-ONLY: Do NOT create, modify, or delete any files\n");
        sb.append("- Do NOT run any commands that modify state (no git commit, no file writes)\n");
        sb.append("- Analyze existing code, configurations, and documentation only\n");
        sb.append("- Report findings as structured output\n");
        sb.append("- If you need to suggest changes, describe them in your output — do not apply them\n");

        return sb.toString();
    }

    /**
     * Builds a refactoring instruction for the REFACTORER agent.
     * Refactorer performs refactoring with before/after test verification to ensure
     * behavioral equivalence.
     */
    public static String buildRefactorerInstruction(Task task, ProjectContext context,
                                                TestResult baselineTests) {
        var sb = new StringBuilder();
        sb.append("# Refactoring Task: ").append(task.id()).append("\n\n");
        sb.append("## Objective\n\n");
        sb.append(task.description()).append("\n\n");

        if (task.inputContext() != null && !task.inputContext().isBlank()) {
            sb.append("## Additional Context\n\n");
            sb.append(task.inputContext()).append("\n\n");
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
        sb.append(task.successCriteria()).append("\n\n");

        sb.append("## Constraints\n\n");
        sb.append("- **CRITICAL**: You MUST make refactoring changes to the code. ");
        sb.append("Do NOT exit until you have actually modified files. ");
        sb.append("If you only explore the codebase without making changes, this task will FAIL.\n");
        sb.append("- BEHAVIORAL EQUIVALENCE: Tests must pass identically before and after refactoring\n");
        sb.append("- Do NOT change any public APIs, method signatures, or external behavior\n");
        sb.append("- Focus on internal code quality: extract methods, reduce duplication, improve naming\n");
        sb.append("- Run tests after each refactoring step to ensure nothing breaks\n");
        sb.append("- If any test fails after refactoring, revert that specific change\n");
        sb.append("- When finished, stage and commit all changes: `git add -A && git commit -m 'done'`\n");
        return sb.toString();
    }

    /**
     * Builds deployment instructions for the DEPLOYER agent.
     * Generates step-by-step markdown that Goose follows to build and deploy the application
     * to Cloud Foundry, including CF auth, build, manifest generation, deploy, and verification.
     *
     * @param task                  the DEPLOYER task
     * @param missionId             mission identifier used for app name and route
     * @param cfAppsDomain          CF apps domain for route generation
     * @param manifestCreatedByTask whether a preceding task already created manifest.yml
     * @param serviceBindings       service instance names to bind (may be null or empty)
     * @param appType               application type, e.g. "spring-boot"
     * @param deployerProperties    deployer configuration properties for manifest defaults
     * @return markdown instruction string for the DEPLOYER agent
     */
    public static String buildDeployerInstruction(Task task, String missionId, String cfAppsDomain,
                                                  boolean manifestCreatedByTask,
                                                  List<String> serviceBindings, String appType,
                                                  DeployerProperties deployerProperties) {
        if (deployerProperties == null) {
            deployerProperties = new DeployerProperties();
        }
        String appName = (missionId != null && !missionId.isBlank()) ? missionId : "app";
        // Falls back to a literal $CF_APPS_DOMAIN placeholder when no domain is provided.
        // The deployer agent must resolve this variable or set the route manually.
        String domain = (cfAppsDomain != null && !cfAppsDomain.isBlank()) ? cfAppsDomain : "$CF_APPS_DOMAIN";
        String route = appName + ".apps." + domain;
        String type = (appType != null && !appType.isBlank()) ? appType : "spring-boot";
        List<String> services = (serviceBindings != null) ? serviceBindings : List.of();

        var defaults = deployerProperties.getDefaults();
        int healthTimeoutMinutes = (deployerProperties.getHealthTimeout() + 59) / 60;

        var sb = new StringBuilder();

        sb.append("# Deployment Task: ").append(task.id()).append("\n\n");
        sb.append("Deploy the completed application to Cloud Foundry.\n\n");

        // Section 1: Application Details
        sb.append("## Application Details\n\n");
        sb.append("- **Type:** ").append(type).append("\n");
        sb.append("- **Artifact path:** target/*.jar\n");
        sb.append("- **Route:** ").append(route).append("\n\n");

        // Section 2: CF Authentication
        sb.append("## CF Authentication\n\n");
        sb.append("```bash\n");
        sb.append("cf api $CF_API_URL");
        if (deployerProperties.isSkipSslValidation()) {
            sb.append(" --skip-ssl-validation");
        }
        sb.append("\n");
        sb.append("cf auth $CF_USERNAME $CF_PASSWORD\n");
        sb.append("cf target -o $CF_ORG -s $CF_SPACE\n");
        sb.append("```\n\n");

        // Section 3: Build
        sb.append("## Build\n\n");
        sb.append("If `./mvnw` exists, use it; otherwise fall back to `mvn`:\n");
        sb.append("```bash\n");
        sb.append("if [ -f ./mvnw ]; then\n");
        sb.append("  ./mvnw clean package -DskipTests\n");
        sb.append("else\n");
        sb.append("  mvn clean package -DskipTests\n");
        sb.append("fi\n");
        sb.append("```\n\n");

        // Section 4: Manifest
        if (manifestCreatedByTask) {
            sb.append("## Manifest\n\n");
            sb.append("A preceding task has already created `manifest.yml`. ");
            sb.append("Use the existing manifest as-is for deployment.\n\n");
        } else {
            sb.append("## Manifest\n\n");
            sb.append("No task created a manifest. Create `manifest.yml` with this content:\n");
            sb.append("```yaml\n");
            sb.append("applications:\n");
            sb.append("- name: ").append(appName).append("\n");
            sb.append("  memory: ").append(defaults.getMemory()).append("\n");
            sb.append("  instances: ").append(defaults.getInstances()).append("\n");
            sb.append("  path: target/*.jar\n");
            sb.append("  buildpacks:\n");
            sb.append("  - ").append(defaults.getBuildpack()).append("\n");
            sb.append("  routes:\n");
            sb.append("  - route: ").append(route).append("\n");
            sb.append("  env:\n");
            sb.append("    JBP_CONFIG_OPEN_JDK_JRE: '{ jre: { version: ")
                    .append(defaults.getJavaVersion()).append(".+ } }'\n");
            if (!services.isEmpty()) {
                sb.append("  services:\n");
                for (String svc : services) {
                    sb.append("  - \"").append(svc).append("\"\n");
                }
            }
            sb.append("```\n\n");
        }

        // Section 5: Deploy
        sb.append("## Deploy\n\n");
        sb.append("```bash\n");
        sb.append("cf push -f manifest.yml\n");
        sb.append("```\n\n");

        // Section 6: Verification
        sb.append("## Verification\n\n");
        sb.append("Check the app status and verify the health check passes:\n");
        sb.append("```bash\n");
        sb.append("cf app ").append(appName).append("\n");
        sb.append("```\n");
        sb.append("Confirm the app shows `running` status and the health check passes within ")
                .append(healthTimeoutMinutes).append(" minutes.\n\n");

        // Section 7: Success Criteria
        sb.append("## Success Criteria\n\n");
        sb.append("- App is running with no crashes\n");
        sb.append("- Health check passes\n");
        sb.append("- App is accessible at `").append(route).append("`\n");

        return sb.toString();
    }

    /**
     * Prepends a runtime environment preamble when runtimeTag is "base",
     * informing the agent that it may need to install tools at runtime.
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
     * Appends a brief note reminding the agent that MCP extension tools
     * are available and should be used when relevant.
     *
     * @param instruction    the base instruction text
     * @param agentType  the agent type (unused for now, kept for future scoping)
     * @param serverNames    names of configured MCP servers (e.g. ["nexus"])
     * @return instruction with MCP tools note appended
     */
    public static String withMcpTools(String instruction, String agentType, List<String> serverNames) {
        if (serverNames == null || serverNames.isEmpty()) return instruction;
        return instruction
                + "\n\n## MCP Tools\n\n"
                + "You have MCP extension tools available:\n"
                + "- **Web Search** (Brave): Search the web for documentation, APIs, examples, "
                + "and best practices. Use this when you need to look up how a library works, "
                + "find error solutions, or research unfamiliar technologies.\n"
                + "- **GitHub**: Repository operations, code search, reading files from repos.\n"
                + "\nUse these tools proactively — especially web search for any technology "
                + "or API you are not fully confident about.\n";
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
