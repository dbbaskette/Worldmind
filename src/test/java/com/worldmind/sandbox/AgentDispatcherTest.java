package com.worldmind.sandbox;

import com.worldmind.core.model.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AgentDispatcherTest {

    private SandboxManager manager;
    private AgentDispatcher bridge;

    @BeforeEach
    void setUp() {
        manager = mock(SandboxManager.class);
        bridge = new AgentDispatcher(manager);
    }

    @Test
    void executeTaskReturnsUpdatedTaskOnSuccess() {
        var task = new Task(
            "TASK-001", "CODER", "Create hello.py",
            "context", "hello.py exists",
            List.of(), TaskStatus.PENDING, 0, 3,
            FailureStrategy.RETRY, List.of(), List.of(), null
        );
        var context = new ProjectContext("/tmp/p", List.of(), "python", "none", Map.of(), 0, "");
        var fileChanges = List.of(new FileRecord("hello.py", "created", 1));
        var execResult = new SandboxManager.ExecutionResult(0, "done", "c-1", fileChanges, 5000L);

        when(manager.executeTask(
            eq("CODER"), eq("TASK-001"), any(), anyString(), any(), any(), any(), anyInt()))
            .thenReturn(execResult);

        var result = bridge.executeTask(task, context, Path.of("/tmp/p"), "", "base");

        // CODER tasks return VERIFYING status (need quality gate evaluation)
        assertEquals(TaskStatus.VERIFYING, result.task().status());
        assertEquals(5000L, result.task().elapsedMs());
        assertEquals(1, result.task().filesAffected().size());
        assertNotNull(result.sandboxInfo());
    }

    @Test
    void executeTaskMarksFailedOnNonZeroExit() {
        var task = new Task(
            "TASK-002", "CODER", "Bad task",
            "", "never",
            List.of(), TaskStatus.PENDING, 0, 3,
            FailureStrategy.RETRY, List.of(), List.of(), null
        );
        var execResult = new SandboxManager.ExecutionResult(1, "error", "c-2", List.of(), 3000L);

        when(manager.executeTask(any(), any(), any(), anyString(), any(), any(), any(), anyInt()))
            .thenReturn(execResult);

        var result = bridge.executeTask(task, null, Path.of("/tmp"), "", "base");

        assertEquals(TaskStatus.FAILED, result.task().status());
    }

    @Test
    void executeTaskIncrementsIteration() {
        var task = new Task(
            "TASK-003", "REVIEWER", "Review code",
            "input", "no issues",
            List.of(), TaskStatus.PENDING, 1, 5,
            FailureStrategy.REPLAN, List.of(), List.of(), null
        );
        var execResult = new SandboxManager.ExecutionResult(0, "ok", "c-3", List.of(), 1000L);

        when(manager.executeTask(any(), any(), any(), anyString(), any(), any(), any(), anyInt()))
            .thenReturn(execResult);

        var result = bridge.executeTask(task, null, Path.of("/tmp"), "", "base");

        assertEquals(2, result.task().iteration());
    }

    @Test
    void executeTaskPopulatesSandboxInfo() {
        var task = new Task(
            "TASK-004", "TESTER", "Run tests",
            "", "all pass",
            List.of(), TaskStatus.PENDING, 0, 3,
            FailureStrategy.RETRY, List.of(), List.of(), null
        );
        var execResult = new SandboxManager.ExecutionResult(0, "tests passed", "container-42", List.of(), 8000L);

        when(manager.executeTask(any(), any(), any(), anyString(), any(), any(), any(), anyInt()))
            .thenReturn(execResult);

        var result = bridge.executeTask(task, null, Path.of("/tmp"), "", "base");

        assertEquals("container-42", result.sandboxInfo().containerId());
        assertEquals("TESTER", result.sandboxInfo().agentType());
        assertEquals("TASK-004", result.sandboxInfo().taskId());
        assertEquals("completed", result.sandboxInfo().status());
        assertNotNull(result.sandboxInfo().startedAt());
        assertNotNull(result.sandboxInfo().completedAt());
    }

    @Test
    void executeTaskCapturesOutput() {
        var task = new Task(
            "TASK-005", "CODER", "Build feature",
            "", "feature works",
            List.of(), TaskStatus.PENDING, 0, 3,
            FailureStrategy.RETRY, List.of(), List.of(), null
        );
        var execResult = new SandboxManager.ExecutionResult(0, "feature built successfully", "c-5", List.of(), 2000L);

        when(manager.executeTask(any(), any(), any(), anyString(), any(), any(), any(), anyInt()))
            .thenReturn(execResult);

        var result = bridge.executeTask(task, null, Path.of("/tmp"), "", "base");

        assertEquals("feature built successfully", result.output());
    }

    @Test
    void executeTaskFailedSandboxInfoStatusIsFailed() {
        var task = new Task(
            "TASK-006", "CODER", "Failing task",
            "", "won't pass",
            List.of(), TaskStatus.PENDING, 0, 3,
            FailureStrategy.RETRY, List.of(), List.of(), null
        );
        var execResult = new SandboxManager.ExecutionResult(1, "crashed", "c-6", List.of(), 500L);

        when(manager.executeTask(any(), any(), any(), anyString(), any(), any(), any(), anyInt()))
            .thenReturn(execResult);

        var result = bridge.executeTask(task, null, Path.of("/tmp"), "", "base");

        assertEquals("failed", result.sandboxInfo().status());
    }
}
