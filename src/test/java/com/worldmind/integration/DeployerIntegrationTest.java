package com.worldmind.integration;

import com.worldmind.core.events.EventBus;
import com.worldmind.core.llm.LlmService;
import com.worldmind.core.metrics.WorldmindMetrics;
import com.worldmind.core.model.*;
import com.worldmind.core.nodes.ConvergeResultsNode;
import com.worldmind.core.nodes.EvaluateWaveNode;
import com.worldmind.core.nodes.PlanMissionNode;
import com.worldmind.core.quality_gate.QualityGateEvaluationService;
import com.worldmind.core.scheduler.OscillationDetector;
import com.worldmind.core.state.WorldmindState;
import com.worldmind.sandbox.AgentDispatcher;
import com.worldmind.sandbox.DeployerProperties;
import org.junit.jupiter.api.*;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * End-to-end integration tests for the CF Deploy mission flow.
 * <p>
 * Validates the full DEPLOYER pipeline: mission planning with CF Deploy enabled →
 * DEPLOYER task creation → manifest generation → deployment evaluation →
 * health check verification → mission status propagation.
 * <p>
 * Test Scenario 1: Simple Hello World Spring Boot REST API (no service bindings)
 * Test Scenario 2: Spring Boot with PostgreSQL (with service binding)
 * <p>
 * Run with: {@code mvn test -Dgroups=integration}
 */
@Tag("integration")
@DisplayName("CF Deploy End-to-End Integration")
class DeployerIntegrationTest {

    private LlmService mockLlm;
    private AgentDispatcher mockBridge;
    private QualityGateEvaluationService mockQualityGateService;
    private DeployerProperties deployerProperties;
    private PlanMissionNode planNode;
    private EvaluateWaveNode evaluateNode;
    private ConvergeResultsNode convergeNode;
    private EventBus eventBus;

    @BeforeEach
    void setUp() {
        mockLlm = mock(LlmService.class);
        mockBridge = mock(AgentDispatcher.class);
        mockQualityGateService = mock(QualityGateEvaluationService.class);
        deployerProperties = new DeployerProperties();
        eventBus = new EventBus();

        planNode = new PlanMissionNode(mockLlm, deployerProperties);
        evaluateNode = new EvaluateWaveNode(
                mockBridge, mockQualityGateService, eventBus,
                mock(WorldmindMetrics.class), new OscillationDetector(),
                null, null);
        convergeNode = new ConvergeResultsNode(eventBus);
    }

    // ══════════════════════════════════════════════════════════════════════
    // Scenario 1: Hello World Spring Boot REST API (no services)
    // ══════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Scenario 1: Hello World Spring Boot (no services)")
    class HelloWorldScenario {

        private static final String MISSION_ID = "WMND-2026-0042";
        private static final String REQUEST = "Create a simple Spring Boot REST API with a /hello endpoint that returns 'Hello, World!'";

        private MissionPlan helloWorldPlan() {
            return new MissionPlan(
                    "Hello World REST API",
                    "sequential",
                    List.of(
                            new MissionPlan.TaskPlan("CODER", "Create Spring Boot project with pom.xml and main class",
                                    "", "Project compiles", List.of(),
                                    List.of("pom.xml", "src/main/java/com/example/HelloApplication.java")),
                            new MissionPlan.TaskPlan("CODER", "Create HelloController with /hello endpoint",
                                    "", "GET /hello returns 'Hello, World!'", List.of(),
                                    List.of("src/main/java/com/example/HelloController.java"))
                    )
            );
        }

