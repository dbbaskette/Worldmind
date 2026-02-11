package com.worldmind.core.model;

import java.io.Serializable;
import java.util.List;

/**
 * Result of classifying a user request — determines complexity and planning approach.
 */
public record Classification(
    String category,
    int complexity,
    List<String> affectedComponents,
    String planningStrategy,
    String runtimeTag
) implements Serializable {}
