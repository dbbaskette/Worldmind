package com.worldmind.core.state;

import com.worldmind.core.model.*;
import org.bsc.langgraph4j.state.AgentState;
import org.bsc.langgraph4j.state.Channel;
import org.bsc.langgraph4j.state.Channels;
import org.bsc.langgraph4j.state.Reducer;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Central graph state for the Worldmind agent workflow.
 * <p>
 * Extends LangGraph4j's {@link AgentState} with typed accessors for every field
 * in the mission lifecycle. List-valued fields use appender channels so that
 * each node can add entries without replacing previous ones.
 */
public class WorldmindState extends AgentState {

    public static final Map<String, Channel<?>> SCHEMA = Map.ofEntries(
        // ── Scalar channels ──────────────────────────────────────────
        Map.entry("missionId",             Channels.base(() -> "")),
        Map.entry("request",               Channels.base(() -> "")),
        Map.entry("interactionMode",       Channels.base(() -> InteractionMode.APPROVE_PLAN.name())),
        Map.entry("status",                Channels.base(() -> MissionStatus.CLASSIFYING.name())),
        Map.entry("classification",        Channels.base((Reducer<Classification>) null)),
        Map.entry("projectContext",        Channels.base((Reducer<ProjectContext>) null)),
        Map.entry("executionStrategy",     Channels.base(() -> ExecutionStrategy.SEQUENTIAL.name())),
        Map.entry("currentTaskIndex", Channels.base(() -> 0)),
        Map.entry("quality_gateGranted",           Channels.base(() -> false)),
        Map.entry("retryContext",          Channels.base(() -> "")),
        Map.entry("metrics",               Channels.base((Reducer<MissionMetrics>) null)),
        Map.entry("productSpec",            Channels.base((Reducer<ProductSpec>) null)),
        Map.entry("prdDocument",           Channels.base(() -> "")),  // User-provided PRD markdown (bypasses spec generation)
        Map.entry("projectPath",           Channels.base(() -> "")),
        Map.entry("gitRemoteUrl",          Channels.base(() -> "")),
        Map.entry("reasoningLevel",        Channels.base(() -> "medium")),
        Map.entry("userExecutionStrategy", Channels.base(() -> "")),  // User override for execution strategy
        Map.entry("createCfDeployment",   Channels.base(() -> false)),  // If true, append CF deployment task
        Map.entry("skipPerTaskTests",     Channels.base(() -> false)),  // If true, skip per-task TESTER agent (saves ~1-2 min/task)
        Map.entry("clarifyingQuestions",  Channels.base((Reducer<ClarifyingQuestions>) null)),  // Questions for user
        Map.entry("clarifyingAnswers",    Channels.base(() -> "")),  // User's answers as JSON
        Map.entry("manifestCreatedByTask", Channels.base(() -> false)),  // True when a planned task targets manifest.yml
        Map.entry("deploymentUrl",        Channels.base(() -> "")),     // URL of deployed app (set by DEPLOYER on success)

        // ── Wave execution channels (Phase 4) ────────────────────────
        Map.entry("waveTaskIds",      Channels.base((Supplier<List<String>>) List::of)),
        Map.entry("waveCount",             Channels.base(() -> 0)),
        Map.entry("waveDispatchResults",   Channels.base((Supplier<List<WaveDispatchResult>>) List::of)),

        // ── Appender channels (list accumulation) ────────────────────
        Map.entry("tasks",            Channels.appender(ArrayList::new)),
        Map.entry("completedTaskIds", Channels.appender(ArrayList::new)),  // Must accumulate across waves!
        Map.entry("retryingTaskIds", Channels.appender(ArrayList::new)),
        Map.entry("sandboxes",             Channels.appender(ArrayList::new)),
        Map.entry("testResults",           Channels.appender(ArrayList::new)),
        Map.entry("reviewFeedback",        Channels.appender(ArrayList::new)),
        Map.entry("errors",                Channels.appender(ArrayList::new))
    );

    public WorldmindState(Map<String, Object> initData) {
        super(initData);
    }

