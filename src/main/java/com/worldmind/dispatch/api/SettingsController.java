package com.worldmind.dispatch.api;

import com.worldmind.mcp.McpClientManager;
import com.worldmind.mcp.McpProperties;
import com.worldmind.starblaster.StarblasterProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/settings")
public class SettingsController {

    private static final Logger log = LoggerFactory.getLogger(SettingsController.class);

    private final McpProperties mcpProperties;
    private final McpClientManager mcpClientManager;
    private final StarblasterProperties starblasterProperties;

    public SettingsController(McpProperties mcpProperties, McpClientManager mcpClientManager,
                              StarblasterProperties starblasterProperties) {
        this.mcpProperties = mcpProperties;
        this.mcpClientManager = mcpClientManager;
        this.starblasterProperties = starblasterProperties;
    }

    @GetMapping("/mcp")
    public ResponseEntity<Map<String, Object>> getMcpSettings() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("enabled", mcpProperties.isEnabled());
        result.put("gooseProvider", starblasterProperties.getGooseProvider());
        result.put("gooseModel", starblasterProperties.getGooseModel());

        List<Map<String, Object>> servers = new ArrayList<>();

        for (var entry : mcpProperties.getServers().entrySet()) {
            String name = entry.getKey();
            McpProperties.ServerConfig config = entry.getValue();

            Map<String, Object> server = new LinkedHashMap<>();
            server.put("name", name);
            server.put("url", config.getUrl());

            // Consumer info with per-consumer tool discovery
            String status = "DOWN";
            List<Map<String, Object>> consumers = new ArrayList<>();
            for (var tokenEntry : config.getTokens().entrySet()) {
                String consumerName = tokenEntry.getKey();
                boolean hasToken = tokenEntry.getValue() != null && !tokenEntry.getValue().isBlank();

                Map<String, Object> consumer = new LinkedHashMap<>();
                consumer.put("name", consumerName);
                consumer.put("hasToken", hasToken);

                List<Map<String, String>> consumerTools = new ArrayList<>();
                if (hasToken) {
                    try {
                        var client = mcpClientManager.getClientFor(consumerName);
                        if (client != null) {
                            var toolResult = client.listTools();
                            status = "UP";
                            if (toolResult.tools() != null) {
                                for (var tool : toolResult.tools()) {
                                    Map<String, String> toolInfo = new LinkedHashMap<>();
                                    toolInfo.put("name", tool.name());
                                    toolInfo.put("description", tool.description() != null ? tool.description() : "");
                                    consumerTools.add(toolInfo);
                                }
                            }
                        }
                    } catch (Exception e) {
                        log.debug("MCP tool discovery failed for consumer '{}' on server '{}': {}", consumerName, name, e.getMessage());
                    }
                }
                consumer.put("tools", consumerTools);
                consumers.add(consumer);
            }
            // Include shared token as "default" consumer if no per-consumer tokens exist
            if (config.getToken() != null && !config.getToken().isBlank() && consumers.isEmpty()) {
                Map<String, Object> defaultConsumer = new LinkedHashMap<>();
                defaultConsumer.put("name", "default");
                defaultConsumer.put("hasToken", true);
                List<Map<String, String>> defaultTools = new ArrayList<>();
                try {
                    var client = mcpClientManager.getClientFor("default");
                    if (client != null) {
                        var toolResult = client.listTools();
                        status = "UP";
                        if (toolResult.tools() != null) {
                            for (var tool : toolResult.tools()) {
                                defaultTools.add(Map.of("name", tool.name(), "description", tool.description() != null ? tool.description() : ""));
                            }
                        }
                    }
                } catch (Exception e) {
                    log.debug("MCP tool discovery failed for default consumer on server '{}': {}", name, e.getMessage());
                }
                defaultConsumer.put("tools", defaultTools);
                consumers.add(defaultConsumer);
            }
            server.put("consumers", consumers);
            server.put("status", status);
            servers.add(server);
        }

        result.put("servers", servers);
        return ResponseEntity.ok(result);
    }
}
