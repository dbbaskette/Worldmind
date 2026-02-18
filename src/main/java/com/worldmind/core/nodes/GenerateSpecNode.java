package com.worldmind.core.nodes;

import com.worldmind.core.llm.LlmService;
import com.worldmind.core.model.Classification;
import com.worldmind.core.model.MissionStatus;
import com.worldmind.core.model.ProductSpec;
import com.worldmind.core.model.ProjectContext;
import com.worldmind.core.state.WorldmindState;
import com.worldmind.mcp.McpToolProvider;
import com.worldmind.starblaster.cf.CloudFoundryProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Generates a {@link ProductSpec} from the classified request and project context,
 * writes it as SPEC.md to the project directory, and optionally commits it to git.
 */
@Component
public class GenerateSpecNode {

    private static final Logger log = LoggerFactory.getLogger(GenerateSpecNode.class);

    private static final String SYSTEM_PROMPT = """
            You are a senior product manager writing a detailed PRD (Product Requirements Document)
            for Worldmind, an agentic code assistant. Given a user request, its classification,
            and project context, produce a comprehensive, implementation-ready specification.
            
            This PRD will be the SOLE INPUT for autonomous coding agents. They CANNOT ask
            follow-up questions, so you must anticipate everything they need to know.
            Be exhaustively specific — vague specs produce vague code.

            ## Required Sections

            ### 1. Title & Overview
            - Descriptive title that captures what's being built
            - Overview paragraph explaining the purpose, context, and value

            ### 2. Goals (3-5 specific, measurable outcomes)
            - Each goal must be verifiable (e.g., "User can move character with arrow keys")
            - Avoid vague goals like "make it better" or "improve UX"

            ### 3. Non-Goals (explicitly out of scope)
            - Prevent scope creep by listing what we're NOT building
            - Include features that might seem related but aren't needed

            ### 4. Components (breakdown of discrete parts)
            For each component specify:
            - Name and single responsibility
            - Exact file paths (e.g., "src/game/player.js", not "the player file")
            - Behavior expectations with concrete inputs/outputs
            - Integration points with other components

            ### 5. Technical Requirements
            - Frameworks, libraries, patterns to use
            - Code conventions (naming, structure)
            - Performance requirements

            ### 6. Edge Cases
            - Error scenarios with expected behavior
            - Boundary conditions (empty states, maximums)
            - Invalid inputs and how to handle them

            ### 7. Acceptance Criteria
            - Checkable conditions that prove the work is complete
            - "When I do X, Y should happen" format

            ## Example Detail Level

            BAD: "Add player movement"
            GOOD: "Implement player movement using arrow keys. Player sprite moves 5px per
                  keypress. Movement is constrained to canvas bounds (0,0 to 800,600).
                  Holding a key should NOT cause continuous movement — one move per keypress.
                  Player starts at position (400, 300)."

            BAD: "Create the game file"
            GOOD: "Create public/game.js containing:
                  - Game class with init(), update(), render() methods
                  - requestAnimationFrame loop running at 60fps
                  - Canvas context stored as this.ctx
                  - Keyboard event listeners attached in init()
                  - Export Game as default"

            Respond with valid JSON matching the schema provided.
            """;

    private final LlmService llmService;
    private final CloudFoundryProperties cfProperties;
    private final McpToolProvider mcpToolProvider;

    public GenerateSpecNode(LlmService llmService,
                            @Autowired(required = false) CloudFoundryProperties cfProperties,
                            @Autowired(required = false) McpToolProvider mcpToolProvider) {
        this.llmService = llmService;
        this.cfProperties = cfProperties;
        this.mcpToolProvider = mcpToolProvider;
    }

    public Map<String, Object> apply(WorldmindState state) {
        // Early-exit for retry: if spec already exists, skip regeneration
        if (state.productSpec().isPresent()) {
            log.info("Product spec already exists, skipping generation");
            return Map.of("status", MissionStatus.PLANNING.name());
        }

        String request = state.request();
        Classification classification = state.classification().orElseThrow(
                () -> new IllegalStateException("Classification must be present before spec generation")
        );
        ProjectContext projectContext = state.projectContext().orElseThrow(
                () -> new IllegalStateException("ProjectContext must be present before spec generation")
        );

        String userPrompt = buildUserPrompt(request, classification, projectContext, state);
        ProductSpec spec = (mcpToolProvider != null && mcpToolProvider.hasTools())
                ? llmService.structuredCallWithTools(SYSTEM_PROMPT, userPrompt, ProductSpec.class, mcpToolProvider.getToolsFor("plan"))
                : llmService.structuredCall(SYSTEM_PROMPT, userPrompt, ProductSpec.class);
        log.info("Generated product spec: {}", spec.title());

        writeSpecFile(state, spec);

        return Map.of(
                "productSpec", spec,
                "status", MissionStatus.PLANNING.name()
        );
    }

