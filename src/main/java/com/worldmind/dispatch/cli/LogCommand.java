package com.worldmind.dispatch.cli;

import com.worldmind.core.persistence.CheckpointQueryService;
import com.worldmind.core.state.WorldmindState;
import org.bsc.langgraph4j.checkpoint.Checkpoint;
import org.springframework.stereotype.Component;
import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;

import java.util.Collection;

/**
 * CLI command: worldmind log &lt;mission-id&gt;
 * <p>
 * Shows a complete execution log for a mission by walking through all
 * checkpoints and displaying state transitions, directive completions,
 * test results, and errors.
 */
@Command(name = "log", mixinStandardHelpOptions = true, description = "Show mission execution log")
@Component
public class LogCommand implements Runnable {

    @Parameters(index = "0", description = "Mission ID")
    private String missionId;

    private final CheckpointQueryService queryService;

    public LogCommand(CheckpointQueryService queryService) {
        this.queryService = queryService;
    }

    @Override
    public void run() {
        ConsoleOutput.printBanner();

        Collection<Checkpoint> checkpoints = queryService.listCheckpoints(missionId);
        if (checkpoints.isEmpty()) {
            ConsoleOutput.error("No log entries found for mission: " + missionId);
            return;
        }

        // Get the final state for summary
        var latestOpt = queryService.getLatestState(missionId);

        ConsoleOutput.info("Execution log for mission " + missionId);
        System.out.println();

        // Walk through checkpoints chronologically
        int step = 1;
        for (Checkpoint cp : checkpoints) {
            String nodeId = cp.getNodeId() != null ? cp.getNodeId() : "start";
            WorldmindState cpState = new WorldmindState(cp.getState());

            System.out.printf("  [%d] %s%n", step, nodeId);

            // Show status transitions
            System.out.printf("       Status: %s%n", cpState.status());

            // Show directives scheduled in this step
            var waveIds = cpState.waveDirectiveIds();
            if (!waveIds.isEmpty()) {
                System.out.printf("       Wave directives: %s%n", String.join(", ", waveIds));
            }

            // Show completed directives
            var completed = cpState.completedDirectiveIds();
            if (!completed.isEmpty()) {
                System.out.printf("       Completed: %s%n", String.join(", ", completed));
            }

            // Show errors at this step
            var errors = cpState.errors();
            if (!errors.isEmpty()) {
                for (var e : errors) {
                    ConsoleOutput.error("       " + e);
                }
            }

            step++;
        }

        // Final summary
        if (latestOpt.isPresent()) {
            WorldmindState finalState = latestOpt.get();
            System.out.println();
            System.out.println("  " + "-".repeat(40));
            System.out.printf("  Final status: %s%n", finalState.status());
            System.out.printf("  Directives: %d total, %d completed%n",
                    finalState.directives().size(),
                    finalState.completedDirectiveIds().size());

            if (finalState.waveCount() > 0) {
                System.out.printf("  Waves: %d%n", finalState.waveCount());
            }

            ConsoleOutput.seal(finalState.sealGranted(),
                    finalState.sealGranted() ? "Quality gate passed" : "Quality gate not passed");
        }
    }
}
