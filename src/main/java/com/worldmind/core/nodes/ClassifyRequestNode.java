package com.worldmind.core.nodes;

import com.worldmind.core.llm.LlmService;
import com.worldmind.core.model.Classification;
import com.worldmind.core.model.MissionStatus;
import com.worldmind.core.state.WorldmindState;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Classifies the incoming user request using an LLM to determine its category,
 * complexity, affected components, and planning strategy.
 * <p>
 * Delegates to {@link LlmService} for structured output so the LLM returns
 * a valid {@link Classification} record.
 */
@Component
public class ClassifyRequestNode {

    private static final String SYSTEM_PROMPT = """
            You are a request classifier for Worldmind, an agentic code assistant.
            Classify the user's request into:
            - category: one of "feature", "bugfix", "refactor", "test", "docs", "research"
            - complexity: 1-5 scale (1=trivial, 5=system-wide)
            - affectedComponents: list of likely affected areas (e.g., "api", "model", "service", "ui", "config", "test")
            - planningStrategy: one of "sequential" (simple tasks), "parallel" (independent subtasks), "adaptive" (complex, needs dynamic planning)

            Respond with valid JSON matching the schema provided.
            """;

    private final LlmService llmService;

    public ClassifyRequestNode(LlmService llmService) {
        this.llmService = llmService;
    }

    public Map<String, Object> apply(WorldmindState state) {
        String request = state.request();
        Classification classification = llmService.structuredCall(
                SYSTEM_PROMPT, request, Classification.class
        );
        return Map.of(
                "classification", classification,
                "status", MissionStatus.UPLOADING.name()
        );
    }
}
