package com.worldmind.mcp;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages MCP sync client lifecycle for all configured servers.
 * <p>
 * Creates MCP clients on-demand per consumer (e.g. "classify", "plan", "coder"),
 * each authenticated with that consumer's scoped token. Clients are cached and
 * reused — consumers sharing the same token share the same connection.
 */
@Component
public class McpClientManager {

    private static final Logger log = LoggerFactory.getLogger(McpClientManager.class);

    private final McpProperties props;

    /** Cache: "server:token-hash" → client. Consumers with the same token share a client. */
    private final Map<String, McpSyncClient> clientCache = new ConcurrentHashMap<>();

    /** Reverse lookup: cache key → server name (for health/shutdown). */
    private final Map<String, String> cacheKeyToServer = new ConcurrentHashMap<>();

    public McpClientManager(McpProperties props) {
        this.props = props;
    }

    @PostConstruct
    void init() {
        if (!props.isConfigured()) {
            log.info("MCP servers disabled or not configured");
            return;
        }

        for (var entry : props.getServers().entrySet()) {
            String name = entry.getKey();
            var config = entry.getValue();
            if (config.getUrl() == null || config.getUrl().isBlank()) continue;

            log.info("MCP server '{}' configured at {}", name, config.getUrl());

            // Startup connectivity check — create one test client to verify the server is reachable
            // and list available tools. Uses the first non-blank token found.
            String testConsumer = config.getTokens().entrySet().stream()
                    .filter(e -> e.getValue() != null && !e.getValue().isBlank())
                    .map(Map.Entry::getKey)
                    .findFirst()
                    .orElse("default");
            try {
                var client = getClientFor(testConsumer);
                if (client != null) {
                    var tools = client.listTools();
                    log.info("MCP server '{}' connected — {} tool(s) available", name,
                            tools.tools() != null ? tools.tools().size() : 0);
                    if (tools.tools() != null) {
                        tools.tools().forEach(t -> log.info("  MCP tool: {}", t.name()));
                    }
                }
            } catch (Exception e) {
                log.warn("MCP server '{}' startup check failed: {}", name, e.getMessage());
            }
        }
    }

    /**
     * Returns a client for the given consumer, creating and caching it if needed.
     * Consumers with identical tokens share the same underlying MCP connection.
     *
     * @param consumer the consumer name (e.g. "classify", "plan", "coder")
     * @return the MCP sync client, or null if not configured
     */
    public McpSyncClient getClientFor(String consumer) {
        if (!props.isConfigured()) return null;

        // For now, we use the first configured server. Multi-server support
        // would iterate all servers and aggregate.
        for (var entry : props.getServers().entrySet()) {
            String serverName = entry.getKey();
            var config = entry.getValue();
            if (config.getUrl() == null || config.getUrl().isBlank()) continue;

            String token = config.getTokenFor(consumer);
            String cacheKey = serverName + ":" + (token != null ? token.hashCode() : "none");

            return clientCache.computeIfAbsent(cacheKey, key -> {
                try {
                    var transportBuilder = HttpClientStreamableHttpTransport.builder(config.getUrl());
                    if (token != null && !token.isBlank()) {
                        transportBuilder.customizeRequest(req ->
                                req.header("Authorization", "Bearer " + token));
                    }

                    var transport = transportBuilder.build();
                    var client = McpClient.sync(transport)
                            .requestTimeout(Duration.ofSeconds(30))
                            .build();
                    client.initialize();

                    cacheKeyToServer.put(key, serverName);
                    log.info("MCP client created for consumer '{}' on server '{}'", consumer, serverName);
                    return client;
                } catch (Exception e) {
                    log.warn("Failed to create MCP client for consumer '{}' on server '{}': {}",
                            consumer, serverName, e.getMessage());
                    return null;
                }
            });
        }
        return null;
    }

    /**
     * Returns all currently cached clients (for health checks).
     */
    public Map<String, McpSyncClient> getClients() {
        return Collections.unmodifiableMap(clientCache);
    }

    /**
     * Returns all cached clients as a list (for aggregated tool discovery).
     */
    public List<McpSyncClient> getClientList() {
        var list = new ArrayList<McpSyncClient>();
        for (var client : clientCache.values()) {
            if (client != null) list.add(client);
        }
        return list;
    }

    public boolean hasClients() {
        return !clientCache.isEmpty();
    }

    public boolean isConfigured() {
        return props.isConfigured();
    }

    @PreDestroy
    void shutdown() {
        for (var entry : clientCache.entrySet()) {
            try {
                if (entry.getValue() != null) {
                    entry.getValue().close();
                    String server = cacheKeyToServer.getOrDefault(entry.getKey(), "unknown");
                    log.info("MCP client disconnected (server: {})", server);
                }
            } catch (Exception e) {
                log.debug("Error closing MCP client '{}': {}", entry.getKey(), e.getMessage());
            }
        }
        clientCache.clear();
        cacheKeyToServer.clear();
    }
}
