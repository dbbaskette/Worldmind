package com.worldmind.core.nodes;

import com.worldmind.core.metrics.WorldmindMetrics;
import com.worldmind.core.model.ExecutionStrategy;
import com.worldmind.core.model.MissionStatus;
import com.worldmind.core.scheduler.TaskScheduler;
import com.worldmind.core.state.WorldmindState;
import com.worldmind.sandbox.SandboxProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.Map;

/**
 * Computes the next wave of eligible tasks and writes the wave IDs to state.
 * An empty wave signals that all tasks are done.
 */
@Component
public class ScheduleWaveNode {

    private static final Logger log = LoggerFactory.getLogger(ScheduleWaveNode.class);

    private final TaskScheduler scheduler;
    private final int maxParallel;
    private final int waveCooldownSeconds;
    private final WorldmindMetrics metrics;

    @Autowired
    public ScheduleWaveNode(TaskScheduler scheduler, SandboxProperties properties, 
                           WorldmindMetrics metrics) {
        this(scheduler, properties.getMaxParallel(), properties.getWaveCooldownSeconds(), metrics);
    }

    ScheduleWaveNode(TaskScheduler scheduler, int maxParallel) {
        this(scheduler, maxParallel, 0, null);
    }

    ScheduleWaveNode(TaskScheduler scheduler, int maxParallel, int waveCooldownSeconds, 
                    WorldmindMetrics metrics) {
        this.scheduler = scheduler;
        this.maxParallel = maxParallel;
        this.waveCooldownSeconds = waveCooldownSeconds;
        this.metrics = metrics;
    }

    public Map<String, Object> apply(WorldmindState state) {
        var tasks = state.tasks();
        var completedIds = new HashSet<>(state.completedTaskIds());
        var strategy = state.executionStrategy();
        int currentWaveCount = state.waveCount();
        
        log.info("ScheduleWaveNode: strategy={}, raw value='{}', userStrategy='{}'",
                strategy, 
                state.<String>value("executionStrategy").orElse("(none)"),
                state.<String>value("userExecutionStrategy").orElse("(none)"));

        // Rate-limit cooldown: wait between waves to avoid API token budget exhaustion.
        // The first wave starts immediately; subsequent waves wait to let the token window reset.
        if (currentWaveCount > 0 && waveCooldownSeconds > 0) {
            log.info("Rate-limit cooldown: waiting {}s before wave {}", waveCooldownSeconds, currentWaveCount + 1);
            try {
                Thread.sleep(waveCooldownSeconds * 1000L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        var waveIds = scheduler.computeNextWave(tasks, completedIds, strategy, maxParallel);
        int nextWaveCount = currentWaveCount + 1;

        if (waveIds.isEmpty()) {
            log.info("Wave {} — no eligible tasks, all done", nextWaveCount);
        } else {
            log.info("Wave {} — scheduling {} tasks: {}", nextWaveCount, waveIds.size(), waveIds);

            // Log when the DEPLOYER wave is being scheduled
            boolean hasDeployer = waveIds.stream()
                    .flatMap(id -> tasks.stream().filter(t -> t.id().equals(id)))
                    .anyMatch(t -> "DEPLOYER".equals(t.agent()));
            if (hasDeployer) {
                log.info("Scheduling DEPLOYER wave for mission {}", state.missionId());
            }

            // Record wave execution metrics
            if (metrics != null) {
                String strategyName = strategy == ExecutionStrategy.PARALLEL ? "parallel" : "sequential";
                metrics.recordWaveExecution(waveIds.size(), strategyName);
            }
        }

        return Map.of(
                "waveTaskIds", waveIds,
                "waveCount", nextWaveCount,
                "status", MissionStatus.EXECUTING.name()
        );
    }
}
