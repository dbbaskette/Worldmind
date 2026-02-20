package com.worldmind.core.nodes;

import com.worldmind.core.model.*;
import com.worldmind.core.state.WorldmindState;
import com.worldmind.sandbox.AgentDispatcher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link DispatchAgentNode}.
 * <p>
 * Uses Mockito to mock {@link AgentDispatcher} so that no real Docker containers are launched.
 */
class DispatchAgentNodeTest {

    private AgentDispatcher bridge;
    private DispatchAgentNode node;

    @BeforeEach
    void setUp() {
        bridge = mock(AgentDispatcher.class);
        node = new DispatchAgentNode(bridge);
    }

    @Test
    @DisplayName("dispatches next PENDING task and returns state updates")
    void applyDispatchesNextPendingTask() {
        var task = new Task(
            "TASK-001", "CODER", "Create file",
            "", "File exists",
            List.of(), TaskStatus.PENDING, 0, 3,
            FailureStrategy.RETRY, List.of(), List.of(), null
        );
        var updatedTask = new Task(
            "TASK-001", "CODER", "Create file",
            "", "File exists",
            List.of(), TaskStatus.PASSED, 1, 3,
            FailureStrategy.RETRY, List.of(), List.of(new FileRecord("hello.py", "created", 1)), 5000L
        );
        var sandboxInfo = new SandboxInfo("c-1", "CODER", "TASK-001", "completed",
            Instant.now(), Instant.now());
        var bridgeResult = new AgentDispatcher.BridgeResult(updatedTask, sandboxInfo, "ok");

        when(bridge.executeTask(any(), any(), any(), any(), any())).thenReturn(bridgeResult);

        var context = new ProjectContext("/tmp/p", List.of(), "java", "spring", Map.of(), 5, "");
        var state = new WorldmindState(Map.of(
            "tasks", List.of(task),
            "currentTaskIndex", 0,
            "projectContext", context
        ));

        var result = node.apply(state);

        assertNotNull(result);
        assertTrue(result.containsKey("sandboxes"));
        assertTrue(result.containsKey("currentTaskIndex"));
        assertEquals(1, result.get("currentTaskIndex"));
        assertEquals(MissionStatus.EXECUTING.name(), result.get("status"));
    }

    @Test
    @DisplayName("returns COMPLETED status when no tasks are present")
    void applySkipsWhenNoTasksPending() {
        var state = new WorldmindState(Map.of(
            "tasks", List.of(),
            "currentTaskIndex", 0
        ));

        var result = node.apply(state);

        assertEquals(MissionStatus.COMPLETED.name(), result.get("status"));
        verifyNoInteractions(bridge);
    }

    @Test
    @DisplayName("returns COMPLETED status when currentTaskIndex exceeds tasks size")
    void applyCompletesWhenIndexExceedsTasks() {
        var task = new Task(
            "TASK-001", "CODER", "Create file",
            "", "File exists",
            List.of(), TaskStatus.PASSED, 1, 3,
            FailureStrategy.RETRY, List.of(), List.of(), 1000L
        );
        var state = new WorldmindState(Map.of(
            "tasks", List.of(task),
            "currentTaskIndex", 1
        ));

        var result = node.apply(state);

        assertEquals(MissionStatus.COMPLETED.name(), result.get("status"));
        verifyNoInteractions(bridge);
    }

    @Test
    @DisplayName("advances index without dispatching when task is not PENDING")
    void applyAdvancesWhenTaskAlreadyProcessed() {
        var task = new Task(
            "TASK-001", "CODER", "Create file",
            "", "File exists",
            List.of(), TaskStatus.PASSED, 1, 3,
            FailureStrategy.RETRY, List.of(), List.of(), 2000L
        );
        var state = new WorldmindState(Map.of(
            "tasks", List.of(task),
            "currentTaskIndex", 0
        ));

        var result = node.apply(state);

        assertEquals(1, result.get("currentTaskIndex"));
        verifyNoInteractions(bridge);
    }

