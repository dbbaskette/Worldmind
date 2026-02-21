package com.worldmind.core.nodes;

import com.worldmind.core.model.*;
import com.worldmind.core.scheduler.TaskScheduler;
import com.worldmind.core.state.WorldmindState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ScheduleWaveNodeTest {

    private TaskScheduler mockScheduler;
    private ScheduleWaveNode node;

    @BeforeEach
    void setUp() {
        mockScheduler = mock(TaskScheduler.class);
        node = new ScheduleWaveNode(mockScheduler, 10);
    }

    @Test
    @DisplayName("Writes waveTaskIds and increments waveCount")
    void writesWaveIdsAndIncrementsCount() {
        when(mockScheduler.computeNextWave(anyList(), anySet(), any(), anyInt()))
                .thenReturn(List.of("TASK-001", "TASK-002"));

        var state = new WorldmindState(Map.of(
                "tasks", List.of(
                        task("TASK-001"), task("TASK-002"), task("TASK-003")
                ),
                "executionStrategy", ExecutionStrategy.PARALLEL.name(),
                "waveCount", 0
        ));

        var result = node.apply(state);

        assertEquals(List.of("TASK-001", "TASK-002"), result.get("waveTaskIds"));
        assertEquals(1, result.get("waveCount"));
        assertEquals(MissionStatus.EXECUTING.name(), result.get("status"));
    }

    @Test
    @DisplayName("Empty wave signals completion")
    void emptyWaveSignalsCompletion() {
        when(mockScheduler.computeNextWave(anyList(), anySet(), any(), anyInt()))
                .thenReturn(List.of());

        var state = new WorldmindState(Map.of(
                "tasks", List.of(task("TASK-001")),
                "completedTaskIds", List.of("TASK-001"),
                "executionStrategy", ExecutionStrategy.PARALLEL.name(),
                "waveCount", 2
        ));

        var result = node.apply(state);

        assertTrue(((List<?>) result.get("waveTaskIds")).isEmpty());
        assertEquals(3, result.get("waveCount"));
    }

    @Test
    @DisplayName("Passes completedTaskIds as set to scheduler")
    void passesCompletedIdsAsSet() {
        when(mockScheduler.computeNextWave(anyList(), anySet(), any(), anyInt()))
                .thenReturn(List.of("TASK-003"));

        var state = new WorldmindState(Map.of(
                "tasks", List.of(task("TASK-001"), task("TASK-002"), task("TASK-003")),
                "completedTaskIds", List.of("TASK-001", "TASK-002"),
                "executionStrategy", ExecutionStrategy.PARALLEL.name(),
                "waveCount", 1
        ));

        node.apply(state);

        verify(mockScheduler).computeNextWave(
                anyList(),
                eq(Set.of("TASK-001", "TASK-002")),
                eq(ExecutionStrategy.PARALLEL),
                eq(10)
        );
    }

    private Task task(String id) {
        return new Task(id, "CODER", "Do " + id, "", "Done", List.of(),
                TaskStatus.PENDING, 0, 3, FailureStrategy.RETRY, List.of(), List.of(), null);
    }

    private Task deployerTask(String id) {
        return new Task(id, "DEPLOYER", "Deploy " + id, "", "App running", List.of(),
                TaskStatus.PENDING, 0, 3, FailureStrategy.RETRY, List.of("manifest.yml"), List.of(), null);
    }

    @Test
    @DisplayName("DEPLOYER wave is scheduled and logged")
    void deployerWaveScheduled() {
        when(mockScheduler.computeNextWave(anyList(), anySet(), any(), anyInt()))
                .thenReturn(List.of("TASK-DEPLOY"));

        var state = new WorldmindState(Map.of(
                "missionId", "test-mission-001",
                "tasks", List.of(
                        task("TASK-001"), task("TASK-002"), deployerTask("TASK-DEPLOY")
                ),
                "completedTaskIds", List.of("TASK-001", "TASK-002"),
                "executionStrategy", ExecutionStrategy.PARALLEL.name(),
                "waveCount", 2
        ));

        var result = node.apply(state);

        assertEquals(List.of("TASK-DEPLOY"), result.get("waveTaskIds"));
        assertEquals(3, result.get("waveCount"));
        assertEquals(MissionStatus.EXECUTING.name(), result.get("status"));
    }
}
