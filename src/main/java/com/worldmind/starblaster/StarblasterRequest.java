package com.worldmind.starblaster;

import java.io.Serializable;
import java.nio.file.Path;
import java.util.Map;

/**
 * Everything needed to open a Starblaster container.
 *
 * @param centurionType  e.g. "forge", "vigil", "gauntlet"
 * @param directiveId    the directive this starblaster serves
 * @param projectPath    host path to the project directory (bind-mounted as /workspace)
 * @param instructionText the full instruction markdown for Goose
 * @param envVars        environment variables to inject (GOOSE_PROVIDER, GOOSE_MODEL, etc.)
 * @param memoryLimitMb  memory limit in MB
 * @param cpuCount       CPU count limit
 */
public record StarblasterRequest(
    String centurionType,
    String directiveId,
    Path projectPath,
    String instructionText,
    Map<String, String> envVars,
    int memoryLimitMb,
    int cpuCount,
    String gitRemoteUrl
) implements Serializable {}
