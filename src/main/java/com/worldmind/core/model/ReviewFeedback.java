package com.worldmind.core.model;

import java.util.List;

/**
 * AI-generated review feedback for code produced by a directive execution.
 */
public record ReviewFeedback(
    String directiveId,
    boolean approved,
    String summary,
    List<String> issues,
    List<String> suggestions,
    int score
) {}
