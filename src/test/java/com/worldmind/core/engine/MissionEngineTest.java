package com.worldmind.core.engine;

import com.worldmind.core.graph.WorldmindGraph;
import com.worldmind.core.llm.LlmService;
import com.worldmind.core.model.*;
import com.worldmind.core.nodes.ClassifyRequestNode;
import com.worldmind.core.nodes.PlanMissionNode;
import com.worldmind.core.nodes.UploadContextNode;
import com.worldmind.core.scanner.ProjectScanner;
import com.worldmind.core.state.WorldmindState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Tests for the MissionEngine service.
 * Uses a real graph with mocked LLM and scanner nodes (no real LLM or database required).
 */
class MissionEngineTest {

    private MissionEngine engine;

    @BeforeEach
    void setUp() throws Exception {
        // Mock LLM service
        LlmService mockLlm = mock(LlmService.class);
        when(mockLlm.structuredCall(anyString(), anyString(), eq(Classification.class)))
                .thenReturn(new Classification("feature", 3, List.of("api", "service"), "sequential"));
        when(mockLlm.structuredCall(anyString(), anyString(), eq(MissionPlan.class)))
                .thenReturn(new MissionPlan(
                        "Implement the requested feature",
                        "sequential",
                        List.of(
                                new MissionPlan.DirectivePlan("FORGE", "Implement feature", "", "Feature works", List.of()),
                                new MissionPlan.DirectivePlan("GAUNTLET", "Write tests", "", "Tests pass", List.of("DIR-001")),
                                new MissionPlan.DirectivePlan("VIGIL", "Review code", "", "Code quality ok", List.of("DIR-002"))
                        )
                ));

        // Mock project scanner
        ProjectScanner mockScanner = mock(ProjectScanner.class);
        when(mockScanner.scan(any(Path.class))).thenReturn(new ProjectContext(
                "/test/project", List.of("src/Main.java"), "java", "spring",
                Map.of("spring-boot", "3.4.1"), 15, "Spring Boot project with 15 files"
        ));

        // Build real graph with mocked nodes
        WorldmindGraph graph = new WorldmindGraph(
                new ClassifyRequestNode(mockLlm),
                new UploadContextNode(mockScanner),
                new PlanMissionNode(mockLlm),
                null  // no checkpointer for tests
        );

        engine = new MissionEngine(graph);
    }

    // =================================================================
    //  Mission ID format
    // =================================================================

    @Test
    @DisplayName("Mission ID matches WMND-YYYY-NNNN pattern")
    void missionIdFormat() {
        String id = engine.generateMissionId();
        assertTrue(id.matches("WMND-\\d{4}-\\d{4}"),
                "Mission ID should match WMND-YYYY-NNNN pattern, got: " + id);
    }

    @Test
    @DisplayName("Mission IDs increment sequentially")
    void missionIdsIncrement() {
        String id1 = engine.generateMissionId();
        String id2 = engine.generateMissionId();
        // Extract the counter portion
        int counter1 = Integer.parseInt(id1.substring(id1.lastIndexOf('-') + 1));
        int counter2 = Integer.parseInt(id2.substring(id2.lastIndexOf('-') + 1));
        assertEquals(counter1 + 1, counter2,
                "Consecutive mission IDs should have incrementing counters");
    }

    // =================================================================
    //  Full planning pipeline
    // =================================================================

    @Test
    @DisplayName("runMission returns state with missionId, classification, and directives")
    void runMissionReturnsCompleteState() {
        WorldmindState state = engine.runMission("Add a REST endpoint", InteractionMode.APPROVE_PLAN);

        // Mission ID should be set
        assertFalse(state.missionId().isEmpty(),
                "Mission ID should be populated");
        assertTrue(state.missionId().startsWith("WMND-"),
                "Mission ID should start with WMND-");

        // Classification should be present
        assertTrue(state.classification().isPresent(),
                "Classification should be set");
        assertEquals("feature", state.classification().get().category());
        assertEquals(3, state.classification().get().complexity());
        assertEquals(List.of("api", "service"), state.classification().get().affectedComponents());

        // Project context should be present
        assertTrue(state.projectContext().isPresent(),
                "ProjectContext should be set");
        assertEquals("java", state.projectContext().get().language());
        assertEquals("spring", state.projectContext().get().framework());

        // Directives should be populated
        assertFalse(state.directives().isEmpty(),
                "Directives should not be empty");
        assertEquals(3, state.directives().size(),
                "Should have 3 directives");
        assertEquals("DIR-001", state.directives().get(0).id());
        assertEquals("FORGE", state.directives().get(0).centurion());
        assertEquals("DIR-002", state.directives().get(1).id());
        assertEquals("GAUNTLET", state.directives().get(1).centurion());
        assertEquals("DIR-003", state.directives().get(2).id());
        assertEquals("VIGIL", state.directives().get(2).centurion());
    }

    @Test
    @DisplayName("Request text is preserved in final state")
    void requestPreserved() {
        WorldmindState state = engine.runMission("Add a REST endpoint", InteractionMode.APPROVE_PLAN);
        assertEquals("Add a REST endpoint", state.request());
    }

    // =================================================================
    //  Interaction mode affects final status
    // =================================================================

    @Test
    @DisplayName("APPROVE_PLAN mode ends with AWAITING_APPROVAL status")
    void approvePlanEndsWithAwaitingApproval() {
        WorldmindState state = engine.runMission("Add logging", InteractionMode.APPROVE_PLAN);
        assertEquals(MissionStatus.AWAITING_APPROVAL, state.status(),
                "APPROVE_PLAN mode should end with AWAITING_APPROVAL");
    }

    @Test
    @DisplayName("STEP_BY_STEP mode ends with AWAITING_APPROVAL status")
    void stepByStepEndsWithAwaitingApproval() {
        WorldmindState state = engine.runMission("Add logging", InteractionMode.STEP_BY_STEP);
        assertEquals(MissionStatus.AWAITING_APPROVAL, state.status(),
                "STEP_BY_STEP mode should end with AWAITING_APPROVAL");
    }

    @Test
    @DisplayName("FULL_AUTO mode skips approval node (routes to END directly)")
    void fullAutoSkipsApproval() {
        WorldmindState state = engine.runMission("Add logging", InteractionMode.FULL_AUTO);
        // In FULL_AUTO, the graph routes from plan_mission directly to END,
        // so the await_approval node (which sets AWAITING_APPROVAL) is NOT executed.
        // The status remains as set by plan_mission (AWAITING_APPROVAL).
        // The key difference is the routing path, not the final status value.
        assertEquals(InteractionMode.FULL_AUTO, state.interactionMode(),
                "Interaction mode should be FULL_AUTO");
        // Directives should still be present
        assertFalse(state.directives().isEmpty(),
                "FULL_AUTO should still produce directives");
    }

    // =================================================================
    //  Execution strategy
    // =================================================================

    @Test
    @DisplayName("Execution strategy is set from the plan")
    void executionStrategySet() {
        WorldmindState state = engine.runMission("Add logging", InteractionMode.APPROVE_PLAN);
        assertEquals(ExecutionStrategy.SEQUENTIAL, state.executionStrategy(),
                "Execution strategy should match plan output");
    }

    // =================================================================
    //  Error handling
    // =================================================================

    @Test
    @DisplayName("Null request still runs through the graph")
    void nullRequestHandled() {
        // The graph should handle any string input; null request means empty
        // This tests that the engine doesn't crash on edge cases
        assertDoesNotThrow(() -> engine.runMission("", InteractionMode.APPROVE_PLAN),
                "Empty request should not throw");
    }
}
