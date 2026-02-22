package com.worldmind.core.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.web.client.RestClientCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;

/**
 * Workaround for Spring AI 1.1.1+ bug (spring-projects/spring-ai#5196)
 * where an empty {@code extra_body} map is always serialized into OpenAI
 * API requests, causing a 400 "Unknown parameter" rejection.
 *
 * Registers a {@link RestClientCustomizer} that strips {@code extra_body}
 * from the JSON payload before it reaches the OpenAI endpoint.
 *
 * Remove this class once Spring AI ships a fix (expected 1.1.3+).
 */
@Configuration
@ConditionalOnProperty(name = "spring.ai.openai.enabled", havingValue = "true", matchIfMissing = false)
public class OpenAiExtraBodyWorkaround {

    private static final Logger log = LoggerFactory.getLogger(OpenAiExtraBodyWorkaround.class);
    private static final String EXTRA_BODY = "extra_body";
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Bean
    RestClientCustomizer openAiExtraBodyRemover() {
        log.info("Registering OpenAI extra_body workaround (spring-ai#5196)");
        return builder -> builder.requestInterceptor((request, body, execution) -> {
            MediaType contentType = request.getHeaders().getContentType();
            boolean isJson = contentType != null && contentType.isCompatibleWith(MediaType.APPLICATION_JSON);
            if (!isJson) {
                return execution.execute(request, body);
            }

            try {
                var root = objectMapper.readTree(body);
                if (root instanceof ObjectNode obj && obj.has(EXTRA_BODY)) {
                    obj.remove(EXTRA_BODY);
                    byte[] cleaned = objectMapper.writeValueAsBytes(obj);
                    return execution.execute(request, cleaned);
                }
            } catch (Exception e) {
                log.debug("Could not inspect request body for extra_body removal: {}", e.getMessage());
            }

            return execution.execute(request, body);
        });
    }
}
