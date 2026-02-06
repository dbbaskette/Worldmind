package com.worldmind.stargate;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.core.DefaultDockerClientConfig;
import com.github.dockerjava.core.DockerClientImpl;
import com.github.dockerjava.zerodep.ZerodepDockerHttpClient;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class StargateConfig {

    private static final String DEFAULT_UNIX_SOCKET = "unix:///var/run/docker.sock";

    @Bean
    @ConditionalOnProperty(name = "worldmind.stargate.provider", havingValue = "docker", matchIfMissing = true)
    public DockerClient dockerClient() {
        String dockerHost = System.getenv().getOrDefault("DOCKER_HOST", DEFAULT_UNIX_SOCKET);
        var config = DefaultDockerClientConfig.createDefaultConfigBuilder()
                .withDockerHost(dockerHost)
                .build();
        // ZerodepDockerHttpClient has built-in Unix socket support (no junixsocket needed)
        var httpClient = new ZerodepDockerHttpClient.Builder()
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
