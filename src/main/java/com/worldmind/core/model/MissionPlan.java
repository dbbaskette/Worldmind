package com.worldmind.core.model;

import java.io.Serializable;
import java.util.List;

/**
 * Structured output from the LLM representing a complete mission plan.
 * <p>
 * The LLM generates this plan based on a classified request and project context.
 * Each {@link TaskPlan} maps to a {@link Task} that will be executed
 * by a Agent worker.
 *
 * @param objective         high-level description of the mission goal
 * @param executionStrategy one of "sequential" or "parallel"
 * @param tasks        ordered list of task plans to execute
 */
public record MissionPlan(
    String objective,
    String executionStrategy,
    List<TaskPlan> tasks
) implements Serializable {

    /**
     * A single planned task within the mission.
     *
     * @param agent       the Agent type: CODER, TESTER, REVIEWER, RESEARCHER, or REFACTORER
     * @param description     what this task should accomplish
     * @param inputContext    contextual information the Agent needs
     * @param successCriteria how to determine if the task succeeded
     * @param dependencies    IDs of tasks this one depends on (e.g. "TASK-001")
     * @param targetFiles     files this task intends to create or modify (for conflict detection)
     */
    public record TaskPlan(
        String agent,
        String description,
        String inputContext,
        String successCriteria,
        List<String> dependencies,
        List<String> targetFiles
    ) implements Serializable {}
}
