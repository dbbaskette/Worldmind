package com.worldmind.stargate;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.core.DefaultDockerClientConfig;
import com.github.dockerjava.core.DockerClientImpl;
import com.github.dockerjava.httpclient5.ApacheDockerHttpClient;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class StargateConfig {

    @Bean
    @ConditionalOnProperty(name = "worldmind.stargate.provider", havingValue = "docker", matchIfMissing = true)
    public DockerClient dockerClient() {
        var config = DefaultDockerClientConfig.createDefaultConfigBuilder().build();
        var httpClient = new ApacheDockerHttpClient.Builder()
                .dockerHost(config.getDockerHost())
                .sslConfig(config.getSSLConfig())
                .build();
        return DockerClientImpl.getInstance(config, httpClient);
    }

    @Bean
    @ConditionalOnProperty(name = "worldmind.stargate.provider", havingValue = "docker", matchIfMissing = true)
    public StargateProvider dockerStargateProvider(DockerClient dockerClient) {
        return new DockerStargateProvider(dockerClient);
    }
}
