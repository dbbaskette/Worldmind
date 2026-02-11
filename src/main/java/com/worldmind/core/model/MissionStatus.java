package com.worldmind.core.model;

/**
 * Lifecycle status of a Worldmind mission.
 */
public enum MissionStatus {
    CLASSIFYING,
    UPLOADING,
    SPECIFYING,
    PLANNING,
    AWAITING_APPROVAL,
    EXECUTING,
    CONVERGING,
    COMPLETED,
    FAILED,
    CANCELLED
}
