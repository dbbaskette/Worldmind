package com.worldmind.starblaster.cf;

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

        var gitWorkspaceManager = new GitWorkspaceManager(cfProperties.getGitRemoteUrl());
        stubApiClient = new StubCfApiClient(cfProperties);
        provider = new CloudFoundryStarblasterProvider(cfProperties, gitWorkspaceManager, stubApiClient);
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
        assertTrue(call.command.contains("goose run"), "Should contain goose run: " + call.command);
        assertTrue(call.command.contains("git push"), "Should contain git push: " + call.command);
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
                ""
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
                ""
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
                ""
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

    // --- Helper methods ---

    private StarblasterRequest makeRequest(String centurionType, String directiveId) {
        return new StarblasterRequest(
                centurionType, directiveId, Path.of("/tmp/project"),
                "Build the feature", Map.of("GOOSE_PROVIDER", "anthropic"),
                4096, 2,
                ""
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
