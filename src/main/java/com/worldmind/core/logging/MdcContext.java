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

    public static void setTask(String missionId, String taskId, String agentType) {
        MDC.put("missionId", missionId);
        MDC.put("taskId", taskId);
        MDC.put("agentType", agentType);
    }

    public static void setWave(String missionId, int waveNumber) {
        MDC.put("missionId", missionId);
        MDC.put("waveNumber", String.valueOf(waveNumber));
    }

    public static void clear() {
        MDC.remove("missionId");
        MDC.remove("taskId");
        MDC.remove("agentType");
        MDC.remove("waveNumber");
    }
}
