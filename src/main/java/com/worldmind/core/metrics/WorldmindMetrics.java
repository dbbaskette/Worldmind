package com.worldmind.core.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Service;

import java.time.Duration;

/**
 * Centralised Micrometer metrics for Worldmind mission execution.
 */
@Service
public class WorldmindMetrics {

    private final MeterRegistry registry;

    public WorldmindMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    public void recordPlanningDuration(long ms) {
        Timer.builder("worldmind.planning.duration")
                .register(registry)
                .record(Duration.ofMillis(ms));
    }

    public void recordDirectiveExecution(String centurionType, long ms) {
        Timer.builder("worldmind.directive.duration")
                .tag("centurion", centurionType)
                .register(registry)
                .record(Duration.ofMillis(ms));
    }

    public void recordSealResult(boolean granted) {
        Counter.builder("worldmind.seal.evaluations")
                .tag("result", granted ? "granted" : "denied")
                .register(registry)
                .increment();
    }

    public void recordIterationDepth(int depth) {
        DistributionSummary.builder("worldmind.iteration.depth")
                .register(registry)
                .record(depth);
    }

    public void recordMissionResult(String status) {
        Counter.builder("worldmind.missions.total")
                .tag("status", status)
                .register(registry)
                .increment();
    }

    public void incrementEscalations(String reason) {
        Counter.builder("worldmind.escalations.total")
                .tag("reason", reason)
                .register(registry)
                .increment();
    }
}
