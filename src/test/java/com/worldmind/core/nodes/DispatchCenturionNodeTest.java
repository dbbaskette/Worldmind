package com.worldmind.core.nodes;

import com.worldmind.core.model.*;
import com.worldmind.core.state.WorldmindState;
import com.worldmind.starblaster.StarblasterBridge;
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
 * Unit tests for {@link DispatchCenturionNode}.
 * <p>
 * Uses Mockito to mock {@link StarblasterBridge} so that no real Docker containers are launched.
 */
class DispatchCenturionNodeTest {

    private StarblasterBridge bridge;
    private DispatchCenturionNode node;

    @BeforeEach
    void setUp() {
        bridge = mock(StarblasterBridge.class);
        node = new DispatchCenturionNode(bridge);
    }

    @Test
    @DisplayName("dispatches next PENDING directive and returns state updates")
    void applyDispatchesNextPendingDirective() {
        var directive = new Directive(
            "DIR-001", "FORGE", "Create file",
            "", "File exists",
            List.of(), DirectiveStatus.PENDING, 0, 3,
            FailureStrategy.RETRY, List.of(), null
        );
        var updatedDirective = new Directive(
            "DIR-001", "FORGE", "Create file",
            "", "File exists",
            List.of(), DirectiveStatus.PASSED, 1, 3,
            FailureStrategy.RETRY, List.of(new FileRecord("hello.py", "created", 1)), 5000L
        );
        var starblasterInfo = new StarblasterInfo("c-1", "FORGE", "DIR-001", "completed",
            Instant.now(), Instant.now());
        var bridgeResult = new StarblasterBridge.BridgeResult(updatedDirective, starblasterInfo, "ok");

        when(bridge.executeDirective(any(), any(), any(), any(), any())).thenReturn(bridgeResult);

        var context = new ProjectContext("/tmp/p", List.of(), "java", "spring", Map.of(), 5, "");
        var state = new WorldmindState(Map.of(
            "directives", List.of(directive),
            "currentDirectiveIndex", 0,
            "projectContext", context
        ));

        var result = node.apply(state);

        assertNotNull(result);
        assertTrue(result.containsKey("starblasters"));
        assertTrue(result.containsKey("currentDirectiveIndex"));
        assertEquals(1, result.get("currentDirectiveIndex"));
        assertEquals(MissionStatus.EXECUTING.name(), result.get("status"));
    }

    @Test
    @DisplayName("returns COMPLETED status when no directives are present")
    void applySkipsWhenNoDirectivesPending() {
        var state = new WorldmindState(Map.of(
            "directives", List.of(),
            "currentDirectiveIndex", 0
        ));

        var result = node.apply(state);

        assertEquals(MissionStatus.COMPLETED.name(), result.get("status"));
        verifyNoInteractions(bridge);
    }

    @Test
    @DisplayName("returns COMPLETED status when currentDirectiveIndex exceeds directives size")
    void applyCompletesWhenIndexExceedsDirectives() {
        var directive = new Directive(
            "DIR-001", "FORGE", "Create file",
            "", "File exists",
            List.of(), DirectiveStatus.PASSED, 1, 3,
            FailureStrategy.RETRY, List.of(), 1000L
        );
        var state = new WorldmindState(Map.of(
            "directives", List.of(directive),
            "currentDirectiveIndex", 1
        ));

        var result = node.apply(state);

        assertEquals(MissionStatus.COMPLETED.name(), result.get("status"));
        verifyNoInteractions(bridge);
    }

    @Test
    @DisplayName("advances index without dispatching when directive is not PENDING")
    void applyAdvancesWhenDirectiveAlreadyProcessed() {
        var directive = new Directive(
            "DIR-001", "FORGE", "Create file",
            "", "File exists",
            List.of(), DirectiveStatus.PASSED, 1, 3,
            FailureStrategy.RETRY, List.of(), 2000L
        );
        var state = new WorldmindState(Map.of(
            "directives", List.of(directive),
            "currentDirectiveIndex", 0
        ));

        var result = node.apply(state);

        assertEquals(1, result.get("currentDirectiveIndex"));
        verifyNoInteractions(bridge);
    }

