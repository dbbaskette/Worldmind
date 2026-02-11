package com.worldmind.mcp;

import io.modelcontextprotocol.client.McpSyncClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.mcp.SyncMcpToolCallbackProvider;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Provides MCP tools to LLM calls, scoped per consumer.
 * <p>
 * Each consumer (e.g. "classify", "plan", "seal") gets tools via its own
 * authenticated MCP client, ensuring Nexus-side permission scoping.
 */
@Component
public class McpToolProvider {

    private static final Logger log = LoggerFactory.getLogger(McpToolProvider.class);
    private static final ToolCallback[] EMPTY = new ToolCallback[0];

    private final McpClientManager clientManager;

    public McpToolProvider(McpClientManager clientManager) {
        this.clientManager = clientManager;
    }

    /**
     * Returns tools available to a specific consumer.
     * The consumer name determines which auth token is used,
     * which controls tool visibility in Nexus.
     *
     * @param consumer the consumer name (e.g. "classify", "plan", "seal", "forge")
     */
    public ToolCallback[] getToolsFor(String consumer) {
        McpSyncClient client = clientManager.getClientFor(consumer);
        if (client == null) {
            return EMPTY;
        }
        try {
            var provider = new SyncMcpToolCallbackProvider(List.of(client));
            var tools = provider.getToolCallbacks();
            log.debug("MCP tool discovery for '{}' returned {} tool(s)", consumer, tools.length);
            return tools;
        } catch (Exception e) {
            log.warn("Failed to discover MCP tools for '{}': {}", consumer, e.getMessage());
            return EMPTY;
        }
    }

    /**
     * Returns true if MCP is configured (clients will be created on-demand).
     */
    public boolean hasTools() {
        return clientManager.isConfigured();
    }
}
