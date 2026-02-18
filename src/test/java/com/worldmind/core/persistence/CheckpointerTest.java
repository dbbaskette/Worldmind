package com.worldmind.core.persistence;

import com.worldmind.core.llm.LlmService;
import com.worldmind.core.model.*;
import com.worldmind.core.graph.WorldmindGraph;
import com.worldmind.core.nodes.*;
import com.worldmind.core.scanner.ProjectScanner;
import com.worldmind.core.state.WorldmindState;
import org.bsc.langgraph4j.RunnableConfig;
import org.bsc.langgraph4j.checkpoint.BaseCheckpointSaver;
import org.bsc.langgraph4j.checkpoint.Checkpoint;
import org.bsc.langgraph4j.checkpoint.MemorySaver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Tests for checkpoint saver integration with the Worldmind graph.
 * Uses {@link MemorySaver} to validate checkpointing behavior
 * without requiring a PostgreSQL database.
 */
class CheckpointerTest {

    private LlmService mockLlm;
    private ProjectScanner mockScanner;
    private ScheduleWaveNode mockScheduleWave;
    private ParallelDispatchNode mockParallelDispatch;
    private EvaluateWaveNode mockEvaluateWave;
    private ConvergeResultsNode mockConvergeNode;

    @BeforeEach
    void setUp() throws Exception {
        mockLlm = mock(LlmService.class);
        when(mockLlm.structuredCall(anyString(), anyString(), eq(Classification.class)))
                .thenReturn(new Classification("feature", 3, List.of("api"), "sequential", "java"));

        when(mockLlm.structuredCall(anyString(), anyString(), eq(MissionPlan.class)))
                .thenReturn(new MissionPlan(
                        "Implement feature",
                        "sequential",
                        List.of(
                                new MissionPlan.DirectivePlan("FORGE", "Implement feature", "", "Feature works", List.of(), List.of()),
                                new MissionPlan.DirectivePlan("VIGIL", "Review code", "", "Code quality ok", List.of("DIR-001"), List.of())
                        )
                ));
        when(mockLlm.structuredCall(anyString(), anyString(), eq(ProductSpec.class)))
                .thenReturn(new ProductSpec(
                        "Test Spec", "Overview", List.of("Goal 1"), List.of("Non-goal 1"),
                        List.of("Req 1"), List.of("Criterion 1"),
                        List.of(), List.of(), List.of()
                ));

        mockScanner = mock(ProjectScanner.class);
        when(mockScanner.scan(any(Path.class))).thenReturn(new ProjectContext(
                ".", List.of(), "unknown", "unknown", Map.of(), 0,
                "unknown project with 0 files"
        ));

        // Mock ScheduleWaveNode
        mockScheduleWave = mock(ScheduleWaveNode.class);
        when(mockScheduleWave.apply(any(WorldmindState.class))).thenAnswer(invocation -> {
            WorldmindState state = invocation.getArgument(0);
            var completedIds = new HashSet<>(state.completedDirectiveIds());
            var directives = state.directives();
            for (var d : directives) {
                if (!completedIds.contains(d.id())) {
                    return Map.of(
                            "waveDirectiveIds", List.of(d.id()),
                            "waveCount", state.waveCount() + 1,
                            "status", MissionStatus.EXECUTING.name()
                    );
                }
            }
            return Map.of(
                    "waveDirectiveIds", List.of(),
                    "waveCount", state.waveCount() + 1,
                    "status", MissionStatus.EXECUTING.name()
            );
        });

        // Mock ParallelDispatchNode
        mockParallelDispatch = mock(ParallelDispatchNode.class);
        when(mockParallelDispatch.apply(any(WorldmindState.class))).thenAnswer(invocation -> {
            WorldmindState state = invocation.getArgument(0);
            var waveIds = state.waveDirectiveIds();
            var results = new ArrayList<WaveDispatchResult>();
            for (var id : waveIds) {
                results.add(new WaveDispatchResult(id, DirectiveStatus.PASSED, List.of(), "OK", 100L));
            }
            return Map.of(
                    "waveDispatchResults", results,
                    "starblasters", List.of(),
                    "status", MissionStatus.EXECUTING.name()
            );
        });

        // Mock EvaluateWaveNode
        mockEvaluateWave = mock(EvaluateWaveNode.class);
        when(mockEvaluateWave.apply(any(WorldmindState.class))).thenAnswer(invocation -> {
            WorldmindState state = invocation.getArgument(0);
            var waveIds = state.waveDirectiveIds();
            return Map.of("completedDirectiveIds", new ArrayList<>(waveIds));
        });

        mockConvergeNode = mock(ConvergeResultsNode.class);
        when(mockConvergeNode.apply(any(WorldmindState.class))).thenReturn(Map.of(
                "metrics", new MissionMetrics(1000, 1, 0, 1, 2, 1, 5, 5),
                "status", MissionStatus.COMPLETED.name()
        ));
    }

    private WorldmindGraph buildGraph(BaseCheckpointSaver saver) throws Exception {
        return new WorldmindGraph(
                new ClassifyRequestNode(mockLlm, null),
                new UploadContextNode(mockScanner),
                new GenerateClarifyingQuestionsNode(mockLlm),
                new GenerateSpecNode(mockLlm, null, null),
                new PlanMissionNode(mockLlm),
                mockScheduleWave,
                mockParallelDispatch,
                mockEvaluateWave,
                mockConvergeNode,
                saver
        );
    }

