package com.worldmind.core.novaforce;

import io.modelcontextprotocol.client.McpSyncClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.mcp.SyncMcpToolCallbackProvider;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Translates MCP tool listings from Nexus into Spring AI {@link ToolCallback} arrays.
 * <p>
 * Each Core graph node calls {@link #getToolsForNode(String)} to get its scoped tools.
 * The node name determines which auth token is used, which controls tool visibility
 * on the Nexus side.
 */
@Component
public class NovaForceToolProvider {

    private static final Logger log = LoggerFactory.getLogger(NovaForceToolProvider.class);
    private static final ToolCallback[] EMPTY = new ToolCallback[0];

    private final NexusClientFactory clientFactory;

    public NovaForceToolProvider(NexusClientFactory clientFactory) {
        this.clientFactory = clientFactory;
    }

    /**
     * Returns tools available to a specific Core graph node.
     *
     * @param nodeName the node name (e.g. "classify", "plan", "converge", "seal", "postmission")
     * @return tool callbacks ready for ChatOptionsBuilder.withTools(), or empty array
     */
    public ToolCallback[] getToolsForNode(String nodeName) {
        if (!clientFactory.isEnabled()) {
            return EMPTY;
        }

        McpSyncClient client = clientFactory.getClient(nodeName);
        if (client == null) {
            return EMPTY;
        }

        try {
            var provider = new SyncMcpToolCallbackProvider(List.of(client));
            var tools = provider.getToolCallbacks();
            log.debug("Nexus tool discovery for node '{}' returned {} tool(s)", nodeName, tools.length);
            return tools;
        } catch (Exception e) {
            log.warn("Failed to discover Nexus tools for node '{}': {}", nodeName, e.getMessage());
            return EMPTY;
        }
    }

    /**
     * Returns true if Nexus is enabled and configured.
     */
    public boolean isEnabled() {
        return clientFactory.isEnabled();
    }
}
