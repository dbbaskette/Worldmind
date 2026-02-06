package com.worldmind.stargate;

import java.io.Serializable;
import java.nio.file.Path;
import java.util.Map;

/**
 * Everything needed to open a Stargate container.
 *
 * @param centurionType  e.g. "forge", "vigil", "gauntlet"
 * @param directiveId    the directive this stargate serves
 * @param projectPath    host path to the project directory (bind-mounted as /workspace)
 * @param instructionText the full instruction markdown for Goose
 * @param envVars        environment variables to inject (GOOSE_PROVIDER, GOOSE_MODEL, etc.)
 * @param memoryLimitMb  memory limit in MB
 * @param cpuCount       CPU count limit
 */
public record StargateRequest(
    String centurionType,
    String directiveId,
    Path projectPath,
    String instructionText,
    Map<String, String> envVars,
    int memoryLimitMb,
    int cpuCount
) implements Serializable {}
