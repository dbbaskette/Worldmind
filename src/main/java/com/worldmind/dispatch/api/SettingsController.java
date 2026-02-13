package com.worldmind.dispatch.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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

        String provider = starblasterProperties.getGooseProvider();
        String model = starblasterProperties.getGooseModel();

        // When provider isn't explicitly configured, resolve from VCAP_SERVICES
        // so the UI shows the actual model bound via CF service binding.
        if (!starblasterProperties.isGooseProviderConfigured()) {
            var vcapResolved = resolveModelFromVcap(starblasterProperties.getGooseServiceName());
            if (vcapResolved != null) {
                provider = vcapResolved.getOrDefault("provider", provider);
                model = vcapResolved.getOrDefault("model", model);
            }
        }

        result.put("gooseProvider", provider);
        result.put("gooseModel", model);

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

    /**
     * Parses VCAP_SERVICES to extract model provider and name from the bound genai service.
     * Checks credentials first (CredHub-resolved at runtime), then falls back to the
     * service plan name which contains the model identifier.
     */
    private Map<String, String> resolveModelFromVcap(String serviceName) {
        String vcapServices = System.getenv("VCAP_SERVICES");
        if (vcapServices == null || vcapServices.isBlank()) {
            return null;
        }
        try {
            JsonNode root = new ObjectMapper().readTree(vcapServices);
            var labels = root.fields();
            while (labels.hasNext()) {
                var label = labels.next();
                for (JsonNode svc : label.getValue()) {
                    String name = svc.has("name") ? svc.get("name").asText() : "";
                    if (serviceName != null && !serviceName.isBlank() && !name.equals(serviceName)) {
                        continue;
                    }

                    Map<String, String> resolved = new LinkedHashMap<>();

                    // Try credentials first (resolved from CredHub at runtime)
                    JsonNode creds = svc.get("credentials");
                    if (creds != null) {
                        if (creds.has("model_provider")) {
                            resolved.put("provider", creds.get("model_provider").asText());
                        }
                        String modelName = creds.has("model_name") ? creds.get("model_name").asText()
                                : creds.has("model") ? creds.get("model").asText() : null;
                        if (modelName != null) {
                            resolved.put("model", modelName);
                        }
                    }

                    // Fall back to the service plan name (e.g. "tanzu-Qwen3-Coder-30B-A3B-vllm-v1")
                    if (!resolved.containsKey("model") && svc.has("plan")) {
                        String plan = svc.get("plan").asText();
                        if (plan != null && !plan.isBlank()) {
                            resolved.put("model", plan);
                        }
                    }

                    // Use the service label as provider fallback (e.g. "genai")
                    if (!resolved.containsKey("provider")) {
                        String svcLabel = label.getKey();
                        if (svcLabel != null && !svcLabel.isBlank()) {
                            resolved.put("provider", svcLabel);
                        }
                    }

                    if (!resolved.isEmpty()) {
                        return resolved;
                    }
                }
            }
        } catch (Exception e) {
            log.debug("Failed to resolve model from VCAP_SERVICES: {}", e.getMessage());
        }
        return null;
    }
}
