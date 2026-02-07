package com.worldmind.core.scheduler;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Detects oscillating failure patterns where a directive alternates
 * between two distinct errors across retries (A-B-A pattern).
 */
@Service
public class OscillationDetector {

    private static final Logger log = LoggerFactory.getLogger(OscillationDetector.class);

    private final ConcurrentHashMap<String, List<String>> errorHistory = new ConcurrentHashMap<>();

    public void recordFailure(String directiveId, String errorMessage) {
        errorHistory.computeIfAbsent(directiveId, k -> new ArrayList<>()).add(errorMessage);
    }

    public boolean isOscillating(String directiveId) {
        var history = errorHistory.get(directiveId);
        if (history == null || history.size() < 3) {
            return false;
        }
        // Check if error[N] matches error[N-2] but differs from error[N-1]
        for (int i = 2; i < history.size(); i++) {
            String current = history.get(i);
            String twoBack = history.get(i - 2);
            String oneBack = history.get(i - 1);
            if (current.equals(twoBack) && !current.equals(oneBack)) {
                return true;
            }
        }
        return false;
    }

    public void clearHistory(String directiveId) {
        errorHistory.remove(directiveId);
    }
}
