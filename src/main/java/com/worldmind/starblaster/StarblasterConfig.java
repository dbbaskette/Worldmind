package com.worldmind.starblaster;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.core.DefaultDockerClientConfig;
import com.github.dockerjava.core.DockerClientImpl;
import com.github.dockerjava.zerodep.ZerodepDockerHttpClient;
import com.worldmind.starblaster.cf.GitWorkspaceManager;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class StarblasterConfig {

    private static final String DEFAULT_UNIX_SOCKET = "unix:///var/run/docker.sock";

    @Bean
    @ConditionalOnProperty(name = "worldmind.starblaster.provider", havingValue = "docker", matchIfMissing = true)
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
    @ConditionalOnProperty(name = "worldmind.starblaster.provider", havingValue = "docker", matchIfMissing = true)
    public StarblasterProvider dockerStarblasterProvider(DockerClient dockerClient,
                                                            StarblasterProperties properties) {
        return new DockerStarblasterProvider(dockerClient, properties.getImageRegistry(), properties.getImagePrefix());
    }

    /**
     * Provides worktree-based execution contexts for isolated parallel directive execution.
     * This bean manages git worktrees to give each directive its own working directory,
     * preventing file conflicts during parallel execution.
     */
    @Bean
    public WorktreeExecutionContext worktreeExecutionContext(GitWorkspaceManager gitWorkspaceManager) {
        return new WorktreeExecutionContext(gitWorkspaceManager);
    }
}
