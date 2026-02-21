package com.worldmind.core.nodes;

import com.worldmind.core.events.EventBus;
import com.worldmind.core.events.WorldmindEvent;
import com.worldmind.core.model.TaskStatus;
import com.worldmind.core.model.MissionMetrics;
import com.worldmind.core.model.MissionStatus;
import com.worldmind.core.state.WorldmindState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;

/**
 * LangGraph4j node that aggregates all task results into final mission metrics.
 *
 * <p>Reads {@code tasks}, {@code testResults}, and {@code sandboxes} from the
 * graph state, computes aggregate counts and durations, and returns state updates
 * including a {@link MissionMetrics} instance and the final {@link MissionStatus}.
 */
@Component
public class ConvergeResultsNode {

    private static final Logger log = LoggerFactory.getLogger(ConvergeResultsNode.class);

    private final EventBus eventBus;

    public ConvergeResultsNode(EventBus eventBus) {
        this.eventBus = eventBus;
    }

    public Map<String, Object> apply(WorldmindState state) {
        var tasks = state.tasks();
        var testResults = state.testResults();

        // Count tasks by status
        int completed = 0;
        int failed = 0;
        int totalIterations = 0;
        int filesCreated = 0;
        int filesModified = 0;

        for (var d : tasks) {
            if (d.status() == TaskStatus.PASSED) completed++;
            else if (d.status() == TaskStatus.FAILED) failed++;
            totalIterations += d.iteration();
            if (d.filesAffected() != null) {
                for (var f : d.filesAffected()) {
                    if ("created".equals(f.action())) filesCreated++;
                    else filesModified++;
                }
            }
        }

        // Aggregate test metrics
        int testsRun = 0;
        int testsPassed = 0;
        for (var tr : testResults) {
            testsRun += tr.totalTests();
            testsPassed += tr.totalTests() - tr.failedTests();
        }

        // Calculate total duration from sandboxes
        long totalDurationMs = state.sandboxes().stream()
            .filter(s -> s.startedAt() != null && s.completedAt() != null)
            .mapToLong(s -> Duration.between(s.startedAt(), s.completedAt()).toMillis())
            .sum();

        // Wave metrics
        int wavesExecuted = state.waveCount();

        // Aggregate duration: sum of all individual task elapsed times
        long aggregateDurationMs = tasks.stream()
                .filter(d -> d.elapsedMs() != null)
                .mapToLong(d -> d.elapsedMs())
                .sum();

        var metrics = new MissionMetrics(
            totalDurationMs,
            completed,
            failed,
            totalIterations,
            filesCreated,
            filesModified,
            testsRun,
            testsPassed,
            wavesExecuted,
            aggregateDurationMs
        );

        // If EvaluateWaveNode already marked mission FAILED (e.g., DEPLOYER failure),
        // preserve that status even if some code tasks passed.
        boolean alreadyFailed = state.status() == MissionStatus.FAILED;
        MissionStatus finalStatus;
        if (alreadyFailed) {
            finalStatus = MissionStatus.FAILED;
        } else if (completed > 0 || tasks.isEmpty()) {
            finalStatus = MissionStatus.COMPLETED;
        } else {
            finalStatus = MissionStatus.FAILED;
        }

        // Include deployment info in summary
        String deploymentUrl = state.deploymentUrl();
        boolean hasDeploymentUrl = deploymentUrl != null && !deploymentUrl.isBlank();

        if (hasDeploymentUrl) {
            log.info("Mission converged — {} completed, {} failed, {} tests ({} passed), {} files changed, deploymentUrl={}",
                    completed, failed, testsRun, testsPassed, filesCreated + filesModified, deploymentUrl);
        } else {
            log.info("Mission converged — {} completed, {} failed, {} tests ({} passed), {} files changed",
                    completed, failed, testsRun, testsPassed, filesCreated + filesModified);
        }

        var eventData = new java.util.HashMap<String, Object>();
        eventData.put("status", finalStatus.name());
        eventData.put("tasksCompleted", completed);
        eventData.put("tasksFailed", failed);
        if (hasDeploymentUrl) {
            eventData.put("deploymentUrl", deploymentUrl);
        }

        eventBus.publish(new WorldmindEvent(
                "mission.completed", state.missionId(), null,
                eventData,
                Instant.now()));

        return Map.of(
            "metrics", metrics,
            "status", finalStatus.name()
        );
    }
}
