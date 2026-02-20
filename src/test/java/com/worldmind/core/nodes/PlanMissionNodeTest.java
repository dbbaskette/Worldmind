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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link PlanMissionNode}.
 * <p>
 * Uses Mockito to mock {@link LlmService} so that no real LLM calls are made.
 */
class PlanMissionNodeTest {

    @Test
    @DisplayName("generates tasks from LLM plan with correct IDs and fields")
    void generatesTasksFromLlmPlan() {
        var mockLlm = mock(LlmService.class);
        var plan = new MissionPlan(
                "Add user endpoint",
                "sequential",
                List.of(
                        new MissionPlan.TaskPlan("RESEARCHER", "Research API", "", "Done", List.of(), List.of()),
                        new MissionPlan.TaskPlan("CODER", "Create model", "", "Model exists", List.of(), List.of()),
                        new MissionPlan.TaskPlan("CODER", "Create controller", "", "Controller works", List.of(), List.of())
                )
        );
        when(mockLlm.structuredCall(anyString(), anyString(), eq(MissionPlan.class))).thenReturn(plan);

        var node = new PlanMissionNode(mockLlm);
        var state = new WorldmindState(Map.of(
                "request", "Add user endpoint",
                "classification", new Classification("feature", 3, List.of("api"), "sequential", "java"),
                "projectContext", new ProjectContext(".", List.of(), "java", "maven", Map.of(), 10, "test")
        ));

        var result = node.apply(state);

        @SuppressWarnings("unchecked")
        var tasks = (List<Task>) result.get("tasks");
        assertEquals(3, tasks.size());
        assertEquals("TASK-001", tasks.get(0).id());
        assertEquals("RESEARCHER", tasks.get(0).agent());
        assertEquals(List.of(), tasks.get(0).dependencies());
        assertEquals("TASK-002", tasks.get(1).id());
        assertEquals("CODER", tasks.get(1).agent());
        // CODER depends on preceding RESEARCHER
        assertEquals(List.of("TASK-001"), tasks.get(1).dependencies());
        assertEquals("TASK-003", tasks.get(2).id());
        assertEquals("CODER", tasks.get(2).agent());
        assertEquals(List.of("TASK-001"), tasks.get(2).dependencies());
        assertEquals("SEQUENTIAL", result.get("executionStrategy"));
        assertEquals("AWAITING_APPROVAL", result.get("status"));
    }

    @Test
    @DisplayName("converts execution strategy to uppercase")
    void convertsExecutionStrategyToUppercase() {
        var mockLlm = mock(LlmService.class);
        var plan = new MissionPlan(
                "Refactor service layer",
                "parallel",
                List.of(
                        new MissionPlan.TaskPlan("REFACTORER", "Refactor service", "", "Service refactored", List.of(), List.of()),
                        new MissionPlan.TaskPlan("REVIEWER", "Review changes", "", "Review passed", List.of("TASK-001"), List.of())
                )
        );
        when(mockLlm.structuredCall(anyString(), anyString(), eq(MissionPlan.class))).thenReturn(plan);

        var node = new PlanMissionNode(mockLlm);
        var state = new WorldmindState(Map.of(
                "request", "Refactor service layer",
                "classification", new Classification("refactor", 4, List.of("service"), "parallel", "java"),
                "projectContext", new ProjectContext(".", List.of(), "java", "spring-boot", Map.of(), 50, "test")
        ));

        var result = node.apply(state);
        assertEquals("PARALLEL", result.get("executionStrategy"));
    }

    @Test
    @DisplayName("sets all task defaults correctly (iteration, maxIterations, failureStrategy)")
    void setsTaskDefaults() {
        var mockLlm = mock(LlmService.class);
        var plan = new MissionPlan(
                "Write tests",
                "sequential",
                List.of(
                        new MissionPlan.TaskPlan("TESTER", "Write unit tests", "context", "All pass", List.of(), List.of())
                )
        );
        when(mockLlm.structuredCall(anyString(), anyString(), eq(MissionPlan.class))).thenReturn(plan);

        var node = new PlanMissionNode(mockLlm);
        var state = new WorldmindState(Map.of(
                "request", "Write tests",
                "classification", new Classification("test", 2, List.of("test"), "sequential", "java"),
                "projectContext", new ProjectContext(".", List.of(), "java", "maven", Map.of(), 5, "test")
        ));

        var result = node.apply(state);

        @SuppressWarnings("unchecked")
        var tasks = (List<Task>) result.get("tasks");
        var task = tasks.get(0);
        assertEquals(0, task.iteration());
        assertEquals(3, task.maxIterations());
        assertEquals(FailureStrategy.RETRY, task.onFailure());
        assertEquals(List.of(), task.filesAffected());
        assertNull(task.elapsedMs());
        assertEquals("context", task.inputContext());
        assertEquals("All pass", task.successCriteria());
    }

