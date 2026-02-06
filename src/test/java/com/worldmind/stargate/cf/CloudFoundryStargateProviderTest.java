package com.worldmind.stargate.cf;

import com.worldmind.stargate.StargateRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link CloudFoundryStargateProvider}.
 *
 * <p>Uses a test subclass to intercept CF CLI commands, allowing us to verify
 * command construction and test polling logic without a real CF environment.
 * Follows the same pattern as {@link GitWorkspaceManagerTest}.
 */
class CloudFoundryStargateProviderTest {

    private TestableCloudFoundryStargateProvider provider;
    private CloudFoundryProperties cfProperties;
    private GitWorkspaceManager gitWorkspaceManager;

    @BeforeEach
    void setUp() {
        cfProperties = new CloudFoundryProperties();
        cfProperties.setApiUrl("https://api.cf.example.com");
        cfProperties.setOrg("worldmind-org");
        cfProperties.setSpace("production");
        cfProperties.setGitRemoteUrl("https://github.com/example/project.git");
        cfProperties.setCenturionApps(Map.of(
                "forge", "centurion-forge",
                "vigil", "centurion-vigil",
                "gauntlet", "centurion-gauntlet"
        ));
        cfProperties.setTaskMemoryMb(2048);
        cfProperties.setTaskDiskMb(4096);
        cfProperties.setTaskTimeoutSeconds(600);

        gitWorkspaceManager = new GitWorkspaceManager(cfProperties.getGitRemoteUrl());
        provider = new TestableCloudFoundryStargateProvider(cfProperties, gitWorkspaceManager);
    }

    // --- openStargate tests ---

    @Test
    void openStargateConstructsCorrectRunTaskCommand() {
        provider.setCfOutput("Creating task for app centurion-forge...\nOK");
        var request = makeRequest("forge", "DIR-001");

        provider.openStargate(request);

        var commands = provider.getExecutedCommands();
        assertEquals(1, commands.size());

        var cmd = commands.get(0);
        assertTrue(cmd.contains("run-task"), "Should contain run-task: " + cmd);
        assertTrue(cmd.contains("centurion-forge"), "Should contain app name: " + cmd);
        assertTrue(cmd.contains("stargate-forge-DIR-001"), "Should contain task name: " + cmd);
        assertTrue(cmd.contains("git clone"), "Should contain git clone: " + cmd);
        assertTrue(cmd.contains("worldmind/DIR-001"), "Should contain branch name: " + cmd);
        assertTrue(cmd.contains("goose run"), "Should contain goose run: " + cmd);
        assertTrue(cmd.contains("git push"), "Should contain git push: " + cmd);
    }

    @Test
    void openStargateReturnsCorrectTaskNameFormat() {
        provider.setCfOutput("Creating task for app centurion-forge...\nOK");
        var request = makeRequest("forge", "DIR-001");

        var stargateId = provider.openStargate(request);

        assertEquals("stargate-forge-DIR-001", stargateId);
    }

    @Test
    void openStargateThrowsOnUnknownCenturionType() {
        var request = makeRequest("unknown", "DIR-001");

        var ex = assertThrows(RuntimeException.class, () -> provider.openStargate(request));
        assertTrue(ex.getMessage().contains("Unknown centurion type"),
                "Should mention unknown type: " + ex.getMessage());
        assertTrue(ex.getMessage().contains("unknown"),
                "Should include the bad type: " + ex.getMessage());
    }

    @Test
    void openStargateUsesRequestMemoryWhenProvided() {
        provider.setCfOutput("OK");
        var request = new StargateRequest(
                "forge", "DIR-002", Path.of("/tmp/project"),
                "Build something", Map.of(), 8192, 4
        );

        provider.openStargate(request);

        var cmd = provider.getExecutedCommands().get(0);
        assertTrue(cmd.contains("8192M"), "Should use request memory: " + cmd);
    }

    @Test
    void openStargateFallsBackToDefaultMemoryWhenZero() {
        provider.setCfOutput("OK");
        var request = new StargateRequest(
                "forge", "DIR-003", Path.of("/tmp/project"),
                "Build something", Map.of(), 0, 2
        );

        provider.openStargate(request);

        var cmd = provider.getExecutedCommands().get(0);
        assertTrue(cmd.contains("2048M"), "Should use default memory: " + cmd);
    }

