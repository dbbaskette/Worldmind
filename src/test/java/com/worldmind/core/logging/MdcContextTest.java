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
    @DisplayName("setDirective puts missionId, directiveId, and centurionType in MDC")
    void setDirective() {
        MdcContext.setDirective("WMND-2026-0001", "DIR-001", "FORGE");
        assertEquals("WMND-2026-0001", MDC.get("missionId"));
        assertEquals("DIR-001", MDC.get("directiveId"));
        assertEquals("FORGE", MDC.get("centurionType"));
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
        MdcContext.setDirective("WMND-2026-0001", "DIR-001", "FORGE");
        MdcContext.setWave("WMND-2026-0001", 2);
        MdcContext.clear();
        assertNull(MDC.get("missionId"));
        assertNull(MDC.get("directiveId"));
        assertNull(MDC.get("centurionType"));
        assertNull(MDC.get("waveNumber"));
    }
}
