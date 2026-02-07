package com.worldmind.dispatch.cli;

import com.worldmind.core.health.HealthCheckService;
import com.worldmind.core.health.HealthStatus;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import picocli.CommandLine.Command;

/**
 * CLI command: worldmind health
 * <p>
 * Checks system health by running all registered health checks
 * and displaying results with colored output.
 */
@Command(name = "health", mixinStandardHelpOptions = true, description = "Check system health")
@Component
public class HealthCommand implements Runnable {

    private final HealthCheckService healthCheckService;

    public HealthCommand(@Autowired(required = false) HealthCheckService healthCheckService) {
        this.healthCheckService = healthCheckService;
    }

    @Override
    public void run() {
        ConsoleOutput.printBanner();

        if (healthCheckService == null) {
            ConsoleOutput.error("Health check service not available");
            return;
        }

        var checks = healthCheckService.checkAll();
        boolean allUp = true;

        for (var check : checks) {
            String label = check.component() + ": " + check.detail();
            switch (check.status()) {
                case UP -> ConsoleOutput.success(label);
                case DOWN -> {
                    ConsoleOutput.error(label);
                    allUp = false;
                }
                case DEGRADED -> {
                    ConsoleOutput.info(label);
                    allUp = false;
                }
            }
        }

        System.out.println("──────────────────────────────────");
        if (allUp) {
            ConsoleOutput.success("Overall: all systems operational");
        } else {
            ConsoleOutput.error("Overall: one or more components degraded or down");
        }
    }
}
