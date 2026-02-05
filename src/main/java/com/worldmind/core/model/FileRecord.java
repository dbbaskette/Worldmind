package com.worldmind.core.model;

/**
 * Tracks a single file change made by a directive execution.
 *
 * @param path         relative path within the project
 * @param action       one of "created", "modified", "deleted"
 * @param linesChanged number of lines added, changed, or removed
 */
public record FileRecord(
    String path,
    String action,
    int linesChanged
) {}
