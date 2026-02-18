package com.worldmind.core.model;

import java.io.Serializable;
import java.util.List;

/**
 * Structured output from the LLM representing a complete mission plan.
 * <p>
 * The LLM generates this plan based on a classified request and project context.
 * Each {@link DirectivePlan} maps to a {@link Directive} that will be executed
 * by a Centurion worker.
 *
 * @param objective         high-level description of the mission goal
 * @param executionStrategy one of "sequential" or "parallel"
 * @param directives        ordered list of directive plans to execute
 */
public record MissionPlan(
    String objective,
    String executionStrategy,
    List<DirectivePlan> directives
) implements Serializable {

    /**
     * A single planned directive within the mission.
     *
     * @param centurion       the Centurion type: FORGE, GAUNTLET, VIGIL, PULSE, or PRISM
     * @param description     what this directive should accomplish
     * @param inputContext    contextual information the Centurion needs
     * @param successCriteria how to determine if the directive succeeded
     * @param dependencies    IDs of directives this one depends on (e.g. "DIR-001")
     * @param targetFiles     files this directive intends to create or modify (for conflict detection)
     */
    public record DirectivePlan(
        String centurion,
        String description,
        String inputContext,
        String successCriteria,
        List<String> dependencies,
        List<String> targetFiles
    ) implements Serializable {}
}
