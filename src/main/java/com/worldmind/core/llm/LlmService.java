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
        try {
            return converter.convert(response);
        } catch (Exception e) {
            log.error("Failed to parse LLM response to {}: {}", outputType.getSimpleName(), e.getMessage());
            log.debug("Raw LLM response: {}", response);
            // Try manual JSON parsing with Jackson for complex nested records
            return parseWithJackson(response, outputType);
        }
    }

    /**
     * Fallback JSON parsing using Jackson ObjectMapper with lenient settings.
     */
    private <T> T parseWithJackson(String json, Class<T> outputType) {
        log.info("Attempting Jackson fallback parsing for {}", outputType.getSimpleName());
        try {
            var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            mapper.configure(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
            mapper.configure(com.fasterxml.jackson.databind.DeserializationFeature.ACCEPT_EMPTY_STRING_AS_NULL_OBJECT, true);
            mapper.configure(com.fasterxml.jackson.databind.DeserializationFeature.ACCEPT_SINGLE_VALUE_AS_ARRAY, true);
            // Enable record support (available in Jackson 2.12+)
            mapper.registerModule(new com.fasterxml.jackson.module.paramnames.ParameterNamesModule());
            
            // Extract JSON from markdown code blocks if present
            String cleaned = json.trim();
            if (cleaned.startsWith("```json")) {
                cleaned = cleaned.substring(7);
            } else if (cleaned.startsWith("```")) {
                cleaned = cleaned.substring(3);
            }
            if (cleaned.endsWith("```")) {
                cleaned = cleaned.substring(0, cleaned.length() - 3);
            }
            cleaned = cleaned.trim();
            
            log.info("Jackson parsing cleaned JSON ({} chars)", cleaned.length());
            T result = mapper.readValue(cleaned, outputType);
            log.info("Jackson fallback parsing succeeded for {}", outputType.getSimpleName());
            return result;
        } catch (Exception e2) {
            log.error("Jackson fallback parsing FAILED for {}: {}", outputType.getSimpleName(), e2.getMessage());
            throw new LlmParseException("Failed to parse LLM response to " + outputType.getSimpleName() 
                    + ": " + e2.getMessage(), e2);
        }
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
        try {
            return converter.convert(response);
        } catch (Exception e) {
            log.error("Failed to parse LLM response to {}: {}", outputType.getSimpleName(), e.getMessage());
            log.debug("Raw LLM response: {}", response);
            return parseWithJackson(response, outputType);
        }
    }
}
