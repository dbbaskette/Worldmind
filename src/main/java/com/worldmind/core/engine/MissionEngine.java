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
import java.util.HashMap;
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
     * Runs the full planning pipeline for a mission request, generating a new mission ID.
     */
    public WorldmindState runMission(String request, InteractionMode mode) {
        return runMission(generateMissionId(), request, mode);
    }

    /**
     * Runs the full planning pipeline for a mission request with a pre-generated mission ID.
     *
     * @param missionId the mission ID to use (e.g. from the REST controller)
     * @param request   the natural-language mission request
     * @param mode      the interaction mode (FULL_AUTO, APPROVE_PLAN, STEP_BY_STEP)
     * @return the final graph state after all nodes have executed
     * @throws RuntimeException if the graph execution returns empty state
     */
    public WorldmindState runMission(String missionId, String request, InteractionMode mode) {
        return runMission(missionId, request, mode, null, null, null);
    }

    public WorldmindState runMission(String missionId, String request, InteractionMode mode, String projectPath) {
        return runMission(missionId, request, mode, projectPath, null, null);
    }

    public WorldmindState runMission(String missionId, String request, InteractionMode mode,
                                     String projectPath, String gitRemoteUrl) {
        return runMission(missionId, request, mode, projectPath, gitRemoteUrl, null, null);
    }

    public WorldmindState runMission(String missionId, String request, InteractionMode mode,
                                     String projectPath, String gitRemoteUrl, String reasoningLevel) {
        return runMission(missionId, request, mode, projectPath, gitRemoteUrl, reasoningLevel, null, false);
    }

    public WorldmindState runMission(String missionId, String request, InteractionMode mode,
                                     String projectPath, String gitRemoteUrl, String reasoningLevel,
                                     String executionStrategy) {
        return runMission(missionId, request, mode, projectPath, gitRemoteUrl, reasoningLevel, executionStrategy, false);
    }

    public WorldmindState runMission(String missionId, String request, InteractionMode mode,
                                     String projectPath, String gitRemoteUrl, String reasoningLevel,
                                     String executionStrategy, boolean createCfDeployment) {
        return runMission(missionId, request, mode, projectPath, gitRemoteUrl, reasoningLevel,
                executionStrategy, createCfDeployment, null, false);
    }

    public WorldmindState runMission(String missionId, String request, InteractionMode mode,
                                     String projectPath, String gitRemoteUrl, String reasoningLevel,
                                     String executionStrategy, boolean createCfDeployment,
                                     String prdDocument) {
        return runMission(missionId, request, mode, projectPath, gitRemoteUrl, reasoningLevel,
                executionStrategy, createCfDeployment, prdDocument, false);
    }

    public WorldmindState runMission(String missionId, String request, InteractionMode mode,
                                     String projectPath, String gitRemoteUrl, String reasoningLevel,
                                     String executionStrategy, boolean createCfDeployment,
                                     String prdDocument, boolean skipPerTaskTests) {
        MdcContext.setMission(missionId);
        try {
            boolean hasPrd = prdDocument != null && !prdDocument.isBlank();
            log.info("Starting mission {} with mode {}, reasoning={}, strategy={}, cfDeploy={}, skipTests={}, hasPRD={} — request: {}", 
                    missionId, mode, reasoningLevel, executionStrategy, createCfDeployment, skipPerTaskTests, hasPrd, request);

            eventBus.publish(new WorldmindEvent(
                    "mission.created", missionId, null,
                    Map.of("mode", mode.name(), "request", request, "hasPRD", hasPrd),
                    Instant.now()));

            var stateMap = new HashMap<String, Object>();
            stateMap.put("missionId", missionId);
            stateMap.put("request", request);
            stateMap.put("interactionMode", mode.name());
            stateMap.put("status", hasPrd ? MissionStatus.PLANNING.name() : MissionStatus.CLASSIFYING.name());
            if (projectPath != null && !projectPath.isBlank()) {
                stateMap.put("projectPath", projectPath);
            }
            if (gitRemoteUrl != null && !gitRemoteUrl.isBlank()) {
                stateMap.put("gitRemoteUrl", gitRemoteUrl);
            }
            if (reasoningLevel != null && !reasoningLevel.isBlank()) {
                stateMap.put("reasoningLevel", reasoningLevel);
            }
            if (executionStrategy != null && !executionStrategy.isBlank()) {
                stateMap.put("userExecutionStrategy", executionStrategy.toUpperCase());
            }
            if (createCfDeployment) {
                stateMap.put("createCfDeployment", true);
            }
            if (skipPerTaskTests) {
                stateMap.put("skipPerTaskTests", true);
            }
            if (hasPrd) {
                stateMap.put("prdDocument", prdDocument);
                log.info("Mission {} using user-provided PRD document ({} chars), skipping spec generation",
                        missionId, prdDocument.length());
            }
            Map<String, Object> initialState = Map.copyOf(stateMap);

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
     * Runs the graph with a pre-populated state map. Used to resume missions
     * after user interactions (e.g., submitting clarifying answers).
     */
    public WorldmindState runMissionWithState(String missionId, Map<String, Object> initialState) {
        MdcContext.setMission(missionId);
        try {
            log.info("Resuming mission {} with pre-populated state", missionId);

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
