package com.worldmind.core.nodes;

import com.worldmind.core.llm.LlmService;
import com.worldmind.core.model.Classification;
import com.worldmind.core.model.MissionStatus;
import com.worldmind.core.state.WorldmindState;
import com.worldmind.mcp.McpToolProvider;
import org.springframework.beans.factory.annotation.Autowired;
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
            - runtimeTag: the Docker image tag for the language/toolchain needed.
              Determine from explicit mentions in the request or common project indicators.
              Must be one of: "java", "python", "node", "rust", "go", "dotnet", or "base" (when unknown).
              Examples: "Spring Boot app" → "java", "React frontend" → "node", "Flask API" → "python"

            Respond with valid JSON matching the schema provided.
            """;

    private final LlmService llmService;
    private final McpToolProvider mcpToolProvider;

    public ClassifyRequestNode(LlmService llmService,
                               @Autowired(required = false) McpToolProvider mcpToolProvider) {
        this.llmService = llmService;
        this.mcpToolProvider = mcpToolProvider;
    }

    public Map<String, Object> apply(WorldmindState state) {
        // Early-exit for retry: if classification already exists, skip re-classifying
        if (state.classification().isPresent()) {
            return Map.of("status", MissionStatus.UPLOADING.name());
        }

        String request = state.request();
        Classification classification = (mcpToolProvider != null && mcpToolProvider.hasTools())
                ? llmService.structuredCallWithTools(SYSTEM_PROMPT, request, Classification.class, mcpToolProvider.getToolsFor("classify"))
                : llmService.structuredCall(SYSTEM_PROMPT, request, Classification.class);
        return Map.of(
                "classification", classification,
                "status", MissionStatus.UPLOADING.name()
        );
    }
}
