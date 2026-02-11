package com.worldmind.integration;

import com.worldmind.starblaster.*;
import com.github.dockerjava.core.DefaultDockerClientConfig;
import com.github.dockerjava.core.DockerClientImpl;
import com.github.dockerjava.zerodep.ZerodepDockerHttpClient;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end integration test for Starblaster execution.
 * Requires Docker to be running and the centurion-forge image built.
 * Run with: mvn test -Dgroups=integration
 */
@Tag("integration")
class ForgeIntegrationTest {

    @Test
    void starblasterExecutesGooseAndCreatesFile(@TempDir Path tempDir) throws Exception {
        // Setup Docker client
        var dockerConfig = DefaultDockerClientConfig.createDefaultConfigBuilder().build();
        var httpClient = new ZerodepDockerHttpClient.Builder()
                .dockerHost(dockerConfig.getDockerHost())
                .sslConfig(dockerConfig.getSSLConfig())
                .build();
        var dockerClient = DockerClientImpl.getInstance(dockerConfig, httpClient);

        var provider = new DockerStarblasterProvider(dockerClient, "ghcr.io/dbbaskette", "starblaster");
        var properties = new StarblasterProperties();
        var manager = new StarblasterManager(provider, properties, null);

        // Execute a simple file creation task
        var result = manager.executeDirective(
            "forge", "INT-001", tempDir,
            "Create a file named hello.py in the current directory with the content: print('Hello from Worldmind')",
            Map.of("GOOSE_PROVIDER", "openai",
                   "GOOSE_MODEL", "qwen2.5-coder-32b",
                   "OPENAI_HOST", "http://host.docker.internal:1234/v1",
                   "OPENAI_API_KEY", "not-needed"),
            "", "python"
        );

        // Verify execution completed
        assertEquals(0, result.exitCode(), "Goose should exit cleanly. Output: " + result.output());
        assertTrue(result.elapsedMs() > 0);

        // Verify file was created
        Path helloFile = tempDir.resolve("hello.py");
        assertTrue(Files.exists(helloFile), "hello.py should have been created");

        String content = Files.readString(helloFile);
        assertTrue(content.contains("Hello"), "File should contain greeting");

        // Verify file change detection
        assertFalse(result.fileChanges().isEmpty(), "Should detect file changes");
    }
}
