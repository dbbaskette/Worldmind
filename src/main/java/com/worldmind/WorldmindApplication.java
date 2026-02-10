package com.worldmind;

import org.springframework.boot.ExitCodeGenerator;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ApplicationContext;

import java.util.Arrays;

@SpringBootApplication
public class WorldmindApplication {

    public static void main(String[] args) {
        // On Cloud Foundry, the buildpack runs the JAR with no arguments.
        // Auto-inject "serve" so the embedded web server starts.
        if (args.length == 0 && System.getenv("VCAP_APPLICATION") != null) {
            args = new String[]{"serve"};
        }

        boolean serveMode = Arrays.asList(args).contains("serve");

        SpringApplicationBuilder builder = new SpringApplicationBuilder(WorldmindApplication.class);

        if (serveMode) {
            // Enable web server for REST API + SSE
            builder.properties(
                    "spring.main.web-application-type=servlet",
                    "spring.main.banner-mode=off"
            );
        } else {
            // CLI-only: no web server
            builder.properties(
                    "spring.main.web-application-type=none",
                    "spring.main.banner-mode=off"
            );
        }

        ApplicationContext ctx = builder.run(args);

        if (!serveMode) {
            // CLI app: exit after command execution
            ExitCodeGenerator exitCodeGen = ctx.getBean(ExitCodeGenerator.class);
            int exitCode = SpringApplication.exit(ctx, exitCodeGen);
            System.exit(exitCode);
        }
        // In serve mode, the embedded web server keeps the JVM alive
    }
}
