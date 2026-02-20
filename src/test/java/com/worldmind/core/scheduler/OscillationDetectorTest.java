package com.worldmind.core.scheduler;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class OscillationDetectorTest {

    private OscillationDetector detector;

    @BeforeEach
    void setUp() {
        detector = new OscillationDetector();
    }

    @Test
    @DisplayName("No oscillation with zero failures")
    void noOscillationWithZeroFailures() {
        assertFalse(detector.isOscillating("TASK-001"));
    }

    @Test
    @DisplayName("No oscillation with one failure")
    void noOscillationWithOneFailure() {
        detector.recordFailure("TASK-001", "Error A");
        assertFalse(detector.isOscillating("TASK-001"));
    }

    @Test
    @DisplayName("No oscillation with two failures")
    void noOscillationWithTwoFailures() {
        detector.recordFailure("TASK-001", "Error A");
        detector.recordFailure("TASK-001", "Error B");
        assertFalse(detector.isOscillating("TASK-001"));
    }

    @Test
    @DisplayName("Oscillation detected with A-B-A pattern")
    void oscillationDetectedWithAlternatingPattern() {
        detector.recordFailure("TASK-001", "Error A");
        detector.recordFailure("TASK-001", "Error B");
        detector.recordFailure("TASK-001", "Error A");
        assertTrue(detector.isOscillating("TASK-001"));
    }

    @Test
    @DisplayName("No oscillation with consistent errors A-A-A")
    void noOscillationWithConsistentErrors() {
        detector.recordFailure("TASK-001", "Error A");
        detector.recordFailure("TASK-001", "Error A");
        detector.recordFailure("TASK-001", "Error A");
        assertFalse(detector.isOscillating("TASK-001"));
    }

    @Test
    @DisplayName("clearHistory resets tracking")
    void clearHistoryResetsTracking() {
        detector.recordFailure("TASK-001", "Error A");
        detector.recordFailure("TASK-001", "Error B");
        detector.recordFailure("TASK-001", "Error A");
        assertTrue(detector.isOscillating("TASK-001"));

        detector.clearHistory("TASK-001");
        assertFalse(detector.isOscillating("TASK-001"));
    }

    @Test
    @DisplayName("Different tasks tracked independently")
    void differentTasksTrackedIndependently() {
        detector.recordFailure("TASK-001", "Error A");
        detector.recordFailure("TASK-001", "Error B");
        detector.recordFailure("TASK-001", "Error A");

        assertFalse(detector.isOscillating("TASK-002"));
        assertTrue(detector.isOscillating("TASK-001"));
    }
}
