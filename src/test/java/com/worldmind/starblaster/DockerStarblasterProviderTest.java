package com.worldmind.starblaster;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.*;
import com.github.dockerjava.api.model.HostConfig;
import com.github.dockerjava.core.command.LogContainerResultCallback;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for DockerStarblasterProvider.
 *
 * <p>The Docker Java client uses a deep fluent builder pattern that is notoriously
 * difficult to mock with Mockito. These tests use manual mock chaining rather than
 * RETURNS_DEEP_STUBS to maintain precise control over verifications and avoid
 * ClassCastException issues with concrete callback classes.
 */
class DockerStarblasterProviderTest {

    private DockerClient dockerClient;
    private DockerStarblasterProvider provider;

    @BeforeEach
    void setUp() {
        dockerClient = mock(DockerClient.class);
        provider = new DockerStarblasterProvider(dockerClient, "ghcr.io/dbbaskette");
    }

    // ── openStarblaster tests ──────────────────────────────────────────────

    @Test
    void openStarblasterCreatesAndStartsContainer() {
        var createCmd = mockCreateContainerCmd("container-abc123");
        var startCmd = mock(StartContainerCmd.class);
        when(dockerClient.startContainerCmd("container-abc123")).thenReturn(startCmd);

        var request = new StarblasterRequest(
            "forge", "DIR-001", Path.of("/tmp/project"),
            "Create hello.py", Map.of("GOOSE_PROVIDER", "openai"),
            4096, 2
        );

        String containerId = provider.openStarblaster(request);

        assertEquals("container-abc123", containerId);
        verify(dockerClient).createContainerCmd("ghcr.io/dbbaskette/centurion-forge:latest");
        verify(dockerClient).startContainerCmd("container-abc123");
        verify(startCmd).exec();
    }

    @Test
    void openStarblasterUsesCorrectImageForCenturionType() {
        mockCreateContainerCmd("container-vigil-001");
        when(dockerClient.startContainerCmd(anyString())).thenReturn(mock(StartContainerCmd.class));

        var request = new StarblasterRequest(
            "vigil", "DIR-002", Path.of("/tmp/project"),
            "Review code", Map.of(),
            2048, 1
        );

        provider.openStarblaster(request);

        verify(dockerClient).createContainerCmd("ghcr.io/dbbaskette/centurion-vigil:latest");
    }

    @Test
    void openStarblasterSetsContainerName() {
        var createCmd = mockCreateContainerCmd("container-xyz");
        when(dockerClient.startContainerCmd(anyString())).thenReturn(mock(StartContainerCmd.class));

        var request = new StarblasterRequest(
            "forge", "DIR-001", Path.of("/tmp/project"),
            "Create hello.py", Map.of(),
            4096, 2
        );

        provider.openStarblaster(request);

        verify(createCmd).withName("starblaster-forge-DIR-001");
    }

    @Test
    void openStarblasterPassesEnvironmentVariables() {
        var createCmd = mockCreateContainerCmd("container-env");
        when(dockerClient.startContainerCmd(anyString())).thenReturn(mock(StartContainerCmd.class));

        var envVars = Map.of(
            "GOOSE_PROVIDER", "openai",
            "GOOSE_MODEL", "gpt-4"
        );
        var request = new StarblasterRequest(
            "forge", "DIR-003", Path.of("/tmp/project"),
            "Build feature", envVars,
            4096, 2
        );

        provider.openStarblaster(request);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<String>> envCaptor = ArgumentCaptor.forClass(List.class);
        verify(createCmd).withEnv(envCaptor.capture());
        List<String> capturedEnv = envCaptor.getValue();
        assertTrue(capturedEnv.contains("GOOSE_PROVIDER=openai"));
        assertTrue(capturedEnv.contains("GOOSE_MODEL=gpt-4"));
    }

    @Test
    void openStarblasterSetsGooseRunCommand() {
        var createCmd = mockCreateContainerCmd("container-cmd");
        when(dockerClient.startContainerCmd(anyString())).thenReturn(mock(StartContainerCmd.class));

        var request = new StarblasterRequest(
            "forge", "DIR-004", Path.of("/tmp/project"),
            "Create a REST API", Map.of(),
            4096, 2
        );

        provider.openStarblaster(request);

        verify(createCmd).withCmd("/workspace/.worldmind/directives/DIR-004.md");
    }

    @Test
    void openStarblasterSetsWorkingDirectory() {
        var createCmd = mockCreateContainerCmd("container-wd");
        when(dockerClient.startContainerCmd(anyString())).thenReturn(mock(StartContainerCmd.class));

        var request = new StarblasterRequest(
            "forge", "DIR-005", Path.of("/tmp/project"),
            "Build feature", Map.of(),
            4096, 2
        );

        provider.openStarblaster(request);

        verify(createCmd).withWorkingDir("/workspace");
    }

    @Test
    void openStarblasterSetsHostConfig() {
        var createCmd = mockCreateContainerCmd("container-hc");
        when(dockerClient.startContainerCmd(anyString())).thenReturn(mock(StartContainerCmd.class));

        var request = new StarblasterRequest(
            "forge", "DIR-006", Path.of("/tmp/project"),
            "Build feature", Map.of(),
            4096, 2
        );

        provider.openStarblaster(request);

        var hostConfigCaptor = ArgumentCaptor.forClass(HostConfig.class);
        verify(createCmd).withHostConfig(hostConfigCaptor.capture());
        HostConfig hostConfig = hostConfigCaptor.getValue();
        assertNotNull(hostConfig);
        // Verify memory limit: 4096 MB = 4096 * 1024 * 1024 bytes
        assertEquals(4096L * 1024 * 1024, hostConfig.getMemory());
        assertEquals(2L, hostConfig.getCpuCount());
    }

