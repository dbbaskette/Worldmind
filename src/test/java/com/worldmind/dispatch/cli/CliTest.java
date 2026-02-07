package com.worldmind.dispatch.cli;

import com.worldmind.core.engine.MissionEngine;
import com.worldmind.core.graph.WorldmindGraph;
import com.worldmind.core.model.*;
import com.worldmind.core.state.WorldmindState;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Tests for the Worldmind CLI command structure.
 * These tests exercise picocli directly without Spring context,
 * validating command parsing, help output, and execution behavior.
 */
class CliTest {

    /**
     * Executes a CLI command with the given args and captures all output.
     * Uses System.out redirection to capture both picocli output and
     * direct System.out.println() calls from command run() methods.
     */
    private record CliResult(int exitCode, String output) {}

    /**
     * Creates a mock MissionEngine that returns a well-formed WorldmindState.
     */
    private MissionEngine createMockEngine() {
        MissionEngine mockEngine = mock(MissionEngine.class);
        // Build a WorldmindState with typical planning output
        var state = new WorldmindState(Map.of(
                "missionId", "WMND-2025-0001",
                "request", "Add logging",
                "interactionMode", InteractionMode.APPROVE_PLAN.name(),
                "status", MissionStatus.AWAITING_APPROVAL.name(),
                "classification", new Classification("feature", 3, List.of("api"), "sequential"),
                "projectContext", new ProjectContext(".", List.of(), "java", "spring", Map.of(), 42, "test project"),
                "executionStrategy", ExecutionStrategy.SEQUENTIAL.name(),
                "directives", List.of(
                        new Directive("DIR-001", "FORGE", "Implement feature", "", "Works", List.of(),
                                DirectiveStatus.PENDING, 0, 3, FailureStrategy.RETRY, List.of(), null)
                )
        ));
        when(mockEngine.runMission(anyString(), any(InteractionMode.class))).thenReturn(state);
        return mockEngine;
    }

    /**
     * Custom picocli IFactory that provides mock dependencies for commands.
     */
    private CommandLine.IFactory createFactory(MissionEngine mockEngine, WorldmindGraph mockGraph) {
        return new CommandLine.IFactory() {
            @Override
            @SuppressWarnings("unchecked")
            public <K> K create(Class<K> cls) throws Exception {
                if (cls == MissionCommand.class) {
                    return (K) new MissionCommand(mockEngine);
                }
                if (cls == HealthCommand.class) {
                    return (K) new HealthCommand(mockGraph);
                }
                // Default: use picocli's default factory for other classes
                return CommandLine.defaultFactory().create(cls);
            }
        };
    }

    private CliResult execute(String... args) {
        return execute(null, null, args);
    }

    private CliResult execute(MissionEngine mockEngine, WorldmindGraph mockGraph, String... args) {
        ByteArrayOutputStream capture = new ByteArrayOutputStream();
        PrintStream capturePrintStream = new PrintStream(capture, true);
        PrintStream originalOut = System.out;
        PrintStream originalErr = System.err;
        System.setOut(capturePrintStream);
        System.setErr(capturePrintStream);
        try {
            WorldmindCommand cmd = new WorldmindCommand();
            CommandLine commandLine;
            if (mockEngine != null || mockGraph != null) {
                var factory = createFactory(
                        mockEngine != null ? mockEngine : mock(MissionEngine.class),
                        mockGraph);
                commandLine = new CommandLine(cmd, factory);
            } else {
                // For help/version tests, use a factory that can handle the constructors
                var factory = createFactory(mock(MissionEngine.class), null);
                commandLine = new CommandLine(cmd, factory);
            }
            int exitCode = commandLine.execute(args);
            capturePrintStream.flush();
            return new CliResult(exitCode, capture.toString());
        } finally {
            System.setOut(originalOut);
            System.setErr(originalErr);
        }
    }

    // =====================================================================
    //  Help output tests
    // =====================================================================

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

    // =====================================================================
    //  Command execution tests
    // =====================================================================

    @Nested
    @DisplayName("Command execution")
    class ExecutionTests {

