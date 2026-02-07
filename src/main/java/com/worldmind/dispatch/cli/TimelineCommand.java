package com.worldmind.dispatch.cli;

import com.worldmind.core.persistence.CheckpointQueryService;
import org.bsc.langgraph4j.checkpoint.Checkpoint;
import org.springframework.stereotype.Component;
import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;

import java.util.Collection;

/**
 * CLI command: worldmind timeline &lt;mission-id&gt;
 * <p>
 * Queries all checkpoints for a mission and displays a chronological
 * list of state transitions with node names.
 */
@Command(name = "timeline", mixinStandardHelpOptions = true, description = "Show mission execution timeline")
@Component
public class TimelineCommand implements Runnable {

    @Parameters(index = "0", description = "Mission ID")
    private String missionId;

    private final CheckpointQueryService queryService;

    public TimelineCommand(CheckpointQueryService queryService) {
        this.queryService = queryService;
    }

    @Override
    public void run() {
        ConsoleOutput.printBanner();

        Collection<Checkpoint> checkpoints = queryService.listCheckpoints(missionId);
        if (checkpoints.isEmpty()) {
            ConsoleOutput.error("No checkpoints found for mission: " + missionId);
            return;
        }

        ConsoleOutput.info("Timeline for mission " + missionId);
        System.out.println();
        System.out.printf("  %-4s %-24s %-24s %s%n", "#", "NODE", "NEXT NODE", "CHECKPOINT ID");
        System.out.println("  " + "-".repeat(72));

        int step = 1;
        for (Checkpoint cp : checkpoints) {
            String nodeId = cp.getNodeId() != null ? cp.getNodeId() : "-";
            String nextNodeId = cp.getNextNodeId() != null ? cp.getNextNodeId() : "-";
            String cpId = cp.getId() != null ? truncate(cp.getId(), 24) : "-";

            System.out.printf("  %-4d %-24s %-24s %s%n", step, nodeId, nextNodeId, cpId);
            step++;
        }

        System.out.println();
        ConsoleOutput.info(checkpoints.size() + " checkpoint" + (checkpoints.size() != 1 ? "s" : "") + " recorded.");
    }

    private static String truncate(String s, int max) {
        if (s == null || s.isEmpty()) return "-";
        return s.length() <= max ? s : s.substring(0, max - 3) + "...";
    }
}
