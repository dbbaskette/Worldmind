package com.worldmind.core.novaforce;

import io.modelcontextprotocol.client.McpSyncClient;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Actuator health indicator for the Nexus MCP Gateway.
 * <p>
 * Only active when {@code worldmind.nexus.enabled=true}. Reports UP when
 * Nexus responds to a ping, DOWN otherwise. Includes URL and tool count.
 */
@Component("nexusHealthIndicator")
@ConditionalOnProperty(prefix = "worldmind.nexus", name = "enabled", havingValue = "true")
public class NexusHealthIndicator implements HealthIndicator {

    private final NexusClientFactory clientFactory;
    private final NexusProperties props;

    public NexusHealthIndicator(NexusClientFactory clientFactory, NexusProperties props) {
        this.clientFactory = clientFactory;
        this.props = props;
    }

    @Override
    public Health health() {
        if (!clientFactory.isEnabled()) {
            return Health.unknown().withDetail("reason", "not configured").build();
        }

        var builder = Health.up()
                .withDetail("nexus.url", props.getUrl());

        // No clients created yet — report configured but idle
        if (!clientFactory.hasClients()) {
            return builder.withDetail("nexus.status", "configured (no active connections yet)").build();
        }

        // Ping cached clients to verify connectivity
        boolean anyDown = false;
        int totalTools = 0;

        for (var entry : clientFactory.getClients().entrySet()) {
            McpSyncClient client = entry.getValue();
            if (client == null) continue;
            try {
                client.ping();
                var tools = client.listTools();
                int toolCount = tools.tools() != null ? tools.tools().size() : 0;
                totalTools += toolCount;
                builder.withDetail(entry.getKey(), "UP (" + toolCount + " tools)");
            } catch (Exception e) {
                builder.withDetail(entry.getKey(), "DOWN: " + e.getMessage());
                anyDown = true;
            }
        }

        builder.withDetail("nexus.toolCount", totalTools);
        return anyDown ? builder.status("DEGRADED").build() : builder.build();
    }
}
