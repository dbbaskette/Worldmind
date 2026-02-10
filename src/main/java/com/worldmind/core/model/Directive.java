package com.worldmind.core.model;

import java.io.Serializable;
import java.util.List;

/**
 * A single unit of work within a mission plan, executed by a Centurion inside a Starblaster container.
 */
public record Directive(
    String id,
    String centurion,
    String description,
    String inputContext,
    String successCriteria,
    List<String> dependencies,
    DirectiveStatus status,
    int iteration,
    int maxIterations,
    FailureStrategy onFailure,
    List<FileRecord> filesAffected,
    Long elapsedMs
) implements Serializable {}
