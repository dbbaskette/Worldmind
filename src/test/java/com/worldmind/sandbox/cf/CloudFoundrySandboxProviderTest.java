package com.worldmind.sandbox.cf;

import com.worldmind.sandbox.InstructionStore;
import com.worldmind.sandbox.OutputStore;
import com.worldmind.sandbox.AgentRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link CloudFoundrySandboxProvider}.
 *
 * <p>Uses a stub {@link CfApiClient} to intercept CF API calls, allowing us to
 * verify task creation, polling logic, and cancellation without a real CF environment.
 */
class CloudFoundrySandboxProviderTest {

    private CloudFoundrySandboxProvider provider;
    private StubCfApiClient stubApiClient;
    private CloudFoundryProperties cfProperties;

    @BeforeEach
    void setUp() {
        cfProperties = new CloudFoundryProperties();
        cfProperties.setApiUrl("https://api.cf.example.com");
        cfProperties.setOrg("worldmind-org");
        cfProperties.setSpace("production");
        cfProperties.setGitRemoteUrl("https://github.com/example/project.git");
        cfProperties.setAgentApps(Map.of(
                "coder", "agent-coder",
                "reviewer", "agent-reviewer",
                "tester", "agent-tester"
        ));
        cfProperties.setTaskMemoryMb(2048);
        cfProperties.setTaskDiskMb(4096);
        cfProperties.setTaskTimeoutSeconds(600);
        cfProperties.setOrchestratorUrl("https://worldmind.example.com");

        var gitWorkspaceManager = new GitWorkspaceManager(cfProperties.getGitRemoteUrl());
        stubApiClient = new StubCfApiClient(cfProperties);
        var instructionStore = new InstructionStore();
        var outputStore = new OutputStore();
        provider = new CloudFoundrySandboxProvider(cfProperties, gitWorkspaceManager, stubApiClient, instructionStore, outputStore);
    }

    // --- openSandbox tests ---

    @Test
    void openSandboxCreatesTaskWithCorrectParameters() {
        var request = makeRequest("coder", "TASK-001");

        provider.openSandbox(request);

        var calls = stubApiClient.createTaskCalls;
        assertEquals(1, calls.size());

        var call = calls.get(0);
        assertEquals("agent-coder", call.appName);
        assertEquals("sandbox-coder-TASK-001", call.taskName);
        assertTrue(call.command.contains("git clone"), "Should contain git clone: " + call.command);
        assertTrue(call.command.contains("worldmind/TASK-001"), "Should contain branch name: " + call.command);
        assertTrue(call.command.contains("curl"), "Should contain curl: " + call.command);
        assertTrue(call.command.contains("goose run --debug --no-session --with-builtin developer"), "Should contain goose run with developer builtin: " + call.command);
        // Instruction file must be passed via -i/--instructions flag
        assertTrue(call.command.contains("-i .worldmind-TASK-001/instruction.md"), "Should pass instruction via -i flag: " + call.command);
        // Verify instruction file is non-empty before running Goose
        assertTrue(call.command.contains("! -s"), "Should check instruction file is non-empty: " + call.command);
        assertTrue(call.command.contains("git push"), "Should contain git push: " + call.command);
        assertTrue(call.command.contains("diagnostics.log"), "Should write diagnostics: " + call.command);
        // Env vars from request are exported so entrypoint.sh can use them
        assertTrue(call.command.contains("export GOOSE_PROVIDER="), "Should export GOOSE_PROVIDER: " + call.command);
    }

    @Test
    void openSandboxExportsAllEnvVars() {
        var request = new AgentRequest(
                "coder", "TASK-001", Path.of("/tmp/project"),
                "Build something", Map.of(
                        "GOOSE_PROVIDER", "anthropic",
                        "ANTHROPIC_API_KEY", "sk-ant-test",
                        "MCP_SERVERS", "NEXUS",
                        "MCP_SERVER_NEXUS_URL", "https://nexus.example.com/mcp",
                        "MCP_SERVER_NEXUS_TOKEN", "test-token"
                ),
                4096, 2, "", "base", 0
        );

        provider.openSandbox(request);

        var call = stubApiClient.createTaskCalls.get(0);
        // All env vars are exported so entrypoint.sh can read provider config and MCP settings
        assertTrue(call.command.contains("GOOSE_PROVIDER"),
                "Should export GOOSE_PROVIDER: " + call.command);
        assertTrue(call.command.contains("ANTHROPIC_API_KEY"),
                "Should export ANTHROPIC_API_KEY: " + call.command);
        assertTrue(call.command.contains("MCP_SERVER_NEXUS_URL"),
                "Should export MCP URL env var: " + call.command);
        assertTrue(call.command.contains("MCP_SERVER_NEXUS_TOKEN"),
                "Should export MCP token env var: " + call.command);
    }