    // ── Scalar accessors ─────────────────────────────────────────────

    public String missionId() {
        return this.<String>value("missionId").orElse("");
    }

    public String request() {
        return this.<String>value("request").orElse("");
    }

    public InteractionMode interactionMode() {
        String raw = this.<String>value("interactionMode").orElse(InteractionMode.APPROVE_PLAN.name());
        return InteractionMode.valueOf(raw);
    }

    public MissionStatus status() {
        String raw = this.<String>value("status").orElse(MissionStatus.CLASSIFYING.name());
        return MissionStatus.valueOf(raw);
    }

    @SuppressWarnings("unchecked")
    public Optional<Classification> classification() {
        Optional<Object> raw = value("classification");
        return raw.map(obj -> {
            if (obj instanceof Classification c) return c;
            if (obj instanceof Map<?, ?> m) {
                var map = (Map<String, Object>) m;
                return new Classification(
                        (String) map.get("category"),
                        map.get("complexity") instanceof Number n ? n.intValue() : 0,
                        map.get("affectedComponents") instanceof List<?> l ? (List<String>) l : List.of(),
                        (String) map.get("planningStrategy"),
                        (String) map.getOrDefault("runtimeTag", "base"));
            }
            return (Classification) obj;
        });
    }

    @SuppressWarnings("unchecked")
    public Optional<ProjectContext> projectContext() {
        Optional<Object> raw = value("projectContext");
        return raw.map(obj -> {
            if (obj instanceof ProjectContext pc) return pc;
            if (obj instanceof Map<?, ?> m) {
                var map = (Map<String, Object>) m;
                return new ProjectContext(
                        (String) map.get("rootPath"),
                        map.get("fileTree") instanceof List<?> l ? (List<String>) l : List.of(),
                        (String) map.get("language"),
                        (String) map.get("framework"),
                        map.get("dependencies") instanceof Map<?, ?> d ? (Map<String, String>) d : Map.of(),
                        map.get("fileCount") instanceof Number n ? n.intValue() : 0,
                        (String) map.get("summary"));
            }
            return (ProjectContext) obj;
        });
    }

    @SuppressWarnings("unchecked")
    public Optional<ProductSpec> productSpec() {
        Optional<Object> raw = value("productSpec");
        return raw.map(obj -> {
            if (obj instanceof ProductSpec ps) return ps;
            if (obj instanceof Map<?, ?> m) {
                var map = (Map<String, Object>) m;
                List<ProductSpec.ComponentSpec> components = List.of();
                if (map.get("components") instanceof List<?> cl && !cl.isEmpty()) {
                    components = cl.stream()
                            .map(c -> {
                                if (c instanceof ProductSpec.ComponentSpec cs) return cs;
                                var cm = (Map<String, Object>) c;
                                return new ProductSpec.ComponentSpec(
                                    (String) cm.get("name"),
                                    (String) cm.get("responsibility"),
                                    cm.get("affectedFiles") instanceof List<?> l ? (List<String>) l : List.of(),
                                    cm.get("behaviorExpectations") instanceof List<?> l ? (List<String>) l : List.of(),
                                    cm.get("integrationPoints") instanceof List<?> l ? (List<String>) l : List.of()
                                );
                            })
                            .toList();
                }
                return new ProductSpec(
                    (String) map.get("title"),
                    (String) map.get("overview"),
                    map.get("goals") instanceof List<?> l ? (List<String>) l : List.of(),
                    map.get("nonGoals") instanceof List<?> l ? (List<String>) l : List.of(),
                    map.get("technicalRequirements") instanceof List<?> l ? (List<String>) l : List.of(),
                    map.get("acceptanceCriteria") instanceof List<?> l ? (List<String>) l : List.of(),
                    components,
                    map.get("edgeCases") instanceof List<?> l ? (List<String>) l : List.of(),
                    map.get("outOfScopeAssumptions") instanceof List<?> l ? (List<String>) l : List.of()
                );
            }
            return (ProductSpec) obj;
        });
    }

