package com.worldmind.core.nodes;

import com.worldmind.core.events.EventBus;
import com.worldmind.core.events.WorldmindEvent;
import com.worldmind.core.model.DirectiveStatus;
import com.worldmind.core.model.MissionMetrics;
import com.worldmind.core.model.MissionStatus;
import com.worldmind.core.novaforce.NovaForceToolProvider;
import com.worldmind.core.state.WorldmindState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;

/**
 * LangGraph4j node that aggregates all directive results into final mission metrics.
 *
 * <p>Reads {@code directives}, {@code testResults}, and {@code starblasters} from the
 * graph state, computes aggregate counts and durations, and returns state updates
 * including a {@link MissionMetrics} instance and the final {@link MissionStatus}.
 *
 * <p>When Nexus is enabled, has access to Datacore (read) tools for verifying
 * mission checkpoint state and centurion output alignment. Tool-based verification
 * is available for future LLM-assisted convergence analysis.
 */
@Component
public class ConvergeResultsNode {

    private static final Logger log = LoggerFactory.getLogger(ConvergeResultsNode.class);

    private final EventBus eventBus;
    private final NovaForceToolProvider novaForceToolProvider;

    public ConvergeResultsNode(EventBus eventBus,
                               @Autowired(required = false) NovaForceToolProvider novaForceToolProvider) {
        this.eventBus = eventBus;
        this.novaForceToolProvider = novaForceToolProvider;
    }

    public Map<String, Object> apply(WorldmindState state) {
        var directives = state.directives();
        var testResults = state.testResults();

        // Log available Nexus tools for this node
        if (novaForceToolProvider != null && novaForceToolProvider.isEnabled()) {
            var tools = novaForceToolProvider.getToolsForNode("converge");
            if (tools.length > 0) {
                log.info("Converge node has {} Nexus tool(s) available (Datacore read)", tools.length);
            }
        }

        // Count directives by status
        int completed = 0;
        int failed = 0;
        int totalIterations = 0;
        int filesCreated = 0;
        int filesModified = 0;

        for (var d : directives) {
            if (d.status() == DirectiveStatus.PASSED) completed++;
            else if (d.status() == DirectiveStatus.FAILED) failed++;
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

        // Calculate total duration from starblasters
        long totalDurationMs = state.starblasters().stream()
            .filter(s -> s.startedAt() != null && s.completedAt() != null)
            .mapToLong(s -> Duration.between(s.startedAt(), s.completedAt()).toMillis())
            .sum();

        // Wave metrics
        int wavesExecuted = state.waveCount();

        // Aggregate duration: sum of all individual directive elapsed times
        long aggregateDurationMs = directives.stream()
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

        // COMPLETED if any directives passed or none exist; FAILED if all failed
        MissionStatus finalStatus = (completed > 0 || directives.isEmpty())
                ? MissionStatus.COMPLETED
                : MissionStatus.FAILED;

        log.info("Mission converged — {} completed, {} failed, {} tests ({} passed), {} files changed",
                completed, failed, testsRun, testsPassed, filesCreated + filesModified);

        eventBus.publish(new WorldmindEvent(
                "mission.completed", state.missionId(), null,
                Map.of("status", finalStatus.name(),
                       "directivesCompleted", completed,
                       "directivesFailed", failed),
                Instant.now()));

        return Map.of(
            "metrics", metrics,
            "status", finalStatus.name()
        );
    }
}