    @Test
    void openSandboxReturnsCorrectTaskNameFormat() {
        var request = makeRequest("coder", "TASK-001");

        var sandboxId = provider.openSandbox(request);

        assertEquals("sandbox-coder-TASK-001", sandboxId);
    }

    @Test
    void openSandboxFallsBackToConventionForUnknownType() {
        var request = makeRequest("unknown", "TASK-001");

        var sandboxId = provider.openSandbox(request);
        assertEquals("sandbox-unknown-TASK-001", sandboxId);

        var call = stubApiClient.createTaskCalls.get(0);
        assertEquals("agent-unknown", call.appName);
    }

    @Test
    void openSandboxUsesRequestMemoryWhenProvided() {
        var request = new AgentRequest(
                "coder", "TASK-002", Path.of("/tmp/project"),
                "Build something", Map.of(), 8192, 4,
                "", "base", 0
        );

        provider.openSandbox(request);

        var call = stubApiClient.createTaskCalls.get(0);
        assertEquals(8192, call.memoryMb);
    }

    @Test
    void openSandboxFallsBackToDefaultMemoryWhenZero() {
        var request = new AgentRequest(
                "coder", "TASK-003", Path.of("/tmp/project"),
                "Build something", Map.of(), 0, 2,
                "", "base", 0
        );

        provider.openSandbox(request);

        var call = stubApiClient.createTaskCalls.get(0);
        assertEquals(2048, call.memoryMb);
    }

    @Test
    void openSandboxIncludesDiskLimit() {
        var request = makeRequest("coder", "TASK-004");

        provider.openSandbox(request);

        var call = stubApiClient.createTaskCalls.get(0);
        assertEquals(4096, call.diskMb);
    }

    @Test
    void openSandboxHandlesCaseInsensitiveAgentType() {
        var request = new AgentRequest(
                "CODER", "TASK-005", Path.of("/tmp/project"),
                "Build something", Map.of(), 4096, 2,
                "", "base", 0
        );

        var sandboxId = provider.openSandbox(request);
        assertEquals("sandbox-coder-TASK-005", sandboxId);
    }

    @Test
    void openSandboxThrowsOnApiFailure() {
        stubApiClient.throwOnCreateTask = true;
        var request = makeRequest("coder", "TASK-001");

        assertThrows(RuntimeException.class, () -> provider.openSandbox(request));
    }

    // --- waitForCompletion tests ---

    @Test
    void waitForCompletionReturnsZeroWhenTaskSucceeded() {
        stubApiClient.taskState = "SUCCEEDED";

        int result = provider.waitForCompletion("sandbox-coder-TASK-001", 30);

        assertEquals(0, result);
    }

    @Test
    void waitForCompletionReturnsOneWhenTaskFailed() {
        stubApiClient.taskState = "FAILED";

        int result = provider.waitForCompletion("sandbox-coder-TASK-001", 30);

        assertEquals(1, result);
    }

    @Test
    void waitForCompletionReturnsMinusOneOnTimeout() {
        stubApiClient.taskState = "RUNNING";

        int result = provider.waitForCompletion("sandbox-coder-TASK-001", 0);

        assertEquals(-1, result);
    }

    @Test
    void waitForCompletionPollsUntilSucceeded() {
        stubApiClient.taskStateSequence = List.of("RUNNING", "RUNNING", "SUCCEEDED");

        int result = provider.waitForCompletion("sandbox-coder-TASK-001", 30);

        assertEquals(0, result);
        assertTrue(stubApiClient.getTaskStateCalls >= 3,
                "Should have polled at least 3 times, got " + stubApiClient.getTaskStateCalls);
    }

    // --- captureOutput tests ---

    @Test
    void captureOutputReturnsFailureReasonWhenFailed() {
        stubApiClient.failureReason = "OOM killed";

        var output = provider.captureOutput("sandbox-coder-TASK-001");

        assertTrue(output.contains("OOM killed"), "Should contain failure reason: " + output);
    }

    @Test
    void captureOutputReturnsEmptyWhenNoOutputAndNoFailure() {
        stubApiClient.failureReason = "";

        var output = provider.captureOutput("sandbox-coder-TASK-001");

        assertEquals("", output, "Should return empty when no output posted and no failure");
    }