    public ExecutionStrategy executionStrategy() {
        String raw = this.<String>value("executionStrategy").orElse(ExecutionStrategy.SEQUENTIAL.name());
        return ExecutionStrategy.valueOf(raw);
    }

    public int currentTaskIndex() {
        return this.<Integer>value("currentTaskIndex").orElse(0);
    }

    public boolean quality_gateGranted() {
        return this.<Boolean>value("quality_gateGranted").orElse(false);
    }

    public String retryContext() {
        return this.<String>value("retryContext").orElse("");
    }

    public String projectPath() {
        return this.<String>value("projectPath").orElse("");
    }

    public String gitRemoteUrl() {
        return this.<String>value("gitRemoteUrl").orElse("");
    }

    public String reasoningLevel() {
        return this.<String>value("reasoningLevel").orElse("medium");
    }

    public boolean createCfDeployment() {
        return this.<Boolean>value("createCfDeployment").orElse(false);
    }

    public boolean skipPerTaskTests() {
        return this.<Boolean>value("skipPerTaskTests").orElse(false);
    }

    public boolean manifestCreatedByTask() {
        return this.<Boolean>value("manifestCreatedByTask").orElse(false);
    }

    public String deploymentUrl() {
        return this.<String>value("deploymentUrl").orElse("");
    }

    @SuppressWarnings("unchecked")
    public Optional<ClarifyingQuestions> clarifyingQuestions() {
        Optional<Object> raw = value("clarifyingQuestions");
        return raw.map(obj -> {
            if (obj instanceof ClarifyingQuestions cq) return cq;
            if (obj instanceof Map<?, ?> m) {
                var map = (Map<String, Object>) m;
                List<ClarifyingQuestions.Question> questions = List.of();
                if (map.get("questions") instanceof List<?> qList) {
                    questions = qList.stream()
                            .filter(q -> q instanceof Map<?, ?>)
                            .map(q -> {
                                var qMap = (Map<String, Object>) q;
                                return new ClarifyingQuestions.Question(
                                        (String) qMap.get("id"),
                                        (String) qMap.get("question"),
                                        (String) qMap.get("category"),
                                        (String) qMap.get("whyAsking"),
                                        qMap.get("suggestedOptions") instanceof List<?> opts 
                                                ? opts.stream().map(Object::toString).toList() : List.of(),
                                        Boolean.TRUE.equals(qMap.get("required")),
                                        (String) qMap.get("defaultAnswer")
                                );
                            }).toList();
                }
                return new ClarifyingQuestions(questions, (String) map.get("summary"));
            }
            return (ClarifyingQuestions) obj;
        });
    }

    public String clarifyingAnswers() {
        return this.<String>value("clarifyingAnswers").orElse("");
    }

    /**
     * User-provided PRD document in markdown format.
     * When present, this bypasses clarifying questions and spec generation phases.
     */
    public String prdDocument() {
        return this.<String>value("prdDocument").orElse("");
    }

    public String runtimeTag() {
        return classification().map(Classification::runtimeTag).orElse("base");
    }

    @SuppressWarnings("unchecked")
    public Optional<MissionMetrics> metrics() {
        Optional<Object> raw = value("metrics");
        return raw.map(obj -> {
            if (obj instanceof MissionMetrics m) return m;
            if (obj instanceof Map<?, ?> m) {
                var map = (Map<String, Object>) m;
                return new MissionMetrics(
                        numLong(map.get("totalDurationMs")),
                        numInt(map.get("tasksCompleted")),
                        numInt(map.get("tasksFailed")),
                        numInt(map.get("totalIterations")),
                        numInt(map.get("filesCreated")),
                        numInt(map.get("filesModified")),
                        numInt(map.get("testsRun")),
                        numInt(map.get("testsPassed")),
                        numInt(map.get("wavesExecuted")),
                        numLong(map.get("aggregateDurationMs")));
            }
            return (MissionMetrics) obj;
        });
    }

    // ── Wave execution accessors (Phase 4) ────────────────────────────

