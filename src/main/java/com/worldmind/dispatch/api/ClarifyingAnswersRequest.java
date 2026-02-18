package com.worldmind.dispatch.api;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Request body for submitting answers to clarifying questions.
 */
public record ClarifyingAnswersRequest(
    @JsonProperty("answers") List<Answer> answers
) {
    public record Answer(
        @JsonProperty("question_id") String questionId,
        @JsonProperty("question") String question,
        @JsonProperty("answer") String answer
    ) {}

    /**
     * Converts answers to a formatted string for inclusion in the spec prompt.
     */
    public String toAnswersString() {
        if (answers == null || answers.isEmpty()) {
            return "";
        }
        return answers.stream()
                .map(a -> String.format("Q: %s\nA: %s", a.question(), a.answer()))
                .collect(Collectors.joining("\n\n"));
    }
}
