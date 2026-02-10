package com.worldmind.dispatch.api;

import com.worldmind.core.events.EventBus;
import com.worldmind.core.events.WorldmindEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Bridges {@link EventBus} subscriptions to {@link SseEmitter} instances for SSE streaming.
 * <p>
 * When a client connects to the SSE endpoint, this service creates an emitter, subscribes
 * to the EventBus for that mission, and forwards events as SSE data frames. Handles emitter
 * lifecycle (completion, timeout, error) by cleaning up subscriptions.
 */
@Service
public class SseStreamingService {

    private static final Logger log = LoggerFactory.getLogger(SseStreamingService.class);

    /** Default emitter timeout: 30 minutes (for long-running missions). */
    private static final long DEFAULT_TIMEOUT_MS = 30 * 60 * 1000L;

    private final EventBus eventBus;
    private final long timeoutMs;

    /** Tracks active emitter registrations for monitoring/cleanup. */
    private final CopyOnWriteArrayList<EmitterRegistration> activeRegistrations = new CopyOnWriteArrayList<>();

    @Autowired
    public SseStreamingService(EventBus eventBus) {
        this(eventBus, DEFAULT_TIMEOUT_MS);
    }

    SseStreamingService(EventBus eventBus, long timeoutMs) {
        this.eventBus = eventBus;
        this.timeoutMs = timeoutMs;
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
            if (event.directiveId() != null) {
                data.put("directiveId", event.directiveId());
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
