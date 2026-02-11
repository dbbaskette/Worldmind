package com.worldmind.core.llm;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Service;

/**
 * Reusable service that wraps Spring AI's {@link ChatClient} to produce
 * structured (typed) output from LLM calls.
 * <p>
 * Uses {@link BeanOutputConverter} to generate a JSON schema from the target
 * Java class, append format instructions to the user prompt, and deserialize
 * the LLM's JSON response into the requested type.
 */
@Service
public class LlmService {

    private static final Logger log = LoggerFactory.getLogger(LlmService.class);

    private final ChatClient chatClient;

    public LlmService(ChatClient.Builder builder,
                      @org.springframework.beans.factory.annotation.Value("${spring.ai.openai.base-url:NOT_SET}") String baseUrl) {
        this.chatClient = builder.build();
        log.info("LlmService initialized — OpenAI base-url: {}", baseUrl);
    }

    /**
     * Sends a system + user prompt to the LLM and returns the response
     * deserialized into the given {@code outputType}.
     *
     * @param systemPrompt instructions for the LLM's role / behaviour
     * @param userPrompt   the end-user's request text
     * @param outputType   the Java class (record or POJO) to deserialize into
     * @param <T>          target type
     * @return an instance of {@code T} populated from the LLM's JSON response
     */
    public <T> T structuredCall(String systemPrompt, String userPrompt, Class<T> outputType) {
        log.info("LLM call started → {}", outputType.getSimpleName());
        long start = System.currentTimeMillis();
        var converter = new BeanOutputConverter<>(outputType);
        log.info("Sending prompt to model...");
        var callResponse = chatClient.prompt()
                .system(systemPrompt)
                .user(userPrompt + "\n\n" + converter.getFormat())
                .call();
        log.info("Model responded, extracting content...");
        String response = callResponse.content();
        long elapsed = System.currentTimeMillis() - start;
        log.info("LLM call complete → {} ({}s)", outputType.getSimpleName(), String.format("%.1f", elapsed / 1000.0));
        if (response == null || response.isBlank()) {
            throw new LlmEmptyResponseException("LLM returned empty content for " + outputType.getSimpleName()
                    + ". Check that the model is running and supports structured JSON output.");
        }
        return converter.convert(response);
    }

    /**
     * Like {@link #structuredCall}, but also provides MCP tools to the LLM.
     * Falls back to the tool-less path when no tools are supplied.
     */
    public <T> T structuredCallWithTools(String systemPrompt, String userPrompt,
                                          Class<T> outputType, ToolCallback... tools) {
        if (tools == null || tools.length == 0) {
            return structuredCall(systemPrompt, userPrompt, outputType);
        }
        log.info("LLM call with {} tool(s) started → {}", tools.length, outputType.getSimpleName());
        long start = System.currentTimeMillis();
        var converter = new BeanOutputConverter<>(outputType);
        var callResponse = chatClient.prompt()
                .system(systemPrompt)
                .user(userPrompt + "\n\n" + converter.getFormat())
                .toolCallbacks(tools)
                .call();
        String response = callResponse.content();
        long elapsed = System.currentTimeMillis() - start;
        log.info("LLM call with tools complete → {} ({}s)", outputType.getSimpleName(),
                String.format("%.1f", elapsed / 1000.0));
        if (response == null || response.isBlank()) {
            throw new LlmEmptyResponseException("LLM returned empty content for " + outputType.getSimpleName()
                    + ". Check that the model is running and supports structured JSON output.");
        }
        return converter.convert(response);
    }
}
