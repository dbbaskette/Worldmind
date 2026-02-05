package com.worldmind.dispatch.cli;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the Worldmind CLI command structure.
 * These tests exercise picocli directly without Spring context,
 * validating command parsing, help output, and stub behavior.
 */
class CliTest {

    /**
     * Executes a CLI command with the given args and captures all output.
     * Uses System.out redirection to capture both picocli output and
     * direct System.out.println() calls from command run() methods.
     */
    private record CliResult(int exitCode, String output) {}

    private CliResult execute(String... args) {
        // Redirect System.out BEFORE creating CommandLine so picocli
        // picks up the redirected stream for its internal PrintWriter
        ByteArrayOutputStream capture = new ByteArrayOutputStream();
        PrintStream capturePrintStream = new PrintStream(capture, true);
        PrintStream originalOut = System.out;
        PrintStream originalErr = System.err;
        System.setOut(capturePrintStream);
        System.setErr(capturePrintStream);
        try {
            WorldmindCommand cmd = new WorldmindCommand();
            CommandLine commandLine = new CommandLine(cmd);
            int exitCode = commandLine.execute(args);
            capturePrintStream.flush();
            return new CliResult(exitCode, capture.toString());
        } finally {
            System.setOut(originalOut);
            System.setErr(originalErr);
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    //  Help output tests
    // ═══════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Help output")
    class HelpTests {

        @Test
        @DisplayName("--help includes all subcommands")
        void helpIncludesAllSubcommands() {
            CliResult result = execute("--help");
            assertEquals(0, result.exitCode());
            String output = result.output();
            assertTrue(output.contains("mission"), "Help should list 'mission' subcommand");
            assertTrue(output.contains("status"), "Help should list 'status' subcommand");
            assertTrue(output.contains("health"), "Help should list 'health' subcommand");
            assertTrue(output.contains("history"), "Help should list 'history' subcommand");
            assertTrue(output.contains("help"), "Help should list 'help' subcommand");
        }

        @Test
        @DisplayName("--help includes description")
        void helpIncludesDescription() {
            CliResult result = execute("--help");
            assertEquals(0, result.exitCode());
            assertTrue(result.output().contains("Agentic code assistant"));
        }

        @Test
        @DisplayName("--version shows version")
        void versionOutput() {
            CliResult result = execute("--version");
            assertEquals(0, result.exitCode());
            assertTrue(result.output().contains("Worldmind 0.1.0"));
        }

        @Test
        @DisplayName("mission --help shows mission options")
        void missionHelpOutput() {
            CliResult result = execute("mission", "--help");
            assertEquals(0, result.exitCode());
            String output = result.output();
            assertTrue(output.contains("Submit a mission request"),
                    "Mission help should show description");
            assertTrue(output.contains("--mode"),
                    "Mission help should show --mode option");
        }

        @Test
        @DisplayName("status --help shows status options")
        void statusHelpOutput() {
            CliResult result = execute("status", "--help");
            assertEquals(0, result.exitCode());
            String output = result.output();
            assertTrue(output.contains("Check mission status"),
                    "Status help should show description");
            assertTrue(output.contains("--watch"),
                    "Status help should show --watch option");
        }

        @Test
        @DisplayName("history --help shows history options")
        void historyHelpOutput() {
            CliResult result = execute("history", "--help");
            assertEquals(0, result.exitCode());
            String output = result.output();
            assertTrue(output.contains("List completed missions"),
                    "History help should show description");
            assertTrue(output.contains("--limit"),
                    "History help should show --limit option");
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    //  Command execution tests
    // ═══════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Command execution stubs")
    class ExecutionTests {

        @Test
        @DisplayName("mission command accepts request argument")
        void missionWithRequest() {
            CliResult result = execute("mission", "Add logging to all services");
            assertEquals(0, result.exitCode());
            String output = result.output();
            assertTrue(output.contains("Add logging to all services"),
                    "Mission output should echo the request");
            assertTrue(output.contains("Phase 1"),
                    "Mission output should mention Phase 1");
        }

        @Test
        @DisplayName("mission command accepts --mode option")
        void missionWithMode() {
            CliResult result = execute("mission", "--mode", "FULL_AUTO", "Refactor database layer");
            assertEquals(0, result.exitCode());
            String output = result.output();
            assertTrue(output.contains("FULL_AUTO"),
                    "Mission output should show the mode");
            assertTrue(output.contains("Refactor database layer"),
                    "Mission output should echo the request");
        }

        @Test
        @DisplayName("mission command fails without request argument")
        void missionWithoutRequest() {
            CliResult result = execute("mission");
            assertNotEquals(0, result.exitCode(),
                    "Mission without request should fail");
        }

        @Test
        @DisplayName("status command accepts mission ID")
        void statusWithMissionId() {
            CliResult result = execute("status", "m-42");
            assertEquals(0, result.exitCode());
            assertTrue(result.output().contains("m-42"),
                    "Status output should echo the mission ID");
        }

        @Test
        @DisplayName("status command fails without mission ID")
        void statusWithoutMissionId() {
            CliResult result = execute("status");
            assertNotEquals(0, result.exitCode(),
                    "Status without mission ID should fail");
        }

        @Test
        @DisplayName("health command produces expected stub output")
        void healthCommandOutput() {
            CliResult result = execute("health");
            assertEquals(0, result.exitCode());
            String output = result.output();
            assertTrue(output.contains("Worldmind Core: not running"),
                    "Health should show core status");
            assertTrue(output.contains("PostgreSQL: not connected"),
                    "Health should show PostgreSQL status");
            assertTrue(output.contains("Docker: checking"),
                    "Health should show Docker status");
        }

        @Test
        @DisplayName("history command runs with default limit")
        void historyCommandDefault() {
            CliResult result = execute("history");
            assertEquals(0, result.exitCode());
            assertTrue(result.output().contains("No missions yet"),
                    "History should indicate no missions");
        }

        @Test
        @DisplayName("history command accepts --limit option")
        void historyWithLimit() {
            CliResult result = execute("history", "--limit", "5");
            assertEquals(0, result.exitCode());
            assertTrue(result.output().contains("No missions yet"),
                    "History should indicate no missions");
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    //  ConsoleOutput tests
    // ═══════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("ConsoleOutput")
    class ConsoleOutputTests {

        @Test
        @DisplayName("printBanner outputs to stdout")
        void printBannerOutputsToStdout() {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            PrintStream originalOut = System.out;
            System.setOut(new PrintStream(out));
            try {
                ConsoleOutput.printBanner();
                String output = out.toString();
                assertTrue(output.contains("WORLDMIND"),
                        "Banner should contain WORLDMIND");
                assertTrue(output.contains("0.1.0"),
                        "Banner should contain version");
            } finally {
                System.setOut(originalOut);
            }
        }

        @Test
        @DisplayName("info outputs message with prefix")
        void infoOutputsWithPrefix() {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            PrintStream originalOut = System.out;
            System.setOut(new PrintStream(out));
            try {
                ConsoleOutput.info("test message");
                String output = out.toString();
                assertTrue(output.contains("WORLDMIND"),
                        "Info should contain WORLDMIND prefix");
                assertTrue(output.contains("test message"),
                        "Info should contain the message");
            } finally {
                System.setOut(originalOut);
            }
        }

        @Test
        @DisplayName("success outputs message")
        void successOutputsMessage() {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            PrintStream originalOut = System.out;
            System.setOut(new PrintStream(out));
            try {
                ConsoleOutput.success("done");
                assertTrue(out.toString().contains("done"),
                        "Success should contain the message");
            } finally {
                System.setOut(originalOut);
            }
        }

        @Test
        @DisplayName("error outputs message")
        void errorOutputsMessage() {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            PrintStream originalOut = System.out;
            System.setOut(new PrintStream(out));
            try {
                ConsoleOutput.error("failed");
                assertTrue(out.toString().contains("failed"),
                        "Error should contain the message");
            } finally {
                System.setOut(originalOut);
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    //  No-subcommand test
    // ═══════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("Running with no args shows banner and usage")
    void noArgsShowsBannerAndUsage() {
        CliResult result = execute();
        assertEquals(0, result.exitCode());
        String output = result.output();
        assertTrue(output.contains("WORLDMIND"),
                "No-args output should show banner");
        assertTrue(output.contains("worldmind"),
                "No-args output should show usage");
    }
}
