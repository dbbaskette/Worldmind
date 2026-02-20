package com.worldmind.core.logging;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import static org.junit.jupiter.api.Assertions.*;

class MdcContextTest {

    @AfterEach
    void tearDown() {
        MdcContext.clear();
    }

    @Test
    @DisplayName("setMission puts missionId in MDC")
    void setMission() {
        MdcContext.setMission("WMND-2026-0001");
        assertEquals("WMND-2026-0001", MDC.get("missionId"));
    }

    @Test
    @DisplayName("setTask puts missionId, taskId, and agentType in MDC")
    void setTask() {
        MdcContext.setTask("WMND-2026-0001", "TASK-001", "CODER");
        assertEquals("WMND-2026-0001", MDC.get("missionId"));
        assertEquals("TASK-001", MDC.get("taskId"));
        assertEquals("CODER", MDC.get("agentType"));
    }

    @Test
    @DisplayName("setWave puts missionId and waveNumber in MDC")
    void setWave() {
        MdcContext.setWave("WMND-2026-0001", 3);
        assertEquals("WMND-2026-0001", MDC.get("missionId"));
        assertEquals("3", MDC.get("waveNumber"));
    }

    @Test
    @DisplayName("clear removes all worldmind MDC keys")
    void clear() {
        MdcContext.setTask("WMND-2026-0001", "TASK-001", "CODER");
        MdcContext.setWave("WMND-2026-0001", 2);
        MdcContext.clear();
        assertNull(MDC.get("missionId"));
        assertNull(MDC.get("taskId"));
        assertNull(MDC.get("agentType"));
        assertNull(MDC.get("waveNumber"));
    }
}
