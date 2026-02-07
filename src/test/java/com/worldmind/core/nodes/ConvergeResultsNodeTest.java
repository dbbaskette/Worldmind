package com.worldmind.core.nodes;

import com.worldmind.core.events.EventBus;
import com.worldmind.core.model.*;
import com.worldmind.core.state.WorldmindState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link ConvergeResultsNode}.
 *
 * <p>No mocks needed — the node reads directly from state and performs
 * pure aggregation logic.
 */
class ConvergeResultsNodeTest {

    private ConvergeResultsNode node;

    @BeforeEach
    void setUp() {
        node = new ConvergeResultsNode(new EventBus());
    }

    @Test
    @DisplayName("converges with mixed results — 2 PASSED + 1 FAILED yields COMPLETED")
    void convergesWithMixedResults() {
        var d1 = new Directive(
            "DIR-001", "FORGE", "Create service",
            "", "Service exists",
            List.of(), DirectiveStatus.PASSED, 1, 3,
            FailureStrategy.RETRY, List.of(), 2000L
        );
        var d2 = new Directive(
            "DIR-002", "FORGE", "Create controller",
            "", "Controller exists",
            List.of(), DirectiveStatus.PASSED, 1, 3,
            FailureStrategy.RETRY, List.of(), 3000L
        );
        var d3 = new Directive(
            "DIR-003", "FORGE", "Create tests",
            "", "Tests pass",
            List.of(), DirectiveStatus.FAILED, 2, 3,
            FailureStrategy.RETRY, List.of(), 5000L
        );

        var state = new WorldmindState(Map.of(
            "directives", List.of(d1, d2, d3),
            "testResults", List.of(),
            "stargates", List.of()
        ));

        var result = node.apply(state);

        assertEquals(MissionStatus.COMPLETED.name(), result.get("status"));
        var metrics = (MissionMetrics) result.get("metrics");
        assertNotNull(metrics);
        assertEquals(2, metrics.directivesCompleted());
        assertEquals(1, metrics.directivesFailed());
    }

    @Test
    @DisplayName("all failed directives result in FAILED status")
    void allFailedResultsInFailedStatus() {
        var d1 = new Directive(
            "DIR-001", "FORGE", "Create service",
            "", "Service exists",
            List.of(), DirectiveStatus.FAILED, 3, 3,
            FailureStrategy.RETRY, List.of(), 10000L
        );
        var d2 = new Directive(
            "DIR-002", "FORGE", "Create controller",
            "", "Controller exists",
            List.of(), DirectiveStatus.FAILED, 3, 3,
            FailureStrategy.RETRY, List.of(), 8000L
        );

        var state = new WorldmindState(Map.of(
            "directives", List.of(d1, d2),
            "testResults", List.of(),
            "stargates", List.of()
        ));

        var result = node.apply(state);

        assertEquals(MissionStatus.FAILED.name(), result.get("status"));
        var metrics = (MissionMetrics) result.get("metrics");
        assertEquals(0, metrics.directivesCompleted());
        assertEquals(2, metrics.directivesFailed());
    }

    @Test
    @DisplayName("empty directives results in COMPLETED status with zero metrics")
    void emptyDirectivesResultsInCompleted() {
        var state = new WorldmindState(Map.of(
            "directives", List.of(),
            "testResults", List.of(),
            "stargates", List.of()
        ));

        var result = node.apply(state);

        assertEquals(MissionStatus.COMPLETED.name(), result.get("status"));
        var metrics = (MissionMetrics) result.get("metrics");
        assertNotNull(metrics);
        assertEquals(0, metrics.directivesCompleted());
        assertEquals(0, metrics.directivesFailed());
        assertEquals(0, metrics.totalIterations());
        assertEquals(0, metrics.filesCreated());
        assertEquals(0, metrics.filesModified());
        assertEquals(0, metrics.testsRun());
        assertEquals(0, metrics.testsPassed());
        assertEquals(0L, metrics.totalDurationMs());
    }

    @Test
    @DisplayName("aggregates test metrics from multiple TestResults")
    void aggregatesTestMetrics() {
        var tr1 = new TestResult("DIR-001", true, 10, 2, "output1", 500L);
        var tr2 = new TestResult("DIR-002", true, 5, 0, "output2", 300L);

        var state = new WorldmindState(Map.of(
            "directives", List.of(),
            "testResults", List.of(tr1, tr2),
            "stargates", List.of()
        ));

        var result = node.apply(state);

        var metrics = (MissionMetrics) result.get("metrics");
        assertEquals(15, metrics.testsRun());
        assertEquals(13, metrics.testsPassed());
    }