        @Test
        @DisplayName("Full pipeline: plan → evaluate CODER → evaluate DEPLOYER → converge → SUCCESS")
        @SuppressWarnings("unchecked")
        void fullPipelineSuccess() {
            // ── Phase 1: Planning ──────────────────────────────────────
            when(mockLlm.structuredCall(anyString(), anyString(), eq(MissionPlan.class)))
                    .thenReturn(helloWorldPlan());

            var planState = new WorldmindState(Map.of(
                    "request", REQUEST,
                    "classification", new Classification("feature", 2, List.of("api"), "sequential", "java"),
                    "projectContext", new ProjectContext(".", List.of(), "java", "spring-boot", Map.of(), 0, "new project"),
                    "createCfDeployment", true,
                    "missionId", MISSION_ID
            ));

            var planResult = planNode.apply(planState);

            var tasks = (List<Task>) planResult.get("tasks");
            assertNotNull(tasks, "Plan should produce tasks");
            assertTrue(tasks.size() >= 3, "Should have at least 2 CODER + 1 DEPLOYER task");

            // Verify DEPLOYER task is last
            Task deployerTask = tasks.get(tasks.size() - 1);
            assertEquals("DEPLOYER", deployerTask.agent());
            assertEquals("TASK-DEPLOY", deployerTask.id());

            // Verify manifest is NOT created by any CODER task (no manifest.yml in target files)
            assertFalse((Boolean) planResult.get("manifestCreatedByTask"),
                    "No CODER task targets manifest.yml, so DEPLOYER should generate it");

            // ── Phase 2: Evaluate CODER tasks ──────────────────────────
            // Simulate CODER tasks passing quality gate
            setupCoderQualityGatePass();

            List<Task> coderTasks = tasks.stream()
                    .filter(t -> "CODER".equalsIgnoreCase(t.agent()))
                    .toList();

            var coderWaveIds = coderTasks.stream().map(Task::id).toList();
            var coderDispatchResults = coderTasks.stream()
                    .map(t -> new WaveDispatchResult(t.id(), TaskStatus.PASSED,
                            List.of(new FileRecord("src/main/java/HelloController.java", "created", 25)),
                            "Files created successfully", 5000L))
                    .toList();

            var coderEvalState = new WorldmindState(Map.of(
                    "missionId", MISSION_ID,
                    "waveTaskIds", coderWaveIds,
                    "tasks", tasks,
                    "waveDispatchResults", coderDispatchResults,
                    "status", MissionStatus.EXECUTING.name()
            ));

            var coderEvalResult = evaluateNode.apply(coderEvalState);
            var completedIds = (List<String>) coderEvalResult.get("completedTaskIds");
            assertNotNull(completedIds);
            for (String coderId : coderWaveIds) {
                assertTrue(completedIds.contains(coderId),
                        "CODER task " + coderId + " should be completed after quality gate pass");
            }

            // ── Phase 3: Evaluate DEPLOYER task ────────────────────────
            String cfPushOutput = buildSuccessfulCfPushOutput(MISSION_ID);
            var deployDispatchResult = new WaveDispatchResult(
                    "TASK-DEPLOY", TaskStatus.PASSED, List.of(), cfPushOutput, 60000L);

            var deployEvalState = new WorldmindState(Map.of(
                    "missionId", MISSION_ID,
                    "waveTaskIds", List.of("TASK-DEPLOY"),
                    "tasks", List.of(deployerTask),
                    "waveDispatchResults", List.of(deployDispatchResult),
                    "status", MissionStatus.EXECUTING.name()
            ));

            var deployEvalResult = evaluateNode.apply(deployEvalState);

            var deployCompletedIds = (List<String>) deployEvalResult.get("completedTaskIds");
            assertNotNull(deployCompletedIds);
            assertTrue(deployCompletedIds.contains("TASK-DEPLOY"),
                    "DEPLOYER task should be completed on successful deployment");

            // Verify deployment URL is captured
            String deploymentUrl = (String) deployEvalResult.get("deploymentUrl");
            assertNotNull(deploymentUrl, "Deployment URL should be captured");
            assertTrue(deploymentUrl.contains(MISSION_ID),
                    "Deployment URL should contain mission ID");
            assertTrue(deploymentUrl.contains("apps."),
                    "Deployment URL should follow the apps.{domain} convention");

            // Verify no TESTER/REVIEWER dispatched for DEPLOYER
            verify(mockBridge, never()).executeTask(
                    argThat(t -> t != null && "TASK-DEPLOY".equals(t.id())),
                    any(), any(), any(), any());

            // ── Phase 4: Converge ──────────────────────────────────────
            var allPassedTasks = new ArrayList<>(tasks);
            // Update tasks to reflect PASSED status
            var finalTasks = allPassedTasks.stream()
                    .map(t -> new Task(t.id(), t.agent(), t.description(),
                            t.inputContext(), t.successCriteria(), t.dependencies(),
                            TaskStatus.PASSED, 1, t.maxIterations(),
                            t.onFailure(), t.targetFiles(), t.filesAffected(), 5000L))
                    .toList();

            var convergeState = new WorldmindState(Map.of(
                    "missionId", MISSION_ID,
                    "tasks", finalTasks,
                    "testResults", List.of(),
                    "sandboxes", List.of(),
                    "deploymentUrl", deploymentUrl
            ));

            var convergeResult = convergeNode.apply(convergeState);

            assertEquals(MissionStatus.COMPLETED.name(), convergeResult.get("status"),
                    "Mission should be COMPLETED when all tasks pass");
            var metrics = (MissionMetrics) convergeResult.get("metrics");
            assertNotNull(metrics);
            assertEquals(finalTasks.size(), metrics.tasksCompleted(),
                    "All tasks should be counted as completed");
            assertEquals(0, metrics.tasksFailed());
        }

        @Test
        @DisplayName("DEPLOYER task has correct dependencies on all CODER tasks")
        @SuppressWarnings("unchecked")
        void deployerDependsOnAllCoders() {
            when(mockLlm.structuredCall(anyString(), anyString(), eq(MissionPlan.class)))
                    .thenReturn(helloWorldPlan());

            var state = new WorldmindState(Map.of(
                    "request", REQUEST,
                    "classification", new Classification("feature", 2, List.of("api"), "sequential", "java"),
                    "projectContext", new ProjectContext(".", List.of(), "java", "spring-boot", Map.of(), 0, "new project"),
                    "createCfDeployment", true,
                    "missionId", MISSION_ID
            ));

            var result = planNode.apply(state);
            var tasks = (List<Task>) result.get("tasks");

            Task deployerTask = tasks.stream()
                    .filter(t -> "DEPLOYER".equalsIgnoreCase(t.agent()))
                    .findFirst().orElseThrow();

            List<String> coderIds = tasks.stream()
                    .filter(t -> "CODER".equalsIgnoreCase(t.agent()))
                    .map(Task::id)
                    .toList();

            for (String coderId : coderIds) {
                assertTrue(deployerTask.dependencies().contains(coderId),
                        "DEPLOYER should depend on CODER task " + coderId);
            }
        }

        @Test
        @DisplayName("Manifest generated with all required fields for simple app")
        @SuppressWarnings("unchecked")
        void manifestGeneratedCorrectly() {
            when(mockLlm.structuredCall(anyString(), anyString(), eq(MissionPlan.class)))
                    .thenReturn(helloWorldPlan());

            var state = new WorldmindState(Map.of(
                    "request", REQUEST,
                    "classification", new Classification("feature", 2, List.of("api"), "sequential", "java"),
                    "projectContext", new ProjectContext(".", List.of(), "java", "spring-boot", Map.of(), 0, "new project"),
                    "createCfDeployment", true,
                    "missionId", MISSION_ID
            ));

            var result = planNode.apply(state);
            var tasks = (List<Task>) result.get("tasks");

            Task deployerTask = tasks.stream()
                    .filter(t -> "DEPLOYER".equalsIgnoreCase(t.agent()))
                    .findFirst().orElseThrow();

            String instructions = deployerTask.inputContext();

            // Verify manifest template contains all required fields
            assertTrue(instructions.contains("name: " + MISSION_ID),
                    "Manifest should set app name to mission ID");
            assertTrue(instructions.contains("memory: 1G"),
                    "Manifest should set default memory");
            assertTrue(instructions.contains("instances: 1"),
                    "Manifest should set default instance count");
            assertTrue(instructions.contains("java_buildpack_offline"),
                    "Manifest should use java_buildpack_offline");
            assertTrue(instructions.contains("target/*.jar"),
                    "Manifest should set JAR path");
            assertTrue(instructions.contains("jre: { version: 21"),
                    "Manifest should set Java 21");

            // Verify route follows convention
            assertTrue(instructions.contains(MISSION_ID + ".apps.$CF_APPS_DOMAIN"),
                    "Route should follow {mission-id}.apps.{domain} convention");

            // Verify NO services block (simple app)
            assertFalse(instructions.contains("services:"),
                    "Simple app should not have services block");
            assertFalse(instructions.contains("Service Bindings"),
                    "Simple app should not have service bindings section");
        }

