package com.worldmind.core.nodes;

import com.worldmind.core.model.*;
import com.worldmind.core.state.WorldmindState;
import com.worldmind.sandbox.AgentDispatcher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ParallelDispatchNodeTest {

    private AgentDispatcher mockBridge;
    private ParallelDispatchNode node;

    @BeforeEach
    void setUp() {
        mockBridge = mock(AgentDispatcher.class);
        node = new ParallelDispatchNode(mockBridge, 10);
    }

    private Task task(String id) {
        return new Task(id, "CODER", "Do " + id, "", "Done", List.of(),
                TaskStatus.PENDING, 0, 3, FailureStrategy.RETRY, List.of(), List.of(), null);
    }

    private AgentDispatcher.BridgeResult successResult(Task d) {
        var updated = new Task(d.id(), d.agent(), d.description(), d.inputContext(),
                d.successCriteria(), d.dependencies(), TaskStatus.PASSED,
                1, d.maxIterations(), d.onFailure(), d.targetFiles(),
                List.of(new FileRecord("test.java", "created", 10)), 500L);
        return new AgentDispatcher.BridgeResult(updated,
                new SandboxInfo("c-" + d.id(), d.agent(), d.id(), "completed", Instant.now(), Instant.now()),
                "Success output");
    }

    private AgentDispatcher.BridgeResult failureResult(Task d) {
        var updated = new Task(d.id(), d.agent(), d.description(), d.inputContext(),
                d.successCriteria(), d.dependencies(), TaskStatus.FAILED,
                1, d.maxIterations(), d.onFailure(), d.targetFiles(), List.of(), 300L);
        return new AgentDispatcher.BridgeResult(updated,
                new SandboxInfo("c-" + d.id(), d.agent(), d.id(), "failed", Instant.now(), Instant.now()),
                "Failure output");
    }

    @Test
    @DisplayName("Single task wave dispatches correctly")
    @SuppressWarnings("unchecked")
    void singleTaskWave() {
        var d1 = task("TASK-001");
        when(mockBridge.executeTask(any(), any(), any(), any(), any())).thenReturn(successResult(d1));

        var state = new WorldmindState(Map.of(
                "waveTaskIds", List.of("TASK-001"),
                "tasks", List.of(d1)
        ));

        var result = node.apply(state);

        var waveResults = (List<WaveDispatchResult>) result.get("waveDispatchResults");
        assertEquals(1, waveResults.size());
        assertEquals("TASK-001", waveResults.get(0).taskId());
        assertEquals(TaskStatus.PASSED, waveResults.get(0).status());

        var sandboxes = (List<SandboxInfo>) result.get("sandboxes");
        assertEquals(1, sandboxes.size());
    }

    @Test
    @DisplayName("3-task wave calls bridge 3 times")
    @SuppressWarnings("unchecked")
    void threeTaskWave() {
        var d1 = task("TASK-001");
        var d2 = task("TASK-002");
        var d3 = task("TASK-003");

        when(mockBridge.executeTask(argThat(d -> d != null && d.id().equals("TASK-001")), any(), any(), any(), any()))
                .thenReturn(successResult(d1));
        when(mockBridge.executeTask(argThat(d -> d != null && d.id().equals("TASK-002")), any(), any(), any(), any()))
                .thenReturn(successResult(d2));
        when(mockBridge.executeTask(argThat(d -> d != null && d.id().equals("TASK-003")), any(), any(), any(), any()))
                .thenReturn(successResult(d3));

        var state = new WorldmindState(Map.of(
                "waveTaskIds", List.of("TASK-001", "TASK-002", "TASK-003"),
                "tasks", List.of(d1, d2, d3)
        ));

        var result = node.apply(state);

        var waveResults = (List<WaveDispatchResult>) result.get("waveDispatchResults");
        assertEquals(3, waveResults.size());

        var sandboxes = (List<SandboxInfo>) result.get("sandboxes");
        assertEquals(3, sandboxes.size());

        verify(mockBridge, times(3)).executeTask(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("One failure among successes: errors appended, sandboxes from all")
    @SuppressWarnings("unchecked")
    void mixedSuccessAndFailure() {
        var d1 = task("TASK-001");
        var d2 = task("TASK-002");

        when(mockBridge.executeTask(argThat(d -> d != null && d.id().equals("TASK-001")), any(), any(), any(), any()))
                .thenReturn(successResult(d1));
        when(mockBridge.executeTask(argThat(d -> d != null && d.id().equals("TASK-002")), any(), any(), any(), any()))
                .thenReturn(failureResult(d2));

        var state = new WorldmindState(Map.of(
                "waveTaskIds", List.of("TASK-001", "TASK-002"),
                "tasks", List.of(d1, d2)
        ));

        var result = node.apply(state);

        var waveResults = (List<WaveDispatchResult>) result.get("waveDispatchResults");
        assertEquals(2, waveResults.size());

        var errors = (List<String>) result.get("errors");
        assertEquals(1, errors.size());
        assertTrue(errors.get(0).contains("TASK-002"));

        var sandboxes = (List<SandboxInfo>) result.get("sandboxes");
        assertEquals(2, sandboxes.size());
    }

    @Test
    @DisplayName("Infrastructure exception captured as error, other tasks still run")
    @SuppressWarnings("unchecked")
    void infrastructureException() {
        var d1 = task("TASK-001");
        var d2 = task("TASK-002");

        when(mockBridge.executeTask(argThat(d -> d != null && d.id().equals("TASK-001")), any(), any(), any(), any()))
                .thenThrow(new RuntimeException("Docker unavailable"));
        when(mockBridge.executeTask(argThat(d -> d != null && d.id().equals("TASK-002")), any(), any(), any(), any()))
                .thenReturn(successResult(d2));

        var state = new WorldmindState(Map.of(
                "waveTaskIds", List.of("TASK-001", "TASK-002"),
                "tasks", List.of(d1, d2)
        ));

        var result = node.apply(state);

        var waveResults = (List<WaveDispatchResult>) result.get("waveDispatchResults");
        assertEquals(2, waveResults.size());

        var errors = (List<String>) result.get("errors");
        assertTrue(errors.stream().anyMatch(e -> e.contains("TASK-001") && e.contains("Docker unavailable")));

        // TASK-002 should still have succeeded
        var dir2Result = waveResults.stream()
                .filter(r -> r.taskId().equals("TASK-002")).findFirst().orElseThrow();
        assertEquals(TaskStatus.PASSED, dir2Result.status());
    }

    @Test
    @DisplayName("Empty wave: no bridge calls")
    @SuppressWarnings("unchecked")
    void emptyWave() {
        var state = new WorldmindState(Map.of(
                "waveTaskIds", List.of(),
                "tasks", List.of()
        ));

        var result = node.apply(state);

        var waveResults = (List<WaveDispatchResult>) result.get("waveDispatchResults");
        assertTrue(waveResults.isEmpty());

        verify(mockBridge, never()).executeTask(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("Retry context is augmented into task and then cleared")
    @SuppressWarnings("unchecked")
    void retryContextAugmented() {
        var d1 = new Task("TASK-001", "CODER", "Do something", "original context", "Done", List.of(),
                TaskStatus.FAILED, 1, 3, FailureStrategy.RETRY, List.of(), List.of(), null);

        when(mockBridge.executeTask(any(), any(), any(), any(), any())).thenAnswer(inv -> {
            Task dispatched = inv.getArgument(0);
            assertTrue(dispatched.inputContext().contains("Retry Context"));
            return successResult(dispatched);
        });

        var state = new WorldmindState(Map.of(
                "waveTaskIds", List.of("TASK-001"),
                "tasks", List.of(d1),
                "retryContext", "Previous test failures: X and Y"
        ));

        var result = node.apply(state);
        assertEquals("", result.get("retryContext"));
    }
}
