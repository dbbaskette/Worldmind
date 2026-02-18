package com.worldmind.core.model;

/**
 * Status of an individual directive within a mission plan.
 */
public enum DirectiveStatus {
    PENDING,
    RUNNING,
    VERIFYING,  // FORGE complete, awaiting GAUNTLET/VIGIL quality gates
    PASSED,
    FAILED,
    SKIPPED
}
