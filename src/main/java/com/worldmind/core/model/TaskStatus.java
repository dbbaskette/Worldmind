package com.worldmind.core.model;

/**
 * Status of an individual task within a mission plan.
 */
public enum TaskStatus {
    PENDING,
    RUNNING,
    VERIFYING,  // CODER complete, awaiting TESTER/REVIEWER quality gates
    PASSED,
    FAILED,
    SKIPPED
}
