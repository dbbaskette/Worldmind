package com.worldmind.core.events;

import java.io.Serializable;
import java.time.Instant;
import java.util.Map;

/**
 * An event emitted during mission execution, used for SSE streaming and CLI watch mode.
 *
 * @param eventType   event type from Spec 9.1 (e.g. "mission.created", "starblaster.opened", "directive.started")
 * @param missionId   the mission this event belongs to
 * @param directiveId the directive this event relates to (nullable for mission-level events)
 * @param payload     arbitrary key-value data associated with the event
 * @param timestamp   when the event occurred
 */
public record WorldmindEvent(
    String eventType,
    String missionId,
    String directiveId,
    Map<String, Object> payload,
    Instant timestamp
) implements Serializable {}
