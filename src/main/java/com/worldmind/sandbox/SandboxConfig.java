package com.worldmind.sandbox;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.core.DefaultDockerClientConfig;
import com.github.dockerjava.core.DockerClientImpl;
import com.github.dockerjava.zerodep.ZerodepDockerHttpClient;
import com.worldmind.core.metrics.WorldmindMetrics;
import com.worldmind.sandbox.cf.GitWorkspaceManager;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class SandboxConfig {

    private static final String DEFAULT_UNIX_SOCKET = "unix:///var/run/docker.sock";

    @Bean
    @ConditionalOnProperty(name = "worldmind.sandbox.provider", havingValue = "docker", matchIfMissing = true)
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
    @ConditionalOnProperty(name = "worldmind.sandbox.provider", havingValue = "docker", matchIfMissing = true)
    public SandboxProvider dockerSandboxProvider(DockerClient dockerClient,
                                                            SandboxProperties properties) {
        return new DockerSandboxProvider(dockerClient, properties.getImageRegistry(), properties.getImagePrefix());
    }

    /**
     * Provides worktree-based execution contexts for isolated parallel task execution.
     * This bean manages git worktrees to give each task its own working directory,
     * preventing file conflicts during parallel execution.
     */
    @Bean
    public WorktreeExecutionContext worktreeExecutionContext(GitWorkspaceManager gitWorkspaceManager,
                                                              @Autowired(required = false) WorldmindMetrics metrics) {
        return new WorktreeExecutionContext(gitWorkspaceManager, metrics);
    }
}
