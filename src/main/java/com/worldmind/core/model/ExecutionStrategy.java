package com.worldmind.core.model;

/**
 * Strategy for executing directives within a mission.
 * <p>
 * SEQUENTIAL: One directive at a time, each sees prior changes (safest, slower).
 * PARALLEL: Multiple directives run concurrently up to maxParallel (faster, risk of file conflicts).
 */
public enum ExecutionStrategy {
    SEQUENTIAL,
    PARALLEL
}
