package com.worldmind.core.model;

import java.io.Serializable;
import java.util.List;

/**
 * A single unit of work within a mission plan, executed by a Centurion inside a Starblaster container.
 * 
 * @param id unique identifier (e.g., "DIR-001")
 * @param centurion type of worker: FORGE, PULSE, PRISM, GAUNTLET, VIGIL
 * @param description what this directive should accomplish
 * @param inputContext additional context/constraints for the centurion
 * @param successCriteria how to determine if the directive succeeded
 * @param dependencies IDs of directives that must complete first
 * @param status current execution status
 * @param iteration current retry attempt (starts at 0)
 * @param maxIterations maximum retry attempts before escalation
 * @param onFailure strategy when directive fails
 * @param targetFiles files this directive intends to create/modify (set by planner, used for conflict detection)
 * @param filesAffected files actually changed (populated after execution from git diff)
 * @param elapsedMs execution time in milliseconds
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
    List<String> targetFiles,
    List<FileRecord> filesAffected,
    Long elapsedMs
) implements Serializable {
}
