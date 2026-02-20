package com.worldmind.dispatch.cli;

import com.worldmind.core.model.MissionStatus;
import com.worldmind.core.persistence.CheckpointQueryService;
import com.worldmind.core.state.WorldmindState;
import org.springframework.stereotype.Component;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * CLI command: worldmind status &lt;mission-id&gt;
 * <p>
 * Queries the checkpoint store for a specific mission and displays
 * formatted status including task progress.
 */
@Command(name = "status", mixinStandardHelpOptions = true, description = "Check mission status")
@Component
public class StatusCommand implements Runnable {

    @Parameters(index = "0", description = "Mission ID")
    private String missionId;

    @Option(names = {"--watch", "-w"}, description = "Watch for live updates via SSE")
    private boolean watch;

    @Option(names = {"--port"}, description = "Server port for watch mode (default: ${DEFAULT-VALUE})",
            defaultValue = "8080")
    private int port;

    private final CheckpointQueryService queryService;

    public StatusCommand(CheckpointQueryService queryService) {
        this.queryService = queryService;
    }

    @Override
    public void run() {
        if (watch) {
            runWatchMode();
            return;
        }

        ConsoleOutput.printBanner();

        var stateOpt = queryService.getLatestState(missionId);
        if (stateOpt.isEmpty()) {
            ConsoleOutput.error("Mission not found: " + missionId);
            return;
        }

        WorldmindState state = stateOpt.get();

        // Mission header
        System.out.println();
        System.out.println("MISSION " + state.missionId());
        System.out.println("Objective: " + state.request());
        System.out.println("Strategy: " + state.executionStrategy());

        // Status with color
        MissionStatus status = state.status();
        if (status == MissionStatus.COMPLETED) {
            ConsoleOutput.success("Status: " + status);
        } else if (status == MissionStatus.FAILED) {
            ConsoleOutput.error("Status: " + status);
        } else {
            ConsoleOutput.info("Status: " + status);
        }

        // Classification
        state.classification().ifPresent(c ->
                ConsoleOutput.info(String.format("Category: %s | Complexity: %d | Components: %s",
                        c.category(), c.complexity(), String.join(", ", c.affectedComponents()))));

        // Task progress table
        var tasks = state.tasks();
        if (!tasks.isEmpty()) {
            System.out.println();
            System.out.printf("  %-10s %-10s %-10s %-8s %s%n",
                    "TASK", "AGENT", "STATUS", "ITER", "DESCRIPTION");
            System.out.println("  " + "-".repeat(64));
            for (var d : tasks) {
                System.out.printf("  %-10s %-10s %-10s %d/%-5d %s%n",
                        d.id(), d.agent(), d.status(),
                        d.iteration(), d.maxIterations(),
                        truncate(d.description(), 30));
            }
        }

        // Wave info
        int waveCount = state.waveCount();
        if (waveCount > 0) {
            System.out.println();
            ConsoleOutput.info("Waves executed: " + waveCount);
        }

        // QualityGate status
        if (status == MissionStatus.COMPLETED || status == MissionStatus.FAILED) {
            ConsoleOutput.quality_gate(state.quality_gateGranted(),
                    state.quality_gateGranted() ? "Quality gate passed" : "Quality gate not passed");
        }

        // Metrics
        state.metrics().ifPresent(ConsoleOutput::metrics);

        // Errors
        var errors = state.errors();
        if (!errors.isEmpty()) {
            System.out.println();
            ConsoleOutput.error("Errors (" + errors.size() + "):");
            for (var e : errors) {
                ConsoleOutput.error("  " + e);
            }
        }
    }

    private void runWatchMode() {
        ConsoleOutput.printBanner();
        ConsoleOutput.info("Watching mission " + missionId + " (connecting to localhost:" + port + ")...");
        System.out.println();

        URI uri = URI.create("http://localhost:" + port + "/api/v1/missions/" + missionId + "/events");

        try {
            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(5))
                    .build();

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(uri)
                    .header("Accept", "text/event-stream")
                    .GET()
                    .build();

            HttpResponse<java.util.stream.Stream<String>> response = client.send(request,
                    HttpResponse.BodyHandlers.ofLines());

            if (response.statusCode() == 404) {
                ConsoleOutput.error("Mission not found: " + missionId);
                return;
            }
            if (response.statusCode() != 200) {
                ConsoleOutput.error("Server returned HTTP " + response.statusCode());
                return;
            }

            // Parse SSE stream
            final String[] currentEventType = {""};
            response.body().forEach(line -> {
                if (line.startsWith("event:")) {
                    currentEventType[0] = line.substring(6).trim();
                } else if (line.startsWith("data:")) {
                    String data = line.substring(5).trim();
                    String eventType = currentEventType[0].isEmpty() ? "message" : currentEventType[0];
                    ConsoleOutput.watchEvent(eventType, data);
                    currentEventType[0] = "";
                }
            });

            System.out.println();
            ConsoleOutput.info("Stream ended.");

        } catch (ConnectException e) {
            ConsoleOutput.error("Cannot connect to Worldmind server at localhost:" + port);
            ConsoleOutput.info("Start the server first: worldmind serve");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            ConsoleOutput.info("Watch interrupted.");
        } catch (Exception e) {
            ConsoleOutput.error("Watch failed: " + e.getMessage());
        }
    }

    private static String truncate(String s, int max) {
        if (s == null || s.isEmpty()) return "-";
        return s.length() <= max ? s : s.substring(0, max - 3) + "...";
    }
}