    @Test
    @DisplayName("includes errors in result when directive fails")
    void applyIncludesErrorsOnFailure() {
        var directive = new Directive(
            "DIR-001", "FORGE", "Create file",
            "", "File exists",
            List.of(), DirectiveStatus.PENDING, 0, 3,
            FailureStrategy.RETRY, List.of(), null
        );
        var failedDirective = new Directive(
            "DIR-001", "FORGE", "Create file",
            "", "File exists",
            List.of(), DirectiveStatus.FAILED, 1, 3,
            FailureStrategy.RETRY, List.of(), 3000L
        );
        var starblasterInfo = new StarblasterInfo("c-2", "FORGE", "DIR-001", "failed",
            Instant.now(), Instant.now());
        var bridgeResult = new StarblasterBridge.BridgeResult(failedDirective, starblasterInfo, "compilation error");

        when(bridge.executeDirective(any(), any(), any(), any(), any())).thenReturn(bridgeResult);

        var context = new ProjectContext("/tmp/p", List.of(), "java", "spring", Map.of(), 5, "");
        var state = new WorldmindState(Map.of(
            "directives", List.of(directive),
            "currentDirectiveIndex", 0,
            "projectContext", context
        ));

        var result = node.apply(state);

        assertTrue(result.containsKey("errors"));
        @SuppressWarnings("unchecked")
        var errors = (List<String>) result.get("errors");
        assertEquals(1, errors.size());
        assertTrue(errors.get(0).contains("DIR-001"));
        assertTrue(errors.get(0).contains("failed"));
    }

    @Test
    @DisplayName("passes correct arguments to StarblasterBridge")
    void applyPassesCorrectArgsToBridge() {
        var directive = new Directive(
            "DIR-001", "FORGE", "Create file",
            "", "File exists",
            List.of(), DirectiveStatus.PENDING, 0, 3,
            FailureStrategy.RETRY, List.of(), null
        );
        var updatedDirective = new Directive(
            "DIR-001", "FORGE", "Create file",
            "", "File exists",
            List.of(), DirectiveStatus.PASSED, 1, 3,
            FailureStrategy.RETRY, List.of(), 1000L
        );
        var starblasterInfo = new StarblasterInfo("c-3", "FORGE", "DIR-001", "completed",
            Instant.now(), Instant.now());
        var bridgeResult = new StarblasterBridge.BridgeResult(updatedDirective, starblasterInfo, "ok");

        when(bridge.executeDirective(any(), any(), any(), any(), any())).thenReturn(bridgeResult);

        var context = new ProjectContext("/tmp/project", List.of("src/Main.java"), "java", "spring", Map.of(), 5, "summary");
        var state = new WorldmindState(Map.of(
            "directives", List.of(directive),
            "currentDirectiveIndex", 0,
            "projectContext", context
        ));

        node.apply(state);

        verify(bridge).executeDirective(
            eq(directive),
            eq(context),
            eq(Path.of("/tmp/project")),
            any(),
            any()
        );
    }

    @Test
    @DisplayName("uses fallback project path when projectContext is absent")
    void applyUsesFallbackPathWhenNoContext() {
        var directive = new Directive(
            "DIR-001", "FORGE", "Create file",
            "", "File exists",
            List.of(), DirectiveStatus.PENDING, 0, 3,
            FailureStrategy.RETRY, List.of(), null
        );
        var updatedDirective = new Directive(
            "DIR-001", "FORGE", "Create file",
            "", "File exists",
            List.of(), DirectiveStatus.PASSED, 1, 3,
            FailureStrategy.RETRY, List.of(), 500L
        );
        var starblasterInfo = new StarblasterInfo("c-4", "FORGE", "DIR-001", "completed",
            Instant.now(), Instant.now());
        var bridgeResult = new StarblasterBridge.BridgeResult(updatedDirective, starblasterInfo, "ok");

        when(bridge.executeDirective(any(), any(), any(), any(), any())).thenReturn(bridgeResult);

        var state = new WorldmindState(Map.of(
            "directives", List.of(directive),
            "currentDirectiveIndex", 0
        ));

        node.apply(state);

        verify(bridge).executeDirective(
            eq(directive),
            isNull(),
            eq(Path.of(".")),
            any(),
            any()
        );
    }

    // ── Retry context tests ─────────────────────────────────────────────

