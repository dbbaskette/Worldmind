package com.worldmind.core.llm;

/**
 * Thrown when the LLM returns null or blank content instead of a valid response.
 */
public class LlmEmptyResponseException extends RuntimeException {

    public LlmEmptyResponseException(String message) {
        super(message);
    }
}
