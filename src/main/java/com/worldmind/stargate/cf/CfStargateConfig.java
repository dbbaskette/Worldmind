package com.worldmind.stargate.cf;

import com.worldmind.stargate.StargateProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Spring configuration for Cloud Foundry Stargate provider.
 * Activates when {@code worldmind.stargate.provider=cloudfoundry}.
 */
@Configuration
@ConditionalOnProperty(name = "worldmind.stargate.provider", havingValue = "cloudfoundry")
@EnableConfigurationProperties(CloudFoundryProperties.class)
public class CfStargateConfig {

    @Bean
    public GitWorkspaceManager gitWorkspaceManager(CloudFoundryProperties cfProperties) {
        return new GitWorkspaceManager(cfProperties.getGitRemoteUrl());
    }

    @Bean
    public StargateProvider cloudFoundryStargateProvider(CloudFoundryProperties cfProperties,
                                                         GitWorkspaceManager gitWorkspaceManager) {
        return new CloudFoundryStargateProvider(cfProperties, gitWorkspaceManager);
    }
}
