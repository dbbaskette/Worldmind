package com.worldmind.starblaster.cf;

import com.worldmind.starblaster.InstructionStore;
import com.worldmind.starblaster.StarblasterProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Spring configuration for Cloud Foundry Starblaster provider.
 * Activates when {@code worldmind.starblaster.provider=cloudfoundry}.
 */
@Configuration
@ConditionalOnProperty(name = "worldmind.starblaster.provider", havingValue = "cloudfoundry")
@EnableConfigurationProperties(CloudFoundryProperties.class)
public class CfStarblasterConfig {

    @Bean
    public GitWorkspaceManager gitWorkspaceManager(CloudFoundryProperties cfProperties) {
        return new GitWorkspaceManager(cfProperties.getGitRemoteUrl());
    }

    @Bean
    public CfApiClient cfApiClient(CloudFoundryProperties cfProperties) {
        return new CfApiClient(cfProperties);
    }

    @Bean
    public StarblasterProvider cloudFoundryStarblasterProvider(CloudFoundryProperties cfProperties,
                                                         GitWorkspaceManager gitWorkspaceManager,
                                                         CfApiClient cfApiClient,
                                                         InstructionStore instructionStore) {
        return new CloudFoundryStarblasterProvider(cfProperties, gitWorkspaceManager, cfApiClient, instructionStore);
    }
}
