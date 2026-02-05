package com.worldmind.core.nodes;

import com.worldmind.core.llm.LlmService;
import com.worldmind.core.model.Classification;
import com.worldmind.core.model.MissionStatus;
import com.worldmind.core.state.WorldmindState;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link ClassifyRequestNode}.
 * <p>
 * Uses Mockito to mock {@link LlmService} so that no real LLM calls are made.
 */
class ClassifyRequestNodeTest {

    @Test
    @DisplayName("classifies request and updates status to UPLOADING")
    void classifiesRequestAndUpdatesStatus() {
        var expectedClassification = new Classification(
                "feature", 3, List.of("api", "service"), "parallel"
        );

        LlmService mockLlm = mock(LlmService.class);
        when(mockLlm.structuredCall(anyString(), anyString(), eq(Classification.class)))
                .thenReturn(expectedClassification);

        var node = new ClassifyRequestNode(mockLlm);
        var state = new WorldmindState(Map.of("request", "Add a REST endpoint for users"));

        Map<String, Object> result = node.apply(state);

        assertEquals(expectedClassification, result.get("classification"));
        assertEquals(MissionStatus.UPLOADING.name(), result.get("status"));
    }

    @Test
    @DisplayName("passes the user request text to LlmService")
    void passesUserRequestToLlm() {
        var classification = new Classification(
                "bugfix", 2, List.of("service"), "sequential"
        );

        LlmService mockLlm = mock(LlmService.class);
        when(mockLlm.structuredCall(anyString(), anyString(), eq(Classification.class)))
                .thenReturn(classification);

        var node = new ClassifyRequestNode(mockLlm);
        String requestText = "Fix the null pointer in UserService.findById";
        var state = new WorldmindState(Map.of("request", requestText));

        node.apply(state);

        verify(mockLlm).structuredCall(anyString(), eq(requestText), eq(Classification.class));
    }

    @Test
    @DisplayName("handles different request types correctly")
    void handlesDifferentRequestTypes() {
        LlmService mockLlm = mock(LlmService.class);

        // Simulate a docs classification
        var docsClassification = new Classification(
                "docs", 1, List.of("docs"), "sequential"
        );
        when(mockLlm.structuredCall(anyString(), eq("Update the README"), eq(Classification.class)))
                .thenReturn(docsClassification);

        // Simulate a refactor classification
        var refactorClassification = new Classification(
                "refactor", 4, List.of("service", "model", "api"), "adaptive"
        );
        when(mockLlm.structuredCall(anyString(), eq("Refactor the entire persistence layer"), eq(Classification.class)))
                .thenReturn(refactorClassification);

        var node = new ClassifyRequestNode(mockLlm);

        // Test docs request
        var docsState = new WorldmindState(Map.of("request", "Update the README"));
        var docsResult = node.apply(docsState);
        assertEquals("docs", ((Classification) docsResult.get("classification")).category());
        assertEquals(1, ((Classification) docsResult.get("classification")).complexity());

        // Test refactor request
        var refactorState = new WorldmindState(Map.of("request", "Refactor the entire persistence layer"));
        var refactorResult = node.apply(refactorState);
        assertEquals("refactor", ((Classification) refactorResult.get("classification")).category());
        assertEquals(4, ((Classification) refactorResult.get("classification")).complexity());
        assertEquals("adaptive", ((Classification) refactorResult.get("classification")).planningStrategy());
    }

    @Test
    @DisplayName("result map contains exactly classification and status keys")
    void resultContainsExpectedKeys() {
        var classification = new Classification(
                "test", 2, List.of("test"), "sequential"
        );

        LlmService mockLlm = mock(LlmService.class);
        when(mockLlm.structuredCall(anyString(), anyString(), eq(Classification.class)))
                .thenReturn(classification);

        var node = new ClassifyRequestNode(mockLlm);
        var state = new WorldmindState(Map.of("request", "Write unit tests for UserService"));

        Map<String, Object> result = node.apply(state);

        assertEquals(2, result.size());
        assertTrue(result.containsKey("classification"));
        assertTrue(result.containsKey("status"));
    }
}
