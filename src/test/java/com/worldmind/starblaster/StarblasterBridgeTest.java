package com.worldmind.starblaster;

import com.worldmind.core.model.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class StarblasterBridgeTest {

    private StarblasterManager manager;
    private StarblasterBridge bridge;

    @BeforeEach
    void setUp() {
        manager = mock(StarblasterManager.class);
        bridge = new StarblasterBridge(manager);
    }

    @Test
    void executeDirectiveReturnsUpdatedDirectiveOnSuccess() {
        var directive = new Directive(
            "DIR-001", "FORGE", "Create hello.py",
            "context", "hello.py exists",
            List.of(), DirectiveStatus.PENDING, 0, 3,
            FailureStrategy.RETRY, List.of(), null
        );
        var context = new ProjectContext("/tmp/p", List.of(), "python", "none", Map.of(), 0, "");
        var fileChanges = List.of(new FileRecord("hello.py", "created", 1));
        var execResult = new StarblasterManager.ExecutionResult(0, "done", "c-1", fileChanges, 5000L);

        when(manager.executeDirective(
            eq("FORGE"), eq("DIR-001"), any(), anyString(), any()))
            .thenReturn(execResult);

        var result = bridge.executeDirective(directive, context, Path.of("/tmp/p"));

        assertEquals(DirectiveStatus.PASSED, result.directive().status());
        assertEquals(5000L, result.directive().elapsedMs());
        assertEquals(1, result.directive().filesAffected().size());
        assertNotNull(result.starblasterInfo());
    }

    @Test
    void executeDirectiveMarksFailedOnNonZeroExit() {
        var directive = new Directive(
            "DIR-002", "FORGE", "Bad task",
            "", "never",
            List.of(), DirectiveStatus.PENDING, 0, 3,
            FailureStrategy.RETRY, List.of(), null
        );
        var execResult = new StarblasterManager.ExecutionResult(1, "error", "c-2", List.of(), 3000L);

        when(manager.executeDirective(any(), any(), any(), anyString(), any()))
            .thenReturn(execResult);

        var result = bridge.executeDirective(directive, null, Path.of("/tmp"));

        assertEquals(DirectiveStatus.FAILED, result.directive().status());
    }

    @Test
    void executeDirectiveIncrementsIteration() {
        var directive = new Directive(
            "DIR-003", "VIGIL", "Review code",
            "input", "no issues",
            List.of(), DirectiveStatus.PENDING, 1, 5,
            FailureStrategy.REPLAN, List.of(), null
        );
        var execResult = new StarblasterManager.ExecutionResult(0, "ok", "c-3", List.of(), 1000L);

        when(manager.executeDirective(any(), any(), any(), anyString(), any()))
            .thenReturn(execResult);

        var result = bridge.executeDirective(directive, null, Path.of("/tmp"));

        assertEquals(2, result.directive().iteration());
    }

    @Test
    void executeDirectivePopulatesStarblasterInfo() {
        var directive = new Directive(
            "DIR-004", "GAUNTLET", "Run tests",
            "", "all pass",
            List.of(), DirectiveStatus.PENDING, 0, 3,
            FailureStrategy.RETRY, List.of(), null
        );
        var execResult = new StarblasterManager.ExecutionResult(0, "tests passed", "container-42", List.of(), 8000L);

        when(manager.executeDirective(any(), any(), any(), anyString(), any()))
            .thenReturn(execResult);

        var result = bridge.executeDirective(directive, null, Path.of("/tmp"));

        assertEquals("container-42", result.starblasterInfo().containerId());
        assertEquals("GAUNTLET", result.starblasterInfo().centurionType());
        assertEquals("DIR-004", result.starblasterInfo().directiveId());
        assertEquals("completed", result.starblasterInfo().status());
        assertNotNull(result.starblasterInfo().startedAt());
        assertNotNull(result.starblasterInfo().completedAt());
    }

    @Test
    void executeDirectiveCapturesOutput() {
        var directive = new Directive(
            "DIR-005", "FORGE", "Build feature",
            "", "feature works",
            List.of(), DirectiveStatus.PENDING, 0, 3,
            FailureStrategy.RETRY, List.of(), null
        );
        var execResult = new StarblasterManager.ExecutionResult(0, "feature built successfully", "c-5", List.of(), 2000L);

        when(manager.executeDirective(any(), any(), any(), anyString(), any()))
            .thenReturn(execResult);

        var result = bridge.executeDirective(directive, null, Path.of("/tmp"));

        assertEquals("feature built successfully", result.output());
    }

    @Test
    void executeDirectiveFailedStarblasterInfoStatusIsFailed() {
        var directive = new Directive(
            "DIR-006", "FORGE", "Failing task",
            "", "won't pass",
            List.of(), DirectiveStatus.PENDING, 0, 3,
            FailureStrategy.RETRY, List.of(), null
        );
        var execResult = new StarblasterManager.ExecutionResult(1, "crashed", "c-6", List.of(), 500L);

        when(manager.executeDirective(any(), any(), any(), anyString(), any()))
            .thenReturn(execResult);

        var result = bridge.executeDirective(directive, null, Path.of("/tmp"));

        assertEquals("failed", result.starblasterInfo().status());
    }
}
