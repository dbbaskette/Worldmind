package com.worldmind.dispatch.cli;

import org.springframework.stereotype.Component;
import picocli.CommandLine;
import picocli.CommandLine.Command;

/**
 * Top-level CLI command for Worldmind.
 * Routes to subcommands: mission, status, health, history.
 */
@Command(
        name = "worldmind",
        mixinStandardHelpOptions = true,
        version = "Worldmind 0.1.0",
        description = "Agentic code assistant powered by LangGraph4j and Goose",
        subcommands = {
                MissionCommand.class,
                StatusCommand.class,
                HealthCommand.class,
                HistoryCommand.class,
                CommandLine.HelpCommand.class
        }
)
@Component
public class WorldmindCommand implements Runnable {

    @Override
    public void run() {
        ConsoleOutput.printBanner();
        // When no subcommand is given, show usage help
        new CommandLine(this).usage(System.out);
    }
}
