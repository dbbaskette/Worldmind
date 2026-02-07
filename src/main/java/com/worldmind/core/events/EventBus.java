package com.worldmind.core.events;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * In-memory pub/sub event bus for mission execution events.
 * <p>
 * Supports per-mission subscriptions and global subscriptions that receive all events.
 * Thread-safe for concurrent publish and subscribe operations.
 */
@Service
public class EventBus {

    private static final Logger log = LoggerFactory.getLogger(EventBus.class);

    /** Per-mission subscribers keyed by missionId. */
    private final ConcurrentHashMap<String, CopyOnWriteArrayList<Consumer<WorldmindEvent>>> missionSubscribers =
            new ConcurrentHashMap<>();

    /** Global subscribers that receive events from all missions. */
    private final CopyOnWriteArrayList<Consumer<WorldmindEvent>> globalSubscribers =
            new CopyOnWriteArrayList<>();

    /**
     * Publish an event to all matching subscribers (mission-specific and global).
     *
     * @param event the event to publish
     */
    public void publish(WorldmindEvent event) {
        log.debug("Publishing event: {} for mission {}", event.eventType(), event.missionId());

        // Notify mission-specific subscribers
        List<Consumer<WorldmindEvent>> missionSubs = missionSubscribers.get(event.missionId());
        if (missionSubs != null) {
            for (Consumer<WorldmindEvent> subscriber : missionSubs) {
                deliverSafely(subscriber, event);
            }
        }

        // Notify global subscribers
        for (Consumer<WorldmindEvent> subscriber : globalSubscribers) {
            deliverSafely(subscriber, event);
        }
    }

    /**
     * Subscribe to events for a specific mission.
     *
     * @param missionId the mission to subscribe to
     * @param consumer  callback invoked for each event
     * @return a {@link Subscription} handle to unsubscribe later
     */
    public Subscription subscribe(String missionId, Consumer<WorldmindEvent> consumer) {
        missionSubscribers.computeIfAbsent(missionId, k -> new CopyOnWriteArrayList<>()).add(consumer);
        log.debug("Subscribed to mission {}", missionId);
        return () -> {
            CopyOnWriteArrayList<Consumer<WorldmindEvent>> subs = missionSubscribers.get(missionId);
            if (subs != null) {
                subs.remove(consumer);
            }
        };
    }

    /**
     * Subscribe to events from all missions (global subscription).
     *
     * @param consumer callback invoked for each event regardless of mission
     * @return a {@link Subscription} handle to unsubscribe later
     */
    public Subscription subscribeAll(Consumer<WorldmindEvent> consumer) {
        globalSubscribers.add(consumer);
        log.debug("Subscribed to all events (global)");
        return () -> globalSubscribers.remove(consumer);
    }

    /**
     * Handle for cancelling a subscription.
     */
    @FunctionalInterface
    public interface Subscription {
        void unsubscribe();
    }

    private void deliverSafely(Consumer<WorldmindEvent> subscriber, WorldmindEvent event) {
        try {
            subscriber.accept(event);
        } catch (Exception e) {
            log.warn("Subscriber threw exception processing event {}: {}",
                    event.eventType(), e.getMessage(), e);
        }
    }
}