    @Test
    void captureOutputReturnsEmptyStringOnError() {
        stubApiClient.throwOnGetFailureReason = true;

        var output = provider.captureOutput("sandbox-coder-TASK-001");

        assertEquals("", output);
    }

    // --- teardownSandbox tests ---

    @Test
    void teardownSandboxCallsCancelTask() {
        provider.teardownSandbox("sandbox-coder-TASK-001");

        assertEquals(1, stubApiClient.cancelTaskCalls.size());
        var call = stubApiClient.cancelTaskCalls.get(0);
        assertEquals("agent-coder", call.appName);
        assertEquals("sandbox-coder-TASK-001", call.taskName);
    }

    @Test
    void teardownSandboxDoesNotThrowOnErrors() {
        stubApiClient.throwOnCancelTask = true;

        assertDoesNotThrow(() -> provider.teardownSandbox("sandbox-coder-TASK-001"));
    }

    // --- getAppNameFromSandboxId tests ---

    @Test
    void getAppNameFromSandboxIdParsesCorrectly() {
        var appName = provider.getAppNameFromSandboxId("sandbox-coder-TASK-001");
        assertEquals("agent-coder", appName);
    }

    @Test
    void getAppNameFromSandboxIdHandlesReviewer() {
        var appName = provider.getAppNameFromSandboxId("sandbox-reviewer-TASK-002");
        assertEquals("agent-reviewer", appName);
    }

    @Test
    void getAppNameFromSandboxIdHandlesTester() {
        var appName = provider.getAppNameFromSandboxId("sandbox-tester-TASK-003");
        assertEquals("agent-tester", appName);
    }

    @Test
    void getAppNameFromSandboxIdHandlesTaskIdWithDashes() {
        var appName = provider.getAppNameFromSandboxId("sandbox-coder-abc-123-def");
        assertEquals("agent-coder", appName);
    }

    @Test
    void getAppNameFromSandboxIdThrowsOnInvalidFormat() {
        assertThrows(RuntimeException.class, () ->
                provider.getAppNameFromSandboxId("invalid-id"));
    }

    @Test
    void getAppNameFromSandboxIdFallsBackForUnknownType() {
        var appName = provider.getAppNameFromSandboxId("sandbox-unknown-TASK-001");
        assertEquals("agent-unknown", appName);
    }

    @Test
    void getAppNameFromSandboxIdThrowsOnMissingTaskId() {
        assertThrows(RuntimeException.class, () ->
                provider.getAppNameFromSandboxId("sandbox-"));
    }

    // --- deriveParentBranch tests ---

    @Test
    void deriveParentBranchReturnsCoderbranchForTester() {
        var branch = provider.deriveParentBranch("tester", "TASK-001-TESTER");
        assertEquals("worldmind/TASK-001", branch);
    }

    @Test
    void deriveParentBranchReturnsCoderbranchForReviewer() {
        var branch = provider.deriveParentBranch("reviewer", "TASK-001-REVIEWER");
        assertEquals("worldmind/TASK-001", branch);
    }

    @Test
    void deriveParentBranchReturnsNullForCoder() {
        var branch = provider.deriveParentBranch("coder", "TASK-001");
        assertNull(branch);
    }

    @Test
    void deriveParentBranchReturnsNullForResearcher() {
        var branch = provider.deriveParentBranch("researcher", "TASK-001");
        assertNull(branch);
    }

    @Test
    void deriveParentBranchHandlesCaseInsensitive() {
        var branch = provider.deriveParentBranch("TESTER", "TASK-001-TESTER");
        assertEquals("worldmind/TASK-001", branch);
    }

    // --- agent-type-aware task commands ---

    @Test
    void testerTaskFetchesParentBranchNotCreatesNew() {
        var request = makeRequest("tester", "TASK-001-TESTER");

        provider.openSandbox(request);

        var call = stubApiClient.createTaskCalls.get(0);
        assertTrue(call.command.contains("git fetch origin worldmind/TASK-001"),
                "TESTER should fetch parent CODER branch: " + call.command);
        assertFalse(call.command.contains("git checkout -B"),
                "TESTER should NOT create a new branch: " + call.command);
        assertFalse(call.command.contains("git push"),
                "TESTER should NOT push: " + call.command);
    }

    @Test
    void reviewerTaskFetchesParentBranchNotCreatesNew() {
        var request = makeRequest("reviewer", "TASK-001-REVIEWER");

        provider.openSandbox(request);

        var call = stubApiClient.createTaskCalls.get(0);
        assertTrue(call.command.contains("git fetch origin worldmind/TASK-001"),
                "REVIEWER should fetch parent CODER branch: " + call.command);
        assertFalse(call.command.contains("git push"),
                "REVIEWER should NOT push: " + call.command);
    }

