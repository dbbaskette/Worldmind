package com.worldmind.dispatch.api;

import com.worldmind.core.engine.MissionEngine;
import com.worldmind.core.events.EventBus;
import com.worldmind.core.model.Directive;
import com.worldmind.core.model.DirectiveStatus;
import com.worldmind.core.model.InteractionMode;
import com.worldmind.core.model.MissionStatus;
import com.worldmind.core.model.ReviewFeedback;
import com.worldmind.core.scheduler.OscillationDetector;
import com.worldmind.core.state.WorldmindState;
import com.worldmind.starblaster.InstructionStore;
import com.worldmind.starblaster.cf.CloudFoundryProperties;
import com.worldmind.starblaster.cf.GitWorkspaceManager;
import org.bsc.langgraph4j.RunnableConfig;
import org.bsc.langgraph4j.checkpoint.BaseCheckpointSaver;
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
    private final BaseCheckpointSaver checkpointSaver;
    private final SseStreamingService sseStreamingService;
    private final OscillationDetector oscillationDetector;
    private final EventBus eventBus;
    private final InstructionStore instructionStore;
    private final GitWorkspaceManager gitWorkspaceManager;
    private final CloudFoundryProperties cfProperties;

    /** In-memory store of running/completed mission states, keyed by missionId. */
    private final ConcurrentHashMap<String, WorldmindState> missionStates = new ConcurrentHashMap<>();

    /** Tracks async mission futures for cancellation support. */
    private final ConcurrentHashMap<String, CompletableFuture<WorldmindState>> missionFutures = new ConcurrentHashMap<>();

    public MissionController(MissionEngine missionEngine,
                             BaseCheckpointSaver checkpointSaver,
                             SseStreamingService sseStreamingService,
                             OscillationDetector oscillationDetector,
                             EventBus eventBus,
                             InstructionStore instructionStore,
                             @org.springframework.beans.factory.annotation.Autowired(required = false)
                             GitWorkspaceManager gitWorkspaceManager,
                             @org.springframework.beans.factory.annotation.Autowired(required = false)
                             CloudFoundryProperties cfProperties) {
        this.missionEngine = missionEngine;
        this.checkpointSaver = checkpointSaver;
        this.sseStreamingService = sseStreamingService;
        this.oscillationDetector = oscillationDetector;
        this.eventBus = eventBus;
        this.instructionStore = instructionStore;
        this.gitWorkspaceManager = gitWorkspaceManager;
        this.cfProperties = cfProperties;
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

        // Clear stale checkpoints from previous runs with the same ID
        // (counter resets on app restart, so IDs can collide)
        try {
            RunnableConfig cleanupConfig = RunnableConfig.builder().threadId(missionId).build();
            checkpointSaver.release(cleanupConfig);
        } catch (Exception e) {
            log.debug("Failed to clear old checkpoints for {}: {}", missionId, e.getMessage());
        }

        // Store a placeholder state while the mission runs
        missionStates.put(missionId, new WorldmindState(Map.of(
                "missionId", missionId,
                "request", request.request(),
                "interactionMode", mode.name(),
                "status", MissionStatus.CLASSIFYING.name()
        )));

        // Run mission asynchronously (uses overload with projectPath/gitRemoteUrl/reasoningLevel)
        CompletableFuture<WorldmindState> future = CompletableFuture.supplyAsync(() -> {
            try {
                WorldmindState result = missionEngine.runMission(missionId, request.request(), mode, 
                        request.projectPath(), request.gitRemoteUrl(), request.reasoningLevel(),
                        request.executionStrategy(), 
                        request.createCfDeployment() != null && request.createCfDeployment());
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
                cleanupMissionResources(missionId);
            }
        });
        missionFutures.put(missionId, future);

        return ResponseEntity.accepted().body(Map.of(
                "mission_id", missionId,
                "status", MissionStatus.CLASSIFYING.name()
        ));
    }

    /**
     * GET /api/v1/missions — List all tracked missions (summary).
     */
    @GetMapping
    public ResponseEntity<List<MissionResponse>> listMissions() {
        List<MissionResponse> list = missionStates.values().stream()
                .map(this::toResponse)
                .toList();
        return ResponseEntity.ok(list);
    }

    /**
     * GET /api/v1/missions/{id} — Get mission status with all directive statuses.
     * During active execution, reads the latest checkpoint for live directive progress.
     */
    @GetMapping("/{id}")
    public ResponseEntity<MissionResponse> getMission(@PathVariable String id) {
        WorldmindState state = missionStates.get(id);
        if (state == null) {
            return ResponseEntity.notFound().build();
        }

        // During active execution, read the latest checkpoint for live state.
        // Only use checkpoint if it has directives — early checkpoints from graph
        // re-invocation may be partial and would erase the in-memory directives.
        if (isActiveStatus(state.status())) {
            try {
                RunnableConfig config = RunnableConfig.builder().threadId(id).build();
                Optional<Checkpoint> latest = checkpointSaver.get(config);
                if (latest.isPresent()) {
                    var checkpointState = new WorldmindState(latest.get().getState());
                    if (!checkpointState.directives().isEmpty()) {
                        state = checkpointState;
                        missionStates.put(id, state);
                    }
                }
            } catch (Exception e) {
                log.debug("Failed to read checkpoint for {}, using in-memory state", id);
            }
        }

        return ResponseEntity.ok(toResponse(state));
    }

    private boolean isActiveStatus(MissionStatus status) {
        return status == MissionStatus.CLASSIFYING
                || status == MissionStatus.UPLOADING
                || status == MissionStatus.SPECIFYING
                || status == MissionStatus.PLANNING
                || status == MissionStatus.EXECUTING;
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

        log.info("Approving mission {} — launching execution", id);

        // Transition to EXECUTING, preserving all existing state so the UI keeps showing directives
        missionStates.put(id, new WorldmindState(buildExecutionStateMap(state)));

        launchAsync(id, state.request(), InteractionMode.FULL_AUTO,
                state.projectPath(), state.gitRemoteUrl());

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
     * POST /api/v1/missions/{id}/retry — Retry failed directives.
     * Accepts optional { "directive_ids": ["DIR-002"] } body.
     * If no body or empty list, retries ALL failed directives.
     */
    @PostMapping("/{id}/retry")
    public ResponseEntity<Map<String, String>> retryMission(
            @PathVariable String id,
            @RequestBody(required = false) RetryRequest retryRequest) {
        WorldmindState state = missionStates.get(id);
        if (state == null) {
            return ResponseEntity.notFound().build();
        }

        // Only allow retry when mission is COMPLETED or FAILED
        if (state.status() != MissionStatus.COMPLETED && state.status() != MissionStatus.FAILED) {
            return ResponseEntity.badRequest().body(
                    Map.of("error", "Retry only allowed when mission is COMPLETED or FAILED; current status: " + state.status()));
        }

        // Determine which directives to retry
        List<String> targetIds;
        if (retryRequest != null && retryRequest.directiveIds() != null && !retryRequest.directiveIds().isEmpty()) {
            targetIds = retryRequest.directiveIds();
        } else {
            // Retry all failed directives
            targetIds = state.directives().stream()
                    .filter(d -> d.status() == DirectiveStatus.FAILED)
                    .map(Directive::id)
                    .toList();
        }

        if (targetIds.isEmpty()) {
            return ResponseEntity.badRequest().body(
                    Map.of("error", "No failed directives to retry"));
        }

        log.info("Retrying mission {} — directives: {}", id, targetIds);

        // Reset directives: status→PENDING, iteration→0
        List<Directive> resetDirectives = state.directives().stream()
                .map(d -> {
                    if (targetIds.contains(d.id())) {
                        return new Directive(
                                d.id(), d.centurion(), d.description(),
                                d.inputContext(), d.successCriteria(), d.dependencies(),
                                DirectiveStatus.PENDING, 0, d.maxIterations(),
                                d.onFailure(), d.targetFiles(), d.filesAffected(), null
                        );
                    }
                    return d;
                })
                .toList();

        // Build completed IDs excluding the ones being retried
        List<String> completedIds = state.completedDirectiveIds().stream()
                .filter(cid -> !targetIds.contains(cid))
                .toList();

        // Clear oscillation history for retried directives
        for (String directiveId : targetIds) {
            oscillationDetector.clearHistory(directiveId);
        }

        // Build new state for retry — start from shared base, override directives/completedIds
        var retryState = buildExecutionStateMap(state);
        retryState.put("directives", resetDirectives);
        retryState.put("completedDirectiveIds", completedIds);
        missionStates.put(id, new WorldmindState(retryState));

        launchAsync(id, state.request(), InteractionMode.FULL_AUTO,
                state.projectPath(), state.gitRemoteUrl());

        return ResponseEntity.ok(Map.of(
                "mission_id", id,
                "status", MissionStatus.EXECUTING.name()
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

        var activeWaveIds = new HashSet<>(state.waveDirectiveIds());
        var completedIds = new HashSet<>(state.completedDirectiveIds());
        return state.directives().stream()
                .filter(d -> d.id().equals(did))
                .findFirst()
                .map(d -> ResponseEntity.ok(toDirectiveResponse(d, state, activeWaveIds, completedIds)))
                .orElse(ResponseEntity.notFound().build());
    }

    // ── Helpers ──────────────────────────────────────────────────────

    /**
     * Launches async graph execution for a mission, storing the result on completion.
     */
    private void launchAsync(String missionId, String request, InteractionMode mode,
                             String projectPath, String gitRemoteUrl) {
        CompletableFuture<WorldmindState> future = CompletableFuture.supplyAsync(() -> {
            try {
                WorldmindState result = missionEngine.runMission(missionId, request, mode, projectPath, gitRemoteUrl);
                if (result != null) {
                    missionStates.put(missionId, result);
                }
                return result;
            } catch (Exception e) {
                log.error("Mission {} failed", missionId, e);
                WorldmindState failedState = new WorldmindState(Map.of(
                        "missionId", missionId,
                        "request", request,
                        "status", MissionStatus.FAILED.name(),
                        "errors", List.of(e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName())
                ));
                missionStates.put(missionId, failedState);
                return failedState;
            } finally {
                missionFutures.remove(missionId);
                cleanupMissionResources(missionId);
            }
        });
        missionFutures.put(missionId, future);
    }

    /**
     * Copies all essential fields from an existing state into a mutable map,
     * overriding interactionMode and status for execution.
     */
    private HashMap<String, Object> buildExecutionStateMap(WorldmindState state) {
        var map = new HashMap<String, Object>();
        map.put("missionId", state.missionId());
        map.put("request", state.request());
        map.put("interactionMode", InteractionMode.FULL_AUTO.name());
        map.put("status", MissionStatus.EXECUTING.name());
        map.put("executionStrategy", state.executionStrategy().name());
        map.put("directives", state.directives());
        state.classification().ifPresent(c -> map.put("classification", c));
        state.projectContext().ifPresent(pc -> map.put("projectContext", pc));
        String pp = state.projectPath();
        if (pp != null && !pp.isBlank()) map.put("projectPath", pp);
        String gru = state.gitRemoteUrl();
        if (gru != null && !gru.isBlank()) map.put("gitRemoteUrl", gru);
        return map;
    }

    /**
     * Frees memory-heavy resources after a mission completes (success or failure).
     * On CF, cleans up any remaining directive branches.
     * 
     * <p>Note: With per-wave merge enabled, passed FORGE/PRISM branches are already merged
     * into main after each wave (in EvaluateWaveNode). This cleanup handles:
     * <ul>
     *   <li>Failed directives (branches preserved for debugging)</li>
     *   <li>Conflicted branches that couldn't be merged</li>
     *   <li>All branches if the mission itself failed</li>
     * </ul>
     */
    private void cleanupMissionResources(String missionId) {
        try {
            eventBus.clearMission(missionId);
            instructionStore.clear();
            // Release checkpoints — state is already in missionStates map
            RunnableConfig config = RunnableConfig.builder().threadId(missionId).build();
            checkpointSaver.release(config);

            // CF git branch cleanup
            if (gitWorkspaceManager != null && cfProperties != null) {
                WorldmindState state = missionStates.get(missionId);
                if (state != null && !state.directives().isEmpty()) {
                    List<String> allDirectiveIds = state.directives().stream()
                            .map(Directive::id)
                            .toList();
                    List<String> passedIds = state.directives().stream()
                            .filter(d -> d.status() == DirectiveStatus.PASSED)
                            .map(Directive::id)
                            .toList();

                    // Use the mission's git URL, falling back to config if not set
                    String missionGitUrl = state.gitRemoteUrl();
                    log.info("Mission {} gitRemoteUrl from state: '{}'", missionId,
                            missionGitUrl != null ? missionGitUrl.replaceAll("://[^@]+@", "://***@") : "null");
                    if (missionGitUrl == null || missionGitUrl.isBlank()) {
                        missionGitUrl = cfProperties.getGitRemoteUrl();
                        log.info("Mission {} falling back to config gitRemoteUrl: '{}'", missionId,
                                missionGitUrl != null ? missionGitUrl.replaceAll("://[^@]+@", "://***@") : "null");
                    }

                    // Per-wave merge already handles passed FORGE/PRISM branches during execution.
                    // At cleanup, we only need to delete branches for:
                    // 1. Failed directives (for debugging, user may want to inspect)
                    // 2. All directives if the mission failed (rollback scenario)
                    // 
                    // For successful missions, passed branches should already be merged and deleted.
                    // We still attempt cleanup in case any were missed (e.g., merge conflict).
                    if (state.status() == MissionStatus.COMPLETED) {
                        // Mission succeeded — clean up passed branches (should already be deleted,
                        // but cleanup handles any stragglers from merge conflicts)
                        if (!passedIds.isEmpty()) {
                            log.info("Cleaning up {} passed directive branches for completed mission {}", 
                                    passedIds.size(), missionId);
                            gitWorkspaceManager.cleanupDirectiveBranches(passedIds, cfProperties.getGitToken(), missionGitUrl);
                        }
                        // Clean up failed/skipped directive branches
                        List<String> failedIds = allDirectiveIds.stream()
                                .filter(id -> !passedIds.contains(id))
                                .toList();
                        if (!failedIds.isEmpty()) {
                            log.info("Cleaning up {} failed/skipped directive branches for mission {}", 
                                    failedIds.size(), missionId);
                            gitWorkspaceManager.cleanupDirectiveBranches(failedIds, cfProperties.getGitToken(), missionGitUrl);
                        }
                    } else {
                        // Mission failed — clean up all branches
                        log.info("Mission {} failed, cleaning up all {} directive branches", 
                                missionId, allDirectiveIds.size());
                        gitWorkspaceManager.cleanupDirectiveBranches(allDirectiveIds, cfProperties.getGitToken(), missionGitUrl);
                    }
                }
            }

            log.debug("Cleaned up resources for mission {}", missionId);
        } catch (Exception e) {
            log.warn("Error cleaning up mission {}: {}", missionId, e.getMessage());
        }
    }

    private MissionResponse toResponse(WorldmindState state) {
        // Build a set of currently-dispatched directive IDs so we can show them as EXECUTING
        var activeWaveIds = new HashSet<>(state.waveDirectiveIds());
        var completedIds = new HashSet<>(state.completedDirectiveIds());

        List<MissionResponse.DirectiveResponse> directives = state.directives().stream()
                .map(d -> toDirectiveResponse(d, state, activeWaveIds, completedIds))
                .toList();

        int waveCount = state.metrics()
                .map(m -> m.wavesExecuted())
                .orElse(state.waveCount());

        return new MissionResponse(
                state.missionId(),
                state.status().name(),
                state.request(),
                state.interactionMode().name(),
                state.executionStrategy().name(),
                state.classification().orElse(null),
                state.productSpec().orElse(null),
                directives,
                state.sealGranted(),
                state.metrics().orElse(null),
                state.errors(),
                waveCount
        );
    }

    private MissionResponse.DirectiveResponse toDirectiveResponse(Directive d, WorldmindState state,
                                                                    Set<String> activeWaveIds,
                                                                    Set<String> completedIds) {
        // Find review feedback for this directive
        Integer reviewScore = null;
        String reviewSummary = null;
        for (ReviewFeedback rf : state.reviewFeedback()) {
            if (d.id().equals(rf.directiveId())) {
                reviewScore = rf.score();
                reviewSummary = rf.summary();
                break;
            }
        }

        // Synthesize EXECUTING status for directives in the current wave that haven't
        // been completed yet. The directives channel isn't updated until EvaluateWaveNode
        // runs, so during dispatch they still show PENDING in the checkpoint.
        String uiStatus;
        if (d.status() == DirectiveStatus.PENDING
                && activeWaveIds.contains(d.id())
                && !completedIds.contains(d.id())
                && state.status() == MissionStatus.EXECUTING) {
            uiStatus = "EXECUTING";
        } else {
            uiStatus = mapDirectiveStatus(d.status());
        }

        return new MissionResponse.DirectiveResponse(
                d.id(),
                d.centurion(),
                d.description(),
                uiStatus,
                d.iteration(),
                d.maxIterations(),
                d.elapsedMs(),
                d.filesAffected(),
                d.onFailure() != null ? d.onFailure().name() : null,
                reviewScore,
                reviewSummary
        );
    }

    /**
     * Maps internal DirectiveStatus enum names to UI-expected status strings.
     * Java: PASSED/RUNNING → UI: FULFILLED/EXECUTING
     */
    private String mapDirectiveStatus(DirectiveStatus status) {
        if (status == null) return null;
        return switch (status) {
            case PASSED -> "FULFILLED";
            case RUNNING -> "EXECUTING";
            default -> status.name();
        };
    }
}
