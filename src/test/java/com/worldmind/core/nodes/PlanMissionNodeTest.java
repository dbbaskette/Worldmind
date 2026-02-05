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
    @DisplayName("generates directives from LLM plan with correct IDs and fields")
    void generatesDirectivesFromLlmPlan() {
        var mockLlm = mock(LlmService.class);
        var plan = new MissionPlan(
                "Add user endpoint",
                "sequential",
                List.of(
                        new MissionPlan.DirectivePlan("FORGE", "Create model", "", "Model exists", List.of()),
                        new MissionPlan.DirectivePlan("FORGE", "Create controller", "", "Controller works", List.of("DIR-001")),
                        new MissionPlan.DirectivePlan("GAUNTLET", "Write tests", "", "Tests pass", List.of("DIR-001", "DIR-002")),
                        new MissionPlan.DirectivePlan("VIGIL", "Review code", "", "Code quality ok", List.of("DIR-003"))
                )
        );
        when(mockLlm.structuredCall(anyString(), anyString(), eq(MissionPlan.class))).thenReturn(plan);

        var node = new PlanMissionNode(mockLlm);
        var state = new WorldmindState(Map.of(
                "request", "Add user endpoint",
                "classification", new Classification("feature", 3, List.of("api"), "sequential"),
                "projectContext", new ProjectContext(".", List.of(), "java", "maven", Map.of(), 10, "test")
        ));

        var result = node.apply(state);

        @SuppressWarnings("unchecked")
        var directives = (List<Directive>) result.get("directives");
        assertEquals(4, directives.size());
        assertEquals("DIR-001", directives.get(0).id());
        assertEquals("FORGE", directives.get(0).centurion());
        assertEquals("Create model", directives.get(0).description());
        assertEquals(DirectiveStatus.PENDING, directives.get(0).status());
        assertEquals("DIR-002", directives.get(1).id());
        assertEquals("DIR-003", directives.get(2).id());
        assertEquals("DIR-004", directives.get(3).id());
        assertEquals("VIGIL", directives.get(3).centurion());
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
                        new MissionPlan.DirectivePlan("PRISM", "Refactor service", "", "Service refactored", List.of()),
                        new MissionPlan.DirectivePlan("VIGIL", "Review changes", "", "Review passed", List.of("DIR-001"))
                )
        );
        when(mockLlm.structuredCall(anyString(), anyString(), eq(MissionPlan.class))).thenReturn(plan);

        var node = new PlanMissionNode(mockLlm);
        var state = new WorldmindState(Map.of(
                "request", "Refactor service layer",
                "classification", new Classification("refactor", 4, List.of("service"), "parallel"),
                "projectContext", new ProjectContext(".", List.of(), "java", "spring-boot", Map.of(), 50, "test")
        ));

        var result = node.apply(state);
        assertEquals("PARALLEL", result.get("executionStrategy"));
    }

    @Test
    @DisplayName("sets all directive defaults correctly (iteration, maxIterations, failureStrategy)")
    void setsDirectiveDefaults() {
        var mockLlm = mock(LlmService.class);
        var plan = new MissionPlan(
                "Write tests",
                "sequential",
                List.of(
                        new MissionPlan.DirectivePlan("GAUNTLET", "Write unit tests", "context", "All pass", List.of())
                )
        );
        when(mockLlm.structuredCall(anyString(), anyString(), eq(MissionPlan.class))).thenReturn(plan);

        var node = new PlanMissionNode(mockLlm);
        var state = new WorldmindState(Map.of(
                "request", "Write tests",
                "classification", new Classification("test", 2, List.of("test"), "sequential"),
                "projectContext", new ProjectContext(".", List.of(), "java", "maven", Map.of(), 5, "test")
        ));

        var result = node.apply(state);

        @SuppressWarnings("unchecked")
        var directives = (List<Directive>) result.get("directives");
        var directive = directives.get(0);
        assertEquals(0, directive.iteration());
        assertEquals(3, directive.maxIterations());
        assertEquals(FailureStrategy.RETRY, directive.onFailure());
        assertEquals(List.of(), directive.filesAffected());
        assertNull(directive.elapsedMs());
        assertEquals("context", directive.inputContext());
        assertEquals("All pass", directive.successCriteria());
    }

    @Test
    @DisplayName("preserves dependencies from plan")
    void preservesDependencies() {
        var mockLlm = mock(LlmService.class);
        var plan = new MissionPlan(
                "Full feature",
                "adaptive",
                List.of(
                        new MissionPlan.DirectivePlan("PULSE", "Research API", "", "Research done", List.of()),
                        new MissionPlan.DirectivePlan("FORGE", "Implement feature", "", "Feature works", List.of("DIR-001")),
                        new MissionPlan.DirectivePlan("GAUNTLET", "Write tests", "", "Tests pass", List.of("DIR-001", "DIR-002")),
                        new MissionPlan.DirectivePlan("VIGIL", "Final review", "", "Approved", List.of("DIR-002", "DIR-003"))
                )
        );
        when(mockLlm.structuredCall(anyString(), anyString(), eq(MissionPlan.class))).thenReturn(plan);

        var node = new PlanMissionNode(mockLlm);
        var state = new WorldmindState(Map.of(
                "request", "Build full feature",
                "classification", new Classification("feature", 5, List.of("api", "service", "model"), "adaptive"),
                "projectContext", new ProjectContext(".", List.of(), "kotlin", "spring-boot", Map.of(), 100, "test")
        ));

        var result = node.apply(state);

        @SuppressWarnings("unchecked")
        var directives = (List<Directive>) result.get("directives");
        assertEquals(List.of(), directives.get(0).dependencies());
        assertEquals(List.of("DIR-001"), directives.get(1).dependencies());
        assertEquals(List.of("DIR-001", "DIR-002"), directives.get(2).dependencies());
        assertEquals(List.of("DIR-002", "DIR-003"), directives.get(3).dependencies());
    }

    @Test
    @DisplayName("passes request and classification details to LLM prompt")
    void passesContextToLlmPrompt() {
        var mockLlm = mock(LlmService.class);
        var plan = new MissionPlan(
                "Stub",
                "sequential",
                List.of(
                        new MissionPlan.DirectivePlan("VIGIL", "Review", "", "Done", List.of())
                )
        );
        when(mockLlm.structuredCall(anyString(), anyString(), eq(MissionPlan.class))).thenReturn(plan);

        var node = new PlanMissionNode(mockLlm);
        var state = new WorldmindState(Map.of(
                "request", "Fix login bug",
                "classification", new Classification("bugfix", 2, List.of("auth", "service"), "sequential"),
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
                "classification", new Classification("feature", 1, List.of(), "sequential")
        ));

        assertThrows(IllegalStateException.class, () -> node.apply(state));
    }

    @Test
    @DisplayName("result map contains exactly directives, executionStrategy, and status keys")
    void resultContainsExpectedKeys() {
        var mockLlm = mock(LlmService.class);
        var plan = new MissionPlan(
                "Test objective",
                "sequential",
                List.of(
                        new MissionPlan.DirectivePlan("VIGIL", "Review", "", "OK", List.of())
                )
        );
        when(mockLlm.structuredCall(anyString(), anyString(), eq(MissionPlan.class))).thenReturn(plan);

        var node = new PlanMissionNode(mockLlm);
        var state = new WorldmindState(Map.of(
                "request", "Test",
                "classification", new Classification("test", 1, List.of(), "sequential"),
                "projectContext", new ProjectContext(".", List.of(), "java", "maven", Map.of(), 1, "test")
        ));

        var result = node.apply(state);

        assertEquals(3, result.size());
        assertTrue(result.containsKey("directives"));
        assertTrue(result.containsKey("executionStrategy"));
        assertTrue(result.containsKey("status"));
    }
}
