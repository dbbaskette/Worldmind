package com.worldmind.sandbox;

import java.io.Serializable;
import java.nio.file.Path;
import java.util.Map;

/**
 * Everything needed to open a Sandbox container.
 *
 * @param agentType  e.g. "coder", "reviewer", "tester"
 * @param taskId    the task this sandbox serves
 * @param projectPath    host path to the project directory (bind-mounted as /workspace)
 * @param instructionText the full instruction markdown for Goose
 * @param envVars        environment variables to inject (GOOSE_PROVIDER, GOOSE_MODEL, etc.)
 * @param memoryLimitMb  memory limit in MB
 * @param cpuCount       CPU count limit
 */
public record AgentRequest(
    String agentType,
    String taskId,
    Path projectPath,
    String instructionText,
    Map<String, String> envVars,
    int memoryLimitMb,
    int cpuCount,
    String gitRemoteUrl,
    String runtimeTag
) implements Serializable {}
