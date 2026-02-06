package com.worldmind.core.nodes;

import com.worldmind.core.model.*;
import com.worldmind.core.state.WorldmindState;
import com.worldmind.stargate.StargateBridge;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link DispatchCenturionNode}.
 * <p>
 * Uses Mockito to mock {@link StargateBridge} so that no real Docker containers are launched.
 */
class DispatchCenturionNodeTest {

    private StargateBridge bridge;
    private DispatchCenturionNode node;

    @BeforeEach
    void setUp() {
        bridge = mock(StargateBridge.class);
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
        var stargateInfo = new StargateInfo("c-1", "FORGE", "DIR-001", "completed",
            Instant.now(), Instant.now());
        var bridgeResult = new StargateBridge.BridgeResult(updatedDirective, stargateInfo, "ok");

        when(bridge.executeDirective(any(), any(), any())).thenReturn(bridgeResult);

        var context = new ProjectContext("/tmp/p", List.of(), "java", "spring", Map.of(), 5, "");
        var state = new WorldmindState(Map.of(
            "directives", List.of(directive),
            "currentDirectiveIndex", 0,
            "projectContext", context
        ));

        var result = node.apply(state);

        assertNotNull(result);
        assertTrue(result.containsKey("stargates"));
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
        var stargateInfo = new StargateInfo("c-2", "FORGE", "DIR-001", "failed",
            Instant.now(), Instant.now());
        var bridgeResult = new StargateBridge.BridgeResult(failedDirective, stargateInfo, "compilation error");

        when(bridge.executeDirective(any(), any(), any())).thenReturn(bridgeResult);

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
    @DisplayName("passes correct arguments to StargateBridge")
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
        var stargateInfo = new StargateInfo("c-3", "FORGE", "DIR-001", "completed",
            Instant.now(), Instant.now());
        var bridgeResult = new StargateBridge.BridgeResult(updatedDirective, stargateInfo, "ok");

        when(bridge.executeDirective(any(), any(), any())).thenReturn(bridgeResult);

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
            eq(Path.of("/tmp/project"))
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
        var stargateInfo = new StargateInfo("c-4", "FORGE", "DIR-001", "completed",
            Instant.now(), Instant.now());
        var bridgeResult = new StargateBridge.BridgeResult(updatedDirective, stargateInfo, "ok");

        when(bridge.executeDirective(any(), any(), any())).thenReturn(bridgeResult);

        var state = new WorldmindState(Map.of(
            "directives", List.of(directive),
            "currentDirectiveIndex", 0
        ));

        node.apply(state);

        verify(bridge).executeDirective(
            eq(directive),
            isNull(),
            eq(Path.of("."))
        );
    }
}
