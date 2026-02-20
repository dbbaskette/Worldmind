package com.worldmind.core.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Service;

import java.time.Duration;

/**
 * Centralised Micrometer metrics for Worldmind mission execution.
 */
@Service
public class WorldmindMetrics {

    private final MeterRegistry registry;

    public WorldmindMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    public void recordPlanningDuration(long ms) {
        Timer.builder("worldmind.planning.duration")
                .register(registry)
                .record(Duration.ofMillis(ms));
    }

    public void recordTaskExecution(String agentType, long ms) {
        Timer.builder("worldmind.task.duration")
                .tag("agent", agentType)
                .register(registry)
                .record(Duration.ofMillis(ms));
    }

    public void recordQualityGateResult(boolean granted) {
        Counter.builder("worldmind.quality_gate.evaluations")
                .tag("result", granted ? "granted" : "denied")
                .register(registry)
                .increment();
    }

    public void recordIterationDepth(int depth) {
        DistributionSummary.builder("worldmind.iteration.depth")
                .register(registry)
                .record(depth);
    }

    public void recordMissionResult(String status) {
        Counter.builder("worldmind.missions.total")
                .tag("status", status)
                .register(registry)
                .increment();
    }

    public void incrementEscalations(String reason) {
        Counter.builder("worldmind.escalations.total")
                .tag("reason", reason)
                .register(registry)
                .increment();
    }

    // --- Parallel Execution Observability ---

    /**
     * Records when a task is deferred from a wave due to file overlap with another task.
     * Helps identify how often the scheduler serializes potentially conflicting tasks.
     *
     * @param taskId the task that was deferred
     */
    public void recordFileOverlapDeferral(String taskId) {
        Counter.builder("worldmind.parallel.file_overlap_deferrals")
                .description("Tasks deferred due to file overlap conflicts")
                .register(registry)
                .increment();
    }

    /**
     * Records a merge conflict that occurred during task branch merging.
     *
     * @param taskId the task whose merge conflicted
     * @param resolved    true if conflict was resolved via retry, false if task must be re-executed
     */
    public void recordMergeConflict(String taskId, boolean resolved) {
        Counter.builder("worldmind.parallel.merge_conflicts")
                .description("Merge conflicts during task branch merging")
                .tag("resolved", String.valueOf(resolved))
                .register(registry)
                .increment();
    }

    /**
     * Records a successful merge retry.
     * Incremented when a merge initially fails but succeeds on retry.
     */
    public void recordMergeRetrySuccess() {
        Counter.builder("worldmind.parallel.merge_retry_success")
                .description("Merge operations that succeeded on retry")
                .register(registry)
                .increment();
    }

    /**
     * Records worktree operations for monitoring parallel execution health.
     *
     * @param operation "acquire", "release", or "cleanup"
     * @param success   whether the operation succeeded
     */
    public void recordWorktreeOperation(String operation, boolean success) {
        Counter.builder("worldmind.parallel.worktree_operations")
                .description("Git worktree lifecycle operations")
                .tag("operation", operation)
                .tag("success", String.valueOf(success))
                .register(registry)
                .increment();
    }

    /**
     * Records the number of active worktrees for a mission.
     * Useful for tracking resource usage during parallel execution.
     *
     * @param count number of active worktrees
     */
    public void recordActiveWorktrees(int count) {
        DistributionSummary.builder("worldmind.parallel.active_worktrees")
                .description("Number of active worktrees per wave")
                .register(registry)
                .record(count);
    }

    /**
     * Records wave execution metrics for parallel vs sequential analysis.
     *
     * @param taskCount number of tasks in the wave
     * @param strategy       "parallel" or "sequential"
     */
    public void recordWaveExecution(int taskCount, String strategy) {
        Counter.builder("worldmind.wave.executions")
                .description("Wave executions by strategy")
                .tag("strategy", strategy)
                .register(registry)
                .increment();

        DistributionSummary.builder("worldmind.wave.task_count")
                .description("Number of tasks per wave")
                .tag("strategy", strategy)
                .register(registry)
                .record(taskCount);
    }
}
