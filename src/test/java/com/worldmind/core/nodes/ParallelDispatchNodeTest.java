package com.worldmind.core.nodes;

import com.worldmind.core.model.*;
import com.worldmind.core.state.WorldmindState;
import com.worldmind.stargate.StargateBridge;
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

    private StargateBridge mockBridge;
    private ParallelDispatchNode node;

    @BeforeEach
    void setUp() {
        mockBridge = mock(StargateBridge.class);
        node = new ParallelDispatchNode(mockBridge, 10);
    }

    private Directive directive(String id) {
        return new Directive(id, "FORGE", "Do " + id, "", "Done", List.of(),
                DirectiveStatus.PENDING, 0, 3, FailureStrategy.RETRY, List.of(), null);
    }

    private StargateBridge.BridgeResult successResult(Directive d) {
        var updated = new Directive(d.id(), d.centurion(), d.description(), d.inputContext(),
                d.successCriteria(), d.dependencies(), DirectiveStatus.PASSED,
                1, d.maxIterations(), d.onFailure(),
                List.of(new FileRecord("test.java", "created", 10)), 500L);
        return new StargateBridge.BridgeResult(updated,
                new StargateInfo("c-" + d.id(), d.centurion(), d.id(), "completed", Instant.now(), Instant.now()),
                "Success output");
    }

    private StargateBridge.BridgeResult failureResult(Directive d) {
        var updated = new Directive(d.id(), d.centurion(), d.description(), d.inputContext(),
                d.successCriteria(), d.dependencies(), DirectiveStatus.FAILED,
                1, d.maxIterations(), d.onFailure(), List.of(), 300L);
        return new StargateBridge.BridgeResult(updated,
                new StargateInfo("c-" + d.id(), d.centurion(), d.id(), "failed", Instant.now(), Instant.now()),
                "Failure output");
    }

    @Test
    @DisplayName("Single directive wave dispatches correctly")
    @SuppressWarnings("unchecked")
    void singleDirectiveWave() {
        var d1 = directive("DIR-001");
        when(mockBridge.executeDirective(any(), any(), any())).thenReturn(successResult(d1));

        var state = new WorldmindState(Map.of(
                "waveDirectiveIds", List.of("DIR-001"),
                "directives", List.of(d1)
        ));

        var result = node.apply(state);

        var waveResults = (List<WaveDispatchResult>) result.get("waveDispatchResults");
        assertEquals(1, waveResults.size());
        assertEquals("DIR-001", waveResults.get(0).directiveId());
        assertEquals(DirectiveStatus.PASSED, waveResults.get(0).status());

        var stargates = (List<StargateInfo>) result.get("stargates");
        assertEquals(1, stargates.size());
    }

    @Test
    @DisplayName("3-directive wave calls bridge 3 times")
    @SuppressWarnings("unchecked")
    void threeDirectiveWave() {
        var d1 = directive("DIR-001");
        var d2 = directive("DIR-002");
        var d3 = directive("DIR-003");

        when(mockBridge.executeDirective(argThat(d -> d != null && d.id().equals("DIR-001")), any(), any()))
                .thenReturn(successResult(d1));
        when(mockBridge.executeDirective(argThat(d -> d != null && d.id().equals("DIR-002")), any(), any()))
                .thenReturn(successResult(d2));
        when(mockBridge.executeDirective(argThat(d -> d != null && d.id().equals("DIR-003")), any(), any()))
                .thenReturn(successResult(d3));

        var state = new WorldmindState(Map.of(
                "waveDirectiveIds", List.of("DIR-001", "DIR-002", "DIR-003"),
                "directives", List.of(d1, d2, d3)
        ));

        var result = node.apply(state);

        var waveResults = (List<WaveDispatchResult>) result.get("waveDispatchResults");
        assertEquals(3, waveResults.size());

        var stargates = (List<StargateInfo>) result.get("stargates");
        assertEquals(3, stargates.size());

        verify(mockBridge, times(3)).executeDirective(any(), any(), any());
    }

    @Test
    @DisplayName("One failure among successes: errors appended, stargates from all")
    @SuppressWarnings("unchecked")
    void mixedSuccessAndFailure() {
        var d1 = directive("DIR-001");
        var d2 = directive("DIR-002");

        when(mockBridge.executeDirective(argThat(d -> d != null && d.id().equals("DIR-001")), any(), any()))
                .thenReturn(successResult(d1));
        when(mockBridge.executeDirective(argThat(d -> d != null && d.id().equals("DIR-002")), any(), any()))
                .thenReturn(failureResult(d2));

        var state = new WorldmindState(Map.of(
                "waveDirectiveIds", List.of("DIR-001", "DIR-002"),
                "directives", List.of(d1, d2)
        ));

        var result = node.apply(state);

        var waveResults = (List<WaveDispatchResult>) result.get("waveDispatchResults");
        assertEquals(2, waveResults.size());

        var errors = (List<String>) result.get("errors");
        assertEquals(1, errors.size());
        assertTrue(errors.get(0).contains("DIR-002"));

        var stargates = (List<StargateInfo>) result.get("stargates");
        assertEquals(2, stargates.size());
    }

    @Test
    @DisplayName("Infrastructure exception captured as error, other directives still run")
    @SuppressWarnings("unchecked")
    void infrastructureException() {
        var d1 = directive("DIR-001");
        var d2 = directive("DIR-002");

        when(mockBridge.executeDirective(argThat(d -> d != null && d.id().equals("DIR-001")), any(), any()))
                .thenThrow(new RuntimeException("Docker unavailable"));
        when(mockBridge.executeDirective(argThat(d -> d != null && d.id().equals("DIR-002")), any(), any()))
                .thenReturn(successResult(d2));

        var state = new WorldmindState(Map.of(
                "waveDirectiveIds", List.of("DIR-001", "DIR-002"),
                "directives", List.of(d1, d2)
        ));

        var result = node.apply(state);

        var waveResults = (List<WaveDispatchResult>) result.get("waveDispatchResults");
        assertEquals(2, waveResults.size());

        var errors = (List<String>) result.get("errors");
        assertTrue(errors.stream().anyMatch(e -> e.contains("DIR-001") && e.contains("Docker unavailable")));

        // DIR-002 should still have succeeded
        var dir2Result = waveResults.stream()
                .filter(r -> r.directiveId().equals("DIR-002")).findFirst().orElseThrow();
        assertEquals(DirectiveStatus.PASSED, dir2Result.status());
    }

    @Test
    @DisplayName("Empty wave: no bridge calls")
    @SuppressWarnings("unchecked")
    void emptyWave() {
        var state = new WorldmindState(Map.of(
                "waveDirectiveIds", List.of(),
                "directives", List.of()
        ));

        var result = node.apply(state);

        var waveResults = (List<WaveDispatchResult>) result.get("waveDispatchResults");
        assertTrue(waveResults.isEmpty());

        verify(mockBridge, never()).executeDirective(any(), any(), any());
    }

    @Test
    @DisplayName("Retry context is augmented into directive and then cleared")
    @SuppressWarnings("unchecked")
    void retryContextAugmented() {
        var d1 = new Directive("DIR-001", "FORGE", "Do something", "original context", "Done", List.of(),
                DirectiveStatus.FAILED, 1, 3, FailureStrategy.RETRY, List.of(), null);

        when(mockBridge.executeDirective(any(), any(), any())).thenAnswer(inv -> {
            Directive dispatched = inv.getArgument(0);
            assertTrue(dispatched.inputContext().contains("Retry Context"));
            return successResult(dispatched);
        });

        var state = new WorldmindState(Map.of(
                "waveDirectiveIds", List.of("DIR-001"),
                "directives", List.of(d1),
                "retryContext", "Previous test failures: X and Y"
        ));

        var result = node.apply(state);
        assertEquals("", result.get("retryContext"));
    }
}
