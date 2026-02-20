package com.worldmind.core.events;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link EventBus}.
 */
class EventBusTest {

    private EventBus eventBus;

    @BeforeEach
    void setUp() {
        eventBus = new EventBus();
    }

    // -- WorldmindEvent record tests ------------------------------------------

    @Nested
    @DisplayName("WorldmindEvent")
    class WorldmindEventTests {

        @Test
        @DisplayName("creates event with all fields")
        void createsEventWithAllFields() {
            Instant now = Instant.now();
            var event = new WorldmindEvent(
                    "mission.created", "M-001", "TASK-001",
                    Map.of("key", "value"), now
            );

            assertEquals("mission.created", event.eventType());
            assertEquals("M-001", event.missionId());
            assertEquals("TASK-001", event.taskId());
            assertEquals(Map.of("key", "value"), event.payload());
            assertEquals(now, event.timestamp());
        }

        @Test
        @DisplayName("allows null taskId")
        void allowsNullTaskId() {
            var event = new WorldmindEvent(
                    "mission.created", "M-001", null,
                    Map.of(), Instant.now()
            );

            assertNull(event.taskId());
        }
    }

    // -- Subscribe and publish tests ------------------------------------------

    @Nested
    @DisplayName("subscribe and publish")
    class SubscribeAndPublishTests {

        @Test
        @DisplayName("delivers event to mission subscriber")
        void deliversEventToMissionSubscriber() {
            List<WorldmindEvent> received = new ArrayList<>();
            eventBus.subscribe("M-001", received::add);

            var event = new WorldmindEvent(
                    "task.started", "M-001", "TASK-001",
                    Map.of(), Instant.now()
            );
            eventBus.publish(event);

            assertEquals(1, received.size());
            assertEquals(event, received.get(0));
        }

        @Test
        @DisplayName("does not deliver event to subscribers of a different mission")
        void doesNotDeliverToDifferentMission() {
            List<WorldmindEvent> received = new ArrayList<>();
            eventBus.subscribe("M-002", received::add);

            var event = new WorldmindEvent(
                    "task.started", "M-001", "TASK-001",
                    Map.of(), Instant.now()
            );
            eventBus.publish(event);

            assertTrue(received.isEmpty());
        }

        @Test
        @DisplayName("delivers event to multiple subscribers for same mission")
        void deliversToMultipleSubscribers() {
            List<WorldmindEvent> received1 = new ArrayList<>();
            List<WorldmindEvent> received2 = new ArrayList<>();
            eventBus.subscribe("M-001", received1::add);
            eventBus.subscribe("M-001", received2::add);

            var event = new WorldmindEvent(
                    "mission.created", "M-001", null,
                    Map.of(), Instant.now()
            );
            eventBus.publish(event);

            assertEquals(1, received1.size());
            assertEquals(1, received2.size());
        }

        @Test
        @DisplayName("delivers multiple events in order")
        void deliversMultipleEventsInOrder() {
            List<WorldmindEvent> received = new ArrayList<>();
            eventBus.subscribe("M-001", received::add);

            var event1 = new WorldmindEvent("mission.created", "M-001", null, Map.of(), Instant.now());
            var event2 = new WorldmindEvent("task.started", "M-001", "TASK-001", Map.of(), Instant.now());
            var event3 = new WorldmindEvent("task.completed", "M-001", "TASK-001", Map.of(), Instant.now());

            eventBus.publish(event1);
            eventBus.publish(event2);
            eventBus.publish(event3);

            assertEquals(3, received.size());
            assertEquals("mission.created", received.get(0).eventType());
            assertEquals("task.started", received.get(1).eventType());
            assertEquals("task.completed", received.get(2).eventType());
        }
    }

    // -- Global subscription tests --------------------------------------------

    @Nested
    @DisplayName("global subscription")
    class GlobalSubscriptionTests {

        @Test
        @DisplayName("global subscriber receives events from all missions")
        void globalSubscriberReceivesAllEvents() {
            List<WorldmindEvent> received = new ArrayList<>();
            eventBus.subscribeAll(received::add);

            var event1 = new WorldmindEvent("mission.created", "M-001", null, Map.of(), Instant.now());
            var event2 = new WorldmindEvent("mission.created", "M-002", null, Map.of(), Instant.now());

            eventBus.publish(event1);
            eventBus.publish(event2);

            assertEquals(2, received.size());
            assertEquals("M-001", received.get(0).missionId());
            assertEquals("M-002", received.get(1).missionId());
        }

