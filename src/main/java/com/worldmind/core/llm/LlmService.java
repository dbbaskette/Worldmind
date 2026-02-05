package com.worldmind.core.llm;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.converter.BeanOutputConverter;
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

    private final ChatClient chatClient;

    public LlmService(ChatClient.Builder builder) {
        this.chatClient = builder.build();
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
        var converter = new BeanOutputConverter<>(outputType);
        String response = chatClient.prompt()
                .system(systemPrompt)
                .user(userPrompt + "\n\n" + converter.getFormat())
                .call()
                .content();
        return converter.convert(response);
    }
}
