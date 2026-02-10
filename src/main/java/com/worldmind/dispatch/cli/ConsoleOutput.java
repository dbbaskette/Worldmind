package com.worldmind.dispatch.cli;

import com.worldmind.core.model.MissionMetrics;
import com.worldmind.core.model.ReviewFeedback;
import com.worldmind.core.model.TestResult;
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

    public static void starblaster(String message) {
        System.out.println(CommandLine.Help.Ansi.AUTO.string(
                "@|fg(magenta) [STARBLASTER]|@ " + message));
    }

    public static void centurion(String type, String message) {
        System.out.println(CommandLine.Help.Ansi.AUTO.string(
                "@|fg(blue) [CENTURION " + type + "]|@ " + message));
    }

    public static void fileChange(String action, String path) {
        String symbol = "created".equals(action) ? "+" : "~";
        System.out.println(CommandLine.Help.Ansi.AUTO.string(
                "  @|fg(green) " + symbol + "|@ " + path));
    }

    public static void testResult(TestResult result) {
        String status = result.passed() ? "@|fg(green) PASS|@" : "@|fg(red) FAIL|@";
        System.out.println(CommandLine.Help.Ansi.AUTO.string(
                "  @|fg(yellow) [TEST]|@ " + status + " " + result.directiveId() +
                " — " + result.totalTests() + " tests, " + result.failedTests() + " failed" +
                " (" + result.durationMs() + "ms)"));
    }

    public static void reviewFeedback(ReviewFeedback feedback) {
        String status = feedback.score() >= 7 ? "@|fg(green) " + feedback.score() + "/10|@" :
                                                "@|fg(red) " + feedback.score() + "/10|@";
        System.out.println(CommandLine.Help.Ansi.AUTO.string(
                "  @|fg(yellow) [REVIEW]|@ " + status + " " + feedback.directiveId() +
                " — " + feedback.summary()));
        for (String issue : feedback.issues()) {
            System.out.println(CommandLine.Help.Ansi.AUTO.string(
                    "    @|fg(red) -|@ " + issue));
        }
    }

    public static void wave(int waveNumber, int directiveCount) {
        System.out.println(CommandLine.Help.Ansi.AUTO.string(
                "@|bold,fg(yellow) [WAVE " + waveNumber + "]|@ dispatching " +
                directiveCount + " directive" + (directiveCount != 1 ? "s" : "")));
    }

    public static void parallelProgress(String directiveId, String status) {
        String statusColor = "PASSED".equals(status) ? "fg(green)" : "fg(red)";
        System.out.println(CommandLine.Help.Ansi.AUTO.string(
                "  @|" + statusColor + " " + status + "|@ " + directiveId));
    }

    public static void waveComplete(int waveNumber, int passed, int failed) {
        System.out.println(CommandLine.Help.Ansi.AUTO.string(
                "@|fg(yellow) [WAVE " + waveNumber + " COMPLETE]|@ " +
                "@|fg(green) " + passed + " passed|@" +
                (failed > 0 ? ", @|fg(red) " + failed + " failed|@" : "")));
    }

    public static void seal(boolean granted, String reason) {
        if (granted) {
            System.out.println(CommandLine.Help.Ansi.AUTO.string(
                    "  @|fg(green),bold [SEAL GRANTED]|@ " + reason));
        } else {
            System.out.println(CommandLine.Help.Ansi.AUTO.string(
                    "  @|fg(red),bold [SEAL DENIED]|@ " + reason));
        }
    }

    public static void metrics(MissionMetrics m) {
        System.out.println("──────────────────────────────────");
        System.out.println(CommandLine.Help.Ansi.AUTO.string(
                "@|bold Mission Metrics|@"));
        System.out.println(CommandLine.Help.Ansi.AUTO.string(
                "  Directives: @|fg(green) " + m.directivesCompleted() + " passed|@, @|fg(red) " +
                m.directivesFailed() + " failed|@"));
        if (m.wavesExecuted() > 0) {
            System.out.println(CommandLine.Help.Ansi.AUTO.string(
                    "  Waves: " + m.wavesExecuted()));
        }
        System.out.println(CommandLine.Help.Ansi.AUTO.string(
                "  Tests: " + m.testsRun() + " run, @|fg(green) " + m.testsPassed() + " passed|@"));
        System.out.println(CommandLine.Help.Ansi.AUTO.string(
                "  Files: " + m.filesCreated() + " created, " + m.filesModified() + " modified"));
        System.out.println(CommandLine.Help.Ansi.AUTO.string(
                "  Duration: " + formatDuration(m.totalDurationMs()) +
                (m.aggregateDurationMs() > 0 ? " (aggregate: " + formatDuration(m.aggregateDurationMs()) + ")" : "")));
    }

    public static void watchEvent(String eventType, String data) {
        String prefix = switch (eventType) {
            case "mission.created" -> "@|fg(cyan) [MISSION]|@";
            case "starblaster.opened", "starblaster.closed" -> "@|fg(magenta) [STARBLASTER]|@";
            case "directive.started", "directive.completed", "directive.failed" -> "@|fg(blue) [DIRECTIVE]|@";
            case "wave.started", "wave.completed" -> "@|bold,fg(yellow) [WAVE]|@";
            case "seal.granted" -> "@|fg(green),bold [SEAL]|@";
            case "seal.denied" -> "@|fg(red),bold [SEAL]|@";
            case "mission.completed" -> "@|fg(green),bold [COMPLETE]|@";
            case "mission.failed" -> "@|fg(red),bold [FAILED]|@";
            default -> "@|fg(white) [" + eventType + "]|@";
        };
        System.out.println(CommandLine.Help.Ansi.AUTO.string(prefix + " " + data));
    }

    private static String formatDuration(long ms) {
        if (ms < 1000) return ms + "ms";
        long seconds = ms / 1000;
        if (seconds < 60) return seconds + "s";
        return (seconds / 60) + "m " + (seconds % 60) + "s";
    }
}
