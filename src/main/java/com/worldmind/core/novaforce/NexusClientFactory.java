package com.worldmind.core.novaforce;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Central factory that creates and caches {@link McpSyncClient} instances per consumer.
 * <p>
 * Each Core graph node authenticates to Nexus using its node-specific token.
 * Clients are created lazily and cached. Consumers sharing the same token
 * share the same underlying connection.
 */
@Component
public class NexusClientFactory {

    private static final Logger log = LoggerFactory.getLogger(NexusClientFactory.class);

    private final NexusProperties props;

    /** Cache: "consumer:token-hash" -> client. */
    private final Map<String, McpSyncClient> clientCache = new ConcurrentHashMap<>();

    public NexusClientFactory(NexusProperties props) {
        this.props = props;
    }

    /**
     * Returns an MCP client for a Core graph node.
     * The client authenticates to Nexus with the node's specific token.
     *
     * @param nodeName the node name (e.g. "classify", "plan", "seal")
     * @return the MCP sync client, or null if Nexus is disabled or no token configured
     */
    public McpSyncClient getClient(String nodeName) {
        if (!props.isEnabled()) return null;

        String token = props.getCoreToken(nodeName);
        if (token == null || token.isBlank()) {
            log.debug("No Nexus token for core node '{}' — returning null", nodeName);
            return null;
        }

        String cacheKey = "core:" + nodeName + ":" + token.hashCode();
        return clientCache.computeIfAbsent(cacheKey, key -> createClient(nodeName, token));
    }

    /**
     * Returns the Nexus token for a Centurion type (for injection into containers).
     *
     * @param centurionType the centurion type (e.g. "forge", "gauntlet", "pulse")
     * @return the token string, or null if not configured
     */
    public String getCenturionToken(String centurionType) {
        if (!props.isEnabled()) return null;
        return props.getCenturionToken(centurionType);
    }

    /**
     * Returns the Nexus gateway URL.
     */
    public String getNexusUrl() {
        return props.getUrl();
    }

    /**
     * Returns true when Nexus is enabled and the URL is configured.
     */
    public boolean isEnabled() {
        return props.isEnabled() && props.getUrl() != null && !props.getUrl().isBlank();
    }

    /**
     * Returns all cached clients (for health checks).
     */
    public Map<String, McpSyncClient> getClients() {
        return Collections.unmodifiableMap(clientCache);
    }

    public boolean hasClients() {
        return !clientCache.isEmpty();
    }

    private McpSyncClient createClient(String consumer, String token) {
        try {
            var transportBuilder = HttpClientStreamableHttpTransport.builder(props.getUrl());
            if (token != null && !token.isBlank()) {
                transportBuilder.customizeRequest(req ->
                        req.header("Authorization", "Bearer " + token));
            }

            var transport = transportBuilder.build();
            var client = McpClient.sync(transport)
                    .requestTimeout(Duration.ofSeconds(30))
                    .build();
            client.initialize();

            log.info("Nexus MCP client created for consumer '{}'", consumer);
            return client;
        } catch (Exception e) {
            log.warn("Failed to create Nexus MCP client for '{}': {}", consumer, e.getMessage());
            return null;
        }
    }

    @PreDestroy
    void shutdown() {
        for (var entry : clientCache.entrySet()) {
            try {
                if (entry.getValue() != null) {
                    entry.getValue().close();
                    log.info("Nexus MCP client closed (key: {})", entry.getKey());
                }
            } catch (Exception e) {
                log.debug("Error closing Nexus MCP client '{}': {}", entry.getKey(), e.getMessage());
            }
        }
        clientCache.clear();
    }
}
