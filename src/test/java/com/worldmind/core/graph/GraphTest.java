package com.worldmind.core.graph;

import com.worldmind.core.llm.LlmService;
import com.worldmind.core.model.*;
import com.worldmind.core.nodes.*;
import com.worldmind.core.scanner.ProjectScanner;
import com.worldmind.core.state.WorldmindState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Tests for the LangGraph4j StateGraph skeleton (Phase 4 wave-based topology).
 */
class GraphTest {

    private WorldmindGraph worldmindGraph;

    @BeforeEach
    void setUp() throws Exception {
        LlmService mockLlm = mock(LlmService.class);
        when(mockLlm.structuredCall(anyString(), anyString(), eq(Classification.class)))
                .thenReturn(new Classification("feature", 3, List.of("api"), "sequential", "java"));

        when(mockLlm.structuredCall(anyString(), anyString(), eq(MissionPlan.class)))
                .thenReturn(new MissionPlan(
                        "Implement feature",
                        "sequential",
                        List.of(
                                new MissionPlan.TaskPlan("CODER", "Implement feature", "", "Feature works", List.of(), List.of()),
                                new MissionPlan.TaskPlan("REVIEWER", "Review code", "", "Code quality ok", List.of("TASK-001"), List.of())
                        )
                ));
        when(mockLlm.structuredCall(anyString(), anyString(), eq(ProductSpec.class)))
                .thenReturn(new ProductSpec(
                        "Test Spec", "Overview", List.of("Goal 1"), List.of("Non-goal 1"),
                        List.of("Req 1"), List.of("Criterion 1"),
                        List.of(), List.of(), List.of()
                ));

        ProjectScanner mockScanner = mock(ProjectScanner.class);
        when(mockScanner.scan(any(Path.class))).thenReturn(new ProjectContext(
                ".", List.of(), "unknown", "unknown", Map.of(), 0,
                "unknown project with 0 files"
        ));

        // Mock ScheduleWaveNode: first call returns wave with IDs, subsequent calls return empty
        ScheduleWaveNode mockScheduleWave = mock(ScheduleWaveNode.class);
        when(mockScheduleWave.apply(any(WorldmindState.class))).thenAnswer(invocation -> {
            WorldmindState state = invocation.getArgument(0);
            var completedIds = new HashSet<>(state.completedTaskIds());
            var tasks = state.tasks();
            // Find first non-completed task
            for (var d : tasks) {
                if (!completedIds.contains(d.id())) {
                    return Map.of(
                            "waveTaskIds", List.of(d.id()),
                            "waveCount", state.waveCount() + 1,
                            "status", MissionStatus.EXECUTING.name()
                    );
                }
            }
            // All done
            return Map.of(
                    "waveTaskIds", List.of(),
                    "waveCount", state.waveCount() + 1,
                    "status", MissionStatus.EXECUTING.name()
            );
        });

        // Mock ParallelDispatchNode
        ParallelDispatchNode mockParallelDispatch = mock(ParallelDispatchNode.class);
        when(mockParallelDispatch.apply(any(WorldmindState.class))).thenAnswer(invocation -> {
            WorldmindState state = invocation.getArgument(0);
            var waveIds = state.waveTaskIds();
            var results = new ArrayList<WaveDispatchResult>();
            for (var id : waveIds) {
                results.add(new WaveDispatchResult(id, TaskStatus.PASSED, List.of(), "OK", 100L));
            }
            return Map.of(
                    "waveDispatchResults", results,
                    "sandboxes", List.of(),
                    "status", MissionStatus.EXECUTING.name()
            );
        });

        // Mock EvaluateWaveNode: marks all dispatched tasks as completed
        EvaluateWaveNode mockEvaluateWave = mock(EvaluateWaveNode.class);
        when(mockEvaluateWave.apply(any(WorldmindState.class))).thenAnswer(invocation -> {
            WorldmindState state = invocation.getArgument(0);
            var waveIds = state.waveTaskIds();
            return Map.of("completedTaskIds", new ArrayList<>(waveIds));
        });

        ConvergeResultsNode mockConvergeNode = mock(ConvergeResultsNode.class);
        when(mockConvergeNode.apply(any(WorldmindState.class))).thenReturn(Map.of(
                "metrics", new MissionMetrics(1000, 1, 0, 1, 2, 1, 5, 5),
                "status", MissionStatus.COMPLETED.name()
        ));

        worldmindGraph = new WorldmindGraph(
                new ClassifyRequestNode(mockLlm, null),
                new UploadContextNode(mockScanner),
                new GenerateClarifyingQuestionsNode(mockLlm),
                new GenerateSpecNode(mockLlm, null, null),
                new PlanMissionNode(mockLlm),
                mockScheduleWave,
                mockParallelDispatch,
                mockEvaluateWave,
                mockConvergeNode,
                null
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
        assertEquals(MissionStatus.AWAITING_APPROVAL, finalState.status());
        assertTrue(finalState.classification().isPresent());
        assertTrue(finalState.projectContext().isPresent());
        assertFalse(finalState.tasks().isEmpty());
        assertEquals("TASK-001", finalState.tasks().get(0).id());
    }

    // ===================================================================
    //  Full run with FULL_AUTO -- skips await_approval, uses wave loop
    // ===================================================================

    @Test
    @DisplayName("Running graph with FULL_AUTO skips approval and dispatches via wave loop")
    void runWithFullAuto() throws Exception {
        var input = new HashMap<String, Object>();
        input.put("missionId", "test-mission-2");
        input.put("request", "Refactor logging");
        input.put("interactionMode", InteractionMode.FULL_AUTO.name());

        Optional<WorldmindState> result = worldmindGraph.getCompiledGraph().invoke(input);

        assertTrue(result.isPresent(), "Graph should produce a result");
        WorldmindState finalState = result.get();

        assertEquals(MissionStatus.COMPLETED, finalState.status());
        assertEquals(InteractionMode.FULL_AUTO, finalState.interactionMode());
        assertTrue(finalState.classification().isPresent());
        assertTrue(finalState.projectContext().isPresent());
        assertFalse(finalState.tasks().isEmpty());
    }

    // ===================================================================
    //  Conditional routing logic
    // ===================================================================

    @Test
    @DisplayName("routeAfterPlan returns 'schedule_wave' for FULL_AUTO")
    void routeReturnsScheduleWaveForFullAuto() {
        var state = new WorldmindState(
                Map.of("interactionMode", InteractionMode.FULL_AUTO.name())
        );
        assertEquals("schedule_wave", worldmindGraph.routeAfterPlan(state));
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
    //  Schedule routing
    // ===================================================================

    @Test
    @DisplayName("routeAfterSchedule returns 'parallel_dispatch' when wave is non-empty")
    void routeAfterScheduleNonEmpty() {
        var state = new WorldmindState(Map.of(
                "waveTaskIds", List.of("TASK-001")
        ));
        assertEquals("parallel_dispatch", worldmindGraph.routeAfterSchedule(state));
    }

    @Test
    @DisplayName("routeAfterSchedule returns 'converge_results' when wave is empty")
    void routeAfterScheduleEmpty() {
        var state = new WorldmindState(Map.of(
                "waveTaskIds", List.of()
        ));
        assertEquals("converge_results", worldmindGraph.routeAfterSchedule(state));
    }

    // ===================================================================
    //  Wave eval routing
    // ===================================================================

    @Test
    @DisplayName("routeAfterWaveEval returns 'converge_results' when mission FAILED")
    void routeAfterWaveEvalFailed() {
        var state = new WorldmindState(Map.of(
                "status", MissionStatus.FAILED.name(),
                "tasks", List.of()
        ));
        assertEquals("converge_results", worldmindGraph.routeAfterWaveEval(state));
    }

    @Test
    @DisplayName("routeAfterWaveEval returns 'converge_results' when all tasks completed")
    void routeAfterWaveEvalAllComplete() {
        var d1 = new Task("TASK-001", "CODER", "Do", "", "Done", List.of(),
                TaskStatus.PENDING, 0, 3, FailureStrategy.RETRY, List.of(), List.of(), null);
        var state = new WorldmindState(Map.of(
                "status", MissionStatus.EXECUTING.name(),
                "tasks", List.of(d1),
                "completedTaskIds", List.of("TASK-001")
        ));
        assertEquals("converge_results", worldmindGraph.routeAfterWaveEval(state));
    }

    @Test
    @DisplayName("routeAfterWaveEval returns 'schedule_wave' when tasks remain")
    void routeAfterWaveEvalRemaining() {
        var d1 = new Task("TASK-001", "CODER", "Do", "", "Done", List.of(),
                TaskStatus.PENDING, 0, 3, FailureStrategy.RETRY, List.of(), List.of(), null);
        var d2 = new Task("TASK-002", "CODER", "Do2", "", "Done", List.of(),
                TaskStatus.PENDING, 0, 3, FailureStrategy.RETRY, List.of(), List.of(), null);
        var state = new WorldmindState(Map.of(
                "status", MissionStatus.EXECUTING.name(),
                "tasks", List.of(d1, d2),
                "completedTaskIds", List.of("TASK-001")
        ));
        assertEquals("schedule_wave", worldmindGraph.routeAfterWaveEval(state));
    }

    // ===================================================================
    //  State progression
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