    // ── waitForCompletion tests ─────────────────────────────────────────

    @Test
    void waitForCompletionReturnsExitCode() throws Exception {
        var waitCmd = mock(WaitContainerCmd.class);
        when(dockerClient.waitContainerCmd("container-123")).thenReturn(waitCmd);

        var callback = mock(WaitContainerResultCallback.class);
        when(waitCmd.exec(any(WaitContainerResultCallback.class))).thenReturn(callback);
        when(callback.awaitStatusCode(300L, TimeUnit.SECONDS)).thenReturn(0);

        int exitCode = provider.waitForCompletion("container-123", 300);

        assertEquals(0, exitCode);
    }

    @Test
    void waitForCompletionReturnsMinusOneOnNullResult() throws Exception {
        var waitCmd = mock(WaitContainerCmd.class);
        when(dockerClient.waitContainerCmd("container-123")).thenReturn(waitCmd);

        var callback = mock(WaitContainerResultCallback.class);
        when(waitCmd.exec(any(WaitContainerResultCallback.class))).thenReturn(callback);
        when(callback.awaitStatusCode(300L, TimeUnit.SECONDS)).thenReturn(null);

        int exitCode = provider.waitForCompletion("container-123", 300);

        assertEquals(-1, exitCode);
    }

    @Test
    void waitForCompletionReturnsMinusOneOnException() throws Exception {
        var waitCmd = mock(WaitContainerCmd.class);
        when(dockerClient.waitContainerCmd("container-123")).thenReturn(waitCmd);
        when(waitCmd.exec(any(WaitContainerResultCallback.class)))
                .thenThrow(new RuntimeException("Docker daemon unreachable"));

        int exitCode = provider.waitForCompletion("container-123", 300);

        assertEquals(-1, exitCode);
    }

    // ── captureOutput tests ─────────────────────────────────────────────

    @Test
    void captureOutputInvokesLogContainerCmd() throws Exception {
        var logCmd = mock(LogContainerCmd.class, RETURNS_SELF);
        when(dockerClient.logContainerCmd("container-123")).thenReturn(logCmd);

        // The Docker Java API pattern: exec(callback) returns the same callback.
        // We return the callback argument after calling onComplete() to release
        // the internal CountDownLatch so awaitCompletion() won't block.
        doAnswer(invocation -> {
            var callback = (LogContainerResultCallback) invocation.getArgument(0);
            callback.onComplete();
            return callback;
        }).when(logCmd).exec(any());

        String output = provider.captureOutput("container-123");

        // The output will be empty since no frames are produced,
        // but we verify the command was constructed correctly
        assertNotNull(output);
        assertEquals("", output);
        verify(dockerClient).logContainerCmd("container-123");
        verify(logCmd).withStdOut(true);
        verify(logCmd).withStdErr(true);
        verify(logCmd).withFollowStream(false);
    }

    // ── teardownStarblaster tests ──────────────────────────────────────────

    @Test
    void teardownStarblasterStopsAndRemovesContainer() {
        var stopCmd = mock(StopContainerCmd.class);
        when(dockerClient.stopContainerCmd("container-123")).thenReturn(stopCmd);

        var removeCmd = mock(RemoveContainerCmd.class);
        when(dockerClient.removeContainerCmd("container-123")).thenReturn(removeCmd);
        when(removeCmd.withForce(true)).thenReturn(removeCmd);

        provider.teardownStarblaster("container-123");

        verify(stopCmd).exec();
        verify(removeCmd).withForce(true);
        verify(removeCmd).exec();
    }

    @Test
    void teardownStarblasterContinuesRemovalEvenIfStopFails() {
        var stopCmd = mock(StopContainerCmd.class);
        when(dockerClient.stopContainerCmd("container-123")).thenReturn(stopCmd);
        when(stopCmd.exec()).thenThrow(new RuntimeException("Container already stopped"));

        var removeCmd = mock(RemoveContainerCmd.class);
        when(dockerClient.removeContainerCmd("container-123")).thenReturn(removeCmd);
        when(removeCmd.withForce(true)).thenReturn(removeCmd);

        assertDoesNotThrow(() -> provider.teardownStarblaster("container-123"));
        verify(removeCmd).exec();
    }

    @Test
    void teardownStarblasterHandlesRemovalFailureGracefully() {
        var stopCmd = mock(StopContainerCmd.class);
        when(dockerClient.stopContainerCmd("container-123")).thenReturn(stopCmd);

        var removeCmd = mock(RemoveContainerCmd.class);
        when(dockerClient.removeContainerCmd("container-123")).thenReturn(removeCmd);
        when(removeCmd.withForce(true)).thenReturn(removeCmd);
        when(removeCmd.exec()).thenThrow(new RuntimeException("Container not found"));

        // Should not throw, just log a warning
        assertDoesNotThrow(() -> provider.teardownStarblaster("container-123"));
    }

    // ── Helper methods ──────────────────────────────────────────────────

    /**
     * Creates a mock for the createContainerCmd fluent chain.
     * Each fluent method returns the same mock (self-referential),
     * and exec() returns a CreateContainerResponse with the given ID.
     */
    private CreateContainerCmd mockCreateContainerCmd(String containerId) {
        var createCmd = mock(CreateContainerCmd.class, RETURNS_SELF);
        when(dockerClient.createContainerCmd(anyString())).thenReturn(createCmd);

        var createResponse = mock(CreateContainerResponse.class);
        when(createResponse.getId()).thenReturn(containerId);
        when(createCmd.exec()).thenReturn(createResponse);

        return createCmd;
    }
}