    @Test
    @DisplayName("assigns deterministic type-based dependencies regardless of LLM output")
    void assignsTypeDependencies() {
        var mockLlm = mock(LlmService.class);
        var plan = new MissionPlan(
                "Full feature",
                "parallel",
                List.of(
                        // LLM dependencies are ignored — system builds them from agent types
                        new MissionPlan.TaskPlan("RESEARCHER", "Research API", "", "Research done", List.of(), List.of()),
                        new MissionPlan.TaskPlan("CODER", "Implement feature", "", "Feature works", List.of("garbage_dep"), List.of()),
                        new MissionPlan.TaskPlan("TESTER", "Write tests", "", "Tests pass", List.of("also_garbage"), List.of()),
                        new MissionPlan.TaskPlan("REVIEWER", "Final review", "", "Approved", List.of("invalid_ref"), List.of())
                )
        );
        when(mockLlm.structuredCall(anyString(), anyString(), eq(MissionPlan.class))).thenReturn(plan);

        var node = new PlanMissionNode(mockLlm);
        var state = new WorldmindState(Map.of(
                "request", "Build full feature",
                "classification", new Classification("feature", 5, List.of("api", "service", "model"), "parallel", "base"),
                "projectContext", new ProjectContext(".", List.of(), "kotlin", "spring-boot", Map.of(), 100, "test")
        ));

        var result = node.apply(state);

        @SuppressWarnings("unchecked")
        var tasks = (List<Task>) result.get("tasks");
        // RESEARCHER: no deps
        assertEquals(List.of(), tasks.get(0).dependencies());
        // CODER: depends on preceding RESEARCHER (TASK-001)
        assertEquals(List.of("TASK-001"), tasks.get(1).dependencies());
        // TESTER: depends on preceding CODER (TASK-002)
        assertEquals(List.of("TASK-002"), tasks.get(2).dependencies());
        // REVIEWER: depends on preceding CODER (TASK-002)
        assertEquals(List.of("TASK-002"), tasks.get(3).dependencies());
    }

    @Test
    @DisplayName("passes request and classification details to LLM prompt")
    void passesContextToLlmPrompt() {
        var mockLlm = mock(LlmService.class);
        var plan = new MissionPlan(
                "Stub",
                "sequential",
                List.of(
                        new MissionPlan.TaskPlan("REVIEWER", "Review", "", "Done", List.of(), List.of())
                )
        );
        when(mockLlm.structuredCall(anyString(), anyString(), eq(MissionPlan.class))).thenReturn(plan);

        var node = new PlanMissionNode(mockLlm);
        var state = new WorldmindState(Map.of(
                "request", "Fix login bug",
                "classification", new Classification("bugfix", 2, List.of("auth", "service"), "sequential", "java"),
                "projectContext", new ProjectContext(".", List.of(), "java", "spring-boot", Map.of(), 25, "test")
        ));

        node.apply(state);

        verify(mockLlm).structuredCall(
                anyString(),
                argThat(prompt ->
                        prompt.contains("Fix login bug") &&
                        prompt.contains("bugfix") &&
                        prompt.contains("auth, service") &&
                        prompt.contains("java") &&
                        prompt.contains("spring-boot") &&
                        prompt.contains("25")
                ),
                eq(MissionPlan.class)
        );
    }

    @Test
    @DisplayName("throws when classification is missing from state")
    void throwsWhenClassificationMissing() {
        var mockLlm = mock(LlmService.class);
        var node = new PlanMissionNode(mockLlm);
        var state = new WorldmindState(Map.of(
                "request", "Do something",
                "projectContext", new ProjectContext(".", List.of(), "java", "maven", Map.of(), 5, "test")
        ));

        assertThrows(IllegalStateException.class, () -> node.apply(state));
    }

    @Test
    @DisplayName("throws when projectContext is missing from state")
    void throwsWhenProjectContextMissing() {
        var mockLlm = mock(LlmService.class);
        var node = new PlanMissionNode(mockLlm);
        var state = new WorldmindState(Map.of(
                "request", "Do something",
                "classification", new Classification("feature", 1, List.of(), "sequential", "base")
        ));

        assertThrows(IllegalStateException.class, () -> node.apply(state));
    }

