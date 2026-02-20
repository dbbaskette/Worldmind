package com.worldmind.core.model;

/**
 * Strategy for executing tasks within a mission.
 * <p>
 * SEQUENTIAL: One task at a time, each sees prior changes (safest, slower).
 * PARALLEL: Multiple tasks run concurrently up to maxParallel (faster, risk of file conflicts).
 */
public enum ExecutionStrategy {
    SEQUENTIAL,
    PARALLEL
}
