package com.worldmind.core.model;

import java.io.Serializable;

/**
 * Outcome of running tests after a directive completes.
 */
public record TestResult(
    String directiveId,
    boolean passed,
    int totalTests,
    int failedTests,
    String output,
    long durationMs
) implements Serializable {}
