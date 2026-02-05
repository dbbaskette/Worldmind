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
        var directives = new ArrayList<Directive>();
        for (int i = 0; i < plan.directives().size(); i++) {
            var dp = plan.directives().get(i);
            String id = String.format("DIR-%03d", i + 1);
            directives.add(new Directive(
                    id, dp.centurion(), dp.description(),
                    dp.inputContext(), dp.successCriteria(),
                    dp.dependencies(),
                    DirectiveStatus.PENDING, 0, 3,
                    FailureStrategy.RETRY, List.of(), null
            ));
        }
        return directives;
    }
}
