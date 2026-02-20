package com.worldmind.core.nodes;

import com.worldmind.core.llm.LlmService;
import com.worldmind.core.model.Classification;
import com.worldmind.core.model.Task;
import com.worldmind.core.model.TaskStatus;
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
 * Generates a mission plan consisting of one or more {@link Task}s
 * by calling the LLM with the classified request and project context.
 * <p>
 * Uses {@link LlmService#structuredCall} to obtain a {@link MissionPlan}
 * (structured output) and converts the plan's {@link MissionPlan.TaskPlan}
 * entries into concrete {@link Task} records with sequential IDs.
 */
@Component
public class PlanMissionNode {

    private static final Logger log = LoggerFactory.getLogger(PlanMissionNode.class);

    private static final String SYSTEM_PROMPT = """
            You are a mission planner for Worldmind, an agentic code assistant.
            Given a classified request and project context, generate a mission plan
            that PRODUCES WORKING CODE. Your primary job is to create tasks that
            result in files being created or modified in the project.

            Available Agents (worker types):
            - CODER: Code generation and implementation — creates and modifies source files
            - RESEARCHER: Research and context gathering (analysis only, produces NO code)
            - REFACTORER: Code refactoring — restructures existing code

            DO NOT include TESTER or REVIEWER tasks — testing and code review
            are run automatically after each CODER/REFACTORER task completes.

            CRITICAL RULES:
            1. Every mission for a feature, bugfix, or refactor MUST include at least one
               CODER or REFACTORER task. These are the ONLY agents that write code.
               A plan with only RESEARCHER is INVALID — it produces no output.
            2. Start with a single RESEARCHER task to gather context, then follow with
               CODER/REFACTORER tasks that do the actual implementation work.
               Skip RESEARCHER only for trivial changes (typo fixes, config tweaks).
            3. Each task should be a single, focused task with a clear deliverable.
            4. Order tasks logically: RESEARCHER (research) -> CODER/REFACTORER (implement).
            5. Leave the dependencies list empty — the system assigns them automatically.
            
            EXECUTION STRATEGY RULES:
            6. DEFAULT TO "sequential" — it is safer and avoids merge conflicts.
            7. For NEW PROJECTS (no existing files), ALWAYS use "sequential".
               New projects have no established file boundaries, so parallel execution
               frequently causes merge conflicts (e.g., multiple tasks creating index.html).
            8. Only use "parallel" when ALL of these conditions are met:
               - Working on an EXISTING codebase with established file structure
               - Each task touches COMPLETELY DIFFERENT files with NO overlap
               - Files are in separate directories or clearly independent domains
            9. When in doubt, use "sequential" — it's slower but reliable.
            
            FILE OWNERSHIP (MANDATORY for all CODER/REFACTORER tasks):
            10. For each CODER/REFACTORER task, you MUST specify "targetFiles" — the files 
                it will create or modify. This is NOT optional.
                Example: ["src/game.js", "src/styles.css", "public/index.html"]
            11. NEVER assign the same file to multiple tasks. Each file should appear
                in exactly ONE task's targetFiles. This is the #1 cause of merge conflicts.
                BAD:  Task 1 targets ["index.html"], Task 2 targets ["index.html", "styles.css"]
                GOOD: Task 1 targets ["index.html"], Task 2 targets ["styles.css"]
            12. Deployment/config files (manifest.yml, Staticfile, Dockerfile, package.json)
                should be in ONE task's targetFiles — typically the LAST task.
            13. If one task needs a file that another creates, list it as a dependency,
                NOT in its own targetFiles. The system will serialize them automatically.
            
            VIOLATION EXAMPLES (DO NOT DO THIS):
            ❌ Two CODER tasks both list "index.html" in targetFiles → MERGE CONFLICT
            ❌ "parallel" execution for a new game project with shared HTML/CSS/JS → MERGE CONFLICT
            ❌ CODER task with empty targetFiles → Cannot detect conflicts
            
            CORRECT EXAMPLES:
            ✅ Sequential plan for new project: each task creates distinct files
            ✅ Parallel plan where TASK-001 targets backend/, TASK-002 targets frontend/
            ✅ Every CODER/REFACTORER has explicit targetFiles with no overlap

            Example plan for "Add a /health endpoint" (existing project):
            executionStrategy: "sequential"
            - RESEARCHER: Analyze existing controller patterns and endpoint conventions
            - CODER: Create HealthController with GET /health returning status JSON
              targetFiles: ["src/main/java/com/example/HealthController.java"]

            Example plan for "Build a snake game with CF deployment" (NEW project):
            executionStrategy: "sequential"   ← ALWAYS sequential for new projects
            - CODER: Create HTML/CSS/JS files for the snake game
              targetFiles: ["public/index.html", "public/styles.css", "public/game.js"]
            - CODER: Create Cloud Foundry manifest.yml and Staticfile for deployment
              targetFiles: ["manifest.yml", "Staticfile"]
            
            Example plan for independent subsystems in EXISTING codebase (rare):
            executionStrategy: "parallel"   ← Only when files are truly independent
            - CODER: Add user preference storage to backend
              targetFiles: ["backend/services/PreferencesService.java", "backend/api/PreferencesController.java"]
            - CODER: Add preference UI components to frontend
              targetFiles: ["frontend/components/PreferencesPanel.tsx", "frontend/styles/preferences.css"]

            Respond with valid JSON matching the schema provided.
            """;

    private final LlmService llmService;

    public PlanMissionNode(LlmService llmService) {
        this.llmService = llmService;
    }

    public Map<String, Object> apply(WorldmindState state) {
        // Early-exit for retry: if tasks already exist, skip re-planning
        if (!state.tasks().isEmpty()) {
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
        // needs to structure tasks from the spec, not call external tools.
        MissionPlan plan = llmService.structuredCall(SYSTEM_PROMPT, userPrompt, MissionPlan.class);

        List<Task> tasks = convertToTasks(plan);
        tasks = ensureCoderTask(tasks, request);
        tasks = assignTypeDependencies(tasks);
        
        // If user requested CF deployment artifacts, append a final task
        if (state.createCfDeployment()) {
            tasks = appendCfDeploymentTask(tasks);
        }

        log.info("Mission plan: {} tasks — {}", tasks.size(),
                tasks.stream().map(d -> d.id() + "[" + d.agent() + "](deps:" + d.dependencies() + ")").toList());

        // Use user's execution strategy override if specified, otherwise use planner's suggestion
        String userStrategy = state.<String>value("userExecutionStrategy").orElse(null);
        log.info("Planning strategy: userExecutionStrategy='{}', LLM suggested='{}'", 
                userStrategy, plan.executionStrategy());
        
        String effectiveStrategy = (userStrategy != null && !userStrategy.isBlank())
                ? userStrategy
                : plan.executionStrategy().toUpperCase();
        
        log.info("Effective execution strategy: {} (user override: {})", 
                effectiveStrategy, userStrategy != null && !userStrategy.isBlank());

        return Map.of(
                "tasks", tasks,
                "executionStrategy", effectiveStrategy,
                "status", MissionStatus.AWAITING_APPROVAL.name()
        );
    }

    /**
     * Guardrail: if the LLM generated a plan with no CODER or REFACTORER tasks,
     * inject a default CODER task. This prevents missions that produce no code.
     */
    private List<Task> ensureCoderTask(List<Task> tasks, String request) {
        boolean hasImplementation = tasks.stream()
                .anyMatch(d -> "CODER".equalsIgnoreCase(d.agent())
                        || "REFACTORER".equalsIgnoreCase(d.agent()));
        if (hasImplementation) return tasks;

        log.warn("LLM plan contained no CODER/REFACTORER tasks — injecting default CODER task");

        String coderId = String.format("TASK-%03d", tasks.size() + 1);
        var coderTask = new Task(
                coderId, "CODER",
                "Implement the requested changes: " + request,
                "Implement all goals from the product specification. Create or modify files as needed.",
                "All specified changes are implemented and the code compiles",
                List.of(),
                TaskStatus.PENDING, 0, 3,
                FailureStrategy.RETRY, List.of(), List.of(), null
        );

        // Insert CODER before any trailing REVIEWER task
        var result = new ArrayList<Task>();
        boolean coderInserted = false;
        for (var d : tasks) {
            if ("REVIEWER".equalsIgnoreCase(d.agent()) && !coderInserted) {
                result.add(coderTask);
                coderInserted = true;
            }
            result.add(d);
        }
        if (!coderInserted) {
            result.add(coderTask);
        }
        return result;
    }

    /**
     * Appends a final CODER task to create Cloud Foundry deployment artifacts.
     * This task depends on all other CODER/REFACTORER tasks so it runs last
     * and can inspect the actual code structure.
     */
    private List<Task> appendCfDeploymentTask(List<Task> tasks) {
        // Find all CODER/REFACTORER task IDs as dependencies
        List<String> coderRefactorerIds = tasks.stream()
                .filter(d -> "CODER".equalsIgnoreCase(d.agent()) || "REFACTORER".equalsIgnoreCase(d.agent()))
                .map(Task::id)
                .toList();

        String cfId = String.format("TASK-%03d", tasks.size() + 1);
        var cfTask = new Task(
                cfId, "CODER",
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
                coderRefactorerIds,
                TaskStatus.PENDING, 0, 3,
                FailureStrategy.SKIP, List.of("manifest.yml", "Staticfile"), List.of(), null
        );

        var result = new ArrayList<>(tasks);
        result.add(cfTask);
        log.info("Appended CF deployment task {} with dependencies on {}", cfId, coderRefactorerIds);
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

                === TASK GENERATION INSTRUCTIONS ===
                Use the IMPLEMENTATION PLAN steps to create tasks. Each step typically
                maps to one CODER task. Include the step's detailed tasks in the
                task's inputContext so the agent knows exactly what to implement.
                
                Use the FILE SPECIFICATIONS to populate targetFiles for each task.
                Include "mustContain" items in the task's successCriteria.
                
                The PRD is the source of truth — tasks should implement it completely.
                """);
        });

        return sb.toString();
    }

    private List<Task> convertToTasks(MissionPlan plan) {
        var tasks = new ArrayList<Task>();
        for (int i = 0; i < plan.tasks().size(); i++) {
            var dp = plan.tasks().get(i);
            String id = String.format("TASK-%03d", i + 1);
            // Use targetFiles from planner if provided, otherwise empty list
            List<String> targetFiles = dp.targetFiles() != null ? dp.targetFiles() : List.of();
            tasks.add(new Task(
                    id, dp.agent(), dp.description(),
                    dp.inputContext(), dp.successCriteria(),
                    List.of(),
                    TaskStatus.PENDING, 0, 3,
                    FailureStrategy.RETRY, targetFiles, List.of(), null
            ));
        }
        return tasks;
    }

    /**
     * Builds deterministic dependencies for all tasks based on agent type ordering.
     * LLM-generated dependencies are unreliable (arbitrary string formats), so we
     * enforce: CODER/REFACTORER depend on all preceding RESEARCHER;
     * TESTER/REVIEWER depend on all preceding CODER/REFACTORER.
     */
    private static List<Task> assignTypeDependencies(List<Task> tasks) {
        var result = new ArrayList<Task>();
        for (var d : tasks) {
            String type = d.agent() != null ? d.agent().toUpperCase() : "";
            var prerequisiteTypes = switch (type) {
                case "CODER", "REFACTORER" -> java.util.Set.of("RESEARCHER");
                case "TESTER" -> java.util.Set.of("CODER", "REFACTORER");
                case "REVIEWER" -> java.util.Set.of("CODER", "REFACTORER");
                default -> java.util.Set.<String>of();
            };

            if (prerequisiteTypes.isEmpty()) {
                result.add(d);
                continue;
            }

            var deps = new ArrayList<String>();
            for (var prev : tasks) {
                if (prev.id().equals(d.id())) break;
                if (prev.agent() != null && prerequisiteTypes.contains(prev.agent().toUpperCase())) {
                    deps.add(prev.id());
                }
            }
            result.add(new Task(d.id(), d.agent(), d.description(),
                    d.inputContext(), d.successCriteria(), deps,
                    d.status(), d.iteration(), d.maxIterations(),
                    d.onFailure(), d.targetFiles(), d.filesAffected(), d.elapsedMs()));
        }
        return result;
    }
}
