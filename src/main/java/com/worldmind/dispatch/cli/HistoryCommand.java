package com.worldmind.dispatch.cli;

import com.worldmind.core.persistence.CheckpointQueryService;
import com.worldmind.core.state.WorldmindState;
import org.springframework.stereotype.Component;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.util.List;

/**
 * CLI command: worldmind history
 * <p>
 * Queries the checkpoint store for all missions and displays them
 * as a table: Mission ID | Status | Objective (truncated) | Strategy.
 */
@Command(name = "history", mixinStandardHelpOptions = true, description = "List completed missions")
@Component
public class HistoryCommand implements Runnable {

    @Option(names = {"--limit", "-n"}, description = "Number of results", defaultValue = "10")
    private int limit;

    private final CheckpointQueryService queryService;

    public HistoryCommand(CheckpointQueryService queryService) {
        this.queryService = queryService;
    }

    @Override
    public void run() {
        ConsoleOutput.printBanner();

        List<String> threadIds = queryService.listAllThreadIds();
        if (threadIds.isEmpty()) {
            ConsoleOutput.info("No missions found.");
            return;
        }

        // Apply limit
        List<String> display = threadIds.size() > limit
                ? threadIds.subList(threadIds.size() - limit, threadIds.size())
                : threadIds;

        ConsoleOutput.info("Missions (" + display.size() + " of " + threadIds.size() + "):");
        System.out.println();
        System.out.printf("  %-20s %-18s %-12s %s%n", "MISSION ID", "STATUS", "STRATEGY", "OBJECTIVE");
        System.out.println("  " + "-".repeat(76));

        for (String threadId : display) {
            var stateOpt = queryService.getLatestState(threadId);
            if (stateOpt.isPresent()) {
                WorldmindState state = stateOpt.get();
                String status = state.status().name();
                String strategy = state.executionStrategy().name();
                String objective = truncate(state.request(), 30);
                System.out.printf("  %-20s %-18s %-12s %s%n", threadId, status, strategy, objective);
            } else {
                System.out.printf("  %-20s %-18s %-12s %s%n", threadId, "UNKNOWN", "-", "-");
            }
        }
    }

    private static String truncate(String s, int max) {
        if (s == null || s.isEmpty()) return "-";
        return s.length() <= max ? s : s.substring(0, max - 3) + "...";
    }
}