    @Test
    @DisplayName("retries FAILED directive when retryContext is present")
    void retriesFailedDirectiveWithRetryContext() {
        var directive = new Directive(
            "DIR-001", "FORGE", "Create file",
            "original context", "File exists",
            List.of(), DirectiveStatus.FAILED, 1, 3,
            FailureStrategy.RETRY, List.of(), 2000L
        );
        var updatedDirective = new Directive(
            "DIR-001", "FORGE", "Create file",
            "original context", "File exists",
            List.of(), DirectiveStatus.PASSED, 2, 3,
            FailureStrategy.RETRY, List.of(new FileRecord("hello.py", "created", 1)), 3000L
        );
        var starblasterInfo = new StarblasterInfo("c-5", "FORGE", "DIR-001", "completed",
            Instant.now(), Instant.now());
        var bridgeResult = new StarblasterBridge.BridgeResult(updatedDirective, starblasterInfo, "ok");

        when(bridge.executeDirective(any(), any(), any(), any(), any())).thenReturn(bridgeResult);

        var context = new ProjectContext("/tmp/p", List.of(), "java", "spring", Map.of(), 5, "");
        var state = new WorldmindState(Map.of(
            "directives", List.of(directive),
            "currentDirectiveIndex", 0,
            "projectContext", context,
            "retryContext", "Previous attempt failed due to missing import"
        ));

        var result = node.apply(state);

        // Bridge should have been called (not skipped)
        verify(bridge).executeDirective(any(), any(), any(), any(), any());
        assertNotNull(result);
        assertTrue(result.containsKey("starblasters"));
        assertEquals(1, result.get("currentDirectiveIndex"));
        assertEquals(MissionStatus.EXECUTING.name(), result.get("status"));
        // retryContext should be cleared
        assertEquals("", result.get("retryContext"));
    }

    @Test
    @DisplayName("does not retry FAILED directive without retryContext")
    void doesNotRetryFailedDirectiveWithoutRetryContext() {
        var directive = new Directive(
            "DIR-001", "FORGE", "Create file",
            "", "File exists",
            List.of(), DirectiveStatus.FAILED, 1, 3,
            FailureStrategy.RETRY, List.of(), 2000L
        );
        var state = new WorldmindState(Map.of(
            "directives", List.of(directive),
            "currentDirectiveIndex", 0
        ));

        var result = node.apply(state);

        // Should advance index without dispatching
        assertEquals(1, result.get("currentDirectiveIndex"));
        verifyNoInteractions(bridge);
    }

    @Test
    @DisplayName("clears retryContext after consuming it")
    void clearsRetryContextAfterConsuming() {
        var directive = new Directive(
            "DIR-001", "FORGE", "Create file",
            "", "File exists",
            List.of(), DirectiveStatus.FAILED, 1, 3,
            FailureStrategy.RETRY, List.of(), 2000L
        );
        var updatedDirective = new Directive(
            "DIR-001", "FORGE", "Create file",
            "", "File exists",
            List.of(), DirectiveStatus.PASSED, 2, 3,
            FailureStrategy.RETRY, List.of(), 1500L
        );
        var starblasterInfo = new StarblasterInfo("c-6", "FORGE", "DIR-001", "completed",
            Instant.now(), Instant.now());
        var bridgeResult = new StarblasterBridge.BridgeResult(updatedDirective, starblasterInfo, "ok");

        when(bridge.executeDirective(any(), any(), any(), any(), any())).thenReturn(bridgeResult);

        var context = new ProjectContext("/tmp/p", List.of(), "java", "spring", Map.of(), 5, "");
        var state = new WorldmindState(Map.of(
            "directives", List.of(directive),
            "currentDirectiveIndex", 0,
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
        var directive = new Directive(
            "DIR-001", "FORGE", "Create file",
            "original context", "File exists",
            List.of(), DirectiveStatus.FAILED, 1, 3,
            FailureStrategy.RETRY, List.of(), 2000L
        );
        var updatedDirective = new Directive(
            "DIR-001", "FORGE", "Create file",
            "original context", "File exists",
            List.of(), DirectiveStatus.PASSED, 2, 3,
            FailureStrategy.RETRY, List.of(), 1500L
        );
        var starblasterInfo = new StarblasterInfo("c-7", "FORGE", "DIR-001", "completed",
            Instant.now(), Instant.now());
        var bridgeResult = new StarblasterBridge.BridgeResult(updatedDirective, starblasterInfo, "ok");

        when(bridge.executeDirective(any(), any(), any(), any(), any())).thenReturn(bridgeResult);

        var context = new ProjectContext("/tmp/p", List.of(), "java", "spring", Map.of(), 5, "");
        var state = new WorldmindState(Map.of(
            "directives", List.of(directive),
            "currentDirectiveIndex", 0,
            "projectContext", context,
            "retryContext", "Previous attempt had compilation error in Main.java"
        ));

        node.apply(state);

        // Verify that the directive passed to bridge has augmented inputContext
        verify(bridge).executeDirective(
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