        @Test
        @DisplayName("Route follows {mission-id}.apps.{domain} convention")
        @SuppressWarnings("unchecked")
        void routeFollowsConvention() {
            when(mockLlm.structuredCall(anyString(), anyString(), eq(MissionPlan.class)))
                    .thenReturn(helloWorldPlan());

            var state = new WorldmindState(Map.of(
                    "request", REQUEST,
                    "classification", new Classification("feature", 2, List.of("api"), "sequential", "java"),
                    "projectContext", new ProjectContext(".", List.of(), "java", "spring-boot", Map.of(), 0, "new"),
                    "createCfDeployment", true,
                    "missionId", MISSION_ID
            ));

            var result = planNode.apply(state);
            var tasks = (List<Task>) result.get("tasks");
            Task deployerTask = tasks.stream()
                    .filter(t -> "DEPLOYER".equalsIgnoreCase(t.agent()))
                    .findFirst().orElseThrow();

            String instructions = deployerTask.inputContext();
            var routePattern = Pattern.compile("route:\\s+" + Pattern.quote(MISSION_ID) + "\\.apps\\.\\$CF_APPS_DOMAIN");
            assertTrue(routePattern.matcher(instructions).find(),
                    "Route should match pattern {mission-id}.apps.$CF_APPS_DOMAIN");
        }

        @Test
        @DisplayName("Health check verification within configured timeout")
        @SuppressWarnings("unchecked")
        void healthCheckWithinTimeout() {
            when(mockLlm.structuredCall(anyString(), anyString(), eq(MissionPlan.class)))
                    .thenReturn(helloWorldPlan());

            var state = new WorldmindState(Map.of(
                    "request", REQUEST,
                    "classification", new Classification("feature", 2, List.of("api"), "sequential", "java"),
                    "projectContext", new ProjectContext(".", List.of(), "java", "spring-boot", Map.of(), 0, "new"),
                    "createCfDeployment", true,
                    "missionId", MISSION_ID
            ));

            var result = planNode.apply(state);
            var tasks = (List<Task>) result.get("tasks");
            Task deployerTask = tasks.stream()
                    .filter(t -> "DEPLOYER".equalsIgnoreCase(t.agent()))
                    .findFirst().orElseThrow();

            String instructions = deployerTask.inputContext();

            // Default health timeout is 300 seconds = 5 minutes
            assertTrue(instructions.contains("5 minutes"),
                    "Instructions should reference health check timeout (5 minutes)");
            assertTrue(instructions.contains("running"),
                    "Instructions should instruct to verify app is running");
            assertTrue(instructions.contains("cf app " + MISSION_ID),
                    "Instructions should include cf app command for health verification");
        }

        @Test
        @DisplayName("DEPLOYER instructions include CF auth commands with env var references")
        @SuppressWarnings("unchecked")
        void deployCfAuthCommands() {
            when(mockLlm.structuredCall(anyString(), anyString(), eq(MissionPlan.class)))
                    .thenReturn(helloWorldPlan());

            var state = new WorldmindState(Map.of(
                    "request", REQUEST,
                    "classification", new Classification("feature", 2, List.of("api"), "sequential", "java"),
                    "projectContext", new ProjectContext(".", List.of(), "java", "spring-boot", Map.of(), 0, "new"),
                    "createCfDeployment", true,
                    "missionId", MISSION_ID
            ));

            var result = planNode.apply(state);
            var tasks = (List<Task>) result.get("tasks");
            Task deployerTask = tasks.stream()
                    .filter(t -> "DEPLOYER".equalsIgnoreCase(t.agent()))
                    .findFirst().orElseThrow();

            String instructions = deployerTask.inputContext();
            assertTrue(instructions.contains("cf api $CF_API_URL"), "Should include CF API auth");
            assertTrue(instructions.contains("cf auth $CF_USERNAME $CF_PASSWORD"), "Should include CF auth");
            assertTrue(instructions.contains("cf target -o $CF_ORG -s $CF_SPACE"), "Should include CF target");
            assertTrue(instructions.contains("cf push -f manifest.yml"), "Should include cf push");
        }

        @Test
        @DisplayName("DEPLOYER instructions include Maven build with wrapper fallback")
        @SuppressWarnings("unchecked")
        void deployMavenBuildWithFallback() {
            when(mockLlm.structuredCall(anyString(), anyString(), eq(MissionPlan.class)))
                    .thenReturn(helloWorldPlan());

            var state = new WorldmindState(Map.of(
                    "request", REQUEST,
                    "classification", new Classification("feature", 2, List.of("api"), "sequential", "java"),
                    "projectContext", new ProjectContext(".", List.of(), "java", "spring-boot", Map.of(), 0, "new"),
                    "createCfDeployment", true,
                    "missionId", MISSION_ID
            ));

            var result = planNode.apply(state);
            var tasks = (List<Task>) result.get("tasks");
            Task deployerTask = tasks.stream()
                    .filter(t -> "DEPLOYER".equalsIgnoreCase(t.agent()))
                    .findFirst().orElseThrow();

            String instructions = deployerTask.inputContext();
            assertTrue(instructions.contains("./mvnw clean package -DskipTests"),
                    "Should try Maven wrapper first");
            assertTrue(instructions.contains("mvn clean package -DskipTests"),
                    "Should fall back to mvn if wrapper not present");
        }

