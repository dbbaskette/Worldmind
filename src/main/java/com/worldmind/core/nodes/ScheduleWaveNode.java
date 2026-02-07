package com.worldmind.core.nodes;

import com.worldmind.core.model.MissionStatus;
import com.worldmind.core.scheduler.DirectiveScheduler;
import com.worldmind.core.state.WorldmindState;
import com.worldmind.stargate.StargateProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.Map;

/**
 * Computes the next wave of eligible directives and writes the wave IDs to state.
 * An empty wave signals that all directives are done.
 */
@Component
public class ScheduleWaveNode {

    private static final Logger log = LoggerFactory.getLogger(ScheduleWaveNode.class);

    private final DirectiveScheduler scheduler;
    private final int maxParallel;

    public ScheduleWaveNode(DirectiveScheduler scheduler, StargateProperties properties) {
        this(scheduler, properties.getMaxParallel());
    }

    ScheduleWaveNode(DirectiveScheduler scheduler, int maxParallel) {
        this.scheduler = scheduler;
        this.maxParallel = maxParallel;
    }

    public Map<String, Object> apply(WorldmindState state) {
        var directives = state.directives();
        var completedIds = new HashSet<>(state.completedDirectiveIds());
        var strategy = state.executionStrategy();
        int currentWaveCount = state.waveCount();

        var waveIds = scheduler.computeNextWave(directives, completedIds, strategy, maxParallel);
        int nextWaveCount = currentWaveCount + 1;

        if (waveIds.isEmpty()) {
            log.info("Wave {} — no eligible directives, all done", nextWaveCount);
        } else {
            log.info("Wave {} — scheduling {} directives: {}", nextWaveCount, waveIds.size(), waveIds);
        }

        return Map.of(
                "waveDirectiveIds", waveIds,
                "waveCount", nextWaveCount,
                "status", MissionStatus.EXECUTING.name()
        );
    }
}
