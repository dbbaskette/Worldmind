package com.worldmind.mcp;

import io.modelcontextprotocol.client.McpSyncClient;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Actuator health indicator for MCP server connections.
 * Pings one cached client to verify connectivity.
 */
@Component
@ConditionalOnProperty(prefix = "worldmind.mcp", name = "enabled", havingValue = "true")
public class McpHealthIndicator implements HealthIndicator {

    private final McpClientManager clientManager;
    private final McpProperties props;

    public McpHealthIndicator(McpClientManager clientManager, McpProperties props) {
        this.clientManager = clientManager;
        this.props = props;
    }

    @Override
    public Health health() {
        if (!clientManager.isConfigured()) {
            return Health.unknown().withDetail("reason", "not configured").build();
        }

        // No clients created yet (none requested) — report configured but idle
        if (!clientManager.hasClients()) {
            var builder = Health.up();
            for (var entry : props.getServers().entrySet()) {
                if (entry.getValue().getUrl() != null && !entry.getValue().getUrl().isBlank()) {
                    builder.withDetail(entry.getKey(), "configured (no active connections yet)");
                }
            }
            return builder.build();
        }

        // Ping one cached client per unique connection to verify connectivity
        var builder = Health.up();
        boolean anyDown = false;

        for (var entry : clientManager.getClients().entrySet()) {
            McpSyncClient client = entry.getValue();
            if (client == null) continue;
            try {
                client.ping();
                builder.withDetail(entry.getKey(), "UP");
            } catch (Exception e) {
                builder.withDetail(entry.getKey(), "DOWN: " + e.getMessage());
                anyDown = true;
            }
        }

        return anyDown ? builder.status("DEGRADED").build() : builder.build();
    }
}
