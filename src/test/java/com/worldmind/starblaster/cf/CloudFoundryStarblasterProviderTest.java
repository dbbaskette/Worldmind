package com.worldmind.starblaster.cf;

import com.worldmind.starblaster.InstructionStore;
import com.worldmind.starblaster.StarblasterRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link CloudFoundryStarblasterProvider}.
 *
 * <p>Uses a stub {@link CfApiClient} to intercept CF API calls, allowing us to
 * verify task creation, polling logic, and cancellation without a real CF environment.
 */
class CloudFoundryStarblasterProviderTest {

    private CloudFoundryStarblasterProvider provider;
    private StubCfApiClient stubApiClient;
    private CloudFoundryProperties cfProperties;

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
        cfProperties.setOrchestratorUrl("https://worldmind.example.com");

        var gitWorkspaceManager = new GitWorkspaceManager(cfProperties.getGitRemoteUrl());
        stubApiClient = new StubCfApiClient(cfProperties);
        var instructionStore = new InstructionStore();
        provider = new CloudFoundryStarblasterProvider(cfProperties, gitWorkspaceManager, stubApiClient, instructionStore);
    }

    // --- openStarblaster tests ---

    @Test
    void openStarblasterCreatesTaskWithCorrectParameters() {
        var request = makeRequest("forge", "DIR-001");

        provider.openStarblaster(request);

        var calls = stubApiClient.createTaskCalls;
        assertEquals(1, calls.size());

        var call = calls.get(0);
        assertEquals("centurion-forge", call.appName);
        assertEquals("starblaster-forge-DIR-001", call.taskName);
        assertTrue(call.command.contains("git clone"), "Should contain git clone: " + call.command);
        assertTrue(call.command.contains("worldmind/DIR-001"), "Should contain branch name: " + call.command);
        assertTrue(call.command.contains("curl"), "Should contain curl: " + call.command);
        assertTrue(call.command.contains("goose run --debug --with-builtin developer"), "Should contain goose run with developer builtin: " + call.command);
        assertTrue(call.command.contains("git push"), "Should contain git push: " + call.command);
        assertTrue(call.command.contains("GOOSE_PROVIDER__HOST"), "Should bridge GOOSE_PROVIDER__HOST: " + call.command);
        assertTrue(call.command.contains("GOOSE_PROVIDER__API_KEY"), "Should bridge GOOSE_PROVIDER__API_KEY: " + call.command);
        assertTrue(call.command.contains("diagnostics.log"), "Should write diagnostics: " + call.command);
    }

    @Test
    void openStarblasterExportsMcpEnvVars() {
        var request = new StarblasterRequest(
                "forge", "DIR-001", Path.of("/tmp/project"),
                "Build something", Map.of(
                        "MCP_SERVERS", "NEXUS",
                        "MCP_SERVER_NEXUS_URL", "https://nexus.example.com/mcp",
                        "MCP_SERVER_NEXUS_TOKEN", "test-token"
                ),
                4096, 2, "", "base"
        );

        provider.openStarblaster(request);

        var call = stubApiClient.createTaskCalls.get(0);
        // MCP env vars are exported so entrypoint.sh can generate config.yaml
        // with auth headers in map format (MCP extensions loaded from config, not CLI)
        assertTrue(call.command.contains("MCP_SERVER_NEXUS_URL"),
                "Should export MCP URL env var: " + call.command);
        assertTrue(call.command.contains("MCP_SERVER_NEXUS_TOKEN"),
                "Should export MCP token env var: " + call.command);
    }

    @Test
    void openStarblasterReturnsCorrectTaskNameFormat() {
        var request = makeRequest("forge", "DIR-001");

        var starblasterId = provider.openStarblaster(request);

        assertEquals("starblaster-forge-DIR-001", starblasterId);
    }

    @Test
    void openStarblasterFallsBackToConventionForUnknownType() {
        var request = makeRequest("unknown", "DIR-001");

        var starblasterId = provider.openStarblaster(request);
        assertEquals("starblaster-unknown-DIR-001", starblasterId);

        var call = stubApiClient.createTaskCalls.get(0);
        assertEquals("centurion-unknown", call.appName);
    }

    @Test
    void openStarblasterUsesRequestMemoryWhenProvided() {
        var request = new StarblasterRequest(
                "forge", "DIR-002", Path.of("/tmp/project"),
                "Build something", Map.of(), 8192, 4,
                "", "base"
        );

        provider.openStarblaster(request);

        var call = stubApiClient.createTaskCalls.get(0);
        assertEquals(8192, call.memoryMb);
    }

    @Test
    void openStarblasterFallsBackToDefaultMemoryWhenZero() {
        var request = new StarblasterRequest(
                "forge", "DIR-003", Path.of("/tmp/project"),
                "Build something", Map.of(), 0, 2,
                "", "base"
        );

        provider.openStarblaster(request);

        var call = stubApiClient.createTaskCalls.get(0);
        assertEquals(2048, call.memoryMb);
    }

    @Test
    void openStarblasterIncludesDiskLimit() {
        var request = makeRequest("forge", "DIR-004");

        provider.openStarblaster(request);

        var call = stubApiClient.createTaskCalls.get(0);
        assertEquals(4096, call.diskMb);
    }

    @Test
    void openStarblasterHandlesCaseInsensitiveCenturionType() {
        var request = new StarblasterRequest(
                "FORGE", "DIR-005", Path.of("/tmp/project"),
                "Build something", Map.of(), 4096, 2,
                "", "base"
        );

        var starblasterId = provider.openStarblaster(request);
        assertEquals("starblaster-forge-DIR-005", starblasterId);
    }

    @Test
    void openStarblasterThrowsOnApiFailure() {
        stubApiClient.throwOnCreateTask = true;
        var request = makeRequest("forge", "DIR-001");

        assertThrows(RuntimeException.class, () -> provider.openStarblaster(request));
    }

    // --- waitForCompletion tests ---

    @Test
    void waitForCompletionReturnsZeroWhenTaskSucceeded() {
        stubApiClient.taskState = "SUCCEEDED";

        int result = provider.waitForCompletion("starblaster-forge-DIR-001", 30);

        assertEquals(0, result);
    }

    @Test
    void waitForCompletionReturnsOneWhenTaskFailed() {
        stubApiClient.taskState = "FAILED";

        int result = provider.waitForCompletion("starblaster-forge-DIR-001", 30);

        assertEquals(1, result);
    }

    @Test
    void waitForCompletionReturnsMinusOneOnTimeout() {
        stubApiClient.taskState = "RUNNING";

        int result = provider.waitForCompletion("starblaster-forge-DIR-001", 0);

        assertEquals(-1, result);
    }

    @Test
    void waitForCompletionPollsUntilSucceeded() {
        stubApiClient.taskStateSequence = List.of("RUNNING", "RUNNING", "SUCCEEDED");

        int result = provider.waitForCompletion("starblaster-forge-DIR-001", 30);

        assertEquals(0, result);
        assertTrue(stubApiClient.getTaskStateCalls >= 3,
                "Should have polled at least 3 times, got " + stubApiClient.getTaskStateCalls);
    }

    // --- captureOutput tests ---

    @Test
    void captureOutputReturnsFailureReasonWhenFailed() {
        stubApiClient.failureReason = "OOM killed";

        var output = provider.captureOutput("starblaster-forge-DIR-001");

        assertTrue(output.contains("OOM killed"), "Should contain failure reason: " + output);
    }

    @Test
    void captureOutputReturnsFallbackWhenNoFailure() {
        stubApiClient.failureReason = "";

        var output = provider.captureOutput("starblaster-forge-DIR-001");

        assertTrue(output.contains("cf logs"), "Should reference cf logs: " + output);
    }

    @Test
    void captureOutputReturnsEmptyStringOnError() {
        stubApiClient.throwOnGetFailureReason = true;

        var output = provider.captureOutput("starblaster-forge-DIR-001");

        assertEquals("", output);
    }

    // --- teardownStarblaster tests ---

    @Test
    void teardownStarblasterCallsCancelTask() {
        provider.teardownStarblaster("starblaster-forge-DIR-001");

        assertEquals(1, stubApiClient.cancelTaskCalls.size());
        var call = stubApiClient.cancelTaskCalls.get(0);
        assertEquals("centurion-forge", call.appName);
        assertEquals("starblaster-forge-DIR-001", call.taskName);
    }

    @Test
    void teardownStarblasterDoesNotThrowOnErrors() {
        stubApiClient.throwOnCancelTask = true;

        assertDoesNotThrow(() -> provider.teardownStarblaster("starblaster-forge-DIR-001"));
    }

    // --- getAppNameFromStarblasterId tests ---

    @Test
    void getAppNameFromStarblasterIdParsesCorrectly() {
        var appName = provider.getAppNameFromStarblasterId("starblaster-forge-DIR-001");
        assertEquals("centurion-forge", appName);
    }

    @Test
    void getAppNameFromStarblasterIdHandlesVigil() {
        var appName = provider.getAppNameFromStarblasterId("starblaster-vigil-DIR-002");
        assertEquals("centurion-vigil", appName);
    }

    @Test
    void getAppNameFromStarblasterIdHandlesGauntlet() {
        var appName = provider.getAppNameFromStarblasterId("starblaster-gauntlet-DIR-003");
        assertEquals("centurion-gauntlet", appName);
    }

    @Test
    void getAppNameFromStarblasterIdHandlesDirectiveIdWithDashes() {
        var appName = provider.getAppNameFromStarblasterId("starblaster-forge-abc-123-def");
        assertEquals("centurion-forge", appName);
    }

    @Test
    void getAppNameFromStarblasterIdThrowsOnInvalidFormat() {
        assertThrows(RuntimeException.class, () ->
                provider.getAppNameFromStarblasterId("invalid-id"));
    }

    @Test
    void getAppNameFromStarblasterIdFallsBackForUnknownType() {
        var appName = provider.getAppNameFromStarblasterId("starblaster-unknown-DIR-001");
        assertEquals("centurion-unknown", appName);
    }

    @Test
    void getAppNameFromStarblasterIdThrowsOnMissingDirectiveId() {
        assertThrows(RuntimeException.class, () ->
                provider.getAppNameFromStarblasterId("starblaster-"));
    }

    // --- detectChanges tests ---

    @Test
    void detectChangesReturnsEmptyListOnGitFailure() {
        // With a real GitWorkspaceManager, clone will fail since the URL is fake.
        // The method should catch the exception and return an empty list.
        var changes = provider.detectChanges("DIR-001", Path.of("/tmp/project"));

        assertNotNull(changes);
        assertTrue(changes.isEmpty());
    }

    @Test
    void resolveAuthenticatedGitUrlEmbedsToken() {
        cfProperties.setGitToken("ghp_test123");
        cfProperties.setGitRemoteUrl("https://github.com/example/project.git");

        // Re-create provider with updated properties
        var gitWorkspaceManager = new GitWorkspaceManager(cfProperties.getGitRemoteUrl());
        provider = new CloudFoundryStarblasterProvider(cfProperties, gitWorkspaceManager, stubApiClient, new com.worldmind.starblaster.InstructionStore());

        var url = provider.resolveAuthenticatedGitUrl();
        assertTrue(url.startsWith("https://x-access-token:ghp_test123@"), "Should embed token: " + url);
        assertTrue(url.contains("github.com/example/project.git"), "Should preserve rest of URL: " + url);
    }

    @Test
    void resolveAuthenticatedGitUrlSkipsTokenWhenBlank() {
        cfProperties.setGitToken("");
        cfProperties.setGitRemoteUrl("https://github.com/example/project.git");

        var gitWorkspaceManager = new GitWorkspaceManager(cfProperties.getGitRemoteUrl());
        provider = new CloudFoundryStarblasterProvider(cfProperties, gitWorkspaceManager, stubApiClient, new com.worldmind.starblaster.InstructionStore());

        var url = provider.resolveAuthenticatedGitUrl();
        assertEquals("https://github.com/example/project.git", url);
    }

    // --- Helper methods ---

    private StarblasterRequest makeRequest(String centurionType, String directiveId) {
        return new StarblasterRequest(
                centurionType, directiveId, Path.of("/tmp/project"),
                "Build the feature", Map.of("GOOSE_PROVIDER", "anthropic"),
                4096, 2,
                "", "base"
        );
    }

    // --- Stub CfApiClient that records calls ---

    static class StubCfApiClient extends CfApiClient {

        final List<CreateTaskCall> createTaskCalls = new ArrayList<>();
        final List<CancelTaskCall> cancelTaskCalls = new ArrayList<>();
        boolean throwOnCreateTask = false;
        boolean throwOnCancelTask = false;
        boolean throwOnGetFailureReason = false;

        String taskState = "RUNNING";
        List<String> taskStateSequence = null;
        int getTaskStateCalls = 0;

        String failureReason = "";

        StubCfApiClient(CloudFoundryProperties cfProperties) {
            super(cfProperties);
        }

        @Override
        public String createTask(String appName, String command, String taskName,
                                 int memoryMb, int diskMb) {
            if (throwOnCreateTask) {
                throw new RuntimeException("Simulated CF API failure");
            }
            createTaskCalls.add(new CreateTaskCall(appName, command, taskName, memoryMb, diskMb));
            return "fake-task-guid";
        }

        @Override
        public String getTaskState(String appName, String taskName) {
            getTaskStateCalls++;
            if (taskStateSequence != null && !taskStateSequence.isEmpty()) {
                int idx = Math.min(getTaskStateCalls - 1, taskStateSequence.size() - 1);
                return taskStateSequence.get(idx);
            }
            return taskState;
        }

        @Override
        public String getTaskFailureReason(String appName, String taskName) {
            if (throwOnGetFailureReason) {
                throw new RuntimeException("Simulated CF API failure");
            }
            return failureReason;
        }

        @Override
        public void cancelTask(String appName, String taskName) {
            if (throwOnCancelTask) {
                throw new RuntimeException("Simulated CF API failure");
            }
            cancelTaskCalls.add(new CancelTaskCall(appName, taskName));
        }

        record CreateTaskCall(String appName, String command, String taskName,
                              int memoryMb, int diskMb) {}
        record CancelTaskCall(String appName, String taskName) {}
    }
}
