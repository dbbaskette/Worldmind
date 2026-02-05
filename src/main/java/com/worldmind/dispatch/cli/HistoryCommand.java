package com.worldmind.dispatch.cli;

import org.springframework.stereotype.Component;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/**
 * CLI command: worldmind history
 * Lists completed missions.
 */
@Command(name = "history", mixinStandardHelpOptions = true, description = "List completed missions")
@Component
public class HistoryCommand implements Runnable {

    @Option(names = {"--limit", "-n"}, description = "Number of results", defaultValue = "10")
    private int limit;

    @Override
    public void run() {
        System.out.println("No missions yet. (limit: " + limit + ")");
    }
}
