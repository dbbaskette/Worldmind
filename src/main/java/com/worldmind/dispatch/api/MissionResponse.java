package com.worldmind.dispatch.api;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.worldmind.core.model.*;

import java.util.List;

/**
 * JSON response for mission endpoints (Spec Section 7.3).
 */
public record MissionResponse(
    @JsonProperty("mission_id") String missionId,
    String status,
    String request,
    @JsonProperty("interaction_mode") String interactionMode,
    @JsonProperty("execution_strategy") String executionStrategy,
    Classification classification,
    List<DirectiveResponse> directives,
    @JsonProperty("seal_granted") boolean sealGranted,
    MissionMetrics metrics,
    List<String> errors
) {

    /**
     * Nested directive representation in the mission response.
     */
    public record DirectiveResponse(
        String id,
        String centurion,
        String description,
        String status,
        int iteration,
        @JsonProperty("max_iterations") int maxIterations,
        @JsonProperty("elapsed_ms") Long elapsedMs,
        @JsonProperty("files_affected") List<FileRecord> filesAffected
    ) {}
}
