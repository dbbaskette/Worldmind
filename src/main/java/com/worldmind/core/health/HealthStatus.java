package com.worldmind.core.health;

import java.util.Map;

public record HealthStatus(
    String component,
    Status status,
    String detail,
    Map<String, String> metadata
) {
    public enum Status { UP, DOWN, DEGRADED }
}
