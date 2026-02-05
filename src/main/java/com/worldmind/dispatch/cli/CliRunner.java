package com.worldmind.dispatch.cli;

import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.ExitCodeGenerator;
import org.springframework.stereotype.Component;
import picocli.CommandLine;
import picocli.CommandLine.IFactory;

/**
 * Bridges picocli with Spring Boot lifecycle.
 * Parses CLI arguments and delegates to the appropriate command.
 */
@Component
public class CliRunner implements CommandLineRunner, ExitCodeGenerator {

    private final WorldmindCommand worldmindCommand;
    private final IFactory factory;
    private int exitCode;

    public CliRunner(WorldmindCommand worldmindCommand, IFactory factory) {
        this.worldmindCommand = worldmindCommand;
        this.factory = factory;
    }

    @Override
    public void run(String... args) throws Exception {
        exitCode = new CommandLine(worldmindCommand, factory).execute(args);
    }

    @Override
    public int getExitCode() {
        return exitCode;
    }
}
