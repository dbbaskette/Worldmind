package com.worldmind.starblaster;

import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;

/**
 * Temporary in-memory store for instruction text.
 *
 * <p>On Cloud Foundry, CF task commands are limited to 4096 characters.
 * Instead of embedding the instruction text in the command, the orchestrator
 * stores it here and the CF task fetches it via HTTP.
 */
@Component
public class InstructionStore {

    private final ConcurrentHashMap<String, String> instructions = new ConcurrentHashMap<>();

    public void put(String key, String instructionText) {
        instructions.put(key, instructionText);
    }

    public String get(String key) {
        return instructions.get(key);
    }

    public String remove(String key) {
        return instructions.remove(key);
    }
}
