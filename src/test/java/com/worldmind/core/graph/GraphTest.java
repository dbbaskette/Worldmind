package com.worldmind.core.graph;

import com.worldmind.core.llm.LlmService;
import com.worldmind.core.model.Classification;
import com.worldmind.core.model.InteractionMode;
import com.worldmind.core.model.MissionMetrics;
import com.worldmind.core.model.MissionPlan;
import com.worldmind.core.model.MissionStatus;
import com.worldmind.core.model.ProjectContext;
import com.worldmind.core.nodes.ClassifyRequestNode;
import com.worldmind.core.nodes.ConvergeResultsNode;
import com.worldmind.core.nodes.DispatchCenturionNode;
import com.worldmind.core.nodes.EvaluateSealNode;
import com.worldmind.core.nodes.PlanMissionNode;
import com.worldmind.core.nodes.UploadContextNode;
import com.worldmind.core.scanner.ProjectScanner;
import com.worldmind.core.state.WorldmindState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
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

        // Mock LlmService to return a fixed MissionPlan for the PlanMissionNode
        when(mockLlm.structuredCall(anyString(), anyString(), eq(MissionPlan.class)))
                .thenReturn(new MissionPlan(
                        "Implement feature",
                        "sequential",
                        List.of(
                                new MissionPlan.DirectivePlan("FORGE", "Implement feature", "", "Feature works", List.of()),
                                new MissionPlan.DirectivePlan("VIGIL", "Review code", "", "Code quality ok", List.of("DIR-001"))
                        )
                ));

        // Create a mock ProjectScanner that returns a fixed ProjectContext
        ProjectScanner mockScanner = mock(ProjectScanner.class);
        when(mockScanner.scan(any(Path.class))).thenReturn(new ProjectContext(
                ".", List.of(), "unknown", "unknown", Map.of(), 0,
                "unknown project with 0 files"
        ));

        // Create a mock DispatchCenturionNode that advances currentDirectiveIndex
        DispatchCenturionNode mockDispatchNode = mock(DispatchCenturionNode.class);
        when(mockDispatchNode.apply(any(WorldmindState.class))).thenAnswer(invocation -> {
            WorldmindState state = invocation.getArgument(0);
            int nextIndex = state.currentDirectiveIndex() + 1;
            return Map.of(
                    "currentDirectiveIndex", nextIndex,
                    "status", MissionStatus.EXECUTING.name()
            );
        });

        // Create mocks for evaluate_seal and converge_results nodes
        EvaluateSealNode mockEvaluateSealNode = mock(EvaluateSealNode.class);
        when(mockEvaluateSealNode.apply(any(WorldmindState.class))).thenReturn(
                Map.of("sealGranted", true));

        ConvergeResultsNode mockConvergeNode = mock(ConvergeResultsNode.class);
        when(mockConvergeNode.apply(any(WorldmindState.class))).thenReturn(Map.of(
                "metrics", new MissionMetrics(1000, 1, 0, 1, 2, 1, 5, 5),
                "status", MissionStatus.COMPLETED.name()
        ));

        worldmindGraph = new WorldmindGraph(
                new ClassifyRequestNode(mockLlm),
                new UploadContextNode(mockScanner),
                new PlanMissionNode(mockLlm),
                mockDispatchNode,
                mockEvaluateSealNode,
                mockConvergeNode,
                null  // no checkpointer for these tests
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
    @DisplayName("Running graph with APPROVE_PLAN stops at plan (no dispatch)")
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
        // APPROVE_PLAN stops at await_approval → END, no dispatch
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
    @DisplayName("Running graph with FULL_AUTO skips approval and dispatches directly")
    void runWithFullAuto() throws Exception {
        var input = new HashMap<String, Object>();
        input.put("missionId", "test-mission-2");
        input.put("request", "Refactor logging");
        input.put("interactionMode", InteractionMode.FULL_AUTO.name());

        Optional<WorldmindState> result = worldmindGraph.getCompiledGraph().invoke(input);

        assertTrue(result.isPresent(), "Graph should produce a result");
        WorldmindState finalState = result.get();

        // In FULL_AUTO, the graph routes to dispatch_centurion directly
        // After dispatch loop completes, converge_results sets status to COMPLETED
        assertEquals(MissionStatus.COMPLETED, finalState.status());
        assertEquals(InteractionMode.FULL_AUTO, finalState.interactionMode());
        assertTrue(finalState.classification().isPresent());
        assertTrue(finalState.projectContext().isPresent());
        assertFalse(finalState.directives().isEmpty());
    }

    // ===================================================================
    //  Conditional routing logic
    // ===================================================================

    @Test
    @DisplayName("routeAfterPlan returns 'dispatch' for FULL_AUTO")
    void routeReturnsDispatchForFullAuto() {
        var state = new WorldmindState(
                Map.of("interactionMode", InteractionMode.FULL_AUTO.name())
        );
        assertEquals("dispatch", worldmindGraph.routeAfterPlan(state));
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

    // ===================================================================
    //  Seal routing logic
    // ===================================================================

    @Test
    @DisplayName("routeAfterSeal returns 'dispatch_centurion' when directives remain")
    void routeReturnsSealDispatchWhenDirectivesRemain() {
        var state = new WorldmindState(Map.of(
                "currentDirectiveIndex", 0,
                "directives", List.of("dir1", "dir2")
        ));
        assertEquals("dispatch_centurion", worldmindGraph.routeAfterSeal(state));
    }

    @Test
    @DisplayName("routeAfterSeal returns 'converge_results' when all directives dispatched")
    void routeReturnsConvergeWhenAllDispatched() {
        var state = new WorldmindState(Map.of(
                "currentDirectiveIndex", 2,
                "directives", List.of("dir1", "dir2")
        ));
        assertEquals("converge_results", worldmindGraph.routeAfterSeal(state));
    }

    @Test
    @DisplayName("routeAfterSeal returns 'converge_results' when no directives exist")
    void routeReturnsConvergeWhenNoDirectives() {
        var state = new WorldmindState(Map.of(
                "currentDirectiveIndex", 0
        ));
        assertEquals("converge_results", worldmindGraph.routeAfterSeal(state));
    }
}