        @Test
        @DisplayName("mission command displays plan output")
        void missionWithRequest() {
            MissionEngine mockEngine = createMockEngine();
            CliResult result = execute(mockEngine, null, "mission", "Add logging to all services");
            assertEquals(0, result.exitCode());
            String output = result.output();
            assertTrue(output.contains("MISSION WMND-2025-0001"),
                    "Output should contain mission ID");
            assertTrue(output.contains("Objective: Add logging to all services"),
                    "Output should echo the request");
            assertTrue(output.contains("DIRECTIVES"),
                    "Output should list directives");
            assertTrue(output.contains("DIR-001"),
                    "Output should contain directive ID");
            assertTrue(output.contains("FORGE"),
                    "Output should contain centurion name");
        }

        @Test
        @DisplayName("mission command accepts --mode option")
        void missionWithMode() {
            MissionEngine mockEngine = createMockEngine();
            CliResult result = execute(mockEngine, null,
                    "mission", "--mode", "FULL_AUTO", "Refactor database layer");
            assertEquals(0, result.exitCode());
            String output = result.output();
            assertTrue(output.contains("MISSION"),
                    "Output should contain MISSION header");
            assertTrue(output.contains("Refactor database layer"),
                    "Output should echo the request");
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
        @DisplayName("health command with graph available shows compiled")
        void healthCommandWithGraph() {
            WorldmindGraph mockGraph = mock(WorldmindGraph.class);
            CliResult result = execute(null, mockGraph, "health");
            assertEquals(0, result.exitCode());
            String output = result.output();
            assertTrue(output.contains("graph compiled"),
                    "Health should show graph compiled");
            assertTrue(output.contains("PostgreSQL: not connected"),
                    "Health should show PostgreSQL status");
            assertTrue(output.contains("Docker: not connected"),
                    "Health should show Docker status");
        }

        @Test
        @DisplayName("health command without graph shows not available")
        void healthCommandWithoutGraph() {
            // Pass null for graph to simulate unavailable
            CliResult result = execute(mock(MissionEngine.class), null, "health");
            assertEquals(0, result.exitCode());
            String output = result.output();
            assertTrue(output.contains("not available"),
                    "Health should show core not available when graph is null");
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

    // =====================================================================
    //  ConsoleOutput tests
    // =====================================================================

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

        @Test
        @DisplayName("wave outputs wave number and directive count")
        void waveOutputsWaveInfo() {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            PrintStream originalOut = System.out;
            System.setOut(new PrintStream(out));
            try {
                ConsoleOutput.wave(2, 3);
                String output = out.toString();
                assertTrue(output.contains("WAVE 2"),
                        "Wave should contain wave number");
                assertTrue(output.contains("3 directives"),
                        "Wave should contain directive count");
            } finally {
                System.setOut(originalOut);
            }
        }

        @Test
        @DisplayName("parallelProgress outputs directive status")
        void parallelProgressOutputsStatus() {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            PrintStream originalOut = System.out;
            System.setOut(new PrintStream(out));
            try {
                ConsoleOutput.parallelProgress("DIR-001", "PASSED");
                String output = out.toString();
                assertTrue(output.contains("PASSED"),
                        "Progress should contain status");
                assertTrue(output.contains("DIR-001"),
                        "Progress should contain directive ID");
            } finally {
                System.setOut(originalOut);
            }
        }

        @Test
        @DisplayName("waveComplete outputs completion summary")
        void waveCompleteOutputsSummary() {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            PrintStream originalOut = System.out;
            System.setOut(new PrintStream(out));
            try {
                ConsoleOutput.waveComplete(1, 2, 1);
                String output = out.toString();
                assertTrue(output.contains("WAVE 1 COMPLETE"),
                        "Wave complete should contain wave number");
                assertTrue(output.contains("2 passed"),
                        "Wave complete should contain passed count");
                assertTrue(output.contains("1 failed"),
                        "Wave complete should contain failed count");
            } finally {
                System.setOut(originalOut);
            }
        }

        @Test
        @DisplayName("metrics shows wave count when non-zero")
        void metricsShowsWaveCount() {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            PrintStream originalOut = System.out;
            System.setOut(new PrintStream(out));
            try {
                ConsoleOutput.metrics(new MissionMetrics(5000L, 3, 0, 4, 2, 1, 10, 10, 2, 8000L));
                String output = out.toString();
                assertTrue(output.contains("Waves: 2"),
                        "Metrics should show wave count");
                assertTrue(output.contains("aggregate"),
                        "Metrics should show aggregate duration");
            } finally {
                System.setOut(originalOut);
            }
        }
    }

    // =====================================================================
    //  No-subcommand test
    // =====================================================================

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
