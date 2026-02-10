package com.worldmind.dispatch.api;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Inbound JSON body for POST /api/v1/missions.
 *
 * @param request      natural-language mission request
 * @param mode         interaction mode (FULL_AUTO, APPROVE_PLAN, STEP_BY_STEP); nullable — defaults to APPROVE_PLAN
 * @param projectPath  absolute path to the project directory
 * @param gitRemoteUrl git remote URL for CF workspace coordination; nullable — falls back to config
 */
public record MissionRequest(
    String request,
    String mode,
    @JsonProperty("project_path") String projectPath,
    @JsonProperty("git_remote_url") String gitRemoteUrl
) {}
