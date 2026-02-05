package com.worldmind.core.model;

import java.io.Serializable;

/**
 * Aggregate metrics collected over the lifetime of a mission.
 */
public record MissionMetrics(
    long totalDurationMs,
    int directivesCompleted,
    int directivesFailed,
    int totalIterations,
    int filesCreated,
    int filesModified,
    int testsRun,
    int testsPassed
) implements Serializable {}
