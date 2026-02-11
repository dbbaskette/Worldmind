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
        Map.entry("currentDirectiveIndex", Channels.base(() -> 0)),
        Map.entry("sealGranted",           Channels.base(() -> false)),
        Map.entry("retryContext",          Channels.base(() -> "")),
        Map.entry("metrics",               Channels.base((Reducer<MissionMetrics>) null)),
        Map.entry("productSpec",            Channels.base((Reducer<ProductSpec>) null)),
        Map.entry("projectPath",           Channels.base(() -> "")),
        Map.entry("gitRemoteUrl",          Channels.base(() -> "")),

        // ── Wave execution channels (Phase 4) ────────────────────────
        Map.entry("waveDirectiveIds",      Channels.base((Supplier<List<String>>) List::of)),
        Map.entry("waveCount",             Channels.base(() -> 0)),
        Map.entry("waveDispatchResults",   Channels.base((Supplier<List<WaveDispatchResult>>) List::of)),

        // ── Appender channels (list accumulation) ────────────────────
        Map.entry("directives",            Channels.appender(ArrayList::new)),
        Map.entry("completedDirectiveIds", Channels.base(
            (Reducer<List<String>>) (old, update) -> {
                var merged = new java.util.LinkedHashSet<>(old != null ? old : List.of());
                merged.addAll(update != null ? update : List.of());
                return List.copyOf(merged);
            },
            ArrayList::new)),
        Map.entry("starblasters",             Channels.appender(ArrayList::new)),
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

    public int currentDirectiveIndex() {
        return this.<Integer>value("currentDirectiveIndex").orElse(0);
    }

    public boolean sealGranted() {
        return this.<Boolean>value("sealGranted").orElse(false);
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
                        numInt(map.get("directivesCompleted")),
                        numInt(map.get("directivesFailed")),
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
    public List<String> waveDirectiveIds() {
        return this.<List<String>>value("waveDirectiveIds").orElse(List.of());
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
    public List<String> completedDirectiveIds() {
        return this.<List<String>>value("completedDirectiveIds").orElse(List.of());
    }

    @SuppressWarnings("unchecked")
    public List<Directive> directives() {
        List<?> raw = this.<List<?>>value("directives").orElse(List.of());
        if (raw.isEmpty()) return List.of();
        List<Directive> all;
        if (raw.getFirst() instanceof Directive) {
            all = (List<Directive>) raw;
        } else {
            // Checkpoint deserialization may produce raw Maps instead of records
            all = raw.stream()
                    .map(item -> item instanceof Map<?, ?> m ? directiveFromMap((Map<String, Object>) m) : (Directive) item)
                    .toList();
        }
        // Appender channel may accumulate duplicates across graph re-invocations.
        // Keep only the last occurrence of each directive ID (from the latest planning run).
        var seen = new java.util.LinkedHashMap<String, Directive>();
        for (var d : all) seen.put(d.id(), d);
        return List.copyOf(seen.values());
    }

    @SuppressWarnings("unchecked")
    public List<StarblasterInfo> starblasters() {
        List<?> raw = this.<List<?>>value("starblasters").orElse(List.of());
        if (raw.isEmpty()) return List.of();
        return raw.stream()
                .map(item -> item instanceof StarblasterInfo si ? si
                        : item instanceof Map<?, ?> m ? starblasterInfoFromMap((Map<String, Object>) m)
                        : (StarblasterInfo) item)
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
    private static Directive directiveFromMap(Map<String, Object> m) {
        List<FileRecord> files = Collections.emptyList();
        Object rawFiles = m.get("filesAffected");
        if (rawFiles instanceof List<?> fl && !fl.isEmpty()) {
            files = fl.stream()
                    .map(f -> f instanceof FileRecord fr ? fr : fileRecordFromMap((Map<String, Object>) f))
                    .toList();
        }
        return new Directive(
                (String) m.get("id"),
                (String) m.get("centurion"),
                (String) m.get("description"),
                (String) m.get("inputContext"),
                (String) m.get("successCriteria"),
                m.get("dependencies") instanceof List<?> deps ? (List<String>) deps : List.of(),
                enumOrNull(DirectiveStatus.class, m.get("status")),
                m.get("iteration") instanceof Number n ? n.intValue() : 0,
                m.get("maxIterations") instanceof Number n ? n.intValue() : 3,
                enumOrNull(FailureStrategy.class, m.get("onFailure")),
                files,
                m.get("elapsedMs") instanceof Number n ? n.longValue() : null
        );
    }

    private static StarblasterInfo starblasterInfoFromMap(Map<String, Object> m) {
        return new StarblasterInfo(
                (String) m.get("containerId"),
                (String) m.get("centurionType"),
                (String) m.get("directiveId"),
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
                (String) m.get("directiveId"),
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
                (String) m.get("directiveId"),
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
                (String) m.get("directiveId"),
                enumOrNull(DirectiveStatus.class, m.get("status")),
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