    @Test
    void openStargateIncludesDiskLimit() {
        provider.setCfOutput("OK");
        var request = makeRequest("forge", "DIR-004");

        provider.openStargate(request);

        var cmd = provider.getExecutedCommands().get(0);
        assertTrue(cmd.contains("4096M"), "Should include disk limit: " + cmd);
    }

    @Test
    void openStargateHandlesCaseInsensitiveCenturionType() {
        provider.setCfOutput("OK");
        var request = new StargateRequest(
                "FORGE", "DIR-005", Path.of("/tmp/project"),
                "Build something", Map.of(), 4096, 2
        );

        var stargateId = provider.openStargate(request);
        assertEquals("stargate-forge-DIR-005", stargateId);
    }

    @Test
    void openStargateThrowsOnCfCommandFailure() {
        provider.setThrowOnCf(true);
        var request = makeRequest("forge", "DIR-001");

        assertThrows(RuntimeException.class, () -> provider.openStargate(request));
    }

    // --- waitForCompletion tests ---

    @Test
    void waitForCompletionReturnsZeroWhenTaskSucceeded() {
        provider.setCfOutput(cfTasksOutput("stargate-forge-DIR-001", "SUCCEEDED"));

        int result = provider.waitForCompletion("stargate-forge-DIR-001", 30);

        assertEquals(0, result);
    }

    @Test
    void waitForCompletionReturnsOneWhenTaskFailed() {
        provider.setCfOutput(cfTasksOutput("stargate-forge-DIR-001", "FAILED"));

        int result = provider.waitForCompletion("stargate-forge-DIR-001", 30);

        assertEquals(1, result);
    }

    @Test
    void waitForCompletionReturnsMinusOneOnTimeout() {
        // Always return RUNNING — with 0 timeout, should immediately time out
        provider.setCfOutput(cfTasksOutput("stargate-forge-DIR-001", "RUNNING"));

        int result = provider.waitForCompletion("stargate-forge-DIR-001", 0);

        assertEquals(-1, result);
    }

    @Test
    void waitForCompletionPollsUntilSucceeded() {
        // Simulate RUNNING on first poll, then SUCCEEDED
        provider.setCfOutputSequence(List.of(
                cfTasksOutput("stargate-forge-DIR-001", "RUNNING"),
                cfTasksOutput("stargate-forge-DIR-001", "SUCCEEDED")
        ));
        // Use minimal poll interval for test speed
        provider.setPollIntervalMs(10);

        int result = provider.waitForCompletion("stargate-forge-DIR-001", 30);

        assertEquals(0, result);
        assertTrue(provider.getExecutedCommands().size() >= 2,
                "Should have polled at least twice");
    }

    // --- captureOutput tests ---

    @Test
    void captureOutputReturnsLogContent() {
        var logOutput = """
                2024-01-01T00:00:00.00+0000 [APP/TASK/stargate-forge-DIR-001/0] OUT Starting task...
                2024-01-01T00:00:01.00+0000 [APP/TASK/stargate-forge-DIR-001/0] OUT Cloning repo...
                2024-01-01T00:00:05.00+0000 [APP/TASK/stargate-forge-DIR-001/0] OUT Running goose...
                2024-01-01T00:00:10.00+0000 [APP/PROC/WEB/0] OUT Health check passed
                2024-01-01T00:00:15.00+0000 [APP/TASK/stargate-forge-DIR-001/0] OUT Task complete
                """;
        provider.setCfOutput(logOutput);

        var output = provider.captureOutput("stargate-forge-DIR-001");

        assertNotNull(output);
        assertFalse(output.isEmpty(), "Should contain log lines");
        assertTrue(output.contains("APP/TASK"), "Should include task log lines");
    }

    @Test
    void captureOutputReturnsEmptyStringOnError() {
        provider.setThrowOnCf(true);

        var output = provider.captureOutput("stargate-forge-DIR-001");

        assertEquals("", output);
    }

