package com.worldmind.core.nodes;

import com.worldmind.core.llm.LlmService;
import com.worldmind.core.model.Classification;
import com.worldmind.core.model.Directive;
import com.worldmind.core.model.DirectiveStatus;
import com.worldmind.core.model.FailureStrategy;
import com.worldmind.core.model.MissionPlan;
import com.worldmind.core.model.MissionStatus;
import com.worldmind.core.model.ProjectContext;
import com.worldmind.core.state.WorldmindState;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

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
            Given a classified request and project context, generate a mission plan.

            Available Centurions (worker types):
            - FORGE: Code generation and implementation
            - GAUNTLET: Test writing and execution
            - VIGIL: Code review and quality assessment
            - PULSE: Research and context gathering
            - PRISM: Code refactoring

            Rules:
            1. Each directive should be a single, focused task
            2. Order directives logically (implementation before testing before review)
            3. Use dependencies to express ordering constraints
            4. Choose execution strategy based on complexity:
               - "sequential" for simple, dependent tasks
               - "parallel" for independent subtasks
               - "adaptive" for complex tasks needing dynamic planning
            5. Every mission should end with a VIGIL review directive

            Respond with valid JSON matching the schema provided.
            """;

    private final LlmService llmService;

    public PlanMissionNode(LlmService llmService) {
        this.llmService = llmService;
    }

    public Map<String, Object> apply(WorldmindState state) {
        String request = state.request();
        Classification classification = state.classification().orElseThrow(
                () -> new IllegalStateException("Classification must be present before planning")
        );
        ProjectContext projectContext = state.projectContext().orElseThrow(
                () -> new IllegalStateException("ProjectContext must be present before planning")
        );

        String userPrompt = buildUserPrompt(request, classification, projectContext);
        MissionPlan plan = llmService.structuredCall(SYSTEM_PROMPT, userPrompt, MissionPlan.class);

        List<Directive> directives = convertToDirectives(plan);

        return Map.of(
                "directives", directives,
                "executionStrategy", plan.executionStrategy().toUpperCase(),
                "status", MissionStatus.AWAITING_APPROVAL.name()
        );
    }

    private String buildUserPrompt(String request, Classification classification, ProjectContext projectContext) {
        return String.format("""
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
                """,
                request,
                classification.category(),
                classification.complexity(),
                String.join(", ", classification.affectedComponents()),
                classification.planningStrategy(),
                projectContext.language(),
                projectContext.framework(),
                projectContext.fileCount()
        );
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
