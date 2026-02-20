package com.worldmind.dispatch.cli;

import com.worldmind.core.model.Task;
import com.worldmind.core.persistence.CheckpointQueryService;
import com.worldmind.core.state.WorldmindState;
import org.springframework.stereotype.Component;
import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;

/**
 * CLI command: worldmind inspect &lt;mission-id&gt; &lt;task-id&gt;
 * <p>
 * Shows detailed information about a specific task within a mission,
 * including agent type, status, files affected, elapsed time, and iteration count.
 */
@Command(name = "inspect", mixinStandardHelpOptions = true, description = "Inspect a task within a mission")
@Component
public class InspectCommand implements Runnable {

    @Parameters(index = "0", description = "Mission ID")
    private String missionId;

    @Parameters(index = "1", description = "Task ID")
    private String taskId;

    private final CheckpointQueryService queryService;

    public InspectCommand(CheckpointQueryService queryService) {
        this.queryService = queryService;
    }

    @Override
    public void run() {
        ConsoleOutput.printBanner();

        var stateOpt = queryService.getLatestState(missionId);
        if (stateOpt.isEmpty()) {
            ConsoleOutput.error("Mission not found: " + missionId);
            return;
        }

        WorldmindState state = stateOpt.get();
        Task found = null;
        for (var d : state.tasks()) {
            if (d.id().equals(taskId)) {
                found = d;
                break;
            }
        }

        if (found == null) {
            ConsoleOutput.error("Task " + taskId + " not found in mission " + missionId);
            return;
        }

        System.out.println();
        System.out.println("TASK " + found.id());
        System.out.println("──────────────────────────────────");
        System.out.println("  Agent:       " + found.agent());
        System.out.println("  Status:          " + found.status());
        System.out.println("  Description:     " + found.description());
        System.out.println("  Success Criteria:" + (found.successCriteria() != null ? " " + found.successCriteria() : " -"));
        System.out.println("  Iteration:       " + found.iteration() + " / " + found.maxIterations());
        System.out.println("  Failure Strategy:" + (found.onFailure() != null ? " " + found.onFailure() : " -"));
        System.out.println("  Elapsed:         " + formatDuration(found.elapsedMs()));

        // Dependencies
        var deps = found.dependencies();
        if (deps != null && !deps.isEmpty()) {
            System.out.println("  Dependencies:    " + String.join(", ", deps));
        } else {
            System.out.println("  Dependencies:    none");
        }

        // Files affected
        var files = found.filesAffected();
        if (files != null && !files.isEmpty()) {
            System.out.println();
            System.out.println("  FILES AFFECTED:");
            for (var f : files) {
                ConsoleOutput.fileChange(f.action(), f.path());
            }
        }

        // Test results for this task
        var testResults = state.testResults();
        var taskTests = testResults.stream()
                .filter(t -> taskId.equals(t.taskId()))
                .toList();
        if (!taskTests.isEmpty()) {
            System.out.println();
            System.out.println("  TEST RESULTS:");
            for (var t : taskTests) {
                ConsoleOutput.testResult(t);
            }
        }

        // Review feedback for this task
        var reviews = state.reviewFeedback();
        var taskReviews = reviews.stream()
                .filter(r -> taskId.equals(r.taskId()))
                .toList();
        if (!taskReviews.isEmpty()) {
            System.out.println();
            System.out.println("  REVIEW FEEDBACK:");
            for (var r : taskReviews) {
                ConsoleOutput.reviewFeedback(r);
            }
        }
    }

    private static String formatDuration(Long ms) {
        if (ms == null) return "-";
        if (ms < 1000) return ms + "ms";
        long seconds = ms / 1000;
        if (seconds < 60) return seconds + "s";
        return (seconds / 60) + "m " + (seconds % 60) + "s";
    }
}
