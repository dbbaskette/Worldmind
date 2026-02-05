package com.worldmind.core.model;

/**
 * Strategy applied when a directive fails execution.
 */
public enum FailureStrategy {
    RETRY,
    REPLAN,
    ESCALATE,
    SKIP
}