    @Test
    @DisplayName("counts file changes — created vs modified")
    void countsFileChanges() {
        var d1 = new Directive(
            "DIR-001", "FORGE", "Create files",
            "", "Files exist",
            List.of(), DirectiveStatus.PASSED, 1, 3,
            FailureStrategy.RETRY,
            List.of(
                new FileRecord("src/Main.java", "created", 50),
                new FileRecord("src/Service.java", "created", 30),
                new FileRecord("pom.xml", "modified", 5)
            ),
            2000L
        );
        var d2 = new Directive(
            "DIR-002", "FORGE", "Update config",
            "", "Config updated",
            List.of(), DirectiveStatus.PASSED, 1, 3,
            FailureStrategy.RETRY,
            List.of(
                new FileRecord("application.yml", "modified", 10),
                new FileRecord("src/Test.java", "created", 40)
            ),
            1500L
        );

        var state = new WorldmindState(Map.of(
            "directives", List.of(d1, d2),
            "testResults", List.of(),
            "stargates", List.of()
        ));

        var result = node.apply(state);

        var metrics = (MissionMetrics) result.get("metrics");
        assertEquals(3, metrics.filesCreated());
        assertEquals(2, metrics.filesModified());
    }

    @Test
    @DisplayName("calculates total iterations across directives")
    void calculatesIterations() {
        var d1 = new Directive(
            "DIR-001", "FORGE", "Create service",
            "", "Service exists",
            List.of(), DirectiveStatus.PASSED, 1, 3,
            FailureStrategy.RETRY, List.of(), 1000L
        );
        var d2 = new Directive(
            "DIR-002", "FORGE", "Create controller",
            "", "Controller exists",
            List.of(), DirectiveStatus.PASSED, 3, 5,
            FailureStrategy.RETRY, List.of(), 5000L
        );
        var d3 = new Directive(
            "DIR-003", "FORGE", "Create tests",
            "", "Tests pass",
            List.of(), DirectiveStatus.FAILED, 2, 3,
            FailureStrategy.RETRY, List.of(), 4000L
        );

        var state = new WorldmindState(Map.of(
            "directives", List.of(d1, d2, d3),
            "testResults", List.of(),
            "stargates", List.of()
        ));

        var result = node.apply(state);

        var metrics = (MissionMetrics) result.get("metrics");
        assertEquals(6, metrics.totalIterations()); // 1 + 3 + 2
    }

    @Test
    @DisplayName("includes waveCount and aggregateDurationMs in metrics")
    void includesWaveMetrics() {
        var d1 = new Directive(
            "DIR-001", "FORGE", "Create service",
            "", "Service exists",
            List.of(), DirectiveStatus.PASSED, 1, 3,
            FailureStrategy.RETRY, List.of(), 2000L
        );
        var d2 = new Directive(
            "DIR-002", "FORGE", "Create controller",
            "", "Controller exists",
            List.of(), DirectiveStatus.PASSED, 1, 3,
            FailureStrategy.RETRY, List.of(), 3000L
        );

        var state = new WorldmindState(Map.of(
            "directives", List.of(d1, d2),
            "testResults", List.of(),
            "stargates", List.of(),
            "waveCount", 2
        ));

        var result = node.apply(state);

        var metrics = (MissionMetrics) result.get("metrics");
        assertEquals(2, metrics.wavesExecuted());
        assertEquals(5000L, metrics.aggregateDurationMs()); // 2000 + 3000
    }

    @Test
    @DisplayName("calculates duration from stargate start/completion times")
    void calculatesDurationFromStargates() {
        var start1 = Instant.parse("2026-02-06T10:00:00Z");
        var end1 = Instant.parse("2026-02-06T10:00:05Z"); // 5000ms
        var start2 = Instant.parse("2026-02-06T10:01:00Z");
        var end2 = Instant.parse("2026-02-06T10:01:03Z"); // 3000ms

        var sg1 = new StargateInfo("c-1", "FORGE", "DIR-001", "completed", start1, end1);
        var sg2 = new StargateInfo("c-2", "FORGE", "DIR-002", "completed", start2, end2);
        // Stargate with null timestamps should be excluded
        var sg3 = new StargateInfo("c-3", "FORGE", "DIR-003", "failed", null, null);

        var state = new WorldmindState(Map.of(
            "directives", List.of(),
            "testResults", List.of(),
            "stargates", List.of(sg1, sg2, sg3)
        ));

        var result = node.apply(state);

        var metrics = (MissionMetrics) result.get("metrics");
        assertEquals(8000L, metrics.totalDurationMs()); // 5000 + 3000
    }
}
