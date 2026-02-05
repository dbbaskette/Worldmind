package com.worldmind.dispatch.cli;

import com.worldmind.core.engine.MissionEngine;
import com.worldmind.core.model.InteractionMode;
import org.springframework.stereotype.Component;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

/**
 * CLI command: worldmind mission "&lt;request&gt;"
 * <p>
 * Submits a mission request to the Worldmind planning pipeline.
 * Invokes the full LangGraph4j graph (classify, upload context, plan)
 * and displays the resulting mission plan.
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

    private final MissionEngine missionEngine;

    public MissionCommand(MissionEngine missionEngine) {
        this.missionEngine = missionEngine;
    }

    @Override
    public void run() {
        ConsoleOutput.printBanner();

        InteractionMode interactionMode;
        try {
            interactionMode = InteractionMode.valueOf(mode);
        } catch (IllegalArgumentException e) {
            ConsoleOutput.error("Invalid mode: " + mode
                    + ". Valid modes: FULL_AUTO, APPROVE_PLAN, STEP_BY_STEP");
            return;
        }

        ConsoleOutput.info("Classifying request...");
        var finalState = missionEngine.runMission(request, interactionMode);

        // Display classification
        finalState.classification().ifPresent(classification ->
                ConsoleOutput.info(String.format(
                        "Category: %s | Complexity: %d | Components: %s",
                        classification.category(),
                        classification.complexity(),
                        String.join(", ", classification.affectedComponents())
                ))
        );

        // Display project context
        finalState.projectContext().ifPresent(context ->
                ConsoleOutput.info(String.format(
                        "Project: %s (%s/%s) — %d files",
                        context.rootPath(), context.language(),
                        context.framework(), context.fileCount()
                ))
        );

        // Display mission info
        System.out.println();
        System.out.println("MISSION " + finalState.missionId());
        System.out.println("Objective: " + request);
        System.out.println("Strategy: " + finalState.executionStrategy());
        System.out.println();

        // Display directives
        var directives = finalState.directives();
        if (!directives.isEmpty()) {
            System.out.println("DIRECTIVES:");
            for (var d : directives) {
                System.out.printf("  %s. [%-8s] %s%n", d.id(), d.centurion(), d.description());
            }
        }

        // Display approval status
        if (finalState.status() == com.worldmind.core.model.MissionStatus.AWAITING_APPROVAL) {
            System.out.println();
            ConsoleOutput.info("Mission planned. Execution coming in Phase 2.");
        }
    }
}
