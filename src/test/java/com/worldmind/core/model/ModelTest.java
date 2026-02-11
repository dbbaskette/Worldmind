package com.worldmind.core.model;

import com.worldmind.core.state.WorldmindState;
import org.bsc.langgraph4j.state.AgentState;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for Xandarian Archive: domain records, enums, and WorldmindState.
 */
class ModelTest {

    // ═══════════════════════════════════════════════════════════════════
    //  Enum tests
    // ═══════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Enums")
    class EnumTests {

        @Test
        @DisplayName("MissionStatus has all expected values")
        void missionStatusValues() {
            MissionStatus[] expected = {
                MissionStatus.CLASSIFYING, MissionStatus.UPLOADING, MissionStatus.SPECIFYING,
                MissionStatus.PLANNING, MissionStatus.AWAITING_APPROVAL, MissionStatus.EXECUTING,
                MissionStatus.CONVERGING, MissionStatus.COMPLETED, MissionStatus.FAILED, MissionStatus.CANCELLED
            };
            assertArrayEquals(expected, MissionStatus.values());
            assertEquals(10, MissionStatus.values().length);
        }

        @Test
        @DisplayName("DirectiveStatus has all expected values")
        void directiveStatusValues() {
            DirectiveStatus[] expected = {
                DirectiveStatus.PENDING, DirectiveStatus.RUNNING,
                DirectiveStatus.PASSED, DirectiveStatus.FAILED, DirectiveStatus.SKIPPED
            };
            assertArrayEquals(expected, DirectiveStatus.values());
            assertEquals(5, DirectiveStatus.values().length);
        }

        @Test
        @DisplayName("ExecutionStrategy has all expected values")
        void executionStrategyValues() {
            ExecutionStrategy[] expected = {
                ExecutionStrategy.SEQUENTIAL, ExecutionStrategy.PARALLEL, ExecutionStrategy.ADAPTIVE
            };
            assertArrayEquals(expected, ExecutionStrategy.values());
            assertEquals(3, ExecutionStrategy.values().length);
        }

        @Test
        @DisplayName("InteractionMode has all expected values")
        void interactionModeValues() {
            InteractionMode[] expected = {
                InteractionMode.FULL_AUTO, InteractionMode.APPROVE_PLAN, InteractionMode.STEP_BY_STEP
            };
            assertArrayEquals(expected, InteractionMode.values());
            assertEquals(3, InteractionMode.values().length);
        }

        @Test
        @DisplayName("FailureStrategy has all expected values")
        void failureStrategyValues() {
            FailureStrategy[] expected = {
                FailureStrategy.RETRY, FailureStrategy.REPLAN,
                FailureStrategy.ESCALATE, FailureStrategy.SKIP
            };
            assertArrayEquals(expected, FailureStrategy.values());
            assertEquals(4, FailureStrategy.values().length);
        }

