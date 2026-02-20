package com.worldmind.core.model;

import java.io.Serializable;
import java.time.Instant;

/**
 * Metadata about a Sandbox container running a Agent for a specific task.
 */
public record SandboxInfo(
    String containerId,
    String agentType,
    String taskId,
    String status,
    Instant startedAt,
    Instant completedAt
) implements Serializable {}
