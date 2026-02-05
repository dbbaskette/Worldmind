package com.worldmind.core.model;

/**
 * Lifecycle status of a Worldmind mission.
 */
public enum MissionStatus {
    CLASSIFYING,
    UPLOADING,
    PLANNING,
    AWAITING_APPROVAL,
    EXECUTING,
    CONVERGING,
    COMPLETED,
    FAILED,
    CANCELLED
}