    private String buildUserPrompt(String request, Classification classification, 
                                   ProjectContext projectContext, WorldmindState state) {
        List<String> fileTreeExcerpt = projectContext.fileTree().stream()
                .limit(50)
                .toList();

        var sb = new StringBuilder();
        sb.append(String.format("""
                Request: %s

                Classification:
                - Category: %s
                - Complexity: %d
                - Affected Components: %s
                - Planning Strategy: %s

                Project Context:
                - Language: %s
                - Framework: %s
                - File Count: %d
                - File Tree (excerpt): %s
                """,
                request,
                classification.category(),
                classification.complexity(),
                String.join(", ", classification.affectedComponents()),
                classification.planningStrategy(),
                projectContext.language(),
                projectContext.framework(),
                projectContext.fileCount(),
                String.join("\n  ", fileTreeExcerpt)
        ));

        // Include clarifying questions and answers if present
        String answers = state.clarifyingAnswers();
        if (answers != null && !answers.isBlank()) {
            sb.append("\n=== CLARIFYING QUESTIONS & ANSWERS ===\n");
            sb.append("The user answered the following questions to clarify requirements:\n\n");
            sb.append(answers);
            sb.append("\n\nUse these answers to create a more precise specification.\n");
        }

        return sb.toString();
    }

    private void writeSpecFile(WorldmindState state, ProductSpec spec) {
        String markdown = formatSpecMarkdown(spec);

        // Try local path first
        String projectPath = state.projectPath();
        if (projectPath != null && !projectPath.isBlank()) {
            Path dir = Path.of(projectPath);
            if (Files.isDirectory(dir)) {
                writeSpecLocal(dir, markdown);
                return;
            }
        }

        // Fall back to git remote (CF deployment)
        String gitRemoteUrl = resolveGitRemoteUrl(state);
        if (gitRemoteUrl != null && !gitRemoteUrl.isBlank()) {
            writeSpecViaGit(gitRemoteUrl, markdown);
            return;
        }

        log.info("No project path or git remote URL available, skipping SPEC.md write");
    }

    private String resolveGitRemoteUrl(WorldmindState state) {
        String url = state.gitRemoteUrl();
        if (url != null && !url.isBlank()) return url;
        if (cfProperties != null) {
            url = cfProperties.getGitRemoteUrl();
            if (url != null && !url.isBlank()) return url;
        }
        return null;
    }

    private String authenticateGitUrl(String gitRemoteUrl) {
        if (cfProperties == null) return gitRemoteUrl;
        String gitToken = cfProperties.getGitToken();
        if (gitToken != null && !gitToken.isBlank() && gitRemoteUrl.startsWith("https://")) {
            return gitRemoteUrl.replace("https://", "https://x-access-token:" + gitToken + "@");
        }
        return gitRemoteUrl;
    }

    private void writeSpecLocal(Path dir, String markdown) {
        try {
            Files.writeString(dir.resolve("SPEC.md"), markdown);
            log.info("Wrote SPEC.md to {}", dir);
            if (Files.isDirectory(dir.resolve(".git"))) {
                runGit(dir, "add", "SPEC.md");
                int exitCode = runGit(dir,
                        "-c", "user.name=Worldmind",
                        "-c", "user.email=worldmind@noreply",
                        "commit", "-m", "Add product specification");
                if (exitCode == 0) log.info("Committed SPEC.md to local git");
            }
        } catch (Exception e) {
            log.warn("Failed to write SPEC.md locally: {}", e.getMessage());
        }
    }

