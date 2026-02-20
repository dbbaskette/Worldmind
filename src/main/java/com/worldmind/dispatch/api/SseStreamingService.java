package com.worldmind.dispatch.api;

import com.worldmind.core.events.EventBus;
import com.worldmind.core.events.WorldmindEvent;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Bridges {@link EventBus} subscriptions to {@link SseEmitter} instances for SSE streaming.
 * <p>
 * When a client connects to the SSE endpoint, this service creates an emitter, subscribes
 * to the EventBus for that mission, and forwards events as SSE data frames. Handles emitter
 * lifecycle (completion, timeout, error) by cleaning up subscriptions.
 * <p>
 * Includes a heartbeat mechanism to keep SSE connections alive through Cloud Foundry's
 * gorouter, which has a ~125 second idle connection timeout. Heartbeats are sent as SSE
 * comments (lines starting with ':') which are ignored by EventSource clients.
 */
@Service
public class SseStreamingService {

    private static final Logger log = LoggerFactory.getLogger(SseStreamingService.class);

    /** Default emitter timeout: 30 minutes (for long-running missions). */
    private static final long DEFAULT_TIMEOUT_MS = 30 * 60 * 1000L;

    /** 
     * Heartbeat interval in seconds. CF gorouter times out idle connections at ~125s,
     * so we send heartbeats every 30 seconds to stay well under that limit.
     */
    private static final long HEARTBEAT_INTERVAL_SECONDS = 30;

    private final EventBus eventBus;
    private final long timeoutMs;

    /** Tracks active emitter registrations for monitoring/cleanup. */
    private final CopyOnWriteArrayList<EmitterRegistration> activeRegistrations = new CopyOnWriteArrayList<>();

    /** Scheduler for sending periodic heartbeats to all active emitters. */
    private final ScheduledExecutorService heartbeatScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "sse-heartbeat");
        t.setDaemon(true);
        return t;
    });

    @Autowired
    public SseStreamingService(EventBus eventBus) {
        this(eventBus, DEFAULT_TIMEOUT_MS);
    }

    SseStreamingService(EventBus eventBus, long timeoutMs) {
        this.eventBus = eventBus;
        this.timeoutMs = timeoutMs;
    }

    @PostConstruct
    void startHeartbeat() {
        heartbeatScheduler.scheduleAtFixedRate(
                this::sendHeartbeats,
                HEARTBEAT_INTERVAL_SECONDS,
                HEARTBEAT_INTERVAL_SECONDS,
                TimeUnit.SECONDS
        );
        log.info("SSE heartbeat scheduler started (interval={}s)", HEARTBEAT_INTERVAL_SECONDS);
    }

    @PreDestroy
    void stopHeartbeat() {
        heartbeatScheduler.shutdown();
        try {
            if (!heartbeatScheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                heartbeatScheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            heartbeatScheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }
        log.info("SSE heartbeat scheduler stopped");
    }

    /**
     * Sends a heartbeat comment to all active emitters to keep connections alive.
     * SSE comment lines (starting with ':') are ignored by EventSource clients
     * but keep the TCP connection active through proxies like CF gorouter.
     */
    private void sendHeartbeats() {
        if (activeRegistrations.isEmpty()) {
            return;
        }

        log.debug("Sending heartbeat to {} active SSE emitters", activeRegistrations.size());
        for (EmitterRegistration registration : activeRegistrations) {
            try {
                // SSE comment: a line starting with ':' — ignored by clients but keeps connection alive
                registration.emitter.send(SseEmitter.event().comment("heartbeat"));
            } catch (IOException e) {
                log.debug("Heartbeat failed for mission {} (connection likely closed): {}",
                        registration.missionId, e.getMessage());
                // Don't remove here — the emitter's onError/onCompletion callbacks will handle cleanup
            } catch (IllegalStateException e) {
                // Emitter already completed/timed out
                log.debug("Heartbeat skipped for mission {} (emitter not active)", registration.missionId);
            }
        }
    }

    /**
     * Creates an SSE emitter that streams events for the given mission.
     *
     * @param missionId the mission to stream events for
     * @return a configured {@link SseEmitter}
     */
    public SseEmitter createEmitter(String missionId) {
        SseEmitter emitter = new SseEmitter(timeoutMs);

        EventBus.Subscription subscription = eventBus.subscribe(missionId, event -> {
            sendEvent(emitter, event);
        });

        var registration = new EmitterRegistration(missionId, emitter, subscription);
        activeRegistrations.add(registration);

        // Register lifecycle callbacks
        emitter.onCompletion(() -> {
            log.debug("SSE emitter completed for mission {}", missionId);
            cleanup(registration);
        });
        emitter.onTimeout(() -> {
            log.debug("SSE emitter timed out for mission {}", missionId);
            cleanup(registration);
        });
        emitter.onError(ex -> {
            log.debug("SSE emitter error for mission {}: {}", missionId, ex.getMessage());
            cleanup(registration);
        });

        // Send initial heartbeat to confirm connection is established
        try {
            emitter.send(SseEmitter.event().comment("connected"));
        } catch (IOException e) {
            log.warn("Failed to send initial heartbeat for mission {}: {}", missionId, e.getMessage());
        }

        log.info("SSE emitter created for mission {} (timeout={}ms)", missionId, timeoutMs);
        return emitter;
    }

    /**
     * Returns the number of currently active SSE emitters.
     */
    public int activeEmitterCount() {
        return activeRegistrations.size();
    }

    private void sendEvent(SseEmitter emitter, WorldmindEvent event) {
        try {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("missionId", event.missionId());
            if (event.taskId() != null) {
                data.put("taskId", event.taskId());
            }
            data.putAll(event.payload());
            data.put("timestamp", event.timestamp().toString());

            emitter.send(SseEmitter.event()
                    .name(event.eventType())
                    .data(data));
        } catch (IOException e) {
            log.debug("Failed to send SSE event {} for mission {}: {}",
                    event.eventType(), event.missionId(), e.getMessage());
        }
    }

    private void cleanup(EmitterRegistration registration) {
        registration.subscription.unsubscribe();
        activeRegistrations.remove(registration);
        log.debug("Cleaned up SSE registration for mission {}", registration.missionId);
    }

    private record EmitterRegistration(
            String missionId,
            SseEmitter emitter,
            EventBus.Subscription subscription
    ) {}
}
