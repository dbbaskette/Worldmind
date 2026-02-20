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
    @DisplayName("recordQualityGateResult increments correct counter")
    void recordQualityGateResult() {
        metrics.recordQualityGateResult(true);
        metrics.recordQualityGateResult(true);
        metrics.recordQualityGateResult(false);

        var granted = registry.find("worldmind.quality_gate.evaluations")
                .tag("result", "granted").counter();
        var denied = registry.find("worldmind.quality_gate.evaluations")
                .tag("result", "denied").counter();

        assertNotNull(granted);
        assertNotNull(denied);
        assertEquals(2.0, granted.count());
        assertEquals(1.0, denied.count());
    }

    @Test
    @DisplayName("recordTaskExecution records by agent tag")
    void recordTaskExecution() {
        metrics.recordTaskExecution("CODER", 200);
        metrics.recordTaskExecution("RESEARCHER", 100);

        var coderTimer = registry.find("worldmind.task.duration")
                .tag("agent", "CODER").timer();
        var researcherTimer = registry.find("worldmind.task.duration")
                .tag("agent", "RESEARCHER").timer();

        assertNotNull(coderTimer);
        assertNotNull(researcherTimer);
        assertEquals(1, coderTimer.count());
        assertEquals(1, researcherTimer.count());
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