    @SuppressWarnings("unchecked")
    public List<String> waveTaskIds() {
        return this.<List<String>>value("waveTaskIds").orElse(List.of());
    }

    public int waveCount() {
        return this.<Integer>value("waveCount").orElse(0);
    }

    @SuppressWarnings("unchecked")
    public List<WaveDispatchResult> waveDispatchResults() {
        List<?> raw = this.<List<?>>value("waveDispatchResults").orElse(List.of());
        if (raw.isEmpty()) return List.of();
        return raw.stream()
                .map(item -> item instanceof WaveDispatchResult wr ? wr
                        : item instanceof Map<?, ?> m ? waveDispatchResultFromMap((Map<String, Object>) m)
                        : (WaveDispatchResult) item)
                .toList();
    }

    // ── List accessors (appender channels) ───────────────────────────

    @SuppressWarnings("unchecked")
    public List<String> completedTaskIds() {
        var completed = this.<List<String>>value("completedTaskIds").orElse(List.of());
        var retrying = retryingTaskIds();
        if (retrying.isEmpty()) return completed;
        // Exclude any IDs that are pending retry (merge conflict reset)
        return completed.stream().filter(id -> !retrying.contains(id)).toList();
    }
    
    @SuppressWarnings("unchecked")
    public List<String> retryingTaskIds() {
        return this.<List<String>>value("retryingTaskIds").orElse(List.of());
    }

    @SuppressWarnings("unchecked")
    public List<Task> tasks() {
        List<?> raw = this.<List<?>>value("tasks").orElse(List.of());
        if (raw.isEmpty()) return List.of();
        List<Task> all;
        if (raw.getFirst() instanceof Task) {
            all = (List<Task>) raw;
        } else {
            // Checkpoint deserialization may produce raw Maps instead of records
            all = raw.stream()
                    .map(item -> item instanceof Map<?, ?> m ? taskFromMap((Map<String, Object>) m) : (Task) item)
                    .toList();
        }
        // Appender channel may accumulate duplicates across graph re-invocations.
        // Keep only the last occurrence of each task ID (from the latest planning run).
        var seen = new java.util.LinkedHashMap<String, Task>();
        for (var d : all) seen.put(d.id(), d);
        return List.copyOf(seen.values());
    }

    @SuppressWarnings("unchecked")
    public List<SandboxInfo> sandboxes() {
        List<?> raw = this.<List<?>>value("sandboxes").orElse(List.of());
        if (raw.isEmpty()) return List.of();
        return raw.stream()
                .map(item -> item instanceof SandboxInfo si ? si
                        : item instanceof Map<?, ?> m ? sandboxInfoFromMap((Map<String, Object>) m)
                        : (SandboxInfo) item)
                .toList();
    }

    @SuppressWarnings("unchecked")
    public List<TestResult> testResults() {
        List<?> raw = this.<List<?>>value("testResults").orElse(List.of());
        if (raw.isEmpty()) return List.of();
        return raw.stream()
                .map(item -> item instanceof TestResult tr ? tr
                        : item instanceof Map<?, ?> m ? testResultFromMap((Map<String, Object>) m)
                        : (TestResult) item)
                .toList();
    }

    @SuppressWarnings("unchecked")
    public List<ReviewFeedback> reviewFeedback() {
        List<?> raw = this.<List<?>>value("reviewFeedback").orElse(List.of());
        if (raw.isEmpty()) return List.of();
        return raw.stream()
                .map(item -> item instanceof ReviewFeedback rf ? rf
                        : item instanceof Map<?, ?> m ? reviewFeedbackFromMap((Map<String, Object>) m)
                        : (ReviewFeedback) item)
                .toList();
    }

    @SuppressWarnings("unchecked")
    public List<String> errors() {
        return this.<List<String>>value("errors").orElse(List.of());
    }

    // ── Map-to-record converters (checkpoint deserialization) ──────

