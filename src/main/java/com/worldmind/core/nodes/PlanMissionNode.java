package com.worldmind.core.nodes;

import com.worldmind.core.llm.LlmService;
import com.worldmind.core.model.Classification;
import com.worldmind.core.model.Directive;
import com.worldmind.core.model.DirectiveStatus;
import com.worldmind.core.model.FailureStrategy;
import com.worldmind.core.model.MissionPlan;
import com.worldmind.core.model.MissionStatus;
import com.worldmind.core.model.ProductSpec;
import com.worldmind.core.model.ProjectContext;
import com.worldmind.core.state.WorldmindState;
import com.worldmind.mcp.McpToolProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Generates a mission plan consisting of one or more {@link Directive}s
 * by calling the LLM with the classified request and project context.
 * <p>
 * Uses {@link LlmService#structuredCall} to obtain a {@link MissionPlan}
 * (structured output) and converts the plan's {@link MissionPlan.DirectivePlan}
 * entries into concrete {@link Directive} records with sequential IDs.
 */
@Component
public class PlanMissionNode {

    private static final String SYSTEM_PROMPT = """
            You are a mission planner for Worldmind, an agentic code assistant.
            Given a classified request and project context, generate a mission plan
            that PRODUCES WORKING CODE. Your primary job is to create directives that
            result in files being created or modified in the project.

            Available Centurions (worker types):
            - FORGE: Code generation and implementation — creates and modifies source files
            - GAUNTLET: Test writing and execution — writes tests and runs them
            - VIGIL: Code review and quality assessment
            - PULSE: Research and context gathering (analysis only, produces NO code)
            - PRISM: Code refactoring — restructures existing code

            CRITICAL RULES:
            1. Every mission for a feature, bugfix, or refactor MUST include at least one
               FORGE or PRISM directive. These are the ONLY centurions that write code.
               A plan with only PULSE and VIGIL is INVALID — it produces no output.
            2. Start with a single PULSE directive to gather context, then follow with
               FORGE/PRISM directives that do the actual implementation work.
               Skip PULSE only for trivial changes (typo fixes, config tweaks).
            3. Each directive should be a single, focused task with a clear deliverable.
            4. Order directives logically: PULSE (research) -> FORGE/PRISM (implement)
               -> GAUNTLET (test) -> VIGIL (review).
            5. Use dependencies to express ordering — FORGE/PRISM depend on PULSE,
               GAUNTLET depends on FORGE/PRISM, VIGIL depends on GAUNTLET.
            6. Choose execution strategy based on complexity:
               - "sequential" for simple, dependent tasks
               - "parallel" for independent subtasks
               - "adaptive" for complex tasks needing dynamic planning
            7. Every mission should end with a VIGIL review directive.

            Example plan for "Add a /health endpoint":
            - PULSE: Analyze existing controller patterns and endpoint conventions
            - FORGE: Create HealthController with GET /health returning status JSON
            - GAUNTLET: Write integration test for /health endpoint
            - VIGIL: Review the new endpoint code and test coverage

            Respond with valid JSON matching the schema provided.
            """;

    private final LlmService llmService;
    private final McpToolProvider mcpToolProvider;

    public PlanMissionNode(LlmService llmService,
                           @Autowired(required = false) McpToolProvider mcpToolProvider) {
        this.llmService = llmService;
        this.mcpToolProvider = mcpToolProvider;
    }

    public Map<String, Object> apply(WorldmindState state) {
        // Early-exit for retry: if directives already exist, skip re-planning
        if (!state.directives().isEmpty()) {
            return Map.of("status", MissionStatus.AWAITING_APPROVAL.name());
        }

        String request = state.request();
        Classification classification = state.classification().orElseThrow(
                () -> new IllegalStateException("Classification must be present before planning")
        );
        ProjectContext projectContext = state.projectContext().orElseThrow(
                () -> new IllegalStateException("ProjectContext must be present before planning")
        );
        Optional<ProductSpec> productSpec = state.productSpec();

        String userPrompt = buildUserPrompt(request, classification, projectContext, productSpec);
        MissionPlan plan = (mcpToolProvider != null && mcpToolProvider.hasTools())
                ? llmService.structuredCallWithTools(SYSTEM_PROMPT, userPrompt, MissionPlan.class, mcpToolProvider.getToolsFor("plan"))
                : llmService.structuredCall(SYSTEM_PROMPT, userPrompt, MissionPlan.class);

        List<Directive> directives = convertToDirectives(plan);

        return Map.of(
                "directives", directives,
                "executionStrategy", plan.executionStrategy().toUpperCase(),
                "status", MissionStatus.AWAITING_APPROVAL.name()
        );
    }

    private String buildUserPrompt(String request, Classification classification,
                                   ProjectContext projectContext, Optional<ProductSpec> productSpec) {
        var sb = new StringBuilder();
        sb.append(String.format("""
                Request: %s

                Classification:
                - Category: %s
                - Complexity: %d
                - Affected Components: %s
                - Planning Strategy: %s
                - Runtime Tag: %s

                Project Context:
                - Language: %s
                - Framework: %s
                - File Count: %d
                """,
                request,
                classification.category(),
                classification.complexity(),
                String.join(", ", classification.affectedComponents()),
                classification.planningStrategy(),
                classification.runtimeTag() != null ? classification.runtimeTag() : "base",
                projectContext.language(),
                projectContext.framework(),
                projectContext.fileCount()
        ));

        productSpec.ifPresent(spec -> {
            sb.append(String.format("""

                Product Specification:
                - Title: %s
                - Overview: %s
                - Goals: %s
                - Non-Goals: %s
                - Technical Requirements: %s
                - Acceptance Criteria: %s
                """,
                spec.title(),
                spec.overview(),
                String.join("; ", spec.goals()),
                String.join("; ", spec.nonGoals()),
                String.join("; ", spec.technicalRequirements()),
                String.join("; ", spec.acceptanceCriteria())
            ));

            if (spec.components() != null && !spec.components().isEmpty()) {
                sb.append("\nComponents:\n");
                for (var comp : spec.components()) {
                    sb.append("  - ").append(comp.name()).append(": ").append(comp.responsibility()).append("\n");
                    if (comp.affectedFiles() != null && !comp.affectedFiles().isEmpty()) {
                        sb.append("    Affected files: ").append(String.join(", ", comp.affectedFiles())).append("\n");
                    }
                    if (comp.behaviorExpectations() != null && !comp.behaviorExpectations().isEmpty()) {
                        sb.append("    Behavior: ").append(String.join("; ", comp.behaviorExpectations())).append("\n");
                    }
                    if (comp.integrationPoints() != null && !comp.integrationPoints().isEmpty()) {
                        sb.append("    Integration points: ").append(String.join(", ", comp.integrationPoints())).append("\n");
                    }
                }
            }

            if (spec.edgeCases() != null && !spec.edgeCases().isEmpty()) {
                sb.append("\nEdge Cases: ").append(String.join("; ", spec.edgeCases())).append("\n");
            }

            if (spec.outOfScopeAssumptions() != null && !spec.outOfScopeAssumptions().isEmpty()) {
                sb.append("\nOut-of-Scope Assumptions: ").append(String.join("; ", spec.outOfScopeAssumptions())).append("\n");
            }

            sb.append("""

                Use this specification to generate focused, well-scoped directives
                that address the goals and meet the acceptance criteria.
                Use the component breakdown to create targeted directives with
                specific file paths and behavioral expectations.
                """);
        });

        return sb.toString();
    }

    private List<Directive> convertToDirectives(MissionPlan plan) {
        int total = plan.directives().size();
        var directives = new ArrayList<Directive>();
        for (int i = 0; i < total; i++) {
            var dp = plan.directives().get(i);
            String id = String.format("DIR-%03d", i + 1);
            // Convert dependencies: LLMs may return numeric indices (0-based or 1-based),
            // compound strings ("0-forge-generate-html"), or already-formatted IDs.
            // Normalize all forms to DIR-xxx and remove self-references and forward-references.
            List<String> deps = dp.dependencies() != null
                    ? dp.dependencies().stream()
                        .map(dep -> normalizeDepId(dep, total))
                        .filter(dep -> dep != null && !dep.equals(id))
                        .toList()
                    : List.of();
            directives.add(new Directive(
                    id, dp.centurion(), dp.description(),
                    dp.inputContext(), dp.successCriteria(),
                    deps,
                    DirectiveStatus.PENDING, 0, 3,
                    FailureStrategy.RETRY, List.of(), null
            ));
        }
        return directives;
    }

    /**
     * Normalizes a dependency reference to a directive ID.
     * Handles 0-based indices (0 -> DIR-001), 1-based indices (1 -> DIR-001),
     * compound strings ("0-forge-generate-html" -> DIR-001),
     * and already-formatted IDs (DIR-001 -> DIR-001).
     *
     * When the index is ambiguous (could be 0-based or 1-based), we use
     * the total directive count to detect 1-based: if the extracted number
     * equals totalDirectives, it must be 1-based (0-based max is total-1).
     */
    private static String normalizeDepId(String dep, int totalDirectives) {
        if (dep == null || dep.isBlank()) return null;
        String trimmed = dep.trim();
        // Already a directive ID
        if (trimmed.toUpperCase().startsWith("DIR-")) return trimmed.toUpperCase();
        // Extract leading numeric index from strings like "0", "1", "0-forge-generate-html", etc.
        String numPart = trimmed.split("[^0-9]", 2)[0];
        if (!numPart.isEmpty()) {
            try {
                int num = Integer.parseInt(numPart);
                // Determine if 0-based or 1-based:
                // If num == 0, it's definitely 0-based
                // If num >= totalDirectives, it's 1-based (0-based can't exceed total-1)
                // Otherwise assume 0-based (more common in LLM output)
                if (num == 0 || num < totalDirectives) {
                    return String.format("DIR-%03d", num + 1); // 0-based
                } else {
                    return String.format("DIR-%03d", num);     // 1-based
                }
            } catch (NumberFormatException ignored) { }
        }
        // Not a number — return as-is (might be a centurion type name)
        return trimmed;
    }
}
