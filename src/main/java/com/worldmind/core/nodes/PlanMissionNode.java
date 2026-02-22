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
import com.worldmind.sandbox.DeployerProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.ObjectMapper;

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
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private static final String SYSTEM_PROMPT = """
            You are a mission planner for Worldmind, an agentic code assistant.
            Given a classified request and project context, generate a mission plan
            that PRODUCES WORKING CODE. Your primary job is to create tasks that
            result in files being created or modified in the project.

            Available Agents (worker types):
            - CODER: Code generation and implementation — creates and modifies source files
            - RESEARCHER: Research and context gathering (analysis only, produces NO code)
            - REFACTORER: Code refactoring — restructures existing code
            - DEPLOYER: Builds and deploys the application to Cloud Foundry (added automatically when CF deployment is enabled — do NOT plan DEPLOYER tasks yourself)

            DO NOT include TESTER or REVIEWER tasks — testing and code review
            are run automatically after each CODER/REFACTORER task completes.

            CRITICAL RULES:
            1. Every mission for a feature, bugfix, or refactor MUST include at least one
               CODER or REFACTORER task. These are the ONLY agents that write code.
               A plan with only RESEARCHER is INVALID — it produces no output.
            2. Start with a single RESEARCHER task to gather context, then follow with
               CODER/REFACTORER tasks that do the actual implementation work.
               Skip RESEARCHER only for trivial changes (typo fixes, config tweaks).
            3. SPLIT INTO SMALL, FOCUSED TASKS. Each CODER task should do ONE thing:
               - ONE model class, ONE repository, ONE service, ONE controller — not all at once.
               - A task touching more than 3-4 files is TOO BIG. Split it.
               - Smaller tasks succeed more often and are easier to retry on failure.
               - Example: "Create TodoItem model" is better than "Create model, repository, and service".
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

            Example plan for "Build a To-Do app with Spring Boot" (NEW project):
            executionStrategy: "sequential"   ← ALWAYS sequential for new projects
            - CODER: Create the Spring Boot project structure with pom.xml and application.properties
              targetFiles: ["pom.xml", "src/main/resources/application.properties", "src/main/java/com/example/TodoApplication.java"]
            - CODER: Create the TodoItem model class
              targetFiles: ["src/main/java/com/example/model/TodoItem.java"]
            - CODER: Create the TodoRepository interface
              targetFiles: ["src/main/java/com/example/repository/TodoRepository.java"]
            - CODER: Create the TodoService with business logic
              targetFiles: ["src/main/java/com/example/service/TodoService.java"]
            - CODER: Create the TodoController REST endpoints
              targetFiles: ["src/main/java/com/example/controller/TodoController.java"]
            Note: If CF deployment is enabled, a DEPLOYER task is added automatically — do NOT plan manifest.yml creation.

            BAD example (tasks too large):
            - CODER: Create model, repository, service, and controller  ← TOO BIG, split into 4 tasks
              targetFiles: ["Model.java", "Repository.java", "Service.java", "Controller.java"]
            
            Example plan for independent subsystems in EXISTING codebase (rare):
            executionStrategy: "parallel"   ← Only when files are truly independent
            - CODER: Add user preference storage to backend
              targetFiles: ["backend/services/PreferencesService.java"]
            - CODER: Add preference UI components to frontend
              targetFiles: ["frontend/components/PreferencesPanel.tsx"]

            TECHNOLOGY-SPECIFIC RULES:
            14. For Spring Boot 3.x projects: ALL Java EE imports MUST use `jakarta.*` (NOT `javax.*`).
                Spring Boot 3 uses Jakarta EE 10. `javax.persistence`, `javax.validation`, etc. will NOT compile.
                Include this in the inputContext for any CODER task that creates JPA entities, validation, or servlets.
            15. For Cloud Foundry deployments: `manifest.yml` MUST include `buildpacks: - java_buildpack_offline`
                for Java apps. Without the buildpack entry, CF staging will fail.
            
            Respond with valid JSON matching the schema provided.
            """;

    private final LlmService llmService;
    private final DeployerProperties deployerProperties;

    public PlanMissionNode(LlmService llmService, DeployerProperties deployerProperties) {
        this.llmService = llmService;
        this.deployerProperties = deployerProperties;
    }

    public Map<String, Object> apply(WorldmindState state) {
        // Early-exit for retry: if tasks already exist, skip re-planning
        if (!state.tasks().isEmpty()) {
            return Map.of("status", MissionStatus.AWAITING_APPROVAL.name());
        }

        String request = state.request();
        String prdDocument = state.prdDocument();
        boolean hasPrd = prdDocument != null && !prdDocument.isBlank();
        
        // If PRD document is provided, use it directly without requiring classification
        Classification classification;
        ProjectContext projectContext;
        Optional<ProductSpec> productSpec = state.productSpec();
        
        if (hasPrd) {
            // Create a synthetic classification for PRD-based missions
            classification = state.classification().orElse(
                    new Classification("feature", 3, List.of("all"), "sequential", "auto")
            );
            projectContext = state.projectContext().orElse(
                    new ProjectContext(".", List.of(), "unknown", "unknown", Map.of(), 0, "")
            );
            log.info("Planning from user-provided PRD document ({} chars)", prdDocument.length());
        } else {
            classification = state.classification().orElseThrow(
                    () -> new IllegalStateException("Classification must be present before planning")
            );
            projectContext = state.projectContext().orElseThrow(
                    () -> new IllegalStateException("ProjectContext must be present before planning")
            );
        }

        String userPrompt = buildUserPrompt(request, classification, projectContext, productSpec, prdDocument);
        // Plan generation uses structuredCall without MCP tools — the planner only
        // needs to structure tasks from the spec, not call external tools.
        MissionPlan plan = llmService.structuredCall(SYSTEM_PROMPT, userPrompt, MissionPlan.class);

        List<Task> tasks = convertToTasks(plan);
        tasks = ensureCoderTask(tasks, request);
        tasks = assignTypeDependencies(tasks);
        
        // Detect whether any planned task targets manifest.yml (before adding DEPLOYER)
        boolean manifestTaskExists = tasks.stream()
                .filter(t -> t.targetFiles() != null)
                .flatMap(t -> t.targetFiles().stream())
                .anyMatch(f -> f.endsWith("/manifest.yml") || f.equals("manifest.yml"));

        // If user requested CF deployment, append a final DEPLOYER task
        if (state.createCfDeployment()) {
            tasks = appendDeployerTask(tasks, state, manifestTaskExists);
        }

        log.info("Mission plan: {} tasks — {}", tasks.size(),
                tasks.stream().map(d -> d.id() + "[" + d.agent() + "](deps:" + d.dependencies() + ")").toList());
        log.info("Manifest created by task: {}", manifestTaskExists);

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
                "status", MissionStatus.AWAITING_APPROVAL.name(),
                "manifestCreatedByTask", manifestTaskExists
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
     * Appends a final DEPLOYER task to build, deploy, and verify the application on Cloud Foundry.
     * This task depends on all CODER/REFACTORER tasks so it runs as the final wave.
     */
    private List<Task> appendDeployerTask(List<Task> tasks, WorldmindState state,
                                           boolean manifestCreatedByTask) {
        List<String> dependencies = tasks.stream()
                .filter(t -> "CODER".equalsIgnoreCase(t.agent()) || "REFACTORER".equalsIgnoreCase(t.agent()))
                .map(Task::id)
                .toList();

        String runtimeTag = state.classification()
                .map(Classification::runtimeTag)
                .orElse("base");
        String instructions = buildDeployerInstructions(state, manifestCreatedByTask, runtimeTag);

        var deployerTask = new Task(
                "TASK-DEPLOY", "DEPLOYER",
                "Build and deploy application to Cloud Foundry",
                instructions,
                "App deployed, started, and health check passes within 5 minutes",
                dependencies,
                TaskStatus.PENDING, 0, 3,
                FailureStrategy.RETRY,
                List.of("manifest.yml"),
                List.of(), null
        );

        var result = new ArrayList<>(tasks);
        result.add(deployerTask);
        log.info("Appended DEPLOYER task TASK-DEPLOY with dependencies on {}", dependencies);
        return result;
    }

    /**
     * Builds comprehensive markdown instructions for the DEPLOYER agent.
     * Includes build commands, manifest generation (conditional), CF auth, deploy, and health check.
     * Build commands and buildpack are derived from the classification's runtimeTag.
     */
    private String buildDeployerInstructions(WorldmindState state, boolean manifestTaskExists,
                                              String runtimeTag) {
        String rawId = state.missionId();
        String missionId = (rawId != null && !rawId.isBlank()) ? rawId : "app";
        String clarifyingAnswers = state.clarifyingAnswers();

        var defaults = deployerProperties.getDefaults();
        int healthTimeoutMinutes = (deployerProperties.getHealthTimeout() + 59) / 60;

        var sb = new StringBuilder();
        sb.append("# Deployment Task\n\n");
        sb.append("Deploy the completed application to Cloud Foundry.\n\n");

        // Application details — derive type from runtimeTag
        String appType = deriveAppType(runtimeTag);
        sb.append("## Application Details\n");
        sb.append("- Type: ").append(appType).append("\n");
        sb.append("- Route: ").append(missionId).append(".apps.$CF_APPS_DOMAIN\n\n");

        // Service bindings from clarifying answers
        List<String> serviceNames = extractServiceNames(clarifyingAnswers);
        if (!serviceNames.isEmpty()) {
            sb.append("## Service Bindings\n");
            for (String svc : serviceNames) {
                sb.append("- `").append(svc).append("`\n");
            }
            sb.append("\n");
        }

        // Steps
        sb.append("## Steps\n\n");

        // Step 1: CF auth
        sb.append("### 1. Authenticate with Cloud Foundry\n");
        sb.append("```bash\n");
        if (deployerProperties.isSkipSslValidation()) {
            sb.append("cf api $CF_API_URL --skip-ssl-validation\n");
        } else {
            sb.append("cf api $CF_API_URL\n");
        }
        sb.append("cf auth $CF_USERNAME $CF_PASSWORD\n");
        sb.append("cf target -o $CF_ORG -s $CF_SPACE\n");
        sb.append("```\n\n");

        // Step 2: Build — conditional on runtime
        sb.append("### 2. Build the application\n");
        appendBuildCommands(sb, runtimeTag);
        sb.append("\n");

        // Step 3: Manifest (conditional)
        if (manifestTaskExists) {
            sb.append("### 3. Use existing manifest\n");
            sb.append("A task has already created `manifest.yml` in the repository. ");
            sb.append("Use that manifest as-is for deployment.\n\n");
        } else {
            sb.append("### 3. Generate manifest.yml\n");
            sb.append("No task created a manifest. Create `manifest.yml` with this content:\n");
            sb.append("```yaml\n");
            sb.append("applications:\n");
            sb.append("- name: ").append(missionId).append("\n");
            sb.append("  memory: ").append(defaults.getMemory()).append("\n");
            sb.append("  instances: ").append(defaults.getInstances()).append("\n");
            appendManifestPathAndBuildpack(sb, runtimeTag, defaults);
            sb.append("  routes:\n");
            sb.append("  - route: ").append(missionId)
                    .append(".apps.$CF_APPS_DOMAIN\n");
            if (isJavaRuntime(runtimeTag)) {
                sb.append("  env:\n");
                sb.append("    JBP_CONFIG_OPEN_JDK_JRE: '{ jre: { version: ")
                        .append(defaults.getJavaVersion()).append(".+ } }'\n");
            }
            if (!serviceNames.isEmpty()) {
                sb.append("  services:\n");
                for (String svc : serviceNames) {
                    sb.append("  - ").append(svc).append("\n");
                }
            }
            sb.append("```\n\n");
        }

        // Step 4: Deploy
        sb.append("### 4. Deploy to Cloud Foundry\n");
        sb.append("```bash\n");
        sb.append("cf push -f manifest.yml\n");
        sb.append("```\n\n");

        // Step 5: Health check
        sb.append("### 5. Verify deployment\n");
        sb.append("Wait for the app to reach `running` state:\n");
        sb.append("```bash\n");
        sb.append("cf app ").append(missionId).append("\n");
        sb.append("```\n");
        sb.append("Confirm the app shows `running` status and the health check passes within ")
                .append(healthTimeoutMinutes).append(" minutes.\n");
        sb.append("Report the deployment URL and status.\n");

        return sb.toString();
    }

    /**
     * Extracts service instance names from clarifying answers JSON.
     * Supports two answer formats:
     * <ol>
     *   <li>Structured JSON array: {@code [{"type":"postgresql","instanceName":"my-db"}]}</li>
     *   <li>Legacy comma/newline-separated: {@code "todo-db, my-redis-cache"}</li>
     * </ol>
     * Sanitizes names to prevent YAML injection.
     */
    List<String> extractServiceNames(String clarifyingAnswers) {
        if (clarifyingAnswers == null || clarifyingAnswers.isBlank()) {
            return List.of();
        }
        try {
            var answers = OBJECT_MAPPER.readTree(clarifyingAnswers);
            if (answers.has("cf_service_bindings")) {
                String serviceAnswer = answers.get("cf_service_bindings").asText();
                if (serviceAnswer == null || serviceAnswer.isBlank()
                        || serviceAnswer.equalsIgnoreCase("No services needed")) {
                    return List.of();
                }

                // Try structured JSON array format first: [{"type":"postgresql","instanceName":"my-db"}]
                try {
                    var arr = OBJECT_MAPPER.readTree(serviceAnswer);
                    if (arr.isArray()) {
                        var names = new ArrayList<String>();
                        for (var node : arr) {
                            String instanceName = node.has("instanceName")
                                    ? node.get("instanceName").asText() : null;
                            if (instanceName != null && !instanceName.isBlank()) {
                                names.add(sanitizeServiceName(instanceName));
                            }
                        }
                        if (!names.isEmpty()) {
                            return names;
                        }
                    }
                } catch (Exception ignored) {
                    // Not JSON array — fall through to legacy parsing
                }

                // Legacy format: comma/newline-separated names
                return java.util.Arrays.stream(serviceAnswer.split("[,\\n]+"))
                        .map(String::trim)
                        .filter(s -> !s.isEmpty())
                        .map(this::sanitizeServiceName)
                        .toList();
            }
        } catch (Exception e) {
            log.warn("Could not parse service names from clarifying answers: {}", e.getMessage());
        }
        return List.of();
    }

    /**
     * Derives a human-readable application type from the classification's runtimeTag.
     */
    static String deriveAppType(String runtimeTag) {
        if (runtimeTag == null) return "Application";
        return switch (runtimeTag.toLowerCase()) {
            case "java", "spring-boot", "spring" -> "Spring Boot";
            case "nodejs", "node" -> "Node.js";
            case "python", "django", "flask" -> "Python";
            case "go", "golang" -> "Go";
            case "ruby", "rails" -> "Ruby";
            case "html", "static" -> "Static HTML";
            default -> "Application";
        };
    }

    private static boolean isJavaRuntime(String runtimeTag) {
        if (runtimeTag == null) return false;
        return switch (runtimeTag.toLowerCase()) {
            case "java", "spring-boot", "spring" -> true;
            default -> false;
        };
    }

    /**
     * Appends runtime-specific build commands to the deployer instructions.
     */
    private static void appendBuildCommands(StringBuilder sb, String runtimeTag) {
        String rt = runtimeTag != null ? runtimeTag.toLowerCase() : "base";
        switch (rt) {
            case "java", "spring-boot", "spring" -> {
                sb.append("If `./mvnw` exists, use it; otherwise fall back to `mvn`:\n");
                sb.append("```bash\n");
                sb.append("if [ -f ./mvnw ]; then\n");
                sb.append("  ./mvnw clean package -DskipTests\n");
                sb.append("else\n");
                sb.append("  mvn clean package -DskipTests\n");
                sb.append("fi\n");
                sb.append("```\n");
            }
            case "nodejs", "node" -> {
                sb.append("```bash\n");
                sb.append("npm install\n");
                sb.append("npm run build\n");
                sb.append("```\n");
            }
            case "python", "django", "flask" -> {
                sb.append("```bash\n");
                sb.append("pip install -r requirements.txt\n");
                sb.append("```\n");
            }
            case "go", "golang" -> {
                sb.append("```bash\n");
                sb.append("go build -o app .\n");
                sb.append("```\n");
            }
            case "html", "static" -> {
                sb.append("No build step required for static content.\n");
            }
            default -> {
                sb.append("Run the project's standard build command.\n");
            }
        }
    }

    /**
     * Appends runtime-specific path and buildpack entries to the manifest template.
     */
    private static void appendManifestPathAndBuildpack(StringBuilder sb, String runtimeTag,
                                                        DeployerProperties.DeployerDefaults defaults) {
        String rt = runtimeTag != null ? runtimeTag.toLowerCase() : "base";
        switch (rt) {
            case "java", "spring-boot", "spring" -> {
                sb.append("  path: target/*.jar\n");
                sb.append("  buildpacks:\n");
                sb.append("  - ").append(defaults.getBuildpack()).append("\n");
            }
            case "nodejs", "node" -> {
                sb.append("  buildpacks:\n");
                sb.append("  - nodejs_buildpack\n");
            }
            case "python", "django", "flask" -> {
                sb.append("  buildpacks:\n");
                sb.append("  - python_buildpack\n");
            }
            case "go", "golang" -> {
                sb.append("  path: app\n");
                sb.append("  buildpacks:\n");
                sb.append("  - go_buildpack\n");
            }
            case "html", "static" -> {
                sb.append("  buildpacks:\n");
                sb.append("  - staticfile_buildpack\n");
            }
            default -> {
                sb.append("  buildpacks:\n");
                sb.append("  - ").append(defaults.getBuildpack()).append("\n");
            }
        }
    }

    private String sanitizeServiceName(String name) {
        return name.replaceAll("[^a-zA-Z0-9\\-]", "");
    }

    private String buildUserPrompt(String request, Classification classification,
                                   ProjectContext projectContext, Optional<ProductSpec> productSpec,
                                   String prdDocument) {
        var sb = new StringBuilder();
        
        // If user provided a PRD document, use it as the primary source
        if (prdDocument != null && !prdDocument.isBlank()) {
            sb.append("""
                === USER-PROVIDED PRD DOCUMENT ===
                
                The user has provided a detailed Product Requirements Document below.
                Use this as the PRIMARY source for creating tasks. The PRD contains
                all the details needed for implementation.
                
                """);
            sb.append(prdDocument);
            sb.append("""
                
                === END OF PRD DOCUMENT ===
                
                === TASK GENERATION INSTRUCTIONS ===
                Create tasks that implement the PRD completely. Each major section or
                feature should map to one or more CODER tasks. Include relevant PRD
                sections in each task's inputContext so agents have full context.
                
                Guidelines:
                - Break down complex features into multiple tasks
                - Include specific file paths in targetFiles where mentioned
                - Copy acceptance criteria directly into task successCriteria
                - Preserve all technical details from the PRD
                
                """);
            return sb.toString();
        }
        
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
                    if (comp.integrationPoints() != null && !comp.integrationPoints().isEmpty()) {
                        sb.append("    Integration Points: ").append(String.join(", ", comp.integrationPoints())).append("\n");
                    }
                }
            }

            if (spec.edgeCases() != null && !spec.edgeCases().isEmpty()) {
                sb.append("\nEDGE CASES:\n");
                for (var ec : spec.edgeCases()) sb.append("  - ").append(ec).append("\n");
            }

            if (spec.outOfScopeAssumptions() != null && !spec.outOfScopeAssumptions().isEmpty()) {
                sb.append("\nOUT-OF-SCOPE ASSUMPTIONS:\n");
                for (var a : spec.outOfScopeAssumptions()) sb.append("  - ").append(a).append("\n");
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
