package com.worldmind.core.model;

/**
 * Strategy applied when a task fails execution.
 */
public enum FailureStrategy {
    RETRY,
    REPLAN,
    ESCALATE,
    SKIP
}
