package com.worldmind.sandbox;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Spring configuration that enables {@link DeployerProperties} binding.
 * Follows the same convention used by
 * {@link com.worldmind.sandbox.cf.CfSandboxConfig} for {@code CloudFoundryProperties}.
 */
@Configuration
@EnableConfigurationProperties(DeployerProperties.class)
public class DeployerConfig {
}