    @Test
    @DisplayName("includes errors in result when task fails")
    void applyIncludesErrorsOnFailure() {
        var task = new Task(
            "TASK-001", "CODER", "Create file",
            "", "File exists",
            List.of(), TaskStatus.PENDING, 0, 3,
            FailureStrategy.RETRY, List.of(), List.of(), null
        );
        var failedTask = new Task(
            "TASK-001", "CODER", "Create file",
            "", "File exists",
            List.of(), TaskStatus.FAILED, 1, 3,
            FailureStrategy.RETRY, List.of(), List.of(), 3000L
        );
        var sandboxInfo = new SandboxInfo("c-2", "CODER", "TASK-001", "failed",
            Instant.now(), Instant.now());
        var bridgeResult = new AgentDispatcher.BridgeResult(failedTask, sandboxInfo, "compilation error");

        when(bridge.executeTask(any(), any(), any(), any(), any())).thenReturn(bridgeResult);

        var context = new ProjectContext("/tmp/p", List.of(), "java", "spring", Map.of(), 5, "");
        var state = new WorldmindState(Map.of(
            "tasks", List.of(task),
            "currentTaskIndex", 0,
            "projectContext", context
        ));

        var result = node.apply(state);

        assertTrue(result.containsKey("errors"));
        @SuppressWarnings("unchecked")
        var errors = (List<String>) result.get("errors");
        assertEquals(1, errors.size());
        assertTrue(errors.get(0).contains("TASK-001"));
        assertTrue(errors.get(0).contains("failed"));
    }

    @Test
    @DisplayName("passes correct arguments to AgentDispatcher")
    void applyPassesCorrectArgsToBridge() {
        var task = new Task(
            "TASK-001", "CODER", "Create file",
            "", "File exists",
            List.of(), TaskStatus.PENDING, 0, 3,
            FailureStrategy.RETRY, List.of(), List.of(), null
        );
        var updatedTask = new Task(
            "TASK-001", "CODER", "Create file",
            "", "File exists",
            List.of(), TaskStatus.PASSED, 1, 3,
            FailureStrategy.RETRY, List.of(), List.of(), 1000L
        );
        var sandboxInfo = new SandboxInfo("c-3", "CODER", "TASK-001", "completed",
            Instant.now(), Instant.now());
        var bridgeResult = new AgentDispatcher.BridgeResult(updatedTask, sandboxInfo, "ok");

        when(bridge.executeTask(any(), any(), any(), any(), any())).thenReturn(bridgeResult);

        var context = new ProjectContext("/tmp/project", List.of("src/Main.java"), "java", "spring", Map.of(), 5, "summary");
        var state = new WorldmindState(Map.of(
            "tasks", List.of(task),
            "currentTaskIndex", 0,
            "projectContext", context
        ));

        node.apply(state);

        verify(bridge).executeTask(
            eq(task),
            eq(context),
            eq(Path.of("/tmp/project")),
            any(),
            any()
        );
    }

    @Test
    @DisplayName("uses fallback project path when projectContext is absent")
    void applyUsesFallbackPathWhenNoContext() {
        var task = new Task(
            "TASK-001", "CODER", "Create file",
            "", "File exists",
            List.of(), TaskStatus.PENDING, 0, 3,
            FailureStrategy.RETRY, List.of(), List.of(), null
        );
        var updatedTask = new Task(
            "TASK-001", "CODER", "Create file",
            "", "File exists",
            List.of(), TaskStatus.PASSED, 1, 3,
            FailureStrategy.RETRY, List.of(), List.of(), 500L
        );
        var sandboxInfo = new SandboxInfo("c-4", "CODER", "TASK-001", "completed",
            Instant.now(), Instant.now());
        var bridgeResult = new AgentDispatcher.BridgeResult(updatedTask, sandboxInfo, "ok");

        when(bridge.executeTask(any(), any(), any(), any(), any())).thenReturn(bridgeResult);

        var state = new WorldmindState(Map.of(
            "tasks", List.of(task),
            "currentTaskIndex", 0
        ));

        node.apply(state);

        verify(bridge).executeTask(
            eq(task),
            isNull(),
            eq(Path.of(".")),
            any(),
            any()
        );
    }

    // ── Retry context tests ─────────────────────────────────────────────

    @Test
    @DisplayName("retries FAILED task when retryContext is present")
    void retriesFailedTaskWithRetryContext() {
        var task = new Task(
            "TASK-001", "CODER", "Create file",
            "original context", "File exists",
            List.of(), TaskStatus.FAILED, 1, 3,
            FailureStrategy.RETRY, List.of(), List.of(), 2000L
        );
        var updatedTask = new Task(
            "TASK-001", "CODER", "Create file",
            "original context", "File exists",
            List.of(), TaskStatus.PASSED, 2, 3,
            FailureStrategy.RETRY, List.of(), List.of(new FileRecord("hello.py", "created", 1)), 3000L
        );
        var sandboxInfo = new SandboxInfo("c-5", "CODER", "TASK-001", "completed",
            Instant.now(), Instant.now());
        var bridgeResult = new AgentDispatcher.BridgeResult(updatedTask, sandboxInfo, "ok");

        when(bridge.executeTask(any(), any(), any(), any(), any())).thenReturn(bridgeResult);

        var context = new ProjectContext("/tmp/p", List.of(), "java", "spring", Map.of(), 5, "");
        var state = new WorldmindState(Map.of(
            "tasks", List.of(task),
            "currentTaskIndex", 0,
            "projectContext", context,
            "retryContext", "Previous attempt failed due to missing import"
        ));

        var result = node.apply(state);

        // Bridge should have been called (not skipped)
        verify(bridge).executeTask(any(), any(), any(), any(), any());
        assertNotNull(result);
        assertTrue(result.containsKey("sandboxes"));
        assertEquals(1, result.get("currentTaskIndex"));
        assertEquals(MissionStatus.EXECUTING.name(), result.get("status"));
        // retryContext should be cleared
        assertEquals("", result.get("retryContext"));
    }

