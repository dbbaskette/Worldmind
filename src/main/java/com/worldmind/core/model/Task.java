package com.worldmind.core.model;

import java.io.Serializable;
import java.util.List;

/**
 * A single unit of work within a mission plan, executed by a Agent inside a Sandbox container.
 * 
 * @param id unique identifier (e.g., "TASK-001")
 * @param agent type of worker: CODER, RESEARCHER, REFACTORER, TESTER, REVIEWER
 * @param description what this task should accomplish
 * @param inputContext additional context/constraints for the agent
 * @param successCriteria how to determine if the task succeeded
 * @param dependencies IDs of tasks that must complete first
 * @param status current execution status
 * @param iteration current retry attempt (starts at 0)
 * @param maxIterations maximum retry attempts before escalation
 * @param onFailure strategy when task fails
 * @param targetFiles files this task intends to create/modify (set by planner, used for conflict detection)
 * @param filesAffected files actually changed (populated after execution from git diff)
 * @param elapsedMs execution time in milliseconds
 */
public record Task(
    String id,
    String agent,
    String description,
    String inputContext,
    String successCriteria,
    List<String> dependencies,
    TaskStatus status,
    int iteration,
    int maxIterations,
    FailureStrategy onFailure,
    List<String> targetFiles,
    List<FileRecord> filesAffected,
    Long elapsedMs
) implements Serializable {
}
