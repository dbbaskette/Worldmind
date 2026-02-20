package com.worldmind.core.model;

import java.io.Serializable;
import java.util.List;

/**
 * AI-generated review feedback for code produced by a task execution.
 */
public record ReviewFeedback(
    String taskId,
    boolean approved,
    String summary,
    List<String> issues,
    List<String> suggestions,
    int score
) implements Serializable {}
