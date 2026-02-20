package com.worldmind.sandbox;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;

/**
 * Temporary in-memory store for instruction text.
 *
 * <p>On Cloud Foundry, CF task commands are limited to 4096 characters.
 * Instead of embedding the instruction text in the command, the orchestrator
 * stores it here and the CF task fetches it via HTTP.
 *
 * <p>Eviction: entries are removed after the task fetches them or on teardown.
 * A hard cap of {@link #MAX_ENTRIES} prevents unbounded growth if cleanup fails.
 */
@Component
public class InstructionStore {

    private static final Logger log = LoggerFactory.getLogger(InstructionStore.class);
    private static final int MAX_ENTRIES = 50;

    private final ConcurrentHashMap<String, String> instructions = new ConcurrentHashMap<>();

    public void put(String key, String instructionText) {
        if (instructions.size() >= MAX_ENTRIES) {
            // Evict oldest entries (ConcurrentHashMap has no order, just clear half)
            log.warn("InstructionStore at capacity ({}), evicting all entries", instructions.size());
            instructions.clear();
        }
        instructions.put(key, instructionText);
    }

    public String get(String key) {
        return instructions.get(key);
    }

    /** Removes and returns the instruction. Called during teardown. */
    public String remove(String key) {
        return instructions.remove(key);
    }

    /** Removes the instruction after the task has fetched it (one-time use). */
    public String consumeAndRemove(String key) {
        return instructions.remove(key);
    }

    public int size() {
        return instructions.size();
    }

    /** Clears all entries. Used on mission completion to prevent leaks. */
    public void clear() {
        int count = instructions.size();
        instructions.clear();
        if (count > 0) {
            log.debug("Cleared {} instruction entries", count);
        }
    }
}
