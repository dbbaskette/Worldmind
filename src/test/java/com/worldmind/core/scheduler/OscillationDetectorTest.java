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
        assertFalse(detector.isOscillating("DIR-001"));
    }

    @Test
    @DisplayName("No oscillation with one failure")
    void noOscillationWithOneFailure() {
        detector.recordFailure("DIR-001", "Error A");
        assertFalse(detector.isOscillating("DIR-001"));
    }

    @Test
    @DisplayName("No oscillation with two failures")
    void noOscillationWithTwoFailures() {
        detector.recordFailure("DIR-001", "Error A");
        detector.recordFailure("DIR-001", "Error B");
        assertFalse(detector.isOscillating("DIR-001"));
    }

    @Test
    @DisplayName("Oscillation detected with A-B-A pattern")
    void oscillationDetectedWithAlternatingPattern() {
        detector.recordFailure("DIR-001", "Error A");
        detector.recordFailure("DIR-001", "Error B");
        detector.recordFailure("DIR-001", "Error A");
        assertTrue(detector.isOscillating("DIR-001"));
    }

    @Test
    @DisplayName("No oscillation with consistent errors A-A-A")
    void noOscillationWithConsistentErrors() {
        detector.recordFailure("DIR-001", "Error A");
        detector.recordFailure("DIR-001", "Error A");
        detector.recordFailure("DIR-001", "Error A");
        assertFalse(detector.isOscillating("DIR-001"));
    }

    @Test
    @DisplayName("clearHistory resets tracking")
    void clearHistoryResetsTracking() {
        detector.recordFailure("DIR-001", "Error A");
        detector.recordFailure("DIR-001", "Error B");
        detector.recordFailure("DIR-001", "Error A");
        assertTrue(detector.isOscillating("DIR-001"));

        detector.clearHistory("DIR-001");
        assertFalse(detector.isOscillating("DIR-001"));
    }

    @Test
    @DisplayName("Different directives tracked independently")
    void differentDirectivesTrackedIndependently() {
        detector.recordFailure("DIR-001", "Error A");
        detector.recordFailure("DIR-001", "Error B");
        detector.recordFailure("DIR-001", "Error A");

        assertFalse(detector.isOscillating("DIR-002"));
        assertTrue(detector.isOscillating("DIR-001"));
    }
}