    @Test
    void captureOutputFiltersToTaskLines() {
        var logOutput = """
                2024-01-01T00:00:00.00+0000 [APP/PROC/WEB/0] OUT Unrelated web log
                2024-01-01T00:00:01.00+0000 [APP/TASK/stargate-forge-DIR-001/0] OUT Task log
                2024-01-01T00:00:02.00+0000 [RTR/0] OUT Router log
                """;
        provider.setCfOutput(logOutput);

        var output = provider.captureOutput("stargate-forge-DIR-001");

        assertTrue(output.contains("Task log"), "Should include task-specific line");
        assertFalse(output.contains("Unrelated web log"), "Should not include unrelated web log");
        assertFalse(output.contains("Router log"), "Should not include router log");
    }

    // --- teardownStargate tests ---

    @Test
    void teardownStargateCallsTerminateTask() {
        provider.setCfOutput("OK");

        provider.teardownStargate("stargate-forge-DIR-001");

        var commands = provider.getExecutedCommands();
        assertEquals(1, commands.size());
        assertTrue(commands.get(0).contains("terminate-task"));
        assertTrue(commands.get(0).contains("centurion-forge"));
        assertTrue(commands.get(0).contains("stargate-forge-DIR-001"));
    }

    @Test
    void teardownStargateDoesNotThrowOnErrors() {
        provider.setThrowOnCf(true);

        assertDoesNotThrow(() -> provider.teardownStargate("stargate-forge-DIR-001"));
    }

    // --- getAppNameFromStargateId tests ---

    @Test
    void getAppNameFromStargateIdParsesCorrectly() {
        var appName = provider.getAppNameFromStargateId("stargate-forge-DIR-001");
        assertEquals("centurion-forge", appName);
    }

    @Test
    void getAppNameFromStargateIdHandlesVigil() {
        var appName = provider.getAppNameFromStargateId("stargate-vigil-DIR-002");
        assertEquals("centurion-vigil", appName);
    }

    @Test
    void getAppNameFromStargateIdHandlesGauntlet() {
        var appName = provider.getAppNameFromStargateId("stargate-gauntlet-DIR-003");
        assertEquals("centurion-gauntlet", appName);
    }

    @Test
    void getAppNameFromStargateIdHandlesDirectiveIdWithDashes() {
        // directiveId might contain dashes, e.g. "abc-123-def"
        var appName = provider.getAppNameFromStargateId("stargate-forge-abc-123-def");
        assertEquals("centurion-forge", appName);
    }

    @Test
    void getAppNameFromStargateIdThrowsOnInvalidFormat() {
        assertThrows(RuntimeException.class, () ->
                provider.getAppNameFromStargateId("invalid-id"));
    }

    @Test
    void getAppNameFromStargateIdThrowsOnUnknownType() {
        assertThrows(RuntimeException.class, () ->
                provider.getAppNameFromStargateId("stargate-unknown-DIR-001"));
    }

    @Test
    void getAppNameFromStargateIdThrowsOnMissingDirectiveId() {
        assertThrows(RuntimeException.class, () ->
                provider.getAppNameFromStargateId("stargate-"));
    }

    // --- parseTaskStatus tests ---

    @Test
    void parseTaskStatusFindsSucceeded() {
        var output = cfTasksOutput("stargate-forge-DIR-001", "SUCCEEDED");
        var status = provider.parseTaskStatus(output, "stargate-forge-DIR-001");
        assertEquals("SUCCEEDED", status);
    }

    @Test
    void parseTaskStatusFindsRunning() {
        var output = cfTasksOutput("stargate-forge-DIR-001", "RUNNING");
        var status = provider.parseTaskStatus(output, "stargate-forge-DIR-001");
        assertEquals("RUNNING", status);
    }

    @Test
    void parseTaskStatusFindsFailed() {
        var output = cfTasksOutput("stargate-forge-DIR-001", "FAILED");
        var status = provider.parseTaskStatus(output, "stargate-forge-DIR-001");
        assertEquals("FAILED", status);
    }

    @Test
    void parseTaskStatusReturnsNullWhenTaskNotFound() {
        var output = cfTasksOutput("other-task", "SUCCEEDED");
        var status = provider.parseTaskStatus(output, "stargate-forge-DIR-001");
        assertNull(status);
    }

