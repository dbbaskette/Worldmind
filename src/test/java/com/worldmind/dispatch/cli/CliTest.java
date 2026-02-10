package com.worldmind.dispatch.cli;

import com.worldmind.core.engine.MissionEngine;
import com.worldmind.core.graph.WorldmindGraph;
import com.worldmind.core.health.HealthCheckService;
import com.worldmind.core.health.HealthStatus;
import com.worldmind.core.model.*;
import com.worldmind.core.persistence.CheckpointQueryService;
import com.worldmind.core.state.WorldmindState;
import org.bsc.langgraph4j.checkpoint.Checkpoint;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;

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
     * Creates a mock CheckpointQueryService for testing CLI commands
     * that query checkpoint data.
     */
    private CheckpointQueryService createMockQueryService() {
        CheckpointQueryService mockService = mock(CheckpointQueryService.class);

        // Default: no missions
        when(mockService.listAllThreadIds()).thenReturn(List.of());
        when(mockService.getLatestState(anyString())).thenReturn(Optional.empty());
        when(mockService.listCheckpoints(anyString())).thenReturn(List.of());

        return mockService;
    }

    /**
     * Creates a mock CheckpointQueryService populated with test mission data.
     */
    private CheckpointQueryService createPopulatedQueryService() {
        CheckpointQueryService mockService = mock(CheckpointQueryService.class);

        // Two missions
        when(mockService.listAllThreadIds()).thenReturn(List.of("WMND-2025-0001", "WMND-2025-0002"));

        // Mission 1 state
        var state1 = new WorldmindState(Map.of(
                "missionId", "WMND-2025-0001",
                "request", "Add logging to all services",
                "status", MissionStatus.COMPLETED.name(),
                "executionStrategy", ExecutionStrategy.SEQUENTIAL.name(),
                "sealGranted", true,
                "directives", List.of(
                        new Directive("DIR-001", "FORGE", "Implement logging", "", "Works", List.of(),
                                DirectiveStatus.PASSED, 1, 3, FailureStrategy.RETRY,
                                List.of(new FileRecord("src/Logger.java", "created", 50)), 1500L),
                        new Directive("DIR-002", "VIGIL", "Review code", "", "Quality ok", List.of("DIR-001"),
                                DirectiveStatus.PASSED, 1, 3, FailureStrategy.RETRY, List.of(), 800L)
                ),
                "testResults", List.of(
                        new TestResult("DIR-001", true, 5, 0, "All tests passed", 200L)
                ),
                "reviewFeedback", List.of(
                        new ReviewFeedback("DIR-001", true, "Excellent code quality", List.of(), List.of(), 9)
                ),
                "completedDirectiveIds", List.of("DIR-001", "DIR-002"),
                "waveCount", 2
        ));
        when(mockService.getLatestState("WMND-2025-0001")).thenReturn(Optional.of(state1));

        // Mission 2 state
        var state2 = new WorldmindState(Map.of(
                "missionId", "WMND-2025-0002",
                "request", "Refactor database layer",
                "status", MissionStatus.FAILED.name(),
                "executionStrategy", ExecutionStrategy.PARALLEL.name(),
                "errors", List.of("Build failed: compilation error in DbService.java")
        ));
        when(mockService.getLatestState("WMND-2025-0002")).thenReturn(Optional.of(state2));

        // Checkpoints for mission 1
        var cp1 = Checkpoint.builder().id("cp-1").nodeId("classify_request").nextNodeId("upload_context")
                .state(Map.of("status", MissionStatus.CLASSIFYING.name(), "missionId", "WMND-2025-0001")).build();
        var cp2 = Checkpoint.builder().id("cp-2").nodeId("upload_context").nextNodeId("plan_mission")
                .state(Map.of("status", MissionStatus.UPLOADING.name(), "missionId", "WMND-2025-0001")).build();
        var cp3 = Checkpoint.builder().id("cp-3").nodeId("plan_mission").nextNodeId("schedule_wave")
                .state(Map.of("status", MissionStatus.PLANNING.name(), "missionId", "WMND-2025-0001")).build();
        when(mockService.listCheckpoints("WMND-2025-0001")).thenReturn(List.of(cp1, cp2, cp3));

        // No checkpoints for unknown mission
        when(mockService.listCheckpoints("WMND-UNKNOWN")).thenReturn(List.of());
        when(mockService.getLatestState("WMND-UNKNOWN")).thenReturn(Optional.empty());

        return mockService;
    }

    /**
     * Custom picocli IFactory that provides mock dependencies for commands.
     */
    private CommandLine.IFactory createFactory(MissionEngine mockEngine,
                                                WorldmindGraph mockGraph,
                                                CheckpointQueryService mockQueryService) {
        return new CommandLine.IFactory() {
            @Override
            @SuppressWarnings("unchecked")
            public <K> K create(Class<K> cls) throws Exception {
                if (cls == MissionCommand.class) {
                    return (K) new MissionCommand(mockEngine);
                }
                if (cls == HealthCommand.class) {
                    HealthCheckService mockHealth = mock(HealthCheckService.class);
                    when(mockHealth.checkAll()).thenReturn(List.of(
                            new HealthStatus("graph", mockGraph != null ? HealthStatus.Status.UP : HealthStatus.Status.DOWN,
                                    mockGraph != null ? "Graph compiled and available" : "Graph not available", Map.of()),
                            new HealthStatus("database", HealthStatus.Status.DOWN, "No DataSource configured", Map.of()),
                            new HealthStatus("docker", HealthStatus.Status.DOWN, "No StarblasterProvider configured", Map.of())
                    ));
                    return (K) new HealthCommand(mockHealth);
                }
                if (cls == HistoryCommand.class) {
                    return (K) new HistoryCommand(mockQueryService);
                }
                if (cls == StatusCommand.class) {
                    return (K) new StatusCommand(mockQueryService);
                }
                if (cls == TimelineCommand.class) {
                    return (K) new TimelineCommand(mockQueryService);
                }
                if (cls == InspectCommand.class) {
                    return (K) new InspectCommand(mockQueryService);
                }
                if (cls == LogCommand.class) {
                    return (K) new LogCommand(mockQueryService);
                }
                if (cls == ServeCommand.class) {
                    return (K) new ServeCommand();
                }
                // Default: use picocli's default factory for other classes
                return CommandLine.defaultFactory().create(cls);
            }
        };
    }

    private CliResult execute(String... args) {
        return execute(null, null, null, args);
    }

    private CliResult execute(MissionEngine mockEngine, WorldmindGraph mockGraph, String... args) {
        return execute(mockEngine, mockGraph, null, args);
    }

    private CliResult execute(MissionEngine mockEngine, WorldmindGraph mockGraph,
                              CheckpointQueryService mockQueryService, String... args) {
        ByteArrayOutputStream capture = new ByteArrayOutputStream();
        PrintStream capturePrintStream = new PrintStream(capture, true);
        PrintStream originalOut = System.out;
        PrintStream originalErr = System.err;
        System.setOut(capturePrintStream);
        System.setErr(capturePrintStream);
        try {
            WorldmindCommand cmd = new WorldmindCommand();
            var factory = createFactory(
                    mockEngine != null ? mockEngine : mock(MissionEngine.class),
                    mockGraph,
                    mockQueryService != null ? mockQueryService : createMockQueryService());
            CommandLine commandLine = new CommandLine(cmd, factory);
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
            assertTrue(output.contains("timeline"), "Help should list 'timeline' subcommand");
            assertTrue(output.contains("inspect"), "Help should list 'inspect' subcommand");
            assertTrue(output.contains("log"), "Help should list 'log' subcommand");
            assertTrue(output.contains("serve"), "Help should list 'serve' subcommand");
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

        @Test
        @DisplayName("timeline --help shows timeline description")
        void timelineHelpOutput() {
            CliResult result = execute("timeline", "--help");
            assertEquals(0, result.exitCode());
            String output = result.output();
            assertTrue(output.contains("Show mission execution timeline"),
                    "Timeline help should show description");
        }

        @Test
        @DisplayName("inspect --help shows inspect description")
        void inspectHelpOutput() {
            CliResult result = execute("inspect", "--help");
            assertEquals(0, result.exitCode());
            String output = result.output();
            assertTrue(output.contains("Inspect a directive within a mission"),
                    "Inspect help should show description");
        }

        @Test
        @DisplayName("log --help shows log description")
        void logHelpOutput() {
            CliResult result = execute("log", "--help");
            assertEquals(0, result.exitCode());
            String output = result.output();
            assertTrue(output.contains("Show mission execution log"),
                    "Log help should show description");
        }

        @Test
        @DisplayName("--help includes serve subcommand")
        void helpIncludesServeCommand() {
            CliResult result = execute("--help");
            assertEquals(0, result.exitCode());
            assertTrue(result.output().contains("serve"), "Help should list 'serve' subcommand");
        }

        @Test
        @DisplayName("serve --help shows serve description")
        void serveHelpOutput() {
            CliResult result = execute("serve", "--help");
            assertEquals(0, result.exitCode());
            String output = result.output();
            assertTrue(output.contains("Start the Worldmind HTTP server"),
                    "Serve help should show description");
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
        @DisplayName("status command shows mission details")
        void statusWithMissionId() {
            var queryService = createPopulatedQueryService();
            CliResult result = execute(null, null, queryService, "status", "WMND-2025-0001");
            assertEquals(0, result.exitCode());
            String output = result.output();
            assertTrue(output.contains("MISSION WMND-2025-0001"),
                    "Status should show mission ID");
            assertTrue(output.contains("Add logging to all services"),
                    "Status should show objective");
            assertTrue(output.contains("COMPLETED"),
                    "Status should show status");
            assertTrue(output.contains("DIR-001"),
                    "Status should show directives");
            assertTrue(output.contains("FORGE"),
                    "Status should show centurion type");
        }

        @Test
        @DisplayName("status command shows error for unknown mission")
        void statusWithUnknownMission() {
            var queryService = createPopulatedQueryService();
            CliResult result = execute(null, null, queryService, "status", "WMND-UNKNOWN");
            assertEquals(0, result.exitCode());
            assertTrue(result.output().contains("Mission not found"),
                    "Status should show not found message");
        }

        @Test
        @DisplayName("status command fails without mission ID")
        void statusWithoutMissionId() {
            CliResult result = execute("status");
            assertNotEquals(0, result.exitCode(),
                    "Status without mission ID should fail");
        }

        @Test
        @DisplayName("status --watch prints connection error when server not running")
        void statusWatchMode() {
            var queryService = createPopulatedQueryService();
            CliResult result = execute(null, null, queryService, "status", "--watch", "WMND-2025-0001");
            assertEquals(0, result.exitCode());
            String output = result.output();
            // Should show either connection error or watch attempt
            assertTrue(output.contains("Watching mission") || output.contains("Cannot connect")
                            || output.contains("requires serve mode") || output.contains("Watch mode"),
                    "Watch should attempt connection or show error");
        }

        @Test
        @DisplayName("health command with graph available shows compiled")
        void healthCommandWithGraph() {
            WorldmindGraph mockGraph = mock(WorldmindGraph.class);
            CliResult result = execute((MissionEngine) null, mockGraph, "health");
            assertEquals(0, result.exitCode());
            String output = result.output();
            assertTrue(output.contains("Graph compiled and available"),
                    "Health should show graph compiled");
        }

        @Test
        @DisplayName("health command without graph shows not available")
        void healthCommandWithoutGraph() {
            CliResult result = execute(mock(MissionEngine.class), (WorldmindGraph) null, "health");
            assertEquals(0, result.exitCode());
            String output = result.output();
            assertTrue(output.contains("Graph not available"),
                    "Health should show core not available when graph is null");
        }

        @Test
        @DisplayName("history command with no missions shows info message")
        void historyCommandEmpty() {
            CliResult result = execute("history");
            assertEquals(0, result.exitCode());
            assertTrue(result.output().contains("No missions found"),
                    "History should indicate no missions");
        }

        @Test
        @DisplayName("history command lists missions from checkpoint store")
        void historyCommandWithMissions() {
            var queryService = createPopulatedQueryService();
            CliResult result = execute(null, null, queryService, "history");
            assertEquals(0, result.exitCode());
            String output = result.output();
            assertTrue(output.contains("WMND-2025-0001"),
                    "History should list mission 1");
            assertTrue(output.contains("WMND-2025-0002"),
                    "History should list mission 2");
            assertTrue(output.contains("COMPLETED"),
                    "History should show mission 1 status");
            assertTrue(output.contains("FAILED"),
                    "History should show mission 2 status");
        }

        @Test
        @DisplayName("history command accepts --limit option")
        void historyWithLimit() {
            var queryService = createPopulatedQueryService();
            CliResult result = execute(null, null, queryService, "history", "--limit", "1");
            assertEquals(0, result.exitCode());
            String output = result.output();
            assertTrue(output.contains("1 of 2"),
                    "History should show limited count");
        }
    }

    // =====================================================================
    //  Timeline command tests
    // =====================================================================

    @Nested
    @DisplayName("Timeline command")
    class TimelineTests {

        @Test
        @DisplayName("timeline shows chronological checkpoints")
        void timelineShowsCheckpoints() {
            var queryService = createPopulatedQueryService();
            CliResult result = execute(null, null, queryService, "timeline", "WMND-2025-0001");
            assertEquals(0, result.exitCode());
            String output = result.output();
            assertTrue(output.contains("Timeline for mission WMND-2025-0001"),
                    "Timeline should show mission ID");
            assertTrue(output.contains("classify_request"),
                    "Timeline should show node names");
            assertTrue(output.contains("upload_context"),
                    "Timeline should show upload node");
            assertTrue(output.contains("plan_mission"),
                    "Timeline should show plan node");
            assertTrue(output.contains("3 checkpoints"),
                    "Timeline should show checkpoint count");
        }

        @Test
        @DisplayName("timeline shows error for unknown mission")
        void timelineUnknownMission() {
            var queryService = createPopulatedQueryService();
            CliResult result = execute(null, null, queryService, "timeline", "WMND-UNKNOWN");
            assertEquals(0, result.exitCode());
            assertTrue(result.output().contains("No checkpoints found"),
                    "Timeline should show not found message");
        }

        @Test
        @DisplayName("timeline fails without mission ID")
        void timelineWithoutMissionId() {
            CliResult result = execute("timeline");
            assertNotEquals(0, result.exitCode(),
                    "Timeline without mission ID should fail");
        }
    }

    // =====================================================================
    //  Inspect command tests
    // =====================================================================

    @Nested
    @DisplayName("Inspect command")
    class InspectTests {

        @Test
        @DisplayName("inspect shows directive details")
        void inspectShowsDirectiveDetails() {
            var queryService = createPopulatedQueryService();
            CliResult result = execute(null, null, queryService, "inspect", "WMND-2025-0001", "DIR-001");
            assertEquals(0, result.exitCode());
            String output = result.output();
            assertTrue(output.contains("DIRECTIVE DIR-001"),
                    "Inspect should show directive ID");
            assertTrue(output.contains("FORGE"),
                    "Inspect should show centurion type");
            assertTrue(output.contains("PASSED"),
                    "Inspect should show status");
            assertTrue(output.contains("Implement logging"),
                    "Inspect should show description");
            assertTrue(output.contains("src/Logger.java"),
                    "Inspect should show affected files");
        }

        @Test
        @DisplayName("inspect shows error for unknown directive")
        void inspectUnknownDirective() {
            var queryService = createPopulatedQueryService();
            CliResult result = execute(null, null, queryService, "inspect", "WMND-2025-0001", "DIR-999");
            assertEquals(0, result.exitCode());
            assertTrue(result.output().contains("Directive DIR-999 not found"),
                    "Inspect should show directive not found");
        }

        @Test
        @DisplayName("inspect shows error for unknown mission")
        void inspectUnknownMission() {
            var queryService = createPopulatedQueryService();
            CliResult result = execute(null, null, queryService, "inspect", "WMND-UNKNOWN", "DIR-001");
            assertEquals(0, result.exitCode());
            assertTrue(result.output().contains("Mission not found"),
                    "Inspect should show mission not found");
        }

        @Test
        @DisplayName("inspect fails without arguments")
        void inspectWithoutArgs() {
            CliResult result = execute("inspect");
            assertNotEquals(0, result.exitCode(),
                    "Inspect without arguments should fail");
        }
    }

    // =====================================================================
    //  Log command tests
    // =====================================================================

    @Nested
    @DisplayName("Log command")
    class LogTests {

        @Test
        @DisplayName("log shows execution log with checkpoint steps")
        void logShowsExecutionLog() {
            var queryService = createPopulatedQueryService();
            CliResult result = execute(null, null, queryService, "log", "WMND-2025-0001");
            assertEquals(0, result.exitCode());
            String output = result.output();
            assertTrue(output.contains("Execution log for mission WMND-2025-0001"),
                    "Log should show mission ID");
            assertTrue(output.contains("classify_request"),
                    "Log should show node names");
            assertTrue(output.contains("Final status"),
                    "Log should show final summary");
        }

        @Test
        @DisplayName("log shows error for unknown mission")
        void logUnknownMission() {
            var queryService = createPopulatedQueryService();
            CliResult result = execute(null, null, queryService, "log", "WMND-UNKNOWN");
            assertEquals(0, result.exitCode());
            assertTrue(result.output().contains("No log entries found"),
                    "Log should show not found message");
        }

        @Test
        @DisplayName("log fails without mission ID")
        void logWithoutMissionId() {
            CliResult result = execute("log");
            assertNotEquals(0, result.exitCode(),
                    "Log without mission ID should fail");
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
