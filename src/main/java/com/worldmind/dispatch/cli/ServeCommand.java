package com.worldmind.dispatch.cli;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.context.WebServerInitializedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import picocli.CommandLine.Command;

/**
 * CLI command: worldmind serve
 * <p>
 * Starts Worldmind as a long-running HTTP server exposing the REST API
 * and SSE event streaming. The web server is enabled by
 * {@link com.worldmind.WorldmindApplication#main} detecting "serve" in args.
 * <p>
 * In serve mode, {@link CliRunner} skips picocli so the embedded web server
 * keeps the JVM alive. The startup banner is printed via a Spring
 * {@link WebServerInitializedEvent} listener once Tomcat is ready.
 * <p>
 * Configure port via: {@code SERVER_PORT=9090 worldmind serve}
 * or {@code java -Dserver.port=9090 -jar worldmind.jar serve}
 */
@Command(name = "serve", mixinStandardHelpOptions = true,
        description = "Start the Worldmind HTTP server")
@Component
public class ServeCommand implements Runnable {

    @Value("${server.port:8080}")
    private int port;

    @Override
    public void run() {
        // Not called in serve mode — CliRunner skips picocli.
        // Kept for picocli subcommand registration and --help.
        printBanner(port);
    }

    @EventListener
    public void onWebServerReady(WebServerInitializedEvent event) {
        int actualPort = event.getWebServer().getPort();
        printBanner(actualPort);
    }

    private static void printBanner(int port) {
        ConsoleOutput.printBanner();
        ConsoleOutput.info("Worldmind server running on port " + port);
        System.out.println();
        System.out.println("  Dashboard:  http://localhost:" + port);
        System.out.println("  API:        http://localhost:" + port + "/api/v1");
        System.out.println();
        ConsoleOutput.info("Press Ctrl+C to stop.");
    }

    public int getPort() {
        return port;
    }
}
