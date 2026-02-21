package com.worldmind.dispatch.api;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.List;

/**
 * Request body for submitting answers to clarifying questions.
 */
public record ClarifyingAnswersRequest(
    @JsonProperty("answers") List<Answer> answers
) {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    public record Answer(
        @JsonProperty("question_id") String questionId,
        @JsonProperty("question") String question,
        @JsonProperty("answer") String answer
    ) {}

    /**
     * Converts answers to a JSON string keyed by question ID.
     * This format is consumed by both spec generation (human-readable JSON)
     * and service name extraction (machine-parseable).
     */
    public String toAnswersString() {
        if (answers == null || answers.isEmpty()) {
            return "{}";
        }
        var map = new LinkedHashMap<String, String>();
        for (var a : answers) {
            map.put(a.questionId(), a.answer());
        }
        try {
            return OBJECT_MAPPER.writeValueAsString(map);
        } catch (Exception e) {
            return "{}";
        }
    }
}
