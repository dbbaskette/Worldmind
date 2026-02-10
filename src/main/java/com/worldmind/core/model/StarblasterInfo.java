package com.worldmind.core.model;

import java.io.Serializable;
import java.time.Instant;

/**
 * Metadata about a Starblaster container running a Centurion for a specific directive.
 */
public record StarblasterInfo(
    String containerId,
    String centurionType,
    String directiveId,
    String status,
    Instant startedAt,
    Instant completedAt
) implements Serializable {}
