package com.worldmind;

import org.springframework.boot.ExitCodeGenerator;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.ApplicationContext;

@SpringBootApplication
public class WorldmindApplication {

    public static void main(String[] args) {
        ApplicationContext ctx = SpringApplication.run(WorldmindApplication.class, args);
        // CLI app: exit after command execution
        ExitCodeGenerator exitCodeGen = ctx.getBean(ExitCodeGenerator.class);
        int exitCode = SpringApplication.exit(ctx, exitCodeGen);
        System.exit(exitCode);
    }
}
