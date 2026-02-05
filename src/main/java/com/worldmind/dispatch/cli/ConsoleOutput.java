package com.worldmind.dispatch.cli;

import picocli.CommandLine;

/**
 * ANSI-colored terminal output utilities for Worldmind CLI.
 */
public class ConsoleOutput {

    private ConsoleOutput() {
        // utility class
    }

    public static void printBanner() {
        System.out.println(CommandLine.Help.Ansi.AUTO.string(
                "@|bold,fg(yellow) WORLDMIND v0.1.0|@"));
        System.out.println("──────────────────────────────────");
    }

    public static void info(String message) {
        System.out.println(CommandLine.Help.Ansi.AUTO.string(
                "@|fg(cyan) [WORLDMIND]|@ " + message));
    }

    public static void success(String message) {
        System.out.println(CommandLine.Help.Ansi.AUTO.string(
                "@|fg(green) +|@ " + message));
    }

    public static void error(String message) {
        System.out.println(CommandLine.Help.Ansi.AUTO.string(
                "@|fg(red) x|@ " + message));
    }
}