    @SuppressWarnings("unchecked")
    private static Task taskFromMap(Map<String, Object> m) {
        List<FileRecord> files = Collections.emptyList();
        Object rawFiles = m.get("filesAffected");
        if (rawFiles instanceof List<?> fl && !fl.isEmpty()) {
            files = fl.stream()
                    .map(f -> f instanceof FileRecord fr ? fr : fileRecordFromMap((Map<String, Object>) f))
                    .toList();
        }
        List<String> targetFiles = m.get("targetFiles") instanceof List<?> tf ? (List<String>) tf : List.of();
        return new Task(
                (String) m.get("id"),
                (String) m.get("agent"),
                (String) m.get("description"),
                (String) m.get("inputContext"),
                (String) m.get("successCriteria"),
                m.get("dependencies") instanceof List<?> deps ? (List<String>) deps : List.of(),
                enumOrNull(TaskStatus.class, m.get("status")),
                m.get("iteration") instanceof Number n ? n.intValue() : 0,
                m.get("maxIterations") instanceof Number n ? n.intValue() : 3,
                enumOrNull(FailureStrategy.class, m.get("onFailure")),
                targetFiles,
                files,
                m.get("elapsedMs") instanceof Number n ? n.longValue() : null
        );
    }

    private static SandboxInfo sandboxInfoFromMap(Map<String, Object> m) {
        return new SandboxInfo(
                (String) m.get("containerId"),
                (String) m.get("agentType"),
                (String) m.get("taskId"),
                (String) m.get("status"),
                instantOrNull(m.get("startedAt")),
                instantOrNull(m.get("completedAt"))
        );
    }

    private static java.time.Instant instantOrNull(Object v) {
        if (v == null) return null;
        if (v instanceof java.time.Instant i) return i;
        if (v instanceof String s) return java.time.Instant.parse(s);
        if (v instanceof Number n) return java.time.Instant.ofEpochMilli(n.longValue());
        return null;
    }

    private static TestResult testResultFromMap(Map<String, Object> m) {
        return new TestResult(
                (String) m.get("taskId"),
                Boolean.TRUE.equals(m.get("passed")),
                numInt(m.get("totalTests")),
                numInt(m.get("failedTests")),
                (String) m.get("output"),
                numLong(m.get("durationMs"))
        );
    }

    @SuppressWarnings("unchecked")
    private static ReviewFeedback reviewFeedbackFromMap(Map<String, Object> m) {
        return new ReviewFeedback(
                (String) m.get("taskId"),
                Boolean.TRUE.equals(m.get("approved")),
                (String) m.get("summary"),
                m.get("issues") instanceof List<?> l ? (List<String>) l : List.of(),
                m.get("suggestions") instanceof List<?> l ? (List<String>) l : List.of(),
                numInt(m.get("score"))
        );
    }

    @SuppressWarnings("unchecked")
    private static WaveDispatchResult waveDispatchResultFromMap(Map<String, Object> m) {
        List<FileRecord> files = List.of();
        Object rawFiles = m.get("filesAffected");
        if (rawFiles instanceof List<?> fl && !fl.isEmpty()) {
            files = fl.stream()
                    .map(f -> f instanceof FileRecord fr ? fr : fileRecordFromMap((Map<String, Object>) f))
                    .toList();
        }
        return new WaveDispatchResult(
                (String) m.get("taskId"),
                enumOrNull(TaskStatus.class, m.get("status")),
                files,
                (String) m.get("output"),
                numLong(m.get("elapsedMs"))
        );
    }

    private static FileRecord fileRecordFromMap(Map<String, Object> m) {
        return new FileRecord(
                (String) m.get("path"),
                (String) m.get("action"),
                m.get("linesChanged") instanceof Number n ? n.intValue() : 0
        );
    }

    private static <E extends Enum<E>> E enumOrNull(Class<E> type, Object value) {
        if (value == null) return null;
        if (type.isInstance(value)) return type.cast(value);
        try { return Enum.valueOf(type, value.toString()); }
        catch (IllegalArgumentException e) { return null; }
    }

    private static int numInt(Object v) {
        return v instanceof Number n ? n.intValue() : 0;
    }

    private static long numLong(Object v) {
        return v instanceof Number n ? n.longValue() : 0L;
    }
}
