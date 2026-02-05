package com.worldmind.dispatch.cli;

import org.springframework.stereotype.Component;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

/**
 * CLI command: worldmind mission "<request>"
 * Submits a mission request for the agentic workflow.
 */
@Command(name = "mission", mixinStandardHelpOptions = true, description = "Submit a mission request")
@Component
public class MissionCommand implements Runnable {

    @Parameters(index = "0", description = "Natural language mission request")
    private String request;

    @Option(names = {"--mode", "-m"},
            description = "Interaction mode: FULL_AUTO, APPROVE_PLAN, STEP_BY_STEP",
            defaultValue = "APPROVE_PLAN")
    private String mode;

    @Override
    public void run() {
        ConsoleOutput.printBanner();
        System.out.println("Mission submission coming in Phase 1...");
        System.out.println("Request: " + request);
        System.out.println("Mode: " + mode);
    }
}