    @Test
    @DisplayName("result map contains exactly tasks, executionStrategy, and status keys")
    void resultContainsExpectedKeys() {
        var mockLlm = mock(LlmService.class);
        var plan = new MissionPlan(
                "Test objective",
                "sequential",
                List.of(
                        new MissionPlan.TaskPlan("REVIEWER", "Review", "", "OK", List.of(), List.of())
                )
        );
        when(mockLlm.structuredCall(anyString(), anyString(), eq(MissionPlan.class))).thenReturn(plan);

        var node = new PlanMissionNode(mockLlm);
        var state = new WorldmindState(Map.of(
                "request", "Test",
                "classification", new Classification("test", 1, List.of(), "sequential", "java"),
                "projectContext", new ProjectContext(".", List.of(), "java", "maven", Map.of(), 1, "test")
        ));

        var result = node.apply(state);

        assertEquals(3, result.size());
        assertTrue(result.containsKey("tasks"));
        assertTrue(result.containsKey("executionStrategy"));
        assertTrue(result.containsKey("status"));
    }

    @Test
    @DisplayName("includes component details, edge cases, and out-of-scope assumptions in LLM prompt")
    void includesNewSpecFieldsInPrompt() {
        var mockLlm = mock(LlmService.class);
        var plan = new MissionPlan(
                "Stub",
                "sequential",
                List.of(
                        new MissionPlan.TaskPlan("REVIEWER", "Review", "", "Done", List.of(), List.of())
                )
        );
        when(mockLlm.structuredCall(anyString(), anyString(), eq(MissionPlan.class))).thenReturn(plan);

        var spec = new ProductSpec(
                "Auth Feature", "Add authentication",
                List.of("Secure login"), List.of("No SSO"),
                List.of("Spring Security"), List.of("Login works"),
                List.of(new ProductSpec.ComponentSpec(
                        "LoginController",
                        "Handles login requests",
                        List.of("src/main/java/LoginController.java"),
                        List.of("Returns 200 on valid credentials"),
                        List.of("UserService", "TokenProvider")
                )),
                List.of("Empty password returns 400"),
                List.of("Database is already configured")
        );

        var node = new PlanMissionNode(mockLlm);
        var state = new WorldmindState(Map.of(
                "request", "Add authentication",
                "classification", new Classification("feature", 3, List.of("auth"), "sequential", "java"),
                "projectContext", new ProjectContext(".", List.of(), "java", "spring-boot", Map.of(), 25, "test"),
                "productSpec", spec
        ));

        node.apply(state);

        verify(mockLlm).structuredCall(
                anyString(),
                argThat(prompt ->
                        prompt.contains("LoginController") &&
                        prompt.contains("Handles login requests") &&
                        prompt.contains("src/main/java/LoginController.java") &&
                        prompt.contains("Returns 200 on valid credentials") &&
                        prompt.contains("UserService") &&
                        prompt.contains("Empty password returns 400") &&
                        prompt.contains("Database is already configured")
                ),
                eq(MissionPlan.class)
        );
    }

    @Test
    @DisplayName("injects CODER task when LLM plan contains only RESEARCHER and REVIEWER")
    void injectsCoderWhenMissing() {
        var mockLlm = mock(LlmService.class);
        var plan = new MissionPlan(
                "Build feature",
                "sequential",
                List.of(
                        new MissionPlan.TaskPlan("RESEARCHER", "Research codebase", "", "Research done", List.of(), List.of()),
                        new MissionPlan.TaskPlan("REVIEWER", "Review code", "", "Review done", List.of(), List.of())
                )
        );
        when(mockLlm.structuredCall(anyString(), anyString(), eq(MissionPlan.class))).thenReturn(plan);

        var node = new PlanMissionNode(mockLlm);
        var state = new WorldmindState(Map.of(
                "request", "Build a pacman game",
                "classification", new Classification("feature", 3, List.of("ui"), "sequential", "base"),
                "projectContext", new ProjectContext(".", List.of(), "html", "none", Map.of(), 5, "test")
        ));

        var result = node.apply(state);

        @SuppressWarnings("unchecked")
        var tasks = (List<Task>) result.get("tasks");
        assertEquals(3, tasks.size());
        assertEquals("RESEARCHER", tasks.get(0).agent());
        assertEquals("CODER", tasks.get(1).agent());
        assertEquals("REVIEWER", tasks.get(2).agent());
        // RESEARCHER: no deps
        assertEquals(List.of(), tasks.get(0).dependencies());
        // CODER: depends on preceding RESEARCHER
        assertEquals(List.of("TASK-001"), tasks.get(1).dependencies());
        // REVIEWER: depends on preceding CODER (TASK-003 is the injected CODER)
        assertEquals(List.of("TASK-003"), tasks.get(2).dependencies());
    }
}
