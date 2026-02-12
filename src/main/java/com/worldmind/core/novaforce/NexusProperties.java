package com.worldmind.core.novaforce;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * Configuration properties for the Nexus MCP Gateway connection.
 * <p>
 * Binds {@code worldmind.nexus.*} from application.yml / environment variables.
 * Each Core graph node and Centurion type authenticates with its own token,
 * enabling Nexus-side per-consumer permission scoping.
 *
 * <pre>
 * worldmind:
 *   nexus:
 *     enabled: true
 *     url: http://nexus:8090/mcp
 *     core:
 *       classify:
 *         token: ...
 *     centurions:
 *       forge:
 *         token: ...
 * </pre>
 */
@Component
@ConfigurationProperties(prefix = "worldmind.nexus")
public class NexusProperties {

    private static final Logger log = LoggerFactory.getLogger(NexusProperties.class);

    private boolean enabled = false;
    private String url = "";
    private Map<String, TokenConfig> core = new HashMap<>();
    private Map<String, TokenConfig> centurions = new HashMap<>();

    @PostConstruct
    void validate() {
        if (enabled && (url == null || url.isBlank())) {
            throw new IllegalStateException(
                    "NEXUS_ENABLED=true but NEXUS_URL is not set. "
                    + "Provide a valid Nexus gateway URL or set NEXUS_ENABLED=false.");
        }
        if (enabled) {
            log.info("Nexus MCP Gateway enabled at {}", url);
            int coreTokens = (int) core.values().stream()
                    .filter(tc -> tc.getToken() != null && !tc.getToken().isBlank())
                    .count();
            int centurionTokens = (int) centurions.values().stream()
                    .filter(tc -> tc.getToken() != null && !tc.getToken().isBlank())
                    .count();
            log.info("Nexus tokens configured: {} core, {} centurion", coreTokens, centurionTokens);

            // Warn about missing tokens (allows incremental rollout)
            core.forEach((name, tc) -> {
                if (tc.getToken() == null || tc.getToken().isBlank()) {
                    log.warn("Nexus core token missing for '{}' — node will have no MCP tools", name);
                }
            });
            centurions.forEach((name, tc) -> {
                if (tc.getToken() == null || tc.getToken().isBlank()) {
                    log.warn("Nexus centurion token missing for '{}' — container will have no MCP tools", name);
                }
            });
        } else {
            log.info("Nexus MCP Gateway disabled");
        }
    }

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public String getUrl() { return url; }
    public void setUrl(String url) { this.url = url; }
    public Map<String, TokenConfig> getCore() { return core; }
    public void setCore(Map<String, TokenConfig> core) { this.core = core; }
    public Map<String, TokenConfig> getCenturions() { return centurions; }
    public void setCenturions(Map<String, TokenConfig> centurions) { this.centurions = centurions; }

    /**
     * Returns the token for a Core graph node (e.g. "classify", "plan", "seal").
     */
    public String getCoreToken(String nodeName) {
        TokenConfig tc = core.get(nodeName.toLowerCase());
        return tc != null ? tc.getToken() : null;
    }

    /**
     * Returns the token for a Centurion type (e.g. "forge", "gauntlet", "pulse").
     */
    public String getCenturionToken(String centurionType) {
        TokenConfig tc = centurions.get(centurionType.toLowerCase());
        return tc != null ? tc.getToken() : null;
    }

    public static class TokenConfig {
        private String token = "";

        public String getToken() { return token; }
        public void setToken(String token) { this.token = token; }
    }
}
