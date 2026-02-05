package com.worldmind.dispatch.cli;

import org.springframework.stereotype.Component;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

/**
 * CLI command: worldmind status <id>
 * Checks the status of a running or completed mission.
 */
@Command(name = "status", mixinStandardHelpOptions = true, description = "Check mission status")
@Component
public class StatusCommand implements Runnable {

    @Parameters(index = "0", description = "Mission ID")
    private String missionId;

    @Option(names = {"--watch", "-w"}, description = "Watch for live updates")
    private boolean watch;

    @Override
    public void run() {
        System.out.println("Status for " + missionId + ": coming in Phase 5...");
        if (watch) {
            System.out.println("(watch mode not yet implemented)");
        }
    }
}
