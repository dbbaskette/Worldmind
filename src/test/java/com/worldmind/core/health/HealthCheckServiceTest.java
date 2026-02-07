package com.worldmind.core.health;

import com.worldmind.core.graph.WorldmindGraph;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

class HealthCheckServiceTest {

    @Test
    @DisplayName("All components null -> all DOWN")
    void allComponentsNullAllDown() {
        var service = new HealthCheckService(null, null, null);
        List<HealthStatus> results = service.checkAll();

        assertEquals(3, results.size());
        for (var status : results) {
            assertEquals(HealthStatus.Status.DOWN, status.status(),
                    status.component() + " should be DOWN when null");
        }
    }

    @Test
    @DisplayName("Graph available -> graph UP")
    void graphAvailableGraphUp() {
        var mockGraph = mock(WorldmindGraph.class);
        var service = new HealthCheckService(mockGraph, null, null);
        List<HealthStatus> results = service.checkAll();

        var graphStatus = results.stream()
                .filter(s -> "graph".equals(s.component()))
                .findFirst()
                .orElseThrow();
        assertEquals(HealthStatus.Status.UP, graphStatus.status());
    }

    @Test
    @DisplayName("checkAll returns graph, database, docker components")
    void checkAllReturnsAllComponents() {
        var service = new HealthCheckService(null, null, null);
        List<HealthStatus> results = service.checkAll();

        var components = results.stream().map(HealthStatus::component).toList();
        assertTrue(components.contains("graph"));
        assertTrue(components.contains("database"));
        assertTrue(components.contains("docker"));
    }
}
