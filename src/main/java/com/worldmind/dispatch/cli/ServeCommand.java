package com.worldmind.dispatch.cli;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import picocli.CommandLine.Command;

/**
 * CLI command: worldmind serve
 * <p>
 * Starts Worldmind as a long-running HTTP server exposing the REST API
 * and SSE event streaming. The web server is enabled by
 * {@link com.worldmind.WorldmindApplication#main} detecting "serve" in args.
 * <p>
 * Configure port via: {@code WORLDMIND_PORT=9090 worldmind serve}
 * or {@code java -Dserver.port=9090 -jar worldmind.jar serve}
 * <p>
 * Endpoints available in serve mode:
 * <ul>
 *   <li>POST /api/v1/missions — Submit a mission</li>
 *   <li>GET  /api/v1/missions/{id} — Get mission status</li>
 *   <li>GET  /api/v1/missions/{id}/events — SSE event stream</li>
 *   <li>POST /api/v1/missions/{id}/approve — Approve a plan</li>
 *   <li>POST /api/v1/missions/{id}/cancel — Cancel a mission</li>
 *   <li>GET  /api/v1/missions/{id}/timeline — Checkpoint timeline</li>
 *   <li>GET  /api/v1/missions/{id}/directives/{did} — Directive detail</li>
 *   <li>GET  /api/v1/health — System health</li>
 *   <li>GET  /api/v1/stargates — Active stargates</li>
 * </ul>
 */
@Command(name = "serve", mixinStandardHelpOptions = true,
        description = "Start the Worldmind HTTP server")
@Component
public class ServeCommand implements Runnable {

    @Value("${server.port:8080}")
    private int port;

    @Override
    public void run() {
        ConsoleOutput.printBanner();
        ConsoleOutput.info("Worldmind server running on port " + port);
        ConsoleOutput.info("API base: http://localhost:" + port + "/api/v1");
        ConsoleOutput.info("SSE stream: GET /api/v1/missions/{id}/events");
        ConsoleOutput.info("Press Ctrl+C to stop.");
    }

    public int getPort() {
        return port;
    }
}