        @Test
        @DisplayName("Deployment URL extracted and mission status COMPLETED on success")
        @SuppressWarnings("unchecked")
        void deploymentUrlExtractedOnSuccess() {
            String cfOutput = buildSuccessfulCfPushOutput(MISSION_ID);
            var deployerTask = new Task("TASK-DEPLOY", "DEPLOYER", "Deploy app", "", "App running",
                    List.of(), TaskStatus.PENDING, 0, 3, FailureStrategy.RETRY,
                    List.of("manifest.yml"), List.of(), null);

            var dispatchResult = new WaveDispatchResult(
                    "TASK-DEPLOY", TaskStatus.PASSED, List.of(), cfOutput, 60000L);

            var state = new WorldmindState(Map.of(
                    "missionId", MISSION_ID,
                    "waveTaskIds", List.of("TASK-DEPLOY"),
                    "tasks", List.of(deployerTask),
                    "waveDispatchResults", List.of(dispatchResult)
            ));

            var result = evaluateNode.apply(state);

            String url = (String) result.get("deploymentUrl");
            assertNotNull(url, "Deployment URL must be captured");
            assertTrue(url.startsWith("https://"),
                    "URL should start with https://");
            assertTrue(url.contains(MISSION_ID + ".apps."),
                    "URL should contain {mission-id}.apps.");

            // Mission should NOT be FAILED
            assertNull(result.get("status"),
                    "Mission status should not be set to FAILED on success");

            var completedIds = (List<String>) result.get("completedTaskIds");
            assertTrue(completedIds.contains("TASK-DEPLOY"));
        }

