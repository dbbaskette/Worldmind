package com.worldmind.core.metrics;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class WorldmindMetricsTest {

    private SimpleMeterRegistry registry;
    private WorldmindMetrics metrics;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        metrics = new WorldmindMetrics(registry);
    }

    @Test
    @DisplayName("recordPlanningDuration creates a timer")
    void recordPlanningDuration() {
        metrics.recordPlanningDuration(1500);
        var timer = registry.find("worldmind.planning.duration").timer();
        assertNotNull(timer);
        assertEquals(1, timer.count());
    }

    @Test
    @DisplayName("recordSealResult increments correct counter")
    void recordSealResult() {
        metrics.recordSealResult(true);
        metrics.recordSealResult(true);
        metrics.recordSealResult(false);

        var granted = registry.find("worldmind.seal.evaluations")
                .tag("result", "granted").counter();
        var denied = registry.find("worldmind.seal.evaluations")
                .tag("result", "denied").counter();

        assertNotNull(granted);
        assertNotNull(denied);
        assertEquals(2.0, granted.count());
        assertEquals(1.0, denied.count());
    }

    @Test
    @DisplayName("recordDirectiveExecution records by centurion tag")
    void recordDirectiveExecution() {
        metrics.recordDirectiveExecution("FORGE", 200);
        metrics.recordDirectiveExecution("PULSE", 100);

        var forgeTimer = registry.find("worldmind.directive.duration")
                .tag("centurion", "FORGE").timer();
        var pulseTimer = registry.find("worldmind.directive.duration")
                .tag("centurion", "PULSE").timer();

        assertNotNull(forgeTimer);
        assertNotNull(pulseTimer);
        assertEquals(1, forgeTimer.count());
        assertEquals(1, pulseTimer.count());
    }

    @Test
    @DisplayName("recordMissionResult increments by status tag")
    void recordMissionResult() {
        metrics.recordMissionResult("COMPLETED");
        metrics.recordMissionResult("COMPLETED");
        metrics.recordMissionResult("FAILED");

        var completed = registry.find("worldmind.missions.total")
                .tag("status", "COMPLETED").counter();
        var failed = registry.find("worldmind.missions.total")
                .tag("status", "FAILED").counter();

        assertNotNull(completed);
        assertNotNull(failed);
        assertEquals(2.0, completed.count());
        assertEquals(1.0, failed.count());
    }

    @Test
    @DisplayName("incrementEscalations increments by reason tag")
    void incrementEscalations() {
        metrics.incrementEscalations("retries_exhausted");

        var counter = registry.find("worldmind.escalations.total")
                .tag("reason", "retries_exhausted").counter();
        assertNotNull(counter);
        assertEquals(1.0, counter.count());
    }

    @Test
    @DisplayName("recordIterationDepth records to distribution summary")
    void recordIterationDepth() {
        metrics.recordIterationDepth(3);
        metrics.recordIterationDepth(5);

        var summary = registry.find("worldmind.iteration.depth").summary();
        assertNotNull(summary);
        assertEquals(2, summary.count());
    }
}
