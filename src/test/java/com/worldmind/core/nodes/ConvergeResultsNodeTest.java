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
        var d1 = new Task(
            "TASK-001", "CODER", "Create service",
            "", "Service exists",
            List.of(), TaskStatus.PASSED, 1, 3,
            FailureStrategy.RETRY, List.of(), List.of(), 2000L
        );
        var d2 = new Task(
            "TASK-002", "CODER", "Create controller",
            "", "Controller exists",
            List.of(), TaskStatus.PASSED, 1, 3,
            FailureStrategy.RETRY, List.of(), List.of(), 3000L
        );
        var d3 = new Task(
            "TASK-003", "CODER", "Create tests",
            "", "Tests pass",
            List.of(), TaskStatus.FAILED, 2, 3,
            FailureStrategy.RETRY, List.of(), List.of(), 5000L
        );

        var state = new WorldmindState(Map.of(
            "tasks", List.of(d1, d2, d3),
            "testResults", List.of(),
            "sandboxes", List.of()
        ));

        var result = node.apply(state);

        assertEquals(MissionStatus.COMPLETED.name(), result.get("status"));
        var metrics = (MissionMetrics) result.get("metrics");
        assertNotNull(metrics);
        assertEquals(2, metrics.tasksCompleted());
        assertEquals(1, metrics.tasksFailed());
    }

    @Test
    @DisplayName("all failed tasks result in FAILED status")
    void allFailedResultsInFailedStatus() {
        var d1 = new Task(
            "TASK-001", "CODER", "Create service",
            "", "Service exists",
            List.of(), TaskStatus.FAILED, 3, 3,
            FailureStrategy.RETRY, List.of(), List.of(), 10000L
        );
        var d2 = new Task(
            "TASK-002", "CODER", "Create controller",
            "", "Controller exists",
            List.of(), TaskStatus.FAILED, 3, 3,
            FailureStrategy.RETRY, List.of(), List.of(), 8000L
        );

        var state = new WorldmindState(Map.of(
            "tasks", List.of(d1, d2),
            "testResults", List.of(),
            "sandboxes", List.of()
        ));

        var result = node.apply(state);

        assertEquals(MissionStatus.FAILED.name(), result.get("status"));
        var metrics = (MissionMetrics) result.get("metrics");
        assertEquals(0, metrics.tasksCompleted());
        assertEquals(2, metrics.tasksFailed());
    }

    @Test
    @DisplayName("empty tasks results in COMPLETED status with zero metrics")
    void emptyTasksResultsInCompleted() {
        var state = new WorldmindState(Map.of(
            "tasks", List.of(),
            "testResults", List.of(),
            "sandboxes", List.of()
        ));

        var result = node.apply(state);

        assertEquals(MissionStatus.COMPLETED.name(), result.get("status"));
        var metrics = (MissionMetrics) result.get("metrics");
        assertNotNull(metrics);
        assertEquals(0, metrics.tasksCompleted());
        assertEquals(0, metrics.tasksFailed());
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
        var tr1 = new TestResult("TASK-001", true, 10, 2, "output1", 500L);
        var tr2 = new TestResult("TASK-002", true, 5, 0, "output2", 300L);

        var state = new WorldmindState(Map.of(
            "tasks", List.of(),
            "testResults", List.of(tr1, tr2),
            "sandboxes", List.of()
        ));

        var result = node.apply(state);

        var metrics = (MissionMetrics) result.get("metrics");
        assertEquals(15, metrics.testsRun());
        assertEquals(13, metrics.testsPassed());
    }

    @Test
    @DisplayName("counts file changes — created vs modified")
    void countsFileChanges() {
        var d1 = new Task(
            "TASK-001", "CODER", "Create files",
            "", "Files exist",
            List.of(), TaskStatus.PASSED, 1, 3,
            FailureStrategy.RETRY, List.of(),
            List.of(
                new FileRecord("src/Main.java", "created", 50),
                new FileRecord("src/Service.java", "created", 30),
                new FileRecord("pom.xml", "modified", 5)
            ),
            2000L
        );
        var d2 = new Task(
            "TASK-002", "CODER", "Update config",
            "", "Config updated",
            List.of(), TaskStatus.PASSED, 1, 3,
            FailureStrategy.RETRY, List.of(),
            List.of(
                new FileRecord("application.yml", "modified", 10),
                new FileRecord("src/Test.java", "created", 40)
            ),
            1500L
        );

        var state = new WorldmindState(Map.of(
            "tasks", List.of(d1, d2),
            "testResults", List.of(),
            "sandboxes", List.of()
        ));

        var result = node.apply(state);

        var metrics = (MissionMetrics) result.get("metrics");
        assertEquals(3, metrics.filesCreated());
        assertEquals(2, metrics.filesModified());
    }

    @Test
    @DisplayName("calculates total iterations across tasks")
    void calculatesIterations() {
        var d1 = new Task(
            "TASK-001", "CODER", "Create service",
            "", "Service exists",
            List.of(), TaskStatus.PASSED, 1, 3,
            FailureStrategy.RETRY, List.of(), List.of(), 1000L
        );
        var d2 = new Task(
            "TASK-002", "CODER", "Create controller",
            "", "Controller exists",
            List.of(), TaskStatus.PASSED, 3, 5,
            FailureStrategy.RETRY, List.of(), List.of(), 5000L
        );
        var d3 = new Task(
            "TASK-003", "CODER", "Create tests",
            "", "Tests pass",
            List.of(), TaskStatus.FAILED, 2, 3,
            FailureStrategy.RETRY, List.of(), List.of(), 4000L
        );

        var state = new WorldmindState(Map.of(
            "tasks", List.of(d1, d2, d3),
            "testResults", List.of(),
            "sandboxes", List.of()
        ));

        var result = node.apply(state);

        var metrics = (MissionMetrics) result.get("metrics");
        assertEquals(6, metrics.totalIterations()); // 1 + 3 + 2
    }

    @Test
    @DisplayName("includes waveCount and aggregateDurationMs in metrics")
    void includesWaveMetrics() {
        var d1 = new Task(
            "TASK-001", "CODER", "Create service",
            "", "Service exists",
            List.of(), TaskStatus.PASSED, 1, 3,
            FailureStrategy.RETRY, List.of(), List.of(), 2000L
        );
        var d2 = new Task(
            "TASK-002", "CODER", "Create controller",
            "", "Controller exists",
            List.of(), TaskStatus.PASSED, 1, 3,
            FailureStrategy.RETRY, List.of(), List.of(), 3000L
        );

        var state = new WorldmindState(Map.of(
            "tasks", List.of(d1, d2),
            "testResults", List.of(),
            "sandboxes", List.of(),
            "waveCount", 2
        ));

        var result = node.apply(state);

        var metrics = (MissionMetrics) result.get("metrics");
        assertEquals(2, metrics.wavesExecuted());
        assertEquals(5000L, metrics.aggregateDurationMs()); // 2000 + 3000
    }

    @Test
    @DisplayName("calculates duration from sandbox start/completion times")
    void calculatesDurationFromSandboxes() {
        var start1 = Instant.parse("2026-02-06T10:00:00Z");
        var end1 = Instant.parse("2026-02-06T10:00:05Z"); // 5000ms
        var start2 = Instant.parse("2026-02-06T10:01:00Z");
        var end2 = Instant.parse("2026-02-06T10:01:03Z"); // 3000ms

        var sg1 = new SandboxInfo("c-1", "CODER", "TASK-001", "completed", start1, end1);
        var sg2 = new SandboxInfo("c-2", "CODER", "TASK-002", "completed", start2, end2);
        // Sandbox with null timestamps should be excluded
        var sg3 = new SandboxInfo("c-3", "CODER", "TASK-003", "failed", null, null);

        var state = new WorldmindState(Map.of(
            "tasks", List.of(),
            "testResults", List.of(),
            "sandboxes", List.of(sg1, sg2, sg3)
        ));

        var result = node.apply(state);

        var metrics = (MissionMetrics) result.get("metrics");
        assertEquals(8000L, metrics.totalDurationMs()); // 5000 + 3000
    }
}
