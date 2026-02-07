package com.worldmind.dispatch.api;

import com.worldmind.core.engine.MissionEngine;
import com.worldmind.core.model.Directive;
import com.worldmind.core.model.InteractionMode;
import com.worldmind.core.model.MissionStatus;
import com.worldmind.core.persistence.JdbcCheckpointSaver;
import com.worldmind.core.state.WorldmindState;
import org.bsc.langgraph4j.RunnableConfig;
import org.bsc.langgraph4j.checkpoint.Checkpoint;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * REST controller for mission lifecycle operations.
 */
@RestController
@RequestMapping("/api/v1/missions")
public class MissionController {

    private static final Logger log = LoggerFactory.getLogger(MissionController.class);

    private final MissionEngine missionEngine;
    private final JdbcCheckpointSaver checkpointSaver;
    private final SseStreamingService sseStreamingService;

    /** In-memory store of running/completed mission states, keyed by missionId. */
    private final ConcurrentHashMap<String, WorldmindState> missionStates = new ConcurrentHashMap<>();

    /** Tracks async mission futures for cancellation support. */
    private final ConcurrentHashMap<String, CompletableFuture<WorldmindState>> missionFutures = new ConcurrentHashMap<>();

    public MissionController(MissionEngine missionEngine,
                             JdbcCheckpointSaver checkpointSaver,
                             SseStreamingService sseStreamingService) {
        this.missionEngine = missionEngine;
        this.checkpointSaver = checkpointSaver;
        this.sseStreamingService = sseStreamingService;
    }

    /**
     * POST /api/v1/missions — Submit a new mission. Runs asynchronously.
     */
    @PostMapping
    public ResponseEntity<Map<String, String>> submitMission(@RequestBody MissionRequest request) {
        InteractionMode mode;
        try {
            mode = request.mode() != null
                    ? InteractionMode.valueOf(request.mode())
                    : InteractionMode.APPROVE_PLAN;
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(
                    Map.of("error", "Invalid mode: " + request.mode()));
        }

        if (request.request() == null || request.request().isBlank()) {
            return ResponseEntity.badRequest().body(
                    Map.of("error", "Request text is required"));
        }

        // Generate an ID synchronously so we can return it immediately
        String missionId = missionEngine.generateMissionId();
        log.info("Accepted mission {} — launching async execution", missionId);

        // Store a placeholder state while the mission runs
        missionStates.put(missionId, new WorldmindState(Map.of(
                "missionId", missionId,
                "request", request.request(),
                "interactionMode", mode.name(),
                "status", MissionStatus.CLASSIFYING.name()
        )));

        // Run mission asynchronously
        CompletableFuture<WorldmindState> future = CompletableFuture.supplyAsync(() -> {
            try {
                WorldmindState result = missionEngine.runMission(request.request(), mode);
                if (result != null) {
                    missionStates.put(missionId, result);
                }
                return result;
            } catch (Exception e) {
                log.error("Mission {} failed", missionId, e);
                WorldmindState failedState = new WorldmindState(Map.of(
                        "missionId", missionId,
                        "request", request.request(),
                        "status", MissionStatus.FAILED.name(),
                        "errors", List.of(e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName())
                ));
                missionStates.put(missionId, failedState);
                return failedState;
            } finally {
                missionFutures.remove(missionId);
            }
        });

        missionFutures.put(missionId, future);

        return ResponseEntity.accepted().body(Map.of(
                "mission_id", missionId,
                "status", MissionStatus.CLASSIFYING.name()
        ));
    }