    @Test
    @DisplayName("does not retry FAILED task without retryContext")
    void doesNotRetryFailedTaskWithoutRetryContext() {
        var task = new Task(
            "TASK-001", "CODER", "Create file",
            "", "File exists",
            List.of(), TaskStatus.FAILED, 1, 3,
            FailureStrategy.RETRY, List.of(), List.of(), 2000L
        );
        var state = new WorldmindState(Map.of(
            "tasks", List.of(task),
            "currentTaskIndex", 0
        ));

        var result = node.apply(state);

        // Should advance index without dispatching
        assertEquals(1, result.get("currentTaskIndex"));
        verifyNoInteractions(bridge);
    }

    @Test
    @DisplayName("clears retryContext after consuming it")
    void clearsRetryContextAfterConsuming() {
        var task = new Task(
            "TASK-001", "CODER", "Create file",
            "", "File exists",
            List.of(), TaskStatus.FAILED, 1, 3,
            FailureStrategy.RETRY, List.of(), List.of(), 2000L
        );
        var updatedTask = new Task(
            "TASK-001", "CODER", "Create file",
            "", "File exists",
            List.of(), TaskStatus.PASSED, 2, 3,
            FailureStrategy.RETRY, List.of(), List.of(), 1500L
        );
        var sandboxInfo = new SandboxInfo("c-6", "CODER", "TASK-001", "completed",
            Instant.now(), Instant.now());
        var bridgeResult = new AgentDispatcher.BridgeResult(updatedTask, sandboxInfo, "ok");

        when(bridge.executeTask(any(), any(), any(), any(), any())).thenReturn(bridgeResult);

        var context = new ProjectContext("/tmp/p", List.of(), "java", "spring", Map.of(), 5, "");
        var state = new WorldmindState(Map.of(
            "tasks", List.of(task),
            "currentTaskIndex", 0,
            "projectContext", context,
            "retryContext", "Fix the compilation error"
        ));

        var result = node.apply(state);

        assertTrue(result.containsKey("retryContext"));
        assertEquals("", result.get("retryContext"));
    }

    @Test
    @DisplayName("augments inputContext with retry info when retryContext is present")
    void augmentsInputContextWithRetryInfo() {
        var task = new Task(
            "TASK-001", "CODER", "Create file",
            "original context", "File exists",
            List.of(), TaskStatus.FAILED, 1, 3,
            FailureStrategy.RETRY, List.of(), List.of(), 2000L
        );
        var updatedTask = new Task(
            "TASK-001", "CODER", "Create file",
            "original context", "File exists",
            List.of(), TaskStatus.PASSED, 2, 3,
            FailureStrategy.RETRY, List.of(), List.of(), 1500L
        );
        var sandboxInfo = new SandboxInfo("c-7", "CODER", "TASK-001", "completed",
            Instant.now(), Instant.now());
        var bridgeResult = new AgentDispatcher.BridgeResult(updatedTask, sandboxInfo, "ok");

        when(bridge.executeTask(any(), any(), any(), any(), any())).thenReturn(bridgeResult);

        var context = new ProjectContext("/tmp/p", List.of(), "java", "spring", Map.of(), 5, "");
        var state = new WorldmindState(Map.of(
            "tasks", List.of(task),
            "currentTaskIndex", 0,
            "projectContext", context,
            "retryContext", "Previous attempt had compilation error in Main.java"
        ));

        node.apply(state);

        // Verify that the task passed to bridge has augmented inputContext
        verify(bridge).executeTask(
            argThat(d -> d.inputContext().contains("## Retry Context (from previous attempt)")
                      && d.inputContext().contains("Previous attempt had compilation error in Main.java")
                      && d.inputContext().contains("original context")),
            eq(context),
            eq(Path.of("/tmp/p")),
            any(),
            any()
        );
    }
}
