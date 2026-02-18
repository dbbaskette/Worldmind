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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

    private static final Logger log = LoggerFactory.getLogger(PlanMissionNode.class);

    private static final String SYSTEM_PROMPT = """
            You are a mission planner for Worldmind, an agentic code assistant.
            Given a classified request and project context, generate a mission plan
            that PRODUCES WORKING CODE. Your primary job is to create directives that
            result in files being created or modified in the project.

            Available Centurions (worker types):
            - FORGE: Code generation and implementation — creates and modifies source files
            - PULSE: Research and context gathering (analysis only, produces NO code)
            - PRISM: Code refactoring — restructures existing code

            DO NOT include GAUNTLET or VIGIL directives — testing and code review
            are run automatically after each FORGE/PRISM directive completes.

            CRITICAL RULES:
            1. Every mission for a feature, bugfix, or refactor MUST include at least one
               FORGE or PRISM directive. These are the ONLY centurions that write code.
               A plan with only PULSE is INVALID — it produces no output.
            2. Start with a single PULSE directive to gather context, then follow with
               FORGE/PRISM directives that do the actual implementation work.
               Skip PULSE only for trivial changes (typo fixes, config tweaks).
            3. Each directive should be a single, focused task with a clear deliverable.
            4. Order directives logically: PULSE (research) -> FORGE/PRISM (implement).
            5. Leave the dependencies list empty — the system assigns them automatically.
            6. Choose execution strategy based on complexity:
               - "sequential" for simple, dependent tasks
               - "parallel" for independent subtasks
               - "adaptive" for complex tasks needing dynamic planning
            
            FILE OWNERSHIP RULES (CRITICAL):
            7. If a later directive is responsible for creating a specific file (e.g., manifest.yml,
               Dockerfile, README), add to EARLIER directives' inputContext:
               "DO NOT create [filename] - that is handled by a later directive."
            8. Each directive's inputContext should list files it should NOT create if another
               directive owns them. This prevents duplicate/conflicting files.
            9. Deployment config files (manifest.yml, Staticfile, Dockerfile) should typically
               be created in the LAST directive so they can reference all created files.

            Example plan for "Add a /health endpoint":
            - PULSE: Analyze existing controller patterns and endpoint conventions
            - FORGE: Create HealthController with GET /health returning status JSON

            Example plan for "Build a static web app with CF deployment":
            - FORGE: Create HTML/CSS/JS files (inputContext: "DO NOT create manifest.yml or Staticfile")
            - FORGE: Create Cloud Foundry manifest.yml and Staticfile for deployment
            
            The second directive owns the deployment config, so the first directive is told not to create it.

            Respond with valid JSON matching the schema provided.
            """;

    private final LlmService llmService;

    public PlanMissionNode(LlmService llmService) {
        this.llmService = llmService;
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
        // Plan generation uses structuredCall without MCP tools — the planner only
        // needs to structure directives from the spec, not call external tools.
        MissionPlan plan = llmService.structuredCall(SYSTEM_PROMPT, userPrompt, MissionPlan.class);

        List<Directive> directives = convertToDirectives(plan);
        directives = ensureForgeDirective(directives, request);
        directives = assignTypeDependencies(directives);

        log.info("Mission plan: {} directives — {}", directives.size(),
                directives.stream().map(d -> d.id() + "[" + d.centurion() + "](deps:" + d.dependencies() + ")").toList());

        return Map.of(
                "directives", directives,
                "executionStrategy", plan.executionStrategy().toUpperCase(),
                "status", MissionStatus.AWAITING_APPROVAL.name()
        );
    }

    /**
     * Guardrail: if the LLM generated a plan with no FORGE or PRISM directives,
     * inject a default FORGE directive. This prevents missions that produce no code.
     */
    private List<Directive> ensureForgeDirective(List<Directive> directives, String request) {
        boolean hasImplementation = directives.stream()
                .anyMatch(d -> "FORGE".equalsIgnoreCase(d.centurion())
                        || "PRISM".equalsIgnoreCase(d.centurion()));
        if (hasImplementation) return directives;

        log.warn("LLM plan contained no FORGE/PRISM directives — injecting default FORGE directive");

        String forgeId = String.format("DIR-%03d", directives.size() + 1);
        var forgeDirective = new Directive(
                forgeId, "FORGE",
                "Implement the requested changes: " + request,
                "Implement all goals from the product specification. Create or modify files as needed.",
                "All specified changes are implemented and the code compiles",
                List.of(),
                DirectiveStatus.PENDING, 0, 3,
                FailureStrategy.RETRY, List.of(), null
        );

        // Insert FORGE before any trailing VIGIL directive
        var result = new ArrayList<Directive>();
        boolean forgeInserted = false;
        for (var d : directives) {
            if ("VIGIL".equalsIgnoreCase(d.centurion()) && !forgeInserted) {
                result.add(forgeDirective);
                forgeInserted = true;
            }
            result.add(d);
        }
        if (!forgeInserted) {
            result.add(forgeDirective);
        }
        return result;
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
        var directives = new ArrayList<Directive>();
        for (int i = 0; i < plan.directives().size(); i++) {
            var dp = plan.directives().get(i);
            String id = String.format("DIR-%03d", i + 1);
            directives.add(new Directive(
                    id, dp.centurion(), dp.description(),
                    dp.inputContext(), dp.successCriteria(),
                    List.of(),
                    DirectiveStatus.PENDING, 0, 3,
                    FailureStrategy.RETRY, List.of(), null
            ));
        }
        return directives;
    }

    /**
     * Builds deterministic dependencies for all directives based on centurion type ordering.
     * LLM-generated dependencies are unreliable (arbitrary string formats), so we
     * enforce: FORGE/PRISM depend on all preceding PULSE;
     * GAUNTLET/VIGIL depend on all preceding FORGE/PRISM.
     */
    private static List<Directive> assignTypeDependencies(List<Directive> directives) {
        var result = new ArrayList<Directive>();
        for (var d : directives) {
            String type = d.centurion() != null ? d.centurion().toUpperCase() : "";
            var prerequisiteTypes = switch (type) {
                case "FORGE", "PRISM" -> java.util.Set.of("PULSE");
                case "GAUNTLET" -> java.util.Set.of("FORGE", "PRISM");
                case "VIGIL" -> java.util.Set.of("FORGE", "PRISM");
                default -> java.util.Set.<String>of();
            };

            if (prerequisiteTypes.isEmpty()) {
                result.add(d);
                continue;
            }

            var deps = new ArrayList<String>();
            for (var prev : directives) {
                if (prev.id().equals(d.id())) break;
                if (prev.centurion() != null && prerequisiteTypes.contains(prev.centurion().toUpperCase())) {
                    deps.add(prev.id());
                }
            }
            result.add(new Directive(d.id(), d.centurion(), d.description(),
                    d.inputContext(), d.successCriteria(), deps,
                    d.status(), d.iteration(), d.maxIterations(),
                    d.onFailure(), d.filesAffected(), d.elapsedMs()));
        }
        return result;
    }
}
