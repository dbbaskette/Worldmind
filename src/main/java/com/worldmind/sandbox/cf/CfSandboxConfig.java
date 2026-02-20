package com.worldmind.sandbox.cf;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.worldmind.sandbox.InstructionStore;
import com.worldmind.sandbox.OutputStore;
import com.worldmind.sandbox.SandboxProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Spring configuration for Cloud Foundry Sandbox provider.
 * Activates when {@code worldmind.sandbox.provider=cloudfoundry}.
 */
@Configuration
@ConditionalOnProperty(name = "worldmind.sandbox.provider", havingValue = "cloudfoundry")
@EnableConfigurationProperties(CloudFoundryProperties.class)
public class CfSandboxConfig {

    private static final Logger log = LoggerFactory.getLogger(CfSandboxConfig.class);

    @Bean
    public GitWorkspaceManager gitWorkspaceManager(CloudFoundryProperties cfProperties) {
        return new GitWorkspaceManager(cfProperties.getGitRemoteUrl());
    }

    @Bean
    public CfApiClient cfApiClient(CloudFoundryProperties cfProperties) {
        return new CfApiClient(cfProperties);
    }

    @Bean
    public SandboxProvider cloudFoundrySandboxProvider(CloudFoundryProperties cfProperties,
                                                         GitWorkspaceManager gitWorkspaceManager,
                                                         CfApiClient cfApiClient,
                                                         InstructionStore instructionStore,
                                                         OutputStore outputStore) {
        resolveOrchestratorUrl(cfProperties);
        log.info("CF Sandbox provider — orchestrator URL: {}", cfProperties.getOrchestratorUrl());
        return new CloudFoundrySandboxProvider(cfProperties, gitWorkspaceManager, cfApiClient, instructionStore, outputStore);
    }

    /**
     * Ensures orchestratorUrl is set. If the YAML placeholder didn't resolve
     * (e.g. vcap.application.uris[0] unavailable), falls back to parsing
     * VCAP_APPLICATION directly.
     */
    private void resolveOrchestratorUrl(CloudFoundryProperties cfProperties) {
        String url = cfProperties.getOrchestratorUrl();
        if (url != null && !url.isBlank() && !url.contains("localhost")) {
            return; // already resolved
        }

        String vcapApp = System.getenv("VCAP_APPLICATION");
        if (vcapApp == null || vcapApp.isBlank()) {
            log.warn("VCAP_APPLICATION not set — orchestrator URL will use localhost fallback");
            return;
        }

        try {
            JsonNode root = new ObjectMapper().readTree(vcapApp);
            JsonNode uris = root.get("uris");
            if (uris != null && uris.isArray() && !uris.isEmpty()) {
                String resolvedUrl = "https://" + uris.get(0).asText();
                cfProperties.setOrchestratorUrl(resolvedUrl);
                log.info("Resolved orchestrator URL from VCAP_APPLICATION: {}", resolvedUrl);
            }
        } catch (Exception e) {
            log.warn("Failed to parse VCAP_APPLICATION for orchestrator URL: {}", e.getMessage());
        }
    }
}
