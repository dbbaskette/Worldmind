package com.worldmind.core.model;

import java.io.Serializable;

/**
 * Aggregate metrics collected over the lifetime of a mission.
 */
public record MissionMetrics(
    long totalDurationMs,
    int tasksCompleted,
    int tasksFailed,
    int totalIterations,
    int filesCreated,
    int filesModified,
    int testsRun,
    int testsPassed,
    int wavesExecuted,
    long aggregateDurationMs
) implements Serializable {

    /** Backward-compatible constructor for existing code. */
    public MissionMetrics(long totalDurationMs, int tasksCompleted, int tasksFailed,
                          int totalIterations, int filesCreated, int filesModified,
                          int testsRun, int testsPassed) {
        this(totalDurationMs, tasksCompleted, tasksFailed, totalIterations,
                filesCreated, filesModified, testsRun, testsPassed, 0, 0L);
    }
}
