package com.worldmind.starblaster;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;

/**
 * Temporary in-memory store for centurion task output.
 *
 * <p>CF tasks do not expose stdout/stderr via the Cloud Controller API.
 * Instead, centurion tasks POST their Goose output back to the orchestrator
 * via HTTP after completion. This store holds that output until the
 * orchestrator reads it in {@code captureOutput()}.
 */
@Component
public class OutputStore {

    private static final Logger log = LoggerFactory.getLogger(OutputStore.class);
    private static final int MAX_ENTRIES = 50;

    private final ConcurrentHashMap<String, String> outputs = new ConcurrentHashMap<>();

    public void put(String key, String output) {
        if (outputs.size() >= MAX_ENTRIES) {
            log.warn("OutputStore at capacity ({}), evicting all entries", outputs.size());
            outputs.clear();
        }
        outputs.put(key, output);
        log.debug("Stored output for {} ({} chars)", key, output.length());
    }

    public String get(String key) {
        return outputs.get(key);
    }

    public String remove(String key) {
        return outputs.remove(key);
    }

    public int size() {
        return outputs.size();
    }

    public void clear() {
        int count = outputs.size();
        outputs.clear();
        if (count > 0) {
            log.debug("Cleared {} output entries", count);
        }
    }
}
