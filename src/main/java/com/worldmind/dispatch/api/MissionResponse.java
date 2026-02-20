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
    @JsonProperty("product_spec") ProductSpec productSpec,
    @JsonProperty("clarifying_questions") ClarifyingQuestions clarifyingQuestions,
    List<DirectiveResponse> directives,
    @JsonProperty("seal_granted") boolean sealGranted,
    MissionMetrics metrics,
    List<String> errors,
    @JsonProperty("wave_count") int waveCount
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
        @JsonProperty("files_affected") List<FileRecord> filesAffected,
        @JsonProperty("on_failure") String onFailure,
        @JsonProperty("review_score") Integer reviewScore,
        @JsonProperty("review_summary") String reviewSummary,
        @JsonProperty("review_issues") List<String> reviewIssues,
        @JsonProperty("review_suggestions") List<String> reviewSuggestions
    ) {}
}