    @Test
    void parseTaskStatusReturnsNullOnEmptyOutput() {
        var status = provider.parseTaskStatus("", "stargate-forge-DIR-001");
        assertNull(status);
    }

    @Test
    void parseTaskStatusReturnsNullOnNullOutput() {
        var status = provider.parseTaskStatus(null, "stargate-forge-DIR-001");
        assertNull(status);
    }

    // --- Helper methods ---

    private StargateRequest makeRequest(String centurionType, String directiveId) {
        return new StargateRequest(
                centurionType, directiveId, Path.of("/tmp/project"),
                "Build the feature", Map.of("GOOSE_PROVIDER", "anthropic"),
                4096, 2
        );
    }

    /**
     * Generates mock {@code cf tasks} output matching the real CF CLI format.
     */
    private String cfTasksOutput(String taskName, String state) {
        return """
                Getting tasks for app centurion-forge in org worldmind-org / space production...
                OK

                id   name                          state       start time                      command
                3    %s   %s   2024-01-01T00:00:00Z   echo hello
                """.formatted(taskName, state);
    }

    // --- Test subclass that intercepts CF CLI commands ---

    /**
     * Testable subclass of CloudFoundryStargateProvider that intercepts CF CLI calls
     * so we can test logic without a real Cloud Foundry environment.
     * Follows the same pattern as {@link GitWorkspaceManagerTest.TestableGitWorkspaceManager}.
     */
    static class TestableCloudFoundryStargateProvider extends CloudFoundryStargateProvider {

        private String cfOutput = "";
        private boolean throwOnCf = false;
        private final List<String> executedCommands = new ArrayList<>();
        private List<String> cfOutputSequence = null;
        private int sequenceIndex = 0;
        private long pollIntervalMs = -1; // -1 means use default

        TestableCloudFoundryStargateProvider(CloudFoundryProperties cfProperties,
                                              GitWorkspaceManager gitWorkspaceManager) {
            super(cfProperties, gitWorkspaceManager);
        }

        void setCfOutput(String output) {
            this.cfOutput = output;
            this.cfOutputSequence = null;
            this.sequenceIndex = 0;
        }

        void setCfOutputSequence(List<String> outputs) {
            this.cfOutputSequence = new ArrayList<>(outputs);
            this.sequenceIndex = 0;
        }

        void setThrowOnCf(boolean throwOnCf) {
            this.throwOnCf = throwOnCf;
        }

        void setPollIntervalMs(long pollIntervalMs) {
            this.pollIntervalMs = pollIntervalMs;
        }

        List<String> getExecutedCommands() {
            return executedCommands;
        }

        @Override
        String runCf(String... args) {
            var command = String.join(" ", args);
            executedCommands.add(command);
            if (throwOnCf) {
                throw new RuntimeException("Simulated CF CLI failure: " + command);
            }
            if (cfOutputSequence != null && sequenceIndex < cfOutputSequence.size()) {
                return cfOutputSequence.get(sequenceIndex++);
            }
            return cfOutput;
        }

        @Override
        int runCfExitCode(String... args) {
            var command = String.join(" ", args);
            executedCommands.add(command);
            if (throwOnCf) {
                return -1;
            }
            return 0;
        }

        @Override
        public int waitForCompletion(String stargateId, int timeoutSeconds) {
            // Override to use a shorter poll interval for tests
            if (pollIntervalMs >= 0) {
                return waitForCompletionWithPollInterval(stargateId, timeoutSeconds, pollIntervalMs);
            }
            return super.waitForCompletion(stargateId, timeoutSeconds);
        }

        private int waitForCompletionWithPollInterval(String stargateId, int timeoutSeconds, long intervalMs) {
            var appName = getAppNameFromStargateId(stargateId);
            long deadline = System.currentTimeMillis() + (timeoutSeconds * 1000L);

            while (System.currentTimeMillis() < deadline) {
                try {
                    var output = runCf("tasks", appName);
                    var status = parseTaskStatus(output, stargateId);

                    if ("SUCCEEDED".equals(status)) {
                        return 0;
                    } else if ("FAILED".equals(status)) {
                        return 1;
                    }

                    Thread.sleep(intervalMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return -1;
                } catch (Exception e) {
                    // continue polling
                }
            }
            return -1;
        }
    }
}
