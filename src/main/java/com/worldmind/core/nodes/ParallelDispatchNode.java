package com.worldmind.core.nodes;

import com.worldmind.core.model.*;
import com.worldmind.core.state.WorldmindState;
import com.worldmind.stargate.StargateBridge;
import com.worldmind.stargate.StargateProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;

/**
 * Dispatches all directives in the current wave concurrently using virtual threads.
 * Bounded by a semaphore at maxParallel. Collects per-directive results into
 * {@code waveDispatchResults} and appends stargate infos and errors.
 */
@Component
public class ParallelDispatchNode {

    private static final Logger log = LoggerFactory.getLogger(ParallelDispatchNode.class);

    private final StargateBridge bridge;
    private final int maxParallel;

    public ParallelDispatchNode(StargateBridge bridge, StargateProperties properties) {
        this(bridge, properties.getMaxParallel());
    }

    ParallelDispatchNode(StargateBridge bridge, int maxParallel) {
        this.bridge = bridge;
        this.maxParallel = maxParallel;
    }

    public Map<String, Object> apply(WorldmindState state) {
        var waveIds = state.waveDirectiveIds();
        var directives = state.directives();
        String retryContext = state.retryContext();
        var projectContext = state.projectContext().orElse(null);
        String projectPath = projectContext != null ? projectContext.rootPath() : ".";

        if (waveIds.isEmpty()) {
            return Map.of(
                    "waveDispatchResults", List.of(),
                    "stargates", List.of(),
                    "status", MissionStatus.EXECUTING.name()
            );
        }

        // Build a lookup map for directive IDs
        var directiveMap = new HashMap<String, Directive>();
        for (var d : directives) {
            directiveMap.put(d.id(), d);
        }

        var semaphore = new Semaphore(maxParallel);
        var futures = new ArrayList<CompletableFuture<DispatchOutcome>>();

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (var id : waveIds) {
                var directive = directiveMap.get(id);
                if (directive == null) {
                    log.warn("Directive {} not found in directives list, skipping", id);
                    continue;
                }

                var directiveToDispatch = applyRetryContext(directive, retryContext);

                futures.add(CompletableFuture.supplyAsync(() -> {
                    try {
                        semaphore.acquire();
                        try {
                            log.info("Dispatching directive {} [{}]: {}",
                                    directiveToDispatch.id(), directiveToDispatch.centurion(),
                                    directiveToDispatch.description());
                            var result = bridge.executeDirective(
                                    directiveToDispatch, projectContext, Path.of(projectPath));
                            return new DispatchOutcome(
                                    new WaveDispatchResult(
                                            directive.id(), result.directive().status(),
                                            result.directive().filesAffected(),
                                            result.output(),
                                            result.directive().elapsedMs() != null ? result.directive().elapsedMs() : 0L),
                                    result.stargateInfo(),
                                    result.directive().status() == DirectiveStatus.FAILED
                                            ? "Directive " + directive.id() + " failed: " + result.output()
                                            : null
                            );
                        } finally {
                            semaphore.release();
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return errorOutcome(directive.id(), "Interrupted: " + e.getMessage());
                    } catch (Exception e) {
                        log.error("Infrastructure error dispatching directive {}: {}",
                                directive.id(), e.getMessage());
                        return errorOutcome(directive.id(), e.getMessage());
                    }
                }, executor));
            }

            // Collect all results
            var waveResults = new ArrayList<WaveDispatchResult>();
            var stargateInfos = new ArrayList<StargateInfo>();
            var errors = new ArrayList<String>();

            for (var future : futures) {
                try {
                    var outcome = future.join();
                    waveResults.add(outcome.result);
                    if (outcome.stargateInfo != null) {
                        stargateInfos.add(outcome.stargateInfo);
                    }
                    if (outcome.error != null) {
                        errors.add(outcome.error);
                    }
                } catch (CompletionException e) {
                    log.error("Unexpected error collecting dispatch result", e);
                }
            }

            var updates = new HashMap<String, Object>();
            updates.put("waveDispatchResults", waveResults);
            updates.put("stargates", stargateInfos);
            updates.put("status", MissionStatus.EXECUTING.name());
            if (!errors.isEmpty()) {
                updates.put("errors", errors);
            }
            // Clear retryContext after consuming
            if (retryContext != null && !retryContext.isEmpty()) {
                updates.put("retryContext", "");
            }
            return updates;
        }
    }

    private Directive applyRetryContext(Directive directive, String retryContext) {
        if (retryContext == null || retryContext.isEmpty()) return directive;
        String augmentedContext = (directive.inputContext() != null ? directive.inputContext() + "\n\n" : "") +
                "## Retry Context (from previous attempt)\n\n" + retryContext;
        return new Directive(
                directive.id(), directive.centurion(), directive.description(),
                augmentedContext, directive.successCriteria(), directive.dependencies(),
                DirectiveStatus.PENDING, directive.iteration(), directive.maxIterations(),
                directive.onFailure(), directive.filesAffected(), directive.elapsedMs()
        );
    }

    private DispatchOutcome errorOutcome(String directiveId, String errorMsg) {
        return new DispatchOutcome(
                new WaveDispatchResult(directiveId, DirectiveStatus.FAILED, List.of(), errorMsg, 0L),
                null,
                "Directive " + directiveId + " infrastructure error: " + errorMsg
        );
    }

    private record DispatchOutcome(WaveDispatchResult result, StargateInfo stargateInfo, String error) {}
}
