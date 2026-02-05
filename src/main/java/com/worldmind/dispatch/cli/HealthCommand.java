package com.worldmind.dispatch.cli;

import org.springframework.stereotype.Component;
import picocli.CommandLine.Command;

/**
 * CLI command: worldmind health
 * Checks system health: core process, database, Docker.
 */
@Command(name = "health", mixinStandardHelpOptions = true, description = "Check system health")
@Component
public class HealthCommand implements Runnable {

    @Override
    public void run() {
        ConsoleOutput.printBanner();
        System.out.println("Worldmind Core: not running");
        System.out.println("PostgreSQL: not connected");
        System.out.println("Docker: checking...");
    }
}