    /**
     * GET /api/v1/missions/{id} — Get mission status with all directive statuses.
     */
    @GetMapping("/{id}")
    public ResponseEntity<MissionResponse> getMission(@PathVariable String id) {
        WorldmindState state = missionStates.get(id);
        if (state == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(toResponse(state));
    }

    /**
     * GET /api/v1/missions/{id}/events — SSE stream of real-time mission events.
     */
    @GetMapping(value = "/{id}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseEntity<SseEmitter> streamEvents(@PathVariable String id) {
        if (!missionStates.containsKey(id)) {
            return ResponseEntity.notFound().build();
        }
        SseEmitter emitter = sseStreamingService.createEmitter(id);
        return ResponseEntity.ok(emitter);
    }

    /**
     * POST /api/v1/missions/{id}/approve — Approve mission plan.
     */
    @PostMapping("/{id}/approve")
    public ResponseEntity<Map<String, String>> approveMission(@PathVariable String id) {
        WorldmindState state = missionStates.get(id);
        if (state == null) {
            return ResponseEntity.notFound().build();
        }
        if (state.status() != MissionStatus.AWAITING_APPROVAL) {
            return ResponseEntity.badRequest().body(
                    Map.of("error", "Mission is not awaiting approval; current status: " + state.status()));
        }

        log.info("Approving mission {}", id);

        // Transition to EXECUTING and kick off the execution phase asynchronously
        WorldmindState executingState = new WorldmindState(Map.of(
                "missionId", state.missionId(),
                "request", state.request(),
                "interactionMode", state.interactionMode().name(),
                "status", MissionStatus.EXECUTING.name()
        ));
        missionStates.put(id, executingState);

        return ResponseEntity.ok(Map.of(
                "mission_id", id,
                "status", MissionStatus.EXECUTING.name()
        ));
    }

    /**
     * POST /api/v1/missions/{id}/edit — Submit plan modifications.
     */
    @PostMapping("/{id}/edit")
    public ResponseEntity<Map<String, String>> editMission(
            @PathVariable String id,
            @RequestBody Map<String, Object> edits) {
        WorldmindState state = missionStates.get(id);
        if (state == null) {
            return ResponseEntity.notFound().build();
        }
        if (state.status() != MissionStatus.AWAITING_APPROVAL) {
            return ResponseEntity.badRequest().body(
                    Map.of("error", "Mission is not awaiting approval; current status: " + state.status()));
        }

        log.info("Received plan edits for mission {}: {}", id, edits.keySet());

        return ResponseEntity.ok(Map.of(
                "mission_id", id,
                "status", MissionStatus.AWAITING_APPROVAL.name(),
                "message", "Plan modifications received"
        ));
    }

    /**
     * POST /api/v1/missions/{id}/cancel — Cancel a running mission.
     */
    @PostMapping("/{id}/cancel")
    public ResponseEntity<Map<String, String>> cancelMission(@PathVariable String id) {
        WorldmindState state = missionStates.get(id);
        if (state == null) {
            return ResponseEntity.notFound().build();
        }

        log.info("Cancelling mission {}", id);

        CompletableFuture<WorldmindState> future = missionFutures.remove(id);
        if (future != null) {
            future.cancel(true);
        }

        WorldmindState cancelledState = new WorldmindState(Map.of(
                "missionId", state.missionId(),
                "request", state.request(),
                "interactionMode", state.interactionMode().name(),
                "status", MissionStatus.CANCELLED.name()
        ));
        missionStates.put(id, cancelledState);

        return ResponseEntity.ok(Map.of(
                "mission_id", id,
                "status", MissionStatus.CANCELLED.name()
        ));
    }

    /**
     * GET /api/v1/missions/{id}/timeline — Checkpointed state history.
     */
    @GetMapping("/{id}/timeline")
    public ResponseEntity<List<Map<String, Object>>> getTimeline(@PathVariable String id) {
        if (!missionStates.containsKey(id)) {
            return ResponseEntity.notFound().build();
        }

        RunnableConfig config = RunnableConfig.builder().threadId(id).build();
        Collection<Checkpoint> checkpoints = checkpointSaver.list(config);

        List<Map<String, Object>> timeline = checkpoints.stream()
                .map(cp -> {
                    Map<String, Object> entry = new LinkedHashMap<>();
                    entry.put("checkpoint_id", cp.getId());
                    entry.put("node_id", cp.getNodeId());
                    entry.put("next_node_id", cp.getNextNodeId());
                    entry.put("state", cp.getState());
                    return entry;
                })
                .toList();

        return ResponseEntity.ok(timeline);
    }

    /**
     * GET /api/v1/missions/{id}/directives/{did} — Detailed directive result.
     */
    @GetMapping("/{id}/directives/{did}")
    public ResponseEntity<MissionResponse.DirectiveResponse> getDirective(
            @PathVariable String id,
            @PathVariable String did) {
        WorldmindState state = missionStates.get(id);
        if (state == null) {
            return ResponseEntity.notFound().build();
        }

        return state.directives().stream()
                .filter(d -> d.id().equals(did))
                .findFirst()
                .map(d -> ResponseEntity.ok(toDirectiveResponse(d)))
                .orElse(ResponseEntity.notFound().build());
    }

    // ── Helpers ──────────────────────────────────────────────────────

    private MissionResponse toResponse(WorldmindState state) {
        List<MissionResponse.DirectiveResponse> directives = state.directives().stream()
                .map(this::toDirectiveResponse)
                .toList();

        return new MissionResponse(
                state.missionId(),
                state.status().name(),
                state.request(),
                state.interactionMode().name(),
                state.executionStrategy().name(),
                state.classification().orElse(null),
                directives,
                state.sealGranted(),
                state.metrics().orElse(null),
                state.errors()
        );
    }

    private MissionResponse.DirectiveResponse toDirectiveResponse(Directive d) {
        return new MissionResponse.DirectiveResponse(
                d.id(),
                d.centurion(),
                d.description(),
                d.status() != null ? d.status().name() : null,
                d.iteration(),
                d.maxIterations(),
                d.elapsedMs(),
                d.filesAffected()
        );
    }
}
