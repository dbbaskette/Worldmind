package com.worldmind.dispatch.cli;

import com.worldmind.core.graph.WorldmindGraph;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import picocli.CommandLine.Command;

/**
 * CLI command: worldmind health
 * <p>
 * Checks system health by verifying whether the LangGraph4j graph
 * is compiled and available, along with other infrastructure status.
 */
@Command(name = "health", mixinStandardHelpOptions = true, description = "Check system health")
@Component
public class HealthCommand implements Runnable {

    private final WorldmindGraph worldmindGraph;

    public HealthCommand(@Autowired(required = false) WorldmindGraph worldmindGraph) {
        this.worldmindGraph = worldmindGraph;
    }

    @Override
    public void run() {
        ConsoleOutput.printBanner();
        if (worldmindGraph != null) {
            ConsoleOutput.success("Worldmind Core: graph compiled");
        } else {
            ConsoleOutput.error("Worldmind Core: not available");
        }
        System.out.println("PostgreSQL: not connected (coming in Phase 1.5)");
        System.out.println("Docker: not connected (coming in Phase 2)");
    }
}
