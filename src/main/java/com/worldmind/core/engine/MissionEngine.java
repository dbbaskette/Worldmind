package com.worldmind.core.engine;

import com.worldmind.core.events.EventBus;
import com.worldmind.core.events.WorldmindEvent;
import com.worldmind.core.graph.WorldmindGraph;
import com.worldmind.core.logging.MdcContext;
import com.worldmind.core.metrics.WorldmindMetrics;
import com.worldmind.core.model.InteractionMode;
import com.worldmind.core.model.MissionStatus;
import com.worldmind.core.state.WorldmindState;
import org.bsc.langgraph4j.RunnableConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Orchestrates mission execution by bridging the CLI to the LangGraph4j graph.
 * <p>
 * Creates initial state from the user request and interaction mode,
 * generates a unique mission ID, and invokes the compiled graph.
 */
@Service
public class MissionEngine {

    private static final Logger log = LoggerFactory.getLogger(MissionEngine.class);
    private static final AtomicInteger MISSION_COUNTER = new AtomicInteger(0);

    private final WorldmindGraph worldmindGraph;
    private final EventBus eventBus;
    private final WorldmindMetrics metrics;

    public MissionEngine(WorldmindGraph worldmindGraph, EventBus eventBus, WorldmindMetrics metrics) {
        this.worldmindGraph = worldmindGraph;
        this.eventBus = eventBus;
        this.metrics = metrics;
    }

    /**
     * Runs the full planning pipeline for a mission request.
     *
     * @param request the natural-language mission request
     * @param mode    the interaction mode (FULL_AUTO, APPROVE_PLAN, STEP_BY_STEP)
     * @return the final graph state after all nodes have executed
     * @throws RuntimeException if the graph execution returns empty state
     */
    public WorldmindState runMission(String request, InteractionMode mode) {
        String missionId = generateMissionId();
        MdcContext.setMission(missionId);
        try {
            log.info("Starting mission {} with mode {} — request: {}", missionId, mode, request);

            eventBus.publish(new WorldmindEvent(
                    "mission.created", missionId, null,
                    Map.of("mode", mode.name(), "request", request),
                    Instant.now()));

            Map<String, Object> initialState = Map.of(
                    "missionId", missionId,
                    "request", request,
                    "interactionMode", mode.name(),
                    "status", MissionStatus.CLASSIFYING.name()
            );

            // Configure graph invocation with thread ID for checkpointing
            var config = RunnableConfig.builder()
                    .threadId(missionId)
                    .build();

            var result = worldmindGraph.getCompiledGraph()
                    .invoke(initialState, config);

            var state = result.orElseThrow(() ->
                    new RuntimeException("Graph execution returned empty state for mission " + missionId));

            metrics.recordMissionResult(state.status().name());
            return state;
        } finally {
            MdcContext.clear();
        }
    }

    /**
     * Generates a unique mission ID in the format WMND-YYYY-NNNN.
     */
    public String generateMissionId() {
        int count = MISSION_COUNTER.incrementAndGet();
        int year = Instant.now().atZone(java.time.ZoneOffset.UTC).getYear();
        return String.format("WMND-%d-%04d", year, count);
    }
}