        @Test
        @DisplayName("Enum valueOf round-trips correctly")
        void enumValueOfRoundTrip() {
            for (MissionStatus s : MissionStatus.values()) {
                assertEquals(s, MissionStatus.valueOf(s.name()));
            }
            for (DirectiveStatus s : DirectiveStatus.values()) {
                assertEquals(s, DirectiveStatus.valueOf(s.name()));
            }
            for (InteractionMode m : InteractionMode.values()) {
                assertEquals(m, InteractionMode.valueOf(m.name()));
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    //  Record tests
    // ═══════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Records")
    class RecordTests {

        @Test
        @DisplayName("Classification record construction and accessors")
        void classificationRecord() {
            var c = new Classification("refactor", 3, List.of("service", "controller"), "iterative", "java");
            assertEquals("refactor", c.category());
            assertEquals(3, c.complexity());
            assertEquals(List.of("service", "controller"), c.affectedComponents());
            assertEquals("iterative", c.planningStrategy());
            assertEquals("java", c.runtimeTag());
        }

        @Test
        @DisplayName("ProjectContext record construction and accessors")
        void projectContextRecord() {
            var pc = new ProjectContext(
                "/home/user/project",
                List.of("src/Main.java", "pom.xml"),
                "Java",
                "Spring Boot",
                Map.of("spring-boot", "3.4.2"),
                2,
                "A Spring Boot app"
            );
            assertEquals("/home/user/project", pc.rootPath());
            assertEquals(2, pc.fileCount());
            assertEquals("Java", pc.language());
            assertEquals("Spring Boot", pc.framework());
            assertEquals(Map.of("spring-boot", "3.4.2"), pc.dependencies());
            assertEquals("A Spring Boot app", pc.summary());
        }

        @Test
        @DisplayName("FileRecord record construction and accessors")
        void fileRecordRecord() {
            var fr = new FileRecord("src/Main.java", "modified", 42);
            assertEquals("src/Main.java", fr.path());
            assertEquals("modified", fr.action());
            assertEquals(42, fr.linesChanged());
        }

        @Test
        @DisplayName("Directive record construction and accessors")
        void directiveRecord() {
            var files = List.of(new FileRecord("Foo.java", "created", 100));
            var d = new Directive(
                "d-001", "code-writer", "Implement Foo service",
                "context blob", "tests pass", List.of(),
                DirectiveStatus.PENDING, 0, 3,
                FailureStrategy.RETRY, files, null
            );
            assertEquals("d-001", d.id());
            assertEquals("code-writer", d.centurion());
            assertEquals(DirectiveStatus.PENDING, d.status());
            assertEquals(FailureStrategy.RETRY, d.onFailure());
            assertEquals(1, d.filesAffected().size());
            assertNull(d.elapsedMs());
        }

        @Test
        @DisplayName("TestResult record construction and accessors")
        void testResultRecord() {
            var tr = new TestResult("d-001", true, 10, 0, "All passed", 1234L);
            assertEquals("d-001", tr.directiveId());
            assertTrue(tr.passed());
            assertEquals(10, tr.totalTests());
            assertEquals(0, tr.failedTests());
            assertEquals(1234L, tr.durationMs());
        }

        @Test
        @DisplayName("ReviewFeedback record construction and accessors")
        void reviewFeedbackRecord() {
            var rf = new ReviewFeedback(
                "d-001", true, "Looks good",
                List.of(), List.of("Add logging"), 9
            );
            assertEquals("d-001", rf.directiveId());
            assertTrue(rf.approved());
            assertEquals(9, rf.score());
            assertEquals(1, rf.suggestions().size());
        }

        @Test
        @DisplayName("StarblasterInfo record construction and accessors")
        void starblasterInfoRecord() {
            Instant now = Instant.now();
            var si = new StarblasterInfo("ctr-abc", "code-writer", "d-001", "running", now, null);
            assertEquals("ctr-abc", si.containerId());
            assertEquals("code-writer", si.centurionType());
            assertEquals("running", si.status());
            assertEquals(now, si.startedAt());
            assertNull(si.completedAt());
        }

        @Test
        @DisplayName("MissionMetrics record construction and accessors")
        void missionMetricsRecord() {
            var mm = new MissionMetrics(60000L, 5, 1, 8, 3, 4, 20, 18, 3, 45000L);
            assertEquals(60000L, mm.totalDurationMs());
            assertEquals(5, mm.directivesCompleted());
            assertEquals(1, mm.directivesFailed());
            assertEquals(8, mm.totalIterations());
            assertEquals(3, mm.filesCreated());
            assertEquals(4, mm.filesModified());
            assertEquals(20, mm.testsRun());
            assertEquals(18, mm.testsPassed());
            assertEquals(3, mm.wavesExecuted());
            assertEquals(45000L, mm.aggregateDurationMs());
        }

        @Test
        @DisplayName("MissionMetrics backward-compatible constructor defaults wave fields")
        void missionMetricsBackwardCompatible() {
            var mm = new MissionMetrics(1000L, 2, 0, 3, 1, 1, 5, 5);
            assertEquals(0, mm.wavesExecuted());
            assertEquals(0L, mm.aggregateDurationMs());
        }

        @Test
        @DisplayName("WaveDispatchResult record construction and accessors")
        void waveDispatchResultRecord() {
            var files = List.of(new FileRecord("Foo.java", "created", 50));
            var wdr = new WaveDispatchResult("d-001", DirectiveStatus.PASSED, files, "output text", 1500L);
            assertEquals("d-001", wdr.directiveId());
            assertEquals(DirectiveStatus.PASSED, wdr.status());
            assertEquals(1, wdr.filesAffected().size());
            assertEquals("output text", wdr.output());
            assertEquals(1500L, wdr.elapsedMs());
        }

        @Test
        @DisplayName("Record equality follows value semantics")
        void recordEquality() {
            var a = new FileRecord("A.java", "created", 10);
            var b = new FileRecord("A.java", "created", 10);
            assertEquals(a, b);
            assertEquals(a.hashCode(), b.hashCode());

            var c = new FileRecord("B.java", "created", 10);
            assertNotEquals(a, c);
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    //  WorldmindState tests
    // ═══════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("WorldmindState")
    class WorldmindStateTests {

        @Test
        @DisplayName("State extends AgentState")
        void stateExtendsAgentState() {
            var state = new WorldmindState(Map.of());
            assertInstanceOf(AgentState.class, state);
        }

        @Test
        @DisplayName("SCHEMA contains all expected keys")
        void schemaContainsAllKeys() {
            var keys = WorldmindState.SCHEMA.keySet();
            var expected = List.of(
                "missionId", "request", "interactionMode", "status",
                "classification", "projectContext", "productSpec", "executionStrategy",
                "directives", "currentDirectiveIndex", "starblasters",
                "testResults", "reviewFeedback", "sealGranted",
                "retryContext", "metrics", "errors", "projectPath", "gitRemoteUrl",
                "completedDirectiveIds", "waveDirectiveIds", "waveCount", "waveDispatchResults"
            );
            for (String key : expected) {
                assertTrue(keys.contains(key), "SCHEMA missing key: " + key);
            }
            assertEquals(expected.size(), keys.size(), "SCHEMA has unexpected extra keys");
        }

        @Test
        @DisplayName("Default scalar values from schema")
        void defaultScalarValues() {
            var state = new WorldmindState(Map.of());
            assertEquals("", state.missionId());
            assertEquals("", state.request());
            assertEquals(InteractionMode.APPROVE_PLAN, state.interactionMode());
            assertEquals(MissionStatus.CLASSIFYING, state.status());
            assertEquals(ExecutionStrategy.SEQUENTIAL, state.executionStrategy());
            assertEquals(0, state.currentDirectiveIndex());
            assertFalse(state.sealGranted());
        }

        @Test
        @DisplayName("Optional fields default to empty")
        void optionalFieldsDefaultEmpty() {
            var state = new WorldmindState(Map.of());
            assertTrue(state.classification().isEmpty());
            assertTrue(state.projectContext().isEmpty());
            assertTrue(state.metrics().isEmpty());
        }

        @Test
        @DisplayName("State constructed with initial data")
        void stateWithInitialData() {
            var init = new HashMap<String, Object>();
            init.put("missionId", "m-42");
            init.put("request", "Add logging to all services");
            init.put("status", MissionStatus.PLANNING.name());
            init.put("sealGranted", true);

            var state = new WorldmindState(init);
            assertEquals("m-42", state.missionId());
            assertEquals("Add logging to all services", state.request());
            assertEquals(MissionStatus.PLANNING, state.status());
            assertTrue(state.sealGranted());
        }

        @Test
        @DisplayName("Classification can be set and retrieved")
        void classificationInState() {
            var classification = new Classification("feature", 5, List.of("api"), "comprehensive", "base");
            var state = new WorldmindState(Map.of("classification", classification));
            assertTrue(state.classification().isPresent());
            assertEquals("feature", state.classification().get().category());
            assertEquals(5, state.classification().get().complexity());
        }

        @Test
        @DisplayName("ProjectContext can be set and retrieved")
        void projectContextInState() {
            var ctx = new ProjectContext("/app", List.of("Main.java"), "Java", "Spring", Map.of(), 1, "test");
            var state = new WorldmindState(Map.of("projectContext", ctx));
            assertTrue(state.projectContext().isPresent());
            assertEquals("/app", state.projectContext().get().rootPath());
        }

        @Test
        @DisplayName("Metrics can be set and retrieved")
        void metricsInState() {
            var m = new MissionMetrics(5000L, 3, 0, 4, 2, 1, 10, 10);
            var state = new WorldmindState(Map.of("metrics", m));
            assertTrue(state.metrics().isPresent());
            assertEquals(5000L, state.metrics().get().totalDurationMs());
        }

        @Test
        @DisplayName("Wave channels default to empty/zero")
        void waveChannelsDefault() {
            var state = new WorldmindState(Map.of());
            assertTrue(state.waveDirectiveIds().isEmpty());
            assertEquals(0, state.waveCount());
            assertTrue(state.waveDispatchResults().isEmpty());
            assertTrue(state.completedDirectiveIds().isEmpty());
        }

        @Test
        @DisplayName("Wave channels can be set and retrieved")
        void waveChannelsSetAndRetrieve() {
            var wdr = new WaveDispatchResult("d-1", DirectiveStatus.PASSED, List.of(), "ok", 100L);
            var state = new WorldmindState(Map.of(
                "waveDirectiveIds", List.of("d-1", "d-2"),
                "waveCount", 3,
                "waveDispatchResults", List.of(wdr),
                "completedDirectiveIds", List.of("d-0")
            ));
            assertEquals(List.of("d-1", "d-2"), state.waveDirectiveIds());
            assertEquals(3, state.waveCount());
            assertEquals(1, state.waveDispatchResults().size());
            assertEquals("d-1", state.waveDispatchResults().get(0).directiveId());
            assertEquals(List.of("d-0"), state.completedDirectiveIds());
        }

        @Test
        @DisplayName("completedDirectiveIds appender accumulates")
        void completedDirectiveIdsAppenderAccumulates() {
            var initData = new HashMap<String, Object>();
            initData.put("completedDirectiveIds", List.of("d-1"));
            var state1Data = AgentState.updateState(initData, Map.of(), WorldmindState.SCHEMA);
            var state1 = new WorldmindState(state1Data);
            assertEquals(1, state1.completedDirectiveIds().size());

            var update = Map.<String, Object>of("completedDirectiveIds", List.of("d-2"));
            var state2Data = AgentState.updateState(state1, update, WorldmindState.SCHEMA);
            var state2 = new WorldmindState(state2Data);
            assertEquals(2, state2.completedDirectiveIds().size());
            assertEquals("d-1", state2.completedDirectiveIds().get(0));
            assertEquals("d-2", state2.completedDirectiveIds().get(1));
        }

        @Test
        @DisplayName("List fields default to empty lists")
        void listFieldsDefaultEmpty() {
            var state = new WorldmindState(Map.of());
            assertTrue(state.directives().isEmpty());
            assertTrue(state.starblasters().isEmpty());
            assertTrue(state.testResults().isEmpty());
            assertTrue(state.reviewFeedback().isEmpty());
            assertTrue(state.errors().isEmpty());
        }

        @Test
        @DisplayName("AppenderChannel accumulates directives via updateState")
        void appenderChannelAccumulatesDirectives() {
            // Start with one directive
            var d1 = new Directive("d-1", "coder", "First", "", "", List.of(),
                DirectiveStatus.PENDING, 0, 3, FailureStrategy.RETRY, List.of(), null);
            var initData = new HashMap<String, Object>();
            initData.put("directives", List.of(d1));
            var state1Data = AgentState.updateState(initData, Map.of(), WorldmindState.SCHEMA);
            var state1 = new WorldmindState(state1Data);
            assertEquals(1, state1.directives().size());

            // Add a second directive via updateState
            var d2 = new Directive("d-2", "tester", "Second", "", "", List.of(),
                DirectiveStatus.PENDING, 0, 3, FailureStrategy.SKIP, List.of(), null);
            var update = Map.<String, Object>of("directives", List.of(d2));
            var state2Data = AgentState.updateState(state1, update, WorldmindState.SCHEMA);
            var state2 = new WorldmindState(state2Data);
            assertEquals(2, state2.directives().size());
            assertEquals("d-1", state2.directives().get(0).id());
            assertEquals("d-2", state2.directives().get(1).id());
        }

        @Test
        @DisplayName("AppenderChannel accumulates errors via updateState")
        void appenderChannelAccumulatesErrors() {
            var initData = new HashMap<String, Object>();
            initData.put("errors", List.of("first error"));
            var state1Data = AgentState.updateState(initData, Map.of(), WorldmindState.SCHEMA);
            var state1 = new WorldmindState(state1Data);
            assertEquals(1, state1.errors().size());

            var update = Map.<String, Object>of("errors", List.of("second error"));
            var state2Data = AgentState.updateState(state1, update, WorldmindState.SCHEMA);
            var state2 = new WorldmindState(state2Data);
            assertEquals(2, state2.errors().size());
            assertEquals("first error", state2.errors().get(0));
            assertEquals("second error", state2.errors().get(1));
        }

        @Test
        @DisplayName("AppenderChannel accumulates test results")
        void appenderChannelAccumulatesTestResults() {
            var tr1 = new TestResult("d-1", true, 5, 0, "OK", 100L);
            var initData = new HashMap<String, Object>();
            initData.put("testResults", List.of(tr1));
            var state1Data = AgentState.updateState(initData, Map.of(), WorldmindState.SCHEMA);
            var state1 = new WorldmindState(state1Data);

            var tr2 = new TestResult("d-2", false, 3, 1, "FAIL", 200L);
            var update = Map.<String, Object>of("testResults", List.of(tr2));
            var state2Data = AgentState.updateState(state1, update, WorldmindState.SCHEMA);
            var state2 = new WorldmindState(state2Data);

            assertEquals(2, state2.testResults().size());
            assertTrue(state2.testResults().get(0).passed());
            assertFalse(state2.testResults().get(1).passed());
        }

        @Test
        @DisplayName("Scalar channels overwrite on update")
        void scalarChannelsOverwrite() {
            var initData = new HashMap<String, Object>();
            initData.put("status", MissionStatus.CLASSIFYING.name());
            var state1Data = AgentState.updateState(initData, Map.of(), WorldmindState.SCHEMA);
            var state1 = new WorldmindState(state1Data);
            assertEquals(MissionStatus.CLASSIFYING, state1.status());

            var update = Map.<String, Object>of("status", MissionStatus.EXECUTING.name());
            var state2Data = AgentState.updateState(state1, update, WorldmindState.SCHEMA);
            var state2 = new WorldmindState(state2Data);
            assertEquals(MissionStatus.EXECUTING, state2.status());
        }
    }
}
