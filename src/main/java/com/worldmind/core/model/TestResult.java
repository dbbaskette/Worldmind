package com.worldmind.core.model;

import java.io.Serializable;

/**
 * Outcome of running tests after a task completes.
 */
public record TestResult(
    String taskId,
    boolean passed,
    int totalTests,
    int failedTests,
    String output,
    long durationMs
) implements Serializable {}
