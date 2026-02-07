package com.worldmind.core.logging;

import org.slf4j.MDC;

/**
 * Utility for managing Worldmind-specific MDC keys for structured logging.
 */
public final class MdcContext {

    private MdcContext() {}

    public static void setMission(String missionId) {
        MDC.put("missionId", missionId);
    }

    public static void setDirective(String missionId, String directiveId, String centurionType) {
        MDC.put("missionId", missionId);
        MDC.put("directiveId", directiveId);
        MDC.put("centurionType", centurionType);
    }

    public static void setWave(String missionId, int waveNumber) {
        MDC.put("missionId", missionId);
        MDC.put("waveNumber", String.valueOf(waveNumber));
    }

    public static void clear() {
        MDC.remove("missionId");
        MDC.remove("directiveId");
        MDC.remove("centurionType");
        MDC.remove("waveNumber");
    }
}