    // ===================================================================
    //  Graph + MemorySaver
    // ===================================================================

    @Test
    @DisplayName("Graph compiles with MemorySaver checkpointer")
    void graphCompilesWithMemorySaver() throws Exception {
        var saver = new MemorySaver();
        var graph = buildGraph(saver);
        assertNotNull(graph.getCompiledGraph(),
                "Compiled graph with MemorySaver should not be null");
    }

    @Test
    @DisplayName("MemorySaver persists checkpoints after graph execution")
    void memorySaverPersistsCheckpoints() throws Exception {
        var saver = new MemorySaver();
        var graph = buildGraph(saver);

        var input = new HashMap<String, Object>();
        input.put("missionId", "checkpoint-test-1");
        input.put("request", "Add a REST endpoint");
        input.put("interactionMode", InteractionMode.APPROVE_PLAN.name());

        var runnableConfig = RunnableConfig.builder()
                .threadId("test-thread-1")
                .build();

        Optional<WorldmindState> result = graph.getCompiledGraph()
                .invoke(input, runnableConfig);

        assertTrue(result.isPresent(), "Graph should produce a result");

        Collection<Checkpoint> checkpoints = saver.list(runnableConfig);
        assertFalse(checkpoints.isEmpty(),
                "MemorySaver should have persisted at least one checkpoint");
    }

    @Test
    @DisplayName("MemorySaver retrieves latest checkpoint for a thread")
    void memorySaverRetrievesLatestCheckpoint() throws Exception {
        var saver = new MemorySaver();
        var graph = buildGraph(saver);

        var input = new HashMap<String, Object>();
        input.put("missionId", "checkpoint-test-2");
        input.put("request", "Refactor service layer");
        input.put("interactionMode", InteractionMode.FULL_AUTO.name());

        var runnableConfig = RunnableConfig.builder()
                .threadId("test-thread-2")
                .build();

        graph.getCompiledGraph().invoke(input, runnableConfig);

        Optional<Checkpoint> latest = saver.get(runnableConfig);
        assertTrue(latest.isPresent(), "Should retrieve latest checkpoint");

        Checkpoint checkpoint = latest.get();
        assertNotNull(checkpoint.getId(), "Checkpoint should have an ID");
        assertNotNull(checkpoint.getState(), "Checkpoint should have state");
    }

    @Test
    @DisplayName("Different threads have independent checkpoints")
    void differentThreadsHaveIndependentCheckpoints() throws Exception {
        var saver = new MemorySaver();
        var graph = buildGraph(saver);

        var input1 = new HashMap<String, Object>();
        input1.put("missionId", "mission-A");
        input1.put("request", "Task A");
        var config1 = RunnableConfig.builder().threadId("thread-A").build();
        graph.getCompiledGraph().invoke(input1, config1);

        var input2 = new HashMap<String, Object>();
        input2.put("missionId", "mission-B");
        input2.put("request", "Task B");
        var config2 = RunnableConfig.builder().threadId("thread-B").build();
        graph.getCompiledGraph().invoke(input2, config2);

        Collection<Checkpoint> thread1Checkpoints = saver.list(config1);
        Collection<Checkpoint> thread2Checkpoints = saver.list(config2);

        assertFalse(thread1Checkpoints.isEmpty(), "Thread A should have checkpoints");
        assertFalse(thread2Checkpoints.isEmpty(), "Thread B should have checkpoints");
    }

    // ===================================================================
    //  Graph without checkpointer
    // ===================================================================

    @Test
    @DisplayName("Graph compiles and runs without any checkpointer")
    void graphWorksWithoutCheckpointer() throws Exception {
        var graph = buildGraph(null);

        var input = new HashMap<String, Object>();
        input.put("missionId", "no-checkpoint-test");
        input.put("request", "Simple task");

        Optional<WorldmindState> result = graph.getCompiledGraph().invoke(input);
        assertTrue(result.isPresent(), "Graph should produce a result even without checkpointer");
        assertEquals(MissionStatus.AWAITING_APPROVAL, result.get().status());
    }

    // ===================================================================
    //  CheckpointerConfig
    // ===================================================================

    @Test
    @DisplayName("CheckpointerConfig creates MemorySaver as fallback")
    void configCreatesMemorySaverFallback() {
        var config = new CheckpointerConfig();
        BaseCheckpointSaver saver = config.checkpointSaver(java.util.Optional.empty());

        assertNotNull(saver, "Fallback saver should not be null");
        assertInstanceOf(MemorySaver.class, saver,
                "Fallback saver should be MemorySaver");
    }

    @Test
    @DisplayName("CheckpointerConfig creates JdbcCheckpointSaver when DataSource provided")
    void configCreatesJdbcSaverWithDataSource() throws Exception {
        var config = new CheckpointerConfig();
        var mockDataSource = mock(javax.sql.DataSource.class);
        var mockConnection = mock(java.sql.Connection.class);
        var mockStatement = mock(java.sql.PreparedStatement.class);

        when(mockDataSource.getConnection()).thenReturn(mockConnection);
        when(mockConnection.prepareStatement(anyString())).thenReturn(mockStatement);

        BaseCheckpointSaver saver = config.checkpointSaver(java.util.Optional.of(mockDataSource));

        assertNotNull(saver, "JDBC saver should not be null");
        assertInstanceOf(JdbcCheckpointSaver.class, saver,
                "Saver should be JdbcCheckpointSaver");

        verify(mockStatement).execute();
    }
}
