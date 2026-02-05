package com.worldmind.core.persistence;

import com.worldmind.core.llm.LlmService;
import com.worldmind.core.model.Classification;
import com.worldmind.core.model.InteractionMode;
import com.worldmind.core.model.MissionPlan;
import com.worldmind.core.model.MissionStatus;
import com.worldmind.core.model.ProjectContext;
import com.worldmind.core.graph.WorldmindGraph;
import com.worldmind.core.nodes.ClassifyRequestNode;
import com.worldmind.core.nodes.PlanMissionNode;
import com.worldmind.core.nodes.UploadContextNode;
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

    @BeforeEach
    void setUp() throws Exception {
        mockLlm = mock(LlmService.class);
        when(mockLlm.structuredCall(anyString(), anyString(), eq(Classification.class)))
                .thenReturn(new Classification("feature", 3, List.of("api"), "sequential"));

        when(mockLlm.structuredCall(anyString(), anyString(), eq(MissionPlan.class)))
                .thenReturn(new MissionPlan(
                        "Implement feature",
                        "sequential",
                        List.of(
                                new MissionPlan.DirectivePlan("FORGE", "Implement feature", "", "Feature works", List.of()),
                                new MissionPlan.DirectivePlan("VIGIL", "Review code", "", "Code quality ok", List.of("DIR-001"))
                        )
                ));

        mockScanner = mock(ProjectScanner.class);
        when(mockScanner.scan(any(Path.class))).thenReturn(new ProjectContext(
                ".", List.of(), "unknown", "unknown", Map.of(), 0,
                "unknown project with 0 files"
        ));
    }

    // ===================================================================
    //  Graph + MemorySaver
    // ===================================================================

    @Test
    @DisplayName("Graph compiles with MemorySaver checkpointer")
    void graphCompilesWithMemorySaver() throws Exception {
        var saver = new MemorySaver();
        var graph = new WorldmindGraph(
                new ClassifyRequestNode(mockLlm),
                new UploadContextNode(mockScanner),
                new PlanMissionNode(mockLlm),
                saver
        );

        assertNotNull(graph.getCompiledGraph(),
                "Compiled graph with MemorySaver should not be null");
    }

    @Test
    @DisplayName("MemorySaver persists checkpoints after graph execution")
    void memorySaverPersistsCheckpoints() throws Exception {
        var saver = new MemorySaver();
        var graph = new WorldmindGraph(
                new ClassifyRequestNode(mockLlm),
                new UploadContextNode(mockScanner),
                new PlanMissionNode(mockLlm),
                saver
        );

        var input = new HashMap<String, Object>();
        input.put("missionId", "checkpoint-test-1");
        input.put("request", "Add a REST endpoint");
        input.put("interactionMode", InteractionMode.APPROVE_PLAN.name());

        // Run with a thread ID so checkpoints are keyed
        var runnableConfig = RunnableConfig.builder()
                .threadId("test-thread-1")
                .build();

        Optional<WorldmindState> result = graph.getCompiledGraph()
                .invoke(input, runnableConfig);

        assertTrue(result.isPresent(), "Graph should produce a result");

        // Verify checkpoints were saved for this thread
        Collection<Checkpoint> checkpoints = saver.list(runnableConfig);
        assertFalse(checkpoints.isEmpty(),
                "MemorySaver should have persisted at least one checkpoint");
    }

    @Test
    @DisplayName("MemorySaver retrieves latest checkpoint for a thread")
    void memorySaverRetrievesLatestCheckpoint() throws Exception {
        var saver = new MemorySaver();
        var graph = new WorldmindGraph(
                new ClassifyRequestNode(mockLlm),
                new UploadContextNode(mockScanner),
                new PlanMissionNode(mockLlm),
                saver
        );

        var input = new HashMap<String, Object>();
        input.put("missionId", "checkpoint-test-2");
        input.put("request", "Refactor service layer");
        input.put("interactionMode", InteractionMode.FULL_AUTO.name());

        var runnableConfig = RunnableConfig.builder()
                .threadId("test-thread-2")
                .build();

        graph.getCompiledGraph().invoke(input, runnableConfig);

        // Get the latest checkpoint
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
        var graph = new WorldmindGraph(
                new ClassifyRequestNode(mockLlm),
                new UploadContextNode(mockScanner),
                new PlanMissionNode(mockLlm),
                saver
        );

        // Run thread 1
        var input1 = new HashMap<String, Object>();
        input1.put("missionId", "mission-A");
        input1.put("request", "Task A");
        var config1 = RunnableConfig.builder().threadId("thread-A").build();
        graph.getCompiledGraph().invoke(input1, config1);

        // Run thread 2
        var input2 = new HashMap<String, Object>();
        input2.put("missionId", "mission-B");
        input2.put("request", "Task B");
        var config2 = RunnableConfig.builder().threadId("thread-B").build();
        graph.getCompiledGraph().invoke(input2, config2);

        // Verify independent
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
        var graph = new WorldmindGraph(
                new ClassifyRequestNode(mockLlm),
                new UploadContextNode(mockScanner),
                new PlanMissionNode(mockLlm),
                null
        );

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
        BaseCheckpointSaver saver = config.memoryCheckpointSaver();

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

        BaseCheckpointSaver saver = config.jdbcCheckpointSaver(mockDataSource);

        assertNotNull(saver, "JDBC saver should not be null");
        assertInstanceOf(JdbcCheckpointSaver.class, saver,
                "Saver should be JdbcCheckpointSaver");

        // Verify createTables was called (via statement.execute)
        verify(mockStatement).execute();
    }
}
