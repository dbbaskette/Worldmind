package com.worldmind.core.graph;

import com.worldmind.core.llm.LlmService;
import com.worldmind.core.model.Classification;
import com.worldmind.core.model.InteractionMode;
import com.worldmind.core.model.MissionStatus;
import com.worldmind.core.nodes.ClassifyRequestNode;
import com.worldmind.core.nodes.PlanMissionNode;
import com.worldmind.core.nodes.UploadContextNode;
import com.worldmind.core.state.WorldmindState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Tests for the LangGraph4j StateGraph skeleton.
 */
class GraphTest {

    private WorldmindGraph worldmindGraph;

    @BeforeEach
    void setUp() throws Exception {
        // Create a mock LlmService that returns a fixed Classification
        LlmService mockLlm = mock(LlmService.class);
        when(mockLlm.structuredCall(anyString(), anyString(), eq(Classification.class)))
                .thenReturn(new Classification("feature", 3, List.of("api"), "sequential"));

        worldmindGraph = new WorldmindGraph(
                new ClassifyRequestNode(mockLlm),
                new UploadContextNode(),
                new PlanMissionNode()
        );
    }

    // ===================================================================
    //  Compilation
    // ===================================================================

    @Test
    @DisplayName("Graph compiles without errors")
    void graphCompiles() {
        assertNotNull(worldmindGraph.getCompiledGraph(),
                "Compiled graph should not be null");
    }

    // ===================================================================
    //  Full run with APPROVE_PLAN (default) -- hits await_approval
    // ===================================================================

    @Test
    @DisplayName("Running graph with APPROVE_PLAN produces AWAITING_APPROVAL status")
    void runWithApprovePlan() throws Exception {
        var input = new HashMap<String, Object>();
        input.put("missionId", "test-mission-1");
        input.put("request", "Add a REST endpoint");
        input.put("interactionMode", InteractionMode.APPROVE_PLAN.name());

        Optional<WorldmindState> result = worldmindGraph.getCompiledGraph().invoke(input);

        assertTrue(result.isPresent(), "Graph should produce a result");
        WorldmindState finalState = result.get();

        assertEquals("test-mission-1", finalState.missionId());
        assertEquals("Add a REST endpoint", finalState.request());
        assertEquals(MissionStatus.AWAITING_APPROVAL, finalState.status());
        assertTrue(finalState.classification().isPresent(),
                "Classification should be set after classify_request");
        assertTrue(finalState.projectContext().isPresent(),
                "ProjectContext should be set after upload_context");
        assertFalse(finalState.directives().isEmpty(),
                "Directives should be populated after plan_mission");
        assertEquals("DIR-001", finalState.directives().get(0).id());
    }

    // ===================================================================
    //  Full run with FULL_AUTO -- skips await_approval
    // ===================================================================

    @Test
    @DisplayName("Running graph with FULL_AUTO skips approval and ends directly")
    void runWithFullAuto() throws Exception {
        var input = new HashMap<String, Object>();
        input.put("missionId", "test-mission-2");
        input.put("request", "Refactor logging");
        input.put("interactionMode", InteractionMode.FULL_AUTO.name());

        Optional<WorldmindState> result = worldmindGraph.getCompiledGraph().invoke(input);

        assertTrue(result.isPresent(), "Graph should produce a result");
        WorldmindState finalState = result.get();

        // In FULL_AUTO, the plan_mission node sets status to AWAITING_APPROVAL
        // but the graph routes to END directly, so status stays as set by plan_mission
        assertEquals(MissionStatus.AWAITING_APPROVAL, finalState.status());
        assertEquals(InteractionMode.FULL_AUTO, finalState.interactionMode());
        assertTrue(finalState.classification().isPresent());
        assertTrue(finalState.projectContext().isPresent());
        assertFalse(finalState.directives().isEmpty());
    }

    // ===================================================================
    //  Conditional routing logic
    // ===================================================================

    @Test
    @DisplayName("routeAfterPlan returns 'end' for FULL_AUTO")
    void routeReturnsEndForFullAuto() {
        var state = new WorldmindState(
                Map.of("interactionMode", InteractionMode.FULL_AUTO.name())
        );
        assertEquals("end", worldmindGraph.routeAfterPlan(state));
    }

    @Test
    @DisplayName("routeAfterPlan returns 'await_approval' for APPROVE_PLAN")
    void routeReturnsAwaitForApprovePlan() {
        var state = new WorldmindState(
                Map.of("interactionMode", InteractionMode.APPROVE_PLAN.name())
        );
        assertEquals("await_approval", worldmindGraph.routeAfterPlan(state));
    }

    @Test
    @DisplayName("routeAfterPlan returns 'await_approval' for STEP_BY_STEP")
    void routeReturnsAwaitForStepByStep() {
        var state = new WorldmindState(
                Map.of("interactionMode", InteractionMode.STEP_BY_STEP.name())
        );
        assertEquals("await_approval", worldmindGraph.routeAfterPlan(state));
    }

    // ===================================================================
    //  State progression through nodes
    // ===================================================================

    @Test
    @DisplayName("Classification is populated with expected values")
    void classificationPopulated() throws Exception {
        var input = new HashMap<String, Object>();
        input.put("request", "Test classification");

        Optional<WorldmindState> result = worldmindGraph.getCompiledGraph().invoke(input);

        assertTrue(result.isPresent());
        var classification = result.get().classification().orElseThrow();
        assertEquals("feature", classification.category());
        assertEquals(3, classification.complexity());
        assertEquals("sequential", classification.planningStrategy());
    }

    @Test
    @DisplayName("ProjectContext is populated with expected values")
    void projectContextPopulated() throws Exception {
        var input = new HashMap<String, Object>();
        input.put("request", "Test context upload");

        Optional<WorldmindState> result = worldmindGraph.getCompiledGraph().invoke(input);

        assertTrue(result.isPresent());
        var ctx = result.get().projectContext().orElseThrow();
        assertEquals(".", ctx.rootPath());
        assertEquals("unknown", ctx.language());
        assertEquals(0, ctx.fileCount());
    }

    @Test
    @DisplayName("Execution strategy is set to SEQUENTIAL")
    void executionStrategySet() throws Exception {
        var input = new HashMap<String, Object>();
        input.put("request", "Test strategy");

        Optional<WorldmindState> result = worldmindGraph.getCompiledGraph().invoke(input);

        assertTrue(result.isPresent());
        assertEquals("SEQUENTIAL",
                result.get().<String>value("executionStrategy").orElse(""));
    }
}
