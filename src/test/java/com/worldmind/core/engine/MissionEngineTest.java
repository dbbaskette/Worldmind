package com.worldmind.core.engine;

import com.worldmind.core.events.EventBus;
import com.worldmind.core.graph.WorldmindGraph;
import com.worldmind.core.llm.LlmService;
import com.worldmind.core.metrics.WorldmindMetrics;
import com.worldmind.core.model.*;
import com.worldmind.core.nodes.*;
import com.worldmind.core.scanner.ProjectScanner;
import com.worldmind.core.state.WorldmindState;
import com.worldmind.sandbox.DeployerProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.*;

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
        LlmService mockLlm = mock(LlmService.class);
        when(mockLlm.structuredCall(anyString(), anyString(), eq(Classification.class)))
                .thenReturn(new Classification("feature", 3, List.of("api", "service"), "sequential", "java"));
        when(mockLlm.structuredCall(anyString(), anyString(), eq(MissionPlan.class)))
                .thenReturn(new MissionPlan(
                        "Implement the requested feature",
                        "sequential",
                        List.of(
                                new MissionPlan.TaskPlan("CODER", "Implement feature", "", "Feature works", List.of(), List.of()),
                                new MissionPlan.TaskPlan("TESTER", "Write tests", "", "Tests pass", List.of("TASK-001"), List.of()),
                                new MissionPlan.TaskPlan("REVIEWER", "Review code", "", "Code quality ok", List.of("TASK-002"), List.of())
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
                "/test/project", List.of("src/Main.java"), "java", "spring",
                Map.of("spring-boot", "3.4.1"), 15, "Spring Boot project with 15 files"
        ));

        // Mock ScheduleWaveNode
        ScheduleWaveNode mockScheduleWave = mock(ScheduleWaveNode.class);
        when(mockScheduleWave.apply(any(WorldmindState.class))).thenAnswer(invocation -> {
            WorldmindState state = invocation.getArgument(0);
            var completedIds = new HashSet<>(state.completedTaskIds());
            var tasks = state.tasks();
            for (var d : tasks) {
                if (!completedIds.contains(d.id())) {
                    return Map.of(
                            "waveTaskIds", List.of(d.id()),
                            "waveCount", state.waveCount() + 1,
                            "status", MissionStatus.EXECUTING.name()
                    );
                }
            }
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

        // Mock EvaluateWaveNode
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

        WorldmindGraph graph = new WorldmindGraph(
                new ClassifyRequestNode(mockLlm, null),
                new UploadContextNode(mockScanner),
                new GenerateClarifyingQuestionsNode(mockLlm),
                new GenerateSpecNode(mockLlm, null, null),
                new PlanMissionNode(mockLlm, new DeployerProperties()),
                mockScheduleWave,
                mockParallelDispatch,
                mockEvaluateWave,
                mockConvergeNode,
                null
        );

        engine = new MissionEngine(graph, new EventBus(), mock(WorldmindMetrics.class));
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
        int counter1 = Integer.parseInt(id1.substring(id1.lastIndexOf('-') + 1));
        int counter2 = Integer.parseInt(id2.substring(id2.lastIndexOf('-') + 1));
        assertEquals(counter1 + 1, counter2,
                "Consecutive mission IDs should have incrementing counters");
    }

    // =================================================================
    //  Full planning pipeline
    // =================================================================

    @Test
    @DisplayName("runMission returns state with missionId, classification, and tasks")
    void runMissionReturnsCompleteState() {
        WorldmindState state = engine.runMission("Add a REST endpoint", InteractionMode.APPROVE_PLAN);

        assertFalse(state.missionId().isEmpty());
        assertTrue(state.missionId().startsWith("WMND-"));
        assertTrue(state.classification().isPresent());
        assertEquals("feature", state.classification().get().category());
        assertEquals(3, state.classification().get().complexity());
        assertEquals(List.of("api", "service"), state.classification().get().affectedComponents());
        assertTrue(state.projectContext().isPresent());
        assertEquals("java", state.projectContext().get().language());
        assertEquals("spring", state.projectContext().get().framework());
        assertFalse(state.tasks().isEmpty());
        assertEquals(3, state.tasks().size());
        assertEquals("TASK-001", state.tasks().get(0).id());
        assertEquals("CODER", state.tasks().get(0).agent());
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
    @DisplayName("APPROVE_PLAN mode stops at plan (awaiting approval)")
    void approvePlanStopsAtPlan() {
        WorldmindState state = engine.runMission("Add logging", InteractionMode.APPROVE_PLAN);
        assertEquals(MissionStatus.AWAITING_APPROVAL, state.status());
    }

    @Test
    @DisplayName("STEP_BY_STEP mode stops at plan (awaiting approval)")
    void stepByStepStopsAtPlan() {
        WorldmindState state = engine.runMission("Add logging", InteractionMode.STEP_BY_STEP);
        assertEquals(MissionStatus.AWAITING_APPROVAL, state.status());
    }

    @Test
    @DisplayName("FULL_AUTO mode skips approval and dispatches directly")
    void fullAutoSkipsApproval() {
        WorldmindState state = engine.runMission("Add logging", InteractionMode.FULL_AUTO);
        assertEquals(InteractionMode.FULL_AUTO, state.interactionMode());
        assertEquals(MissionStatus.COMPLETED, state.status());
        assertFalse(state.tasks().isEmpty());
    }

    // =================================================================
    //  Execution strategy
    // =================================================================

    @Test
    @DisplayName("Execution strategy is set from the plan")
    void executionStrategySet() {
        WorldmindState state = engine.runMission("Add logging", InteractionMode.APPROVE_PLAN);
        assertEquals(ExecutionStrategy.SEQUENTIAL, state.executionStrategy());
    }

    // =================================================================
    //  Error handling
    // =================================================================

    @Test
    @DisplayName("Null request still runs through the graph")
    void nullRequestHandled() {
        assertDoesNotThrow(() -> engine.runMission("", InteractionMode.APPROVE_PLAN));
    }
}
