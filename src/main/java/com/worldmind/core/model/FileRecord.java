package com.worldmind.core.model;

import java.io.Serializable;

/**
 * Tracks a single file change made by a task execution.
 *
 * @param path         relative path within the project
 * @param action       one of "created", "modified", "deleted"
 * @param linesChanged number of lines added, changed, or removed
 */
public record FileRecord(
    String path,
    String action,
    int linesChanged
) implements Serializable {}
