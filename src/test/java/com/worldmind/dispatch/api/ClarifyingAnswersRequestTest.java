package com.worldmind.dispatch.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link ClarifyingAnswersRequest}.
 */
class ClarifyingAnswersRequestTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Test
    @DisplayName("toAnswersString produces JSON keyed by question ID")
    void toAnswersStringProducesJson() throws Exception {
        var request = new ClarifyingAnswersRequest(List.of(
                new ClarifyingAnswersRequest.Answer("q1", "What scope?", "Full scope"),
                new ClarifyingAnswersRequest.Answer("q2", "What tech?", "Spring Boot")
        ));

        String result = request.toAnswersString();
        var json = OBJECT_MAPPER.readTree(result);

        assertTrue(json.has("q1"));
        assertTrue(json.has("q2"));
        assertEquals("Full scope", json.get("q1").asText());
        assertEquals("Spring Boot", json.get("q2").asText());
    }

    @Test
    @DisplayName("toAnswersString includes cf_service_bindings as JSON array string")
    void toAnswersStringIncludesServiceBindings() throws Exception {
        String serviceAnswer = "[{\"type\":\"postgresql\",\"instanceName\":\"my-db\"}]";
        var request = new ClarifyingAnswersRequest(List.of(
                new ClarifyingAnswersRequest.Answer("q1", "What scope?", "Full scope"),
                new ClarifyingAnswersRequest.Answer(
                        "cf_service_bindings",
                        "Service bindings?",
                        serviceAnswer
                )
        ));

        String result = request.toAnswersString();
        var json = OBJECT_MAPPER.readTree(result);

        assertTrue(json.has("cf_service_bindings"));
        assertEquals(serviceAnswer, json.get("cf_service_bindings").asText());
    }

    @Test
    @DisplayName("toAnswersString returns empty JSON object for null answers")
    void toAnswersStringReturnsEmptyForNull() {
        var request = new ClarifyingAnswersRequest(null);
        assertEquals("{}", request.toAnswersString());
    }

    @Test
    @DisplayName("toAnswersString returns empty JSON object for empty list")
    void toAnswersStringReturnsEmptyForEmptyList() {
        var request = new ClarifyingAnswersRequest(List.of());
        assertEquals("{}", request.toAnswersString());
    }
}
