package com.worldmind.core.llm;

/**
 * Thrown when LLM output cannot be parsed into the expected type.
 */
public class LlmParseException extends RuntimeException {
    public LlmParseException(String message) {
        super(message);
    }

    public LlmParseException(String message, Throwable cause) {
        super(message, cause);
    }
}
