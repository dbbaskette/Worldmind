package com.worldmind.core.state;

import com.worldmind.core.model.*;
import org.bsc.langgraph4j.state.AgentState;
import org.bsc.langgraph4j.state.Channel;
import org.bsc.langgraph4j.state.Channels;
import org.bsc.langgraph4j.state.Reducer;

import java.util.ArrayList;
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

        // ── Wave execution channels (Phase 4) ────────────────────────
        Map.entry("waveDirectiveIds",      Channels.base((Supplier<List<String>>) List::of)),
        Map.entry("waveCount",             Channels.base(() -> 0)),
        Map.entry("waveDispatchResults",   Channels.base((Supplier<List<WaveDispatchResult>>) List::of)),

        // ── Appender channels (list accumulation) ────────────────────
        Map.entry("directives",            Channels.appender(ArrayList::new)),
        Map.entry("completedDirectiveIds", Channels.appender(ArrayList::new)),
        Map.entry("stargates",             Channels.appender(ArrayList::new)),
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

    public Optional<Classification> classification() {
        return value("classification");
    }

    public Optional<ProjectContext> projectContext() {
        return value("projectContext");
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

    public Optional<MissionMetrics> metrics() {
        return value("metrics");
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
        return this.<List<WaveDispatchResult>>value("waveDispatchResults").orElse(List.of());
    }

    // ── List accessors (appender channels) ───────────────────────────

    @SuppressWarnings("unchecked")
    public List<String> completedDirectiveIds() {
        return this.<List<String>>value("completedDirectiveIds").orElse(List.of());
    }

    @SuppressWarnings("unchecked")
    public List<Directive> directives() {
        return this.<List<Directive>>value("directives").orElse(List.of());
    }

    @SuppressWarnings("unchecked")
    public List<StargateInfo> stargates() {
        return this.<List<StargateInfo>>value("stargates").orElse(List.of());
    }

    @SuppressWarnings("unchecked")
    public List<TestResult> testResults() {
        return this.<List<TestResult>>value("testResults").orElse(List.of());
    }

    @SuppressWarnings("unchecked")
    public List<ReviewFeedback> reviewFeedback() {
        return this.<List<ReviewFeedback>>value("reviewFeedback").orElse(List.of());
    }

    @SuppressWarnings("unchecked")
    public List<String> errors() {
        return this.<List<String>>value("errors").orElse(List.of());
    }
}
