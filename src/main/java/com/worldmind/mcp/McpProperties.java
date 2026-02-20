package com.worldmind.mcp;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * Configuration properties for remote MCP server connections.
 * <p>
 * Supports multiple named servers, each with a URL, a shared token,
 * and optional per-consumer token overrides (for scoped permissions).
 *
 * <pre>
 * worldmind:
 *   mcp:
 *     enabled: true
 *     servers:
 *       nexus:
 *         url: https://nexus.example.com/sse
 *         token: shared-token
 *         tokens:
 *           orchestrator: scoped-token-for-orchestrator
 *           coder: scoped-token-for-coder
 * </pre>
 */
@Component
@ConfigurationProperties(prefix = "worldmind.mcp")
public class McpProperties {

    private boolean enabled = false;
    private Map<String, ServerConfig> servers = new HashMap<>();

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public Map<String, ServerConfig> getServers() { return servers; }
    public void setServers(Map<String, ServerConfig> servers) { this.servers = servers; }

    /**
     * Returns {@code true} when MCP is enabled and at least one server has a URL.
     */
    public boolean isConfigured() {
        return enabled && !servers.isEmpty()
                && servers.values().stream()
                    .anyMatch(s -> s.getUrl() != null && !s.getUrl().isBlank());
    }

    public static class ServerConfig {
        private String url = "";
        private String token = "";
        private Map<String, String> tokens = new HashMap<>();

        public String getUrl() { return url; }
        public void setUrl(String url) { this.url = url; }
        public String getToken() { return token; }
        public void setToken(String token) { this.token = token; }
        public Map<String, String> getTokens() { return tokens; }
        public void setTokens(Map<String, String> tokens) { this.tokens = tokens; }

        /**
         * Returns the token for a specific consumer (e.g. "orchestrator", "coder").
         * Falls back to the shared token if no per-consumer override exists.
         */
        public String getTokenFor(String consumer) {
            String specific = tokens.get(consumer.toLowerCase());
            if (specific != null && !specific.isBlank()) return specific;
            return token;
        }
    }
}