    private void writeSpecViaGit(String gitRemoteUrl, String markdown) {
        Path tempDir = null;
        try {
            tempDir = Files.createTempDirectory("worldmind-spec-");
            String authUrl = authenticateGitUrl(gitRemoteUrl);

            log.info("Cloning repo to write SPEC.md...");
            int cloneExit = runGit(tempDir, "clone", "--depth", "1", authUrl, ".");
            if (cloneExit != 0) {
                log.warn("Git clone failed (exit {}), skipping SPEC.md write", cloneExit);
                return;
            }

            Files.writeString(tempDir.resolve("SPEC.md"), markdown);
            runGit(tempDir, "add", "SPEC.md");

            int commitExit = runGit(tempDir,
                    "-c", "user.name=Worldmind",
                    "-c", "user.email=worldmind@noreply",
                    "commit", "-m", "Add product specification");
            if (commitExit != 0) {
                log.warn("Git commit exited with code {} (SPEC.md may already exist)", commitExit);
                return;
            }

            int pushExit = runGit(tempDir, "push");
            if (pushExit == 0) {
                log.info("Pushed SPEC.md to remote repo");
            } else {
                log.warn("Git push failed (exit {})", pushExit);
            }
        } catch (Exception e) {
            log.warn("Failed to write SPEC.md via git: {}", e.getMessage());
        } finally {
            if (tempDir != null) {
                try {
                    // Clean up temp directory
                    try (var walk = Files.walk(tempDir)) {
                        walk.sorted(java.util.Comparator.reverseOrder())
                                .forEach(p -> { try { Files.delete(p); } catch (Exception ignored) {} });
                    }
                } catch (Exception ignored) {}
            }
        }
    }

    private int runGit(Path workDir, String... args) throws Exception {
        var cmd = new java.util.ArrayList<String>();
        cmd.add("git");
        cmd.addAll(List.of(args));
        var process = new ProcessBuilder(cmd)
                .directory(workDir.toFile())
                .redirectErrorStream(true)
                .start();
        String output = new String(process.getInputStream().readAllBytes());
        int exitCode = process.waitFor();
        if (exitCode != 0) {
            log.debug("git {} exited {}: {}", args[0], exitCode, output.trim());
        }
        return exitCode;
    }

    private String formatSpecMarkdown(ProductSpec spec) {
        var sb = new StringBuilder();
        sb.append("# Product Requirements Document: ").append(spec.title()).append("\n\n");
        sb.append("## Overview\n").append(spec.overview()).append("\n\n");

        sb.append("## Goals\n");
        for (String goal : spec.goals()) {
            sb.append("- ").append(goal).append("\n");
        }
        sb.append("\n");

        sb.append("## Non-Goals\n");
        for (String nonGoal : spec.nonGoals()) {
            sb.append("- ").append(nonGoal).append("\n");
        }
        sb.append("\n");

        if (spec.components() != null && !spec.components().isEmpty()) {
            sb.append("## Components\n\n");
            for (var comp : spec.components()) {
                sb.append("### ").append(comp.name()).append("\n");
                sb.append("**Responsibility:** ").append(comp.responsibility()).append("\n\n");
                if (comp.affectedFiles() != null && !comp.affectedFiles().isEmpty()) {
                    sb.append("**Affected Files:**\n");
                    for (String file : comp.affectedFiles()) {
                        sb.append("- `").append(file).append("`\n");
                    }
                    sb.append("\n");
                }
                if (comp.behaviorExpectations() != null && !comp.behaviorExpectations().isEmpty()) {
                    sb.append("**Behavior Expectations:**\n");
                    for (String behavior : comp.behaviorExpectations()) {
                        sb.append("- ").append(behavior).append("\n");
                    }
                    sb.append("\n");
                }
                if (comp.integrationPoints() != null && !comp.integrationPoints().isEmpty()) {
                    sb.append("**Integration Points:**\n");
                    for (String point : comp.integrationPoints()) {
                        sb.append("- ").append(point).append("\n");
                    }
                    sb.append("\n");
                }
            }
        }

        sb.append("## Technical Requirements\n");
        for (String req : spec.technicalRequirements()) {
            sb.append("- ").append(req).append("\n");
        }
        sb.append("\n");

        if (spec.edgeCases() != null && !spec.edgeCases().isEmpty()) {
            sb.append("## Edge Cases\n");
            for (String edgeCase : spec.edgeCases()) {
                sb.append("- ").append(edgeCase).append("\n");
            }
            sb.append("\n");
        }

        if (spec.outOfScopeAssumptions() != null && !spec.outOfScopeAssumptions().isEmpty()) {
            sb.append("## Out-of-Scope Assumptions\n");
            for (String assumption : spec.outOfScopeAssumptions()) {
                sb.append("- ").append(assumption).append("\n");
            }
            sb.append("\n");
        }

        sb.append("## Acceptance Criteria\n");
        for (String criterion : spec.acceptanceCriteria()) {
            sb.append("- [ ] ").append(criterion).append("\n");
        }
        sb.append("\n");

        return sb.toString();
    }
}
