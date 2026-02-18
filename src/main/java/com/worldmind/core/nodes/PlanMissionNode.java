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
            6. Choose execution strategy based on task dependencies:
               - "sequential" for tasks where each step depends on prior work (safer, no conflicts)
               - "parallel" for independent subtasks that don't touch the same files (faster)
            
            FILE OWNERSHIP (CRITICAL for parallel execution):
            7. For each FORGE/PRISM directive, specify "targetFiles" — the files it will create or modify.
               Example: ["src/game.js", "src/styles.css", "public/index.html"]
            8. Directives with overlapping targetFiles CANNOT run in parallel — they will conflict.
               The system uses targetFiles to detect conflicts and serialize conflicting directives.
            9. Deployment config files (manifest.yml, Staticfile, Dockerfile, package.json) should
               typically be in ONE directive's targetFiles — usually the LAST directive.
            10. If you assign the same file to multiple directives, add to earlier directives'
                inputContext: "DO NOT create [filename] - that is handled by a later directive."

            Example plan for "Add a /health endpoint":
            - PULSE: Analyze existing controller patterns and endpoint conventions
            - FORGE: Create HealthController with GET /health returning status JSON
              targetFiles: ["src/main/java/com/example/HealthController.java"]

            Example plan for "Build a snake game with CF deployment":
            - FORGE: Create HTML/CSS/JS files for the snake game
              targetFiles: ["public/index.html", "public/styles.css", "public/game.js"]
              inputContext: "DO NOT create manifest.yml or Staticfile"
            - FORGE: Create Cloud Foundry manifest.yml and Staticfile for deployment
              targetFiles: ["manifest.yml", "Staticfile"]
              inputContext: "Use 'default-route: true' instead of hardcoded routes."
            
            The second directive owns the deployment config, so the first is told not to create it.

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
        
        // If user requested CF deployment artifacts, append a final directive
        if (state.createCfDeployment()) {
            directives = appendCfDeploymentDirective(directives);
        }

        log.info("Mission plan: {} directives — {}", directives.size(),
                directives.stream().map(d -> d.id() + "[" + d.centurion() + "](deps:" + d.dependencies() + ")").toList());

        // Use user's execution strategy override if specified, otherwise use planner's suggestion
        String userStrategy = state.<String>value("userExecutionStrategy").orElse(null);
        log.info("Planning strategy: userExecutionStrategy='{}', LLM suggested='{}'", 
                userStrategy, plan.executionStrategy());
        
        String effectiveStrategy = (userStrategy != null && !userStrategy.isBlank())
                ? userStrategy
                : plan.executionStrategy().toUpperCase();
        
        log.info("Effective execution strategy: {} (user override: {})", 
                effectiveStrategy, userStrategy != null && !userStrategy.isBlank())

        return Map.of(
                "directives", directives,
                "executionStrategy", effectiveStrategy,
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
                FailureStrategy.RETRY, List.of(), List.of(), null
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

    /**
     * Appends a final FORGE directive to create Cloud Foundry deployment artifacts.
     * This directive depends on all other FORGE/PRISM directives so it runs last
     * and can inspect the actual code structure.
     */
    private List<Directive> appendCfDeploymentDirective(List<Directive> directives) {
        // Find all FORGE/PRISM directive IDs as dependencies
        List<String> forgePrismIds = directives.stream()
                .filter(d -> "FORGE".equalsIgnoreCase(d.centurion()) || "PRISM".equalsIgnoreCase(d.centurion()))
                .map(Directive::id)
                .toList();

        String cfId = String.format("DIR-%03d", directives.size() + 1);
        var cfDirective = new Directive(
                cfId, "FORGE",
                "Create Cloud Foundry deployment artifacts based on the completed application",
                """
                Examine the project structure and create appropriate Cloud Foundry deployment files.
                
                STEPS:
                1. List all files to understand what was built (HTML/CSS/JS, Java, Node, etc.)
                2. Determine the appropriate buildpack based on the file types
                3. Create manifest.yml with:
                   - A descriptive app name matching the application's purpose
                   - 'default-route: true' (NEVER hardcode routes)
                   - Appropriate memory and disk quotas
                   - The correct buildpack
                4. For staticfile apps: Create a Staticfile with 'root: public' if files are in public/
                5. Verify the manifest references valid paths that exist
                
                DO NOT create deployment artifacts if they already exist in the project.
                """,
                "Valid manifest.yml created that can be used with 'cf push'",
                forgePrismIds,
                DirectiveStatus.PENDING, 0, 3,
                FailureStrategy.SKIP, List.of("manifest.yml", "Staticfile"), List.of(), null
        );

        var result = new ArrayList<>(directives);
        result.add(cfDirective);
        log.info("Appended CF deployment directive {} with dependencies on {}", cfId, forgePrismIds);
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

                === PRODUCT REQUIREMENTS DOCUMENT ===
                Title: %s
                Overview: %s
                
                GOALS:
                %s
                
                NON-GOALS:
                %s
                
                TECHNICAL REQUIREMENTS:
                %s
                
                ACCEPTANCE CRITERIA:
                %s
                """,
                spec.title(),
                spec.overview(),
                spec.goals().stream().map(g -> "- " + g).reduce((a, b) -> a + "\n" + b).orElse("(none)"),
                spec.nonGoals().stream().map(g -> "- " + g).reduce((a, b) -> a + "\n" + b).orElse("(none)"),
                spec.technicalRequirements().stream().map(r -> "- " + r).reduce((a, b) -> a + "\n" + b).orElse("(none)"),
                spec.acceptanceCriteria().stream().map(c -> "- " + c).reduce((a, b) -> a + "\n" + b).orElse("(none)")
            ));

            if (spec.components() != null && !spec.components().isEmpty()) {
                sb.append("\nCOMPONENTS:\n");
                for (var comp : spec.components()) {
                    sb.append("  [").append(comp.name()).append("]\n");
                    sb.append("    Responsibility: ").append(comp.responsibility()).append("\n");
                    if (comp.affectedFiles() != null && !comp.affectedFiles().isEmpty()) {
                        sb.append("    Files: ").append(String.join(", ", comp.affectedFiles())).append("\n");
                    }
                    if (comp.behaviorExpectations() != null && !comp.behaviorExpectations().isEmpty()) {
                        sb.append("    Behavior:\n");
                        for (var b : comp.behaviorExpectations()) {
                            sb.append("      - ").append(b).append("\n");
                        }
                    }
                }
            }

            if (spec.edgeCases() != null && !spec.edgeCases().isEmpty()) {
                sb.append("\nEDGE CASES:\n");
                for (var ec : spec.edgeCases()) sb.append("  - ").append(ec).append("\n");
            }

            sb.append("""

                === DIRECTIVE GENERATION INSTRUCTIONS ===
                Use the IMPLEMENTATION PLAN steps to create directives. Each step typically
                maps to one FORGE directive. Include the step's detailed tasks in the
                directive's inputContext so the centurion knows exactly what to implement.
                
                Use the FILE SPECIFICATIONS to populate targetFiles for each directive.
                Include "mustContain" items in the directive's successCriteria.
                
                The PRD is the source of truth — directives should implement it completely.
                """);
        });

        return sb.toString();
    }

    private List<Directive> convertToDirectives(MissionPlan plan) {
        var directives = new ArrayList<Directive>();
        for (int i = 0; i < plan.directives().size(); i++) {
            var dp = plan.directives().get(i);
            String id = String.format("DIR-%03d", i + 1);
            // Use targetFiles from planner if provided, otherwise empty list
            List<String> targetFiles = dp.targetFiles() != null ? dp.targetFiles() : List.of();
            directives.add(new Directive(
                    id, dp.centurion(), dp.description(),
                    dp.inputContext(), dp.successCriteria(),
                    List.of(),
                    DirectiveStatus.PENDING, 0, 3,
                    FailureStrategy.RETRY, targetFiles, List.of(), null
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
                    d.onFailure(), d.targetFiles(), d.filesAffected(), d.elapsedMs()));
        }
        return result;
    }
}