        @Test
        @DisplayName("global and mission-specific subscribers both receive the event")
        void globalAndMissionSpecificBothReceive() {
            List<WorldmindEvent> globalReceived = new ArrayList<>();
            List<WorldmindEvent> missionReceived = new ArrayList<>();
            eventBus.subscribeAll(globalReceived::add);
            eventBus.subscribe("M-001", missionReceived::add);

            var event = new WorldmindEvent("task.started", "M-001", "TASK-001", Map.of(), Instant.now());
            eventBus.publish(event);

            assertEquals(1, globalReceived.size());
            assertEquals(1, missionReceived.size());
        }
    }

    // -- Unsubscribe tests ----------------------------------------------------

    @Nested
    @DisplayName("unsubscribe")
    class UnsubscribeTests {

        @Test
        @DisplayName("unsubscribing stops delivery of future events")
        void unsubscribeStopsDelivery() {
            List<WorldmindEvent> received = new ArrayList<>();
            EventBus.Subscription subscription = eventBus.subscribe("M-001", received::add);

            var event1 = new WorldmindEvent("task.started", "M-001", "TASK-001", Map.of(), Instant.now());
            eventBus.publish(event1);
            assertEquals(1, received.size());

            subscription.unsubscribe();

            var event2 = new WorldmindEvent("task.completed", "M-001", "TASK-001", Map.of(), Instant.now());
            eventBus.publish(event2);
            assertEquals(1, received.size()); // still 1, no new event
        }

        @Test
        @DisplayName("unsubscribing global subscription stops delivery")
        void unsubscribeGlobalStopsDelivery() {
            List<WorldmindEvent> received = new ArrayList<>();
            EventBus.Subscription subscription = eventBus.subscribeAll(received::add);

            var event1 = new WorldmindEvent("mission.created", "M-001", null, Map.of(), Instant.now());
            eventBus.publish(event1);
            assertEquals(1, received.size());

            subscription.unsubscribe();

            var event2 = new WorldmindEvent("mission.created", "M-002", null, Map.of(), Instant.now());
            eventBus.publish(event2);
            assertEquals(1, received.size());
        }

        @Test
        @DisplayName("unsubscribing one subscriber does not affect others")
        void unsubscribeDoesNotAffectOthers() {
            List<WorldmindEvent> received1 = new ArrayList<>();
            List<WorldmindEvent> received2 = new ArrayList<>();
            EventBus.Subscription sub1 = eventBus.subscribe("M-001", received1::add);
            eventBus.subscribe("M-001", received2::add);

            sub1.unsubscribe();

            var event = new WorldmindEvent("task.started", "M-001", "TASK-001", Map.of(), Instant.now());
            eventBus.publish(event);

            assertTrue(received1.isEmpty());
            assertEquals(1, received2.size());
        }
    }

    // -- Concurrency tests ----------------------------------------------------

    @Nested
    @DisplayName("concurrency")
    class ConcurrencyTests {

        @Test
        @DisplayName("handles concurrent publishes safely")
        void handlesConcurrentPublishes() throws InterruptedException {
            CopyOnWriteArrayList<WorldmindEvent> received = new CopyOnWriteArrayList<>();
            eventBus.subscribe("M-001", received::add);

            int threadCount = 10;
            int eventsPerThread = 100;
            CountDownLatch latch = new CountDownLatch(threadCount);

            for (int t = 0; t < threadCount; t++) {
                final int threadId = t;
                new Thread(() -> {
                    for (int i = 0; i < eventsPerThread; i++) {
                        var event = new WorldmindEvent(
                                "event." + threadId + "." + i, "M-001", null,
                                Map.of(), Instant.now()
                        );
                        eventBus.publish(event);
                    }
                    latch.countDown();
                }).start();
            }

            assertTrue(latch.await(10, TimeUnit.SECONDS));
            assertEquals(threadCount * eventsPerThread, received.size());
        }
    }

    // -- Edge cases -----------------------------------------------------------

    @Nested
    @DisplayName("edge cases")
    class EdgeCaseTests {

        @Test
        @DisplayName("publishing with no subscribers does not throw")
        void publishWithNoSubscribersDoesNotThrow() {
            var event = new WorldmindEvent("mission.created", "M-001", null, Map.of(), Instant.now());
            assertDoesNotThrow(() -> eventBus.publish(event));
        }

        @Test
        @DisplayName("subscriber exception does not prevent delivery to other subscribers")
        void subscriberExceptionDoesNotPreventOthers() {
            List<WorldmindEvent> received = new ArrayList<>();

            eventBus.subscribe("M-001", e -> {
                throw new RuntimeException("boom");
            });
            eventBus.subscribe("M-001", received::add);

            var event = new WorldmindEvent("task.started", "M-001", "TASK-001", Map.of(), Instant.now());
            eventBus.publish(event);

            assertEquals(1, received.size());
        }
    }
}