    @Test
    void researcherTaskStaysOnDefaultBranch() {
        var request = makeRequest("researcher", "TASK-001");

        provider.openSandbox(request);

        var call = stubApiClient.createTaskCalls.get(0);
        assertFalse(call.command.contains("git checkout -B"),
                "RESEARCHER should NOT create a branch: " + call.command);
        assertFalse(call.command.contains("git push"),
                "RESEARCHER should NOT push: " + call.command);
    }

    @Test
    void coderTaskCreatesAndPushesBranch() {
        var request = makeRequest("coder", "TASK-001");

        provider.openSandbox(request);

        var call = stubApiClient.createTaskCalls.get(0);
        // All CODER tasks (first attempt or retry) always start fresh from main
        // This prevents stale branches from causing merge conflicts
        assertTrue(call.command.contains("git push origin --delete worldmind/TASK-001"),
                "CODER should delete any existing remote branch: " + call.command);
        assertTrue(call.command.contains("git branch -D worldmind/TASK-001"),
                "CODER should delete any existing local branch: " + call.command);
        assertTrue(call.command.contains("git checkout -b worldmind/TASK-001"),
                "CODER should create fresh branch from main: " + call.command);
        assertTrue(call.command.contains("git push -uf origin worldmind/TASK-001"),
                "CODER should push branch: " + call.command);
    }

    @Test
    void coderRetryForcesFreshBranch() {
        // Retry (iteration > 0) should delete the old branch and create fresh from main
        var request = new AgentRequest(
                "coder", "TASK-RETRY", Path.of("/tmp/project"),
                "Fix the issues", Map.of("GOOSE_PROVIDER", "anthropic"),
                4096, 2,
                "", "base", 1  // iteration = 1 means retry
        );

        provider.openSandbox(request);

        var call = stubApiClient.createTaskCalls.get(0);
        // Retry should delete old branch and create fresh
        assertTrue(call.command.contains("git push origin --delete worldmind/TASK-RETRY"),
                "Retry should delete old branch: " + call.command);
        assertTrue(call.command.contains("git branch -D worldmind/TASK-RETRY"),
                "Retry should delete local branch: " + call.command);
        assertTrue(call.command.contains("git checkout -b worldmind/TASK-RETRY"),
                "Retry should create fresh branch: " + call.command);
    }

    // --- detectChanges tests ---

    @Test
    void detectChangesReturnsEmptyListOnGitFailure() {
        // With a real GitWorkspaceManager, clone will fail since the URL is fake.
        // The method should catch the exception and return an empty list.
        var changes = provider.detectChanges("TASK-001", Path.of("/tmp/project"));

        assertNotNull(changes);
        assertTrue(changes.isEmpty());
    }

    @Test
    void resolveAuthenticatedGitUrlEmbedsToken() {
        cfProperties.setGitToken("ghp_test123");
        cfProperties.setGitRemoteUrl("https://github.com/example/project.git");

        // Re-create provider with updated properties
        var gitWorkspaceManager = new GitWorkspaceManager(cfProperties.getGitRemoteUrl());
        provider = new CloudFoundrySandboxProvider(cfProperties, gitWorkspaceManager, stubApiClient, new com.worldmind.sandbox.InstructionStore(), new com.worldmind.sandbox.OutputStore());

        var url = provider.resolveAuthenticatedGitUrl();
        assertTrue(url.startsWith("https://x-access-token:ghp_test123@"), "Should embed token: " + url);
        assertTrue(url.contains("github.com/example/project.git"), "Should preserve rest of URL: " + url);
    }

    @Test
    void resolveAuthenticatedGitUrlSkipsTokenWhenBlank() {
        cfProperties.setGitToken("");
        cfProperties.setGitRemoteUrl("https://github.com/example/project.git");

        var gitWorkspaceManager = new GitWorkspaceManager(cfProperties.getGitRemoteUrl());
        provider = new CloudFoundrySandboxProvider(cfProperties, gitWorkspaceManager, stubApiClient, new com.worldmind.sandbox.InstructionStore(), new com.worldmind.sandbox.OutputStore());

        var url = provider.resolveAuthenticatedGitUrl();
        assertEquals("https://github.com/example/project.git", url);
    }

    // --- Helper methods ---

    private AgentRequest makeRequest(String agentType, String taskId) {
        return new AgentRequest(
                agentType, taskId, Path.of("/tmp/project"),
                "Build the feature", Map.of("GOOSE_PROVIDER", "anthropic"),
                4096, 2,
                "", "base", 0
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