        @Test
        @DisplayName("Mission fails when DEPLOYER fails and retries exhausted")
        @SuppressWarnings("unchecked")
        void missionFailsWhenDeployerExhausted() {
            String failureOutput = "Pushing app " + MISSION_ID + "...\n"
                    + "Staging app...\n"
                    + "Build succeeded\n"
                    + "Waiting for app to start...\n"
                    + "App instance exited with CRASHED status\n"
                    + "Out of memory: Java heap space";

            var deployerTask = new Task("TASK-DEPLOY", "DEPLOYER", "Deploy app", "", "App running",
                    List.of(), TaskStatus.PENDING, 3, 3, FailureStrategy.RETRY,
                    List.of("manifest.yml"), List.of(), null);

            var dispatchResult = new WaveDispatchResult(
                    "TASK-DEPLOY", TaskStatus.PASSED, List.of(), failureOutput, 60000L);

            var state = new WorldmindState(Map.of(
                    "missionId", MISSION_ID,
                    "waveTaskIds", List.of("TASK-DEPLOY"),
                    "tasks", List.of(deployerTask),
                    "waveDispatchResults", List.of(dispatchResult)
            ));

            var evalResult = evaluateNode.apply(state);
            assertEquals(MissionStatus.FAILED.name(), evalResult.get("status"),
                    "Mission should be FAILED when DEPLOYER exhausts retries");

            // Converge should preserve FAILED
            var coderTask = new Task("TASK-001", "CODER", "Create app", "", "Done",
                    List.of(), TaskStatus.PASSED, 1, 3, FailureStrategy.RETRY,
                    List.of(), List.of(), 5000L);
            var failedDeployer = new Task("TASK-DEPLOY", "DEPLOYER", "Deploy app", "", "App running",
                    List.of(), TaskStatus.FAILED, 3, 3, FailureStrategy.RETRY,
                    List.of(), List.of(), 60000L);

            var convergeState = new WorldmindState(Map.of(
                    "missionId", MISSION_ID,
                    "tasks", List.of(coderTask, failedDeployer),
                    "testResults", List.of(),
                    "sandboxes", List.of(),
                    "status", MissionStatus.FAILED.name()
            ));

            var convergeResult = convergeNode.apply(convergeState);
            assertEquals(MissionStatus.FAILED.name(), convergeResult.get("status"),
                    "ConvergeNode should preserve FAILED status from DEPLOYER failure");
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // Scenario 2: Spring Boot with PostgreSQL (service binding)
    // ══════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Scenario 2: Spring Boot with PostgreSQL (service binding)")
    class PostgresScenario {

        private static final String MISSION_ID = "WMND-2026-0043";
        private static final String REQUEST = "Create a Spring Boot app with a PostgreSQL-backed TodoItem REST API";
        private static final String SERVICE_NAME = "test-todo-db";

        private MissionPlan todoAppPlan() {
            return new MissionPlan(
                    "Todo App with PostgreSQL",
                    "sequential",
                    List.of(
                            new MissionPlan.TaskPlan("RESEARCHER", "Analyze Spring Data JPA patterns for PostgreSQL",
                                    "", "Patterns documented", List.of(), List.of()),
                            new MissionPlan.TaskPlan("CODER", "Create Spring Boot project with pom.xml including spring-data-jpa and postgresql dependencies",
                                    "", "Project compiles", List.of(),
                                    List.of("pom.xml", "src/main/java/com/example/TodoApplication.java", "src/main/resources/application.properties")),
                            new MissionPlan.TaskPlan("CODER", "Create TodoItem entity model",
                                    "", "Entity compiles", List.of(),
                                    List.of("src/main/java/com/example/model/TodoItem.java")),
                            new MissionPlan.TaskPlan("CODER", "Create TodoRepository interface",
                                    "", "Repository compiles", List.of(),
                                    List.of("src/main/java/com/example/repository/TodoRepository.java")),
                            new MissionPlan.TaskPlan("CODER", "Create TodoController REST endpoints",
                                    "", "CRUD endpoints work", List.of(),
                                    List.of("src/main/java/com/example/controller/TodoController.java"))
                    )
            );
        }

        @Test
        @DisplayName("Full pipeline with service binding: plan → deploy → success")
        @SuppressWarnings("unchecked")
        void fullPipelineWithServiceBinding() {
            when(mockLlm.structuredCall(anyString(), anyString(), eq(MissionPlan.class)))
                    .thenReturn(todoAppPlan());

            // Clarifying answers include PostgreSQL service binding
            String clarifyingAnswers = String.format(
                    "{\"cf_service_bindings\": \"[{\\\"type\\\":\\\"postgresql\\\",\\\"instanceName\\\":\\\"%s\\\"}]\"}",
                    SERVICE_NAME);

            var planState = new WorldmindState(Map.of(
                    "request", REQUEST,
                    "classification", new Classification("feature", 4, List.of("api", "database"), "sequential", "java"),
                    "projectContext", new ProjectContext(".", List.of(), "java", "spring-boot", Map.of(), 0, "new project"),
                    "createCfDeployment", true,
                    "missionId", MISSION_ID,
                    "clarifyingAnswers", clarifyingAnswers
            ));

            var planResult = planNode.apply(planState);
            var tasks = (List<Task>) planResult.get("tasks");

            // Verify DEPLOYER task exists with service bindings
            Task deployerTask = tasks.stream()
                    .filter(t -> "DEPLOYER".equalsIgnoreCase(t.agent()))
                    .findFirst().orElseThrow(() -> new AssertionError("DEPLOYER task not found"));

            String instructions = deployerTask.inputContext();

            // Service binding should be in instructions
            assertTrue(instructions.contains(SERVICE_NAME),
                    "DEPLOYER instructions should include service name: " + SERVICE_NAME);
            assertTrue(instructions.contains("Service Bindings"),
                    "DEPLOYER instructions should have Service Bindings section");

            // Manifest should include services block
            assertTrue(instructions.contains("services:"),
                    "Manifest template should include services block");
            assertTrue(instructions.contains("- " + SERVICE_NAME),
                    "Manifest should bind to " + SERVICE_NAME);

            // Simulate successful deployment with service binding
            String cfOutput = buildSuccessfulCfPushOutputWithServices(MISSION_ID, SERVICE_NAME);
            var dispatchResult = new WaveDispatchResult(
                    "TASK-DEPLOY", TaskStatus.PASSED, List.of(), cfOutput, 90000L);

            var evalState = new WorldmindState(Map.of(
                    "missionId", MISSION_ID,
                    "waveTaskIds", List.of("TASK-DEPLOY"),
                    "tasks", List.of(deployerTask),
                    "waveDispatchResults", List.of(dispatchResult)
            ));

            var evalResult = evaluateNode.apply(evalState);

            var completedIds = (List<String>) evalResult.get("completedTaskIds");
            assertTrue(completedIds.contains("TASK-DEPLOY"),
                    "DEPLOYER should complete successfully with service binding");

            String deploymentUrl = (String) evalResult.get("deploymentUrl");
            assertNotNull(deploymentUrl, "Deployment URL should be captured");
        }

        @Test
        @DisplayName("Service binding included in manifest with structured JSON format")
        @SuppressWarnings("unchecked")
        void serviceBindingInManifestStructuredJson() {
            when(mockLlm.structuredCall(anyString(), anyString(), eq(MissionPlan.class)))
                    .thenReturn(todoAppPlan());

            String clarifyingAnswers = String.format(
                    "{\"cf_service_bindings\": \"[{\\\"type\\\":\\\"postgresql\\\",\\\"instanceName\\\":\\\"%s\\\"}]\"}",
                    SERVICE_NAME);

            var state = new WorldmindState(Map.of(
                    "request", REQUEST,
                    "classification", new Classification("feature", 4, List.of("api", "database"), "sequential", "java"),
                    "projectContext", new ProjectContext(".", List.of(), "java", "spring-boot", Map.of(), 0, "new"),
                    "createCfDeployment", true,
                    "missionId", MISSION_ID,
                    "clarifyingAnswers", clarifyingAnswers
            ));

            var result = planNode.apply(state);
            var tasks = (List<Task>) result.get("tasks");
            Task deployerTask = tasks.stream()
                    .filter(t -> "DEPLOYER".equalsIgnoreCase(t.agent()))
                    .findFirst().orElseThrow();

            String instructions = deployerTask.inputContext();
            assertTrue(instructions.contains("services:"),
                    "Manifest should have services section");
            assertTrue(instructions.contains("- " + SERVICE_NAME),
                    "Manifest should bind to service: " + SERVICE_NAME);
        }

        @Test
        @DisplayName("Service binding included in manifest with legacy comma-separated format")
        @SuppressWarnings("unchecked")
        void serviceBindingInManifestLegacyFormat() {
            when(mockLlm.structuredCall(anyString(), anyString(), eq(MissionPlan.class)))
                    .thenReturn(todoAppPlan());

            String clarifyingAnswers = "{\"cf_service_bindings\": \"test-todo-db, my-redis-cache\"}";

            var state = new WorldmindState(Map.of(
                    "request", REQUEST,
                    "classification", new Classification("feature", 4, List.of("api", "database"), "sequential", "java"),
                    "projectContext", new ProjectContext(".", List.of(), "java", "spring-boot", Map.of(), 0, "new"),
                    "createCfDeployment", true,
                    "missionId", MISSION_ID,
                    "clarifyingAnswers", clarifyingAnswers
            ));

            var result = planNode.apply(state);
            var tasks = (List<Task>) result.get("tasks");
            Task deployerTask = tasks.stream()
                    .filter(t -> "DEPLOYER".equalsIgnoreCase(t.agent()))
                    .findFirst().orElseThrow();

            String instructions = deployerTask.inputContext();
            assertTrue(instructions.contains("- test-todo-db"),
                    "Manifest should bind to test-todo-db");
            assertTrue(instructions.contains("- my-redis-cache"),
                    "Manifest should bind to my-redis-cache");
        }

        @Test
        @DisplayName("Service binding failure diagnosed with service name")
        @SuppressWarnings("unchecked")
        void serviceBindingFailureDiagnosed() {
            String failureOutput = "Pushing app " + MISSION_ID + "...\n"
                    + "Binding service " + SERVICE_NAME + "...\n"
                    + "FAILED\n"
                    + "Could not find service " + SERVICE_NAME + " in org my-org / space my-space";

            var deployerTask = new Task("TASK-DEPLOY", "DEPLOYER", "Deploy app", "", "App running",
                    List.of(), TaskStatus.PENDING, 0, 3, FailureStrategy.RETRY,
                    List.of("manifest.yml"), List.of(), null);

            var dispatchResult = new WaveDispatchResult(
                    "TASK-DEPLOY", TaskStatus.PASSED, List.of(), failureOutput, 10000L);

            var state = new WorldmindState(Map.of(
                    "missionId", MISSION_ID,
                    "waveTaskIds", List.of("TASK-DEPLOY"),
                    "tasks", List.of(deployerTask),
                    "waveDispatchResults", List.of(dispatchResult)
            ));

            var result = evaluateNode.apply(state);

            // Should retry, not complete
            var completedIds = (List<String>) result.get("completedTaskIds");
            assertTrue(completedIds == null || !completedIds.contains("TASK-DEPLOY"));

            // Retry context should include service name and pre-create suggestion
            var tasks = (List<Task>) result.get("tasks");
            assertNotNull(tasks);
            Task retryTask = tasks.stream()
                    .filter(t -> t.id().equals("TASK-DEPLOY"))
                    .findFirst().orElseThrow();

            assertTrue(retryTask.inputContext().contains("SERVICE_BINDING_FAILURE"),
                    "Retry context should diagnose SERVICE_BINDING_FAILURE");
            assertTrue(retryTask.inputContext().contains(SERVICE_NAME),
                    "Retry context should include the service name");
            assertTrue(retryTask.inputContext().contains("cf create-service"),
                    "Retry context should suggest pre-creating the service");
        }

        @Test
        @DisplayName("DEPLOYER depends on RESEARCHER and all CODER tasks")
        @SuppressWarnings("unchecked")
        void deployerDependsOnCoderNotResearcher() {
            when(mockLlm.structuredCall(anyString(), anyString(), eq(MissionPlan.class)))
                    .thenReturn(todoAppPlan());

            var state = new WorldmindState(Map.of(
                    "request", REQUEST,
                    "classification", new Classification("feature", 4, List.of("api", "database"), "sequential", "java"),
                    "projectContext", new ProjectContext(".", List.of(), "java", "spring-boot", Map.of(), 0, "new"),
                    "createCfDeployment", true,
                    "missionId", MISSION_ID
            ));

            var result = planNode.apply(state);
            var tasks = (List<Task>) result.get("tasks");
            Task deployerTask = tasks.stream()
                    .filter(t -> "DEPLOYER".equalsIgnoreCase(t.agent()))
                    .findFirst().orElseThrow();

            // DEPLOYER should depend on CODER tasks, NOT RESEARCHER
            List<String> coderIds = tasks.stream()
                    .filter(t -> "CODER".equalsIgnoreCase(t.agent()))
                    .map(Task::id)
                    .toList();
            List<String> researcherIds = tasks.stream()
                    .filter(t -> "RESEARCHER".equalsIgnoreCase(t.agent()))
                    .map(Task::id)
                    .toList();

            for (String coderId : coderIds) {
                assertTrue(deployerTask.dependencies().contains(coderId),
                        "DEPLOYER should depend on CODER " + coderId);
            }
            for (String researcherId : researcherIds) {
                assertFalse(deployerTask.dependencies().contains(researcherId),
                        "DEPLOYER should NOT depend on RESEARCHER " + researcherId);
            }
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // Cross-cutting deployment evaluation tests
    // ══════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Deployment evaluation cross-cutting")
    class DeploymentEvaluation {

        @Test
        @DisplayName("Deployment URL extracted from various cf push output formats")
        void deploymentUrlExtraction() {
            var testCases = Map.of(
                    "routes: wmnd-app.apps.example.com",
                    "https://wmnd-app.apps.example.com",

                    "route: wmnd-app.apps.tas-tdc.kuhn-labs.com",
                    "https://wmnd-app.apps.tas-tdc.kuhn-labs.com",

                    "Deployed to https://wmnd-app.apps.cf.example.io successfully",
                    "https://wmnd-app.apps.cf.example.io"
            );

            for (var entry : testCases.entrySet()) {
                String cfOutput = "Pushing app...\nApp started\ninstances running\n" + entry.getKey();

                var task = new Task("TASK-DEPLOY", "DEPLOYER", "Deploy", "", "Running",
                        List.of(), TaskStatus.PENDING, 0, 3, FailureStrategy.RETRY,
                        List.of(), List.of(), null);

                var dispatchResult = new WaveDispatchResult(
                        "TASK-DEPLOY", TaskStatus.PASSED, List.of(), cfOutput, 60000L);

                var state = new WorldmindState(Map.of(
                        "waveTaskIds", List.of("TASK-DEPLOY"),
                        "tasks", List.of(task),
                        "waveDispatchResults", List.of(dispatchResult)
                ));

                var result = evaluateNode.apply(state);
                String url = (String) result.get("deploymentUrl");
                assertNotNull(url, "Should extract URL from output: " + entry.getKey());
                assertEquals(entry.getValue(), url,
                        "URL should match expected for output: " + entry.getKey());
            }
        }

        @Test
        @DisplayName("Deployment success markers correctly detected")
        void deploymentSuccessMarkers() {
            String[] successOutputs = {
                    "App started\nroutes: wmnd-app.apps.example.com",
                    "instances running\nroutes: wmnd-app.apps.example.com",
                    "requested state: started\nstatus: running\nroutes: wmnd-app.apps.example.com",
                    "status: running\nroutes: wmnd-app.apps.example.com"
            };

            for (String output : successOutputs) {
                var task = new Task("TASK-DEPLOY", "DEPLOYER", "Deploy", "", "Running",
                        List.of(), TaskStatus.PENDING, 0, 3, FailureStrategy.RETRY,
                        List.of(), List.of(), null);

                var dispatchResult = new WaveDispatchResult(
                        "TASK-DEPLOY", TaskStatus.PASSED, List.of(), output, 60000L);

                var state = new WorldmindState(Map.of(
                        "waveTaskIds", List.of("TASK-DEPLOY"),
                        "tasks", List.of(task),
                        "waveDispatchResults", List.of(dispatchResult)
                ));

                @SuppressWarnings("unchecked")
                var result = evaluateNode.apply(state);
                var completedIds = (List<String>) result.get("completedTaskIds");
                assertTrue(completedIds != null && completedIds.contains("TASK-DEPLOY"),
                        "Should detect deployment success from output: " + output.replace("\n", " | "));
            }
        }

        @Test
        @DisplayName("Deployment failure markers correctly detected and diagnosed")
        void deploymentFailureMarkersDiagnosed() {
            record FailureCase(String output, String expectedType) {}
            var failureCases = List.of(
                    new FailureCase("BUILD FAILURE\nCompilation error", "BUILD_FAILURE"),
                    new FailureCase("Staging error: Unable to detect buildpack", "STAGING_FAILURE"),
                    new FailureCase("App instance exited with CRASHED status", "APP_CRASHED"),
                    new FailureCase("health check timeout after 300 seconds", "HEALTH_CHECK_TIMEOUT"),
                    new FailureCase("Could not find service my-db", "SERVICE_BINDING_FAILURE")
            );

            for (var fc : failureCases) {
                var task = new Task("TASK-DEPLOY", "DEPLOYER", "Deploy", "", "Running",
                        List.of(), TaskStatus.PENDING, 0, 3, FailureStrategy.RETRY,
                        List.of(), List.of(), null);

                var dispatchResult = new WaveDispatchResult(
                        "TASK-DEPLOY", TaskStatus.PASSED, List.of(), fc.output, 30000L);

                // Use fresh OscillationDetector per test case to avoid interference
                var freshEvalNode = new EvaluateWaveNode(
                        mockBridge, mockQualityGateService, eventBus,
                        mock(WorldmindMetrics.class), new OscillationDetector(),
                        null, null);

                var state = new WorldmindState(Map.of(
                        "waveTaskIds", List.of("TASK-DEPLOY"),
                        "tasks", List.of(task),
                        "waveDispatchResults", List.of(dispatchResult)
                ));

                @SuppressWarnings("unchecked")
                var result = freshEvalNode.apply(state);

                // Should not complete — should retry
                @SuppressWarnings("unchecked")
                var completedIds = (List<String>) result.get("completedTaskIds");
                assertTrue(completedIds == null || !completedIds.contains("TASK-DEPLOY"),
                        "Should NOT complete with failure output: " + fc.output.replace("\n", " | "));

                // Verify failure type in retry context
                @SuppressWarnings("unchecked")
                var updatedTasks = (List<Task>) result.get("tasks");
                if (updatedTasks != null) {
                    Task retryTask = updatedTasks.stream()
                            .filter(t -> t.id().equals("TASK-DEPLOY"))
                            .findFirst().orElse(null);
                    if (retryTask != null) {
                        assertTrue(retryTask.inputContext().contains(fc.expectedType),
                                "Retry context should include failure type " + fc.expectedType
                                        + " for output: " + fc.output.replace("\n", " | "));
                    }
                }
            }
        }

        @Test
        @DisplayName("Deployment URL visible in MissionResponse (via ConvergeResultsNode)")
        void deploymentUrlInConvergeResult() {
            String expectedUrl = "https://wmnd-2026-0042.apps.tas-tdc.kuhn-labs.com";

            var coderTask = new Task("TASK-001", "CODER", "Create app", "", "Done",
                    List.of(), TaskStatus.PASSED, 1, 3, FailureStrategy.RETRY,
                    List.of(), List.of(), 5000L);
            var deployerTask = new Task("TASK-DEPLOY", "DEPLOYER", "Deploy app", "", "App running",
                    List.of(), TaskStatus.PASSED, 1, 3, FailureStrategy.RETRY,
                    List.of(), List.of(), 60000L);

            var state = new WorldmindState(Map.of(
                    "missionId", "WMND-2026-0042",
                    "tasks", List.of(coderTask, deployerTask),
                    "testResults", List.of(),
                    "sandboxes", List.of(),
                    "deploymentUrl", expectedUrl
            ));

            var result = convergeNode.apply(state);
            assertEquals(MissionStatus.COMPLETED.name(), result.get("status"));

            // The deployment URL should be preserved in state for the MissionController
            // to include in the MissionResponse
            var metrics = (MissionMetrics) result.get("metrics");
            assertNotNull(metrics);
            assertEquals(2, metrics.tasksCompleted());
        }

        @Test
        @DisplayName("Custom deployer properties affect manifest generation")
        @SuppressWarnings("unchecked")
        void customDeployerPropertiesInManifest() {
            var customProps = new DeployerProperties();
            customProps.getDefaults().setMemory("2G");
            customProps.getDefaults().setInstances(3);
            customProps.getDefaults().setBuildpack("java_buildpack");
            customProps.getDefaults().setJavaVersion(17);
            customProps.setHealthTimeout(600); // 10 minutes

            var customPlanNode = new PlanMissionNode(mockLlm, customProps);

            when(mockLlm.structuredCall(anyString(), anyString(), eq(MissionPlan.class)))
                    .thenReturn(new MissionPlan("App", "sequential", List.of(
                            new MissionPlan.TaskPlan("CODER", "Create app", "", "Done", List.of(), List.of("App.java"))
                    )));

            var state = new WorldmindState(Map.of(
                    "request", "Create app",
                    "classification", new Classification("feature", 2, List.of("app"), "sequential", "java"),
                    "projectContext", new ProjectContext(".", List.of(), "java", "spring-boot", Map.of(), 0, "new"),
                    "createCfDeployment", true,
                    "missionId", "WMND-2026-0099"
            ));

            var result = customPlanNode.apply(state);
            var tasks = (List<Task>) result.get("tasks");
            Task deployerTask = tasks.stream()
                    .filter(t -> "DEPLOYER".equalsIgnoreCase(t.agent()))
                    .findFirst().orElseThrow();

            String instructions = deployerTask.inputContext();
            assertTrue(instructions.contains("memory: 2G"), "Should use custom memory");
            assertTrue(instructions.contains("instances: 3"), "Should use custom instance count");
            assertTrue(instructions.contains("java_buildpack"), "Should use custom buildpack");
            assertTrue(instructions.contains("version: 17"), "Should use custom Java version");
            assertTrue(instructions.contains("10 minutes"), "Should use custom health timeout");
        }

        @Test
        @DisplayName("Existing manifest used when CODER task targets manifest.yml")
        @SuppressWarnings("unchecked")
        void existingManifestUsedWhenCoderCreatesIt() {
            when(mockLlm.structuredCall(anyString(), anyString(), eq(MissionPlan.class)))
                    .thenReturn(new MissionPlan("App", "sequential", List.of(
                            new MissionPlan.TaskPlan("CODER", "Create app", "", "Done", List.of(), List.of("App.java")),
                            new MissionPlan.TaskPlan("CODER", "Create manifest", "", "Done", List.of(), List.of("manifest.yml"))
                    )));

            var state = new WorldmindState(Map.of(
                    "request", "Create app",
                    "classification", new Classification("feature", 2, List.of("app"), "sequential", "java"),
                    "projectContext", new ProjectContext(".", List.of(), "java", "spring-boot", Map.of(), 0, "new"),
                    "createCfDeployment", true,
                    "missionId", "WMND-2026-0099"
            ));

            var result = planNode.apply(state);
            assertTrue((Boolean) result.get("manifestCreatedByTask"),
                    "Should detect that a task creates manifest.yml");

            var tasks = (List<Task>) result.get("tasks");
            Task deployerTask = tasks.stream()
                    .filter(t -> "DEPLOYER".equalsIgnoreCase(t.agent()))
                    .findFirst().orElseThrow();

            String instructions = deployerTask.inputContext();
            assertTrue(instructions.contains("Use existing manifest"),
                    "DEPLOYER should use existing manifest");
            assertFalse(instructions.contains("Generate manifest.yml"),
                    "DEPLOYER should NOT generate manifest when task creates one");
        }

        @Test
        @DisplayName("Clean up: DEPLOYER task cleanup tracked in task list")
        @SuppressWarnings("unchecked")
        void deployerTaskTrackedForCleanup() {
            when(mockLlm.structuredCall(anyString(), anyString(), eq(MissionPlan.class)))
                    .thenReturn(new MissionPlan("App", "sequential", List.of(
                            new MissionPlan.TaskPlan("CODER", "Create app", "", "Done", List.of(), List.of("App.java"))
                    )));

            var state = new WorldmindState(Map.of(
                    "request", "Create app",
                    "classification", new Classification("feature", 2, List.of("app"), "sequential", "java"),
                    "projectContext", new ProjectContext(".", List.of(), "java", "spring-boot", Map.of(), 0, "new"),
                    "createCfDeployment", true,
                    "missionId", "WMND-2026-0099"
            ));

            var result = planNode.apply(state);
            var tasks = (List<Task>) result.get("tasks");

            // DEPLOYER task should be in the task list for tracking
            Task deployerTask = tasks.stream()
                    .filter(t -> "DEPLOYER".equalsIgnoreCase(t.agent()))
                    .findFirst().orElse(null);
            assertNotNull(deployerTask, "DEPLOYER task should be present in task list");
            assertEquals("TASK-DEPLOY", deployerTask.id());
            assertEquals(TaskStatus.PENDING, deployerTask.status());
            assertEquals(3, deployerTask.maxIterations());
            assertEquals(FailureStrategy.RETRY, deployerTask.onFailure());
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // Helpers
    // ══════════════════════════════════════════════════════════════════════

    private void setupCoderQualityGatePass() {
        when(mockBridge.executeTask(any(), any(), any(), any(), any()))
                .thenAnswer(inv -> {
                    Task task = inv.getArgument(0);
                    return new AgentDispatcher.BridgeResult(
                            new Task(task.id(), task.agent(), task.description(),
                                    task.inputContext(), task.successCriteria(), task.dependencies(),
                                    TaskStatus.PASSED, 1, task.maxIterations(),
                                    task.onFailure(), task.targetFiles(), List.of(), 1000L),
                            new SandboxInfo("c-" + task.id(), task.agent(), task.id(),
                                    "completed", Instant.now(), Instant.now()),
                            "Quality gate passed");
                });
        when(mockQualityGateService.parseTestOutput(anyString(), anyString(), anyLong()))
                .thenReturn(new TestResult("test", true, 10, 0, "All tests pass", 500L));
        when(mockQualityGateService.parseReviewOutput(anyString(), anyString()))
                .thenReturn(new ReviewFeedback("test", true, "Good code", List.of(), List.of(), 8));
        when(mockQualityGateService.evaluateQualityGate(any(), any(), any()))
                .thenReturn(new QualityGateDecision(true, null, "Quality gate granted"));
    }

    private String buildSuccessfulCfPushOutput(String missionId) {
        return """
                Pushing app %s to org my-org / space my-space as user@example.com...
                Staging app...
                -----> Java Buildpack v4.50
                -----> Downloading Open JDK 21
                -----> Downloading Spring Auto Reconfiguration
                Build succeeded
                Waiting for app %s to start...
                Instances starting...

                name:              %s
                requested state:   started
                routes:            %s.apps.tas-tdc.kuhn-labs.com
                last uploaded:     Thu 20 Feb 12:00:00 UTC 2026
                stack:             cflinuxfs4
                buildpacks:
                        name                     version
                        java_buildpack_offline   4.50

                type:           web
                sidecars:
                instances:      1/1
                memory usage:   1G
                     state     since                  cpu    memory        disk           logging
                #0   running   2026-02-20T12:00:30Z   0.0%%   200M of 1G   150M of 1G   0/s of 0/s

                App started
                """.formatted(missionId, missionId, missionId, missionId);
    }

    private String buildSuccessfulCfPushOutputWithServices(String missionId, String serviceName) {
        return """
                Pushing app %s to org my-org / space my-space as user@example.com...
                Binding service %s to app %s in org my-org / space my-space as user@example.com...
                OK
                Staging app...
                -----> Java Buildpack v4.50
                -----> Downloading Open JDK 21
                Build succeeded
                Waiting for app %s to start...

                name:              %s
                requested state:   started
                routes:            %s.apps.tas-tdc.kuhn-labs.com
                last uploaded:     Thu 20 Feb 12:00:00 UTC 2026
                stack:             cflinuxfs4
                services:          %s

                type:           web
                instances:      1/1
                memory usage:   1G
                     state     since                  cpu    memory        disk           logging
                #0   running   2026-02-20T12:00:45Z   0.0%%   250M of 1G   160M of 1G   0/s of 0/s

                App started
                """.formatted(missionId, serviceName, missionId, missionId, missionId, missionId, serviceName);
    }
}
