package com.worldmind.core.nodes;

import com.worldmind.core.model.*;
import com.worldmind.core.scheduler.DirectiveScheduler;
import com.worldmind.core.state.WorldmindState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ScheduleWaveNodeTest {

    private DirectiveScheduler mockScheduler;
    private ScheduleWaveNode node;

    @BeforeEach
    void setUp() {
        mockScheduler = mock(DirectiveScheduler.class);
        node = new ScheduleWaveNode(mockScheduler, 10);
    }

    @Test
    @DisplayName("Writes waveDirectiveIds and increments waveCount")
    void writesWaveIdsAndIncrementsCount() {
        when(mockScheduler.computeNextWave(anyList(), anySet(), any(), anyInt()))
                .thenReturn(List.of("DIR-001", "DIR-002"));

        var state = new WorldmindState(Map.of(
                "directives", List.of(
                        directive("DIR-001"), directive("DIR-002"), directive("DIR-003")
                ),
                "executionStrategy", ExecutionStrategy.PARALLEL.name(),
                "waveCount", 0
        ));

        var result = node.apply(state);

        assertEquals(List.of("DIR-001", "DIR-002"), result.get("waveDirectiveIds"));
        assertEquals(1, result.get("waveCount"));
        assertEquals(MissionStatus.EXECUTING.name(), result.get("status"));
    }

    @Test
    @DisplayName("Empty wave signals completion")
    void emptyWaveSignalsCompletion() {
        when(mockScheduler.computeNextWave(anyList(), anySet(), any(), anyInt()))
                .thenReturn(List.of());

        var state = new WorldmindState(Map.of(
                "directives", List.of(directive("DIR-001")),
                "completedDirectiveIds", List.of("DIR-001"),
                "executionStrategy", ExecutionStrategy.PARALLEL.name(),
                "waveCount", 2
        ));

        var result = node.apply(state);

        assertTrue(((List<?>) result.get("waveDirectiveIds")).isEmpty());
        assertEquals(3, result.get("waveCount"));
    }

    @Test
    @DisplayName("Passes completedDirectiveIds as set to scheduler")
    void passesCompletedIdsAsSet() {
        when(mockScheduler.computeNextWave(anyList(), anySet(), any(), anyInt()))
                .thenReturn(List.of("DIR-003"));

        var state = new WorldmindState(Map.of(
                "directives", List.of(directive("DIR-001"), directive("DIR-002"), directive("DIR-003")),
                "completedDirectiveIds", List.of("DIR-001", "DIR-002"),
                "executionStrategy", ExecutionStrategy.PARALLEL.name(),
                "waveCount", 1
        ));

        node.apply(state);

        verify(mockScheduler).computeNextWave(
                anyList(),
                eq(Set.of("DIR-001", "DIR-002")),
                eq(ExecutionStrategy.PARALLEL),
                eq(10)
        );
    }

    private Directive directive(String id) {
        return new Directive(id, "FORGE", "Do " + id, "", "Done", List.of(),
                DirectiveStatus.PENDING, 0, 3, FailureStrategy.RETRY, List.of(), null);
    }
}
