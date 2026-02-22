package com.worldmind.dispatch.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.worldmind.core.engine.MissionEngine;
import com.worldmind.core.model.*;
import com.worldmind.core.persistence.JdbcCheckpointSaver;
import com.worldmind.core.state.WorldmindState;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Map;

import static org.hamcrest.Matchers.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(MissionController.class)
@TestPropertySource(properties = "spring.main.web-application-type=servlet")
class MissionControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private MissionEngine missionEngine;

    @MockitoBean
    private JdbcCheckpointSaver checkpointSaver;

    @MockitoBean
    private SseStreamingService sseStreamingService;

    @MockitoBean
    private com.worldmind.core.scheduler.OscillationDetector oscillationDetector;

    @MockitoBean
    private com.worldmind.core.events.EventBus eventBus;

    @MockitoBean
    private com.worldmind.sandbox.InstructionStore instructionStore;

    // Optional CF dependencies — provided as mocks so Spring can inject them.
    // In production these are null when CF is not active.
    @MockitoBean(enforceOverride = false)
    private com.worldmind.sandbox.cf.GitWorkspaceManager gitWorkspaceManager;

    @MockitoBean(enforceOverride = false)
    private com.worldmind.sandbox.cf.CloudFoundryProperties cfProperties;

    // ── POST /api/v1/missions ────────────────────────────────────────

    @Test
    @DisplayName("POST /missions returns 202 Accepted with mission_id")
    void submitMission() throws Exception {
        when(missionEngine.generateMissionId()).thenReturn("WMND-2026-0001");

        String body = objectMapper.writeValueAsString(
                new MissionRequest("Add a REST endpoint", "FULL_AUTO", "/tmp/project", null, null, null, null, null, null));

        mockMvc.perform(post("/api/v1/missions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.mission_id").value("WMND-2026-0001"))
                .andExpect(jsonPath("$.status").value("CLASSIFYING"));
    }

    @Test
    @DisplayName("POST /missions with invalid mode returns 400")
    void submitMissionBadMode() throws Exception {
        String body = """
                {"request":"Do something","mode":"INVALID_MODE","project_path":"/tmp"}
                """;

        mockMvc.perform(post("/api/v1/missions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error", containsString("Invalid mode")));
    }

    @Test
    @DisplayName("POST /missions with blank request returns 400")
    void submitMissionBlankRequest() throws Exception {
        String body = """
                {"request":"","mode":"FULL_AUTO","project_path":"/tmp"}
                """;

        mockMvc.perform(post("/api/v1/missions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error", containsString("required")));
    }

    @Test
    @DisplayName("POST /missions with null mode defaults to APPROVE_PLAN")
    void submitMissionDefaultMode() throws Exception {
        when(missionEngine.generateMissionId()).thenReturn("WMND-2026-0002");

        String body = """
                {"request":"Add logging","project_path":"/tmp"}
                """;

        mockMvc.perform(post("/api/v1/missions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.mission_id").value("WMND-2026-0002"));
    }

    // ── GET /api/v1/missions/{id} ────────────────────────────────────

    @Test
    @DisplayName("GET /missions/{id} returns 404 for unknown mission")
    void getMissionNotFound() throws Exception {
        mockMvc.perform(get("/api/v1/missions/WMND-2026-9999"))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("GET /missions/{id} returns mission after submission")
    void getMissionAfterSubmit() throws Exception {
        when(missionEngine.generateMissionId()).thenReturn("WMND-2026-0003");

        String body = objectMapper.writeValueAsString(
                new MissionRequest("Build feature X", "APPROVE_PLAN", "/tmp/project", null, null, null, null, null, null));

        // Submit the mission first
        mockMvc.perform(post("/api/v1/missions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isAccepted());

        // Retrieve it
        mockMvc.perform(get("/api/v1/missions/WMND-2026-0003"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mission_id").value("WMND-2026-0003"))
                .andExpect(jsonPath("$.request").value("Build feature X"))
                .andExpect(jsonPath("$.interaction_mode").value("APPROVE_PLAN"));
    }

    // ── GET /api/v1/missions/{id}/events ─────────────────────────────

    @Test
    @DisplayName("GET /missions/{id}/events returns 404 for unknown mission")
    void sseNotFound() throws Exception {
        mockMvc.perform(get("/api/v1/missions/WMND-FAKE/events"))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("GET /missions/{id}/events returns SSE emitter for known mission")
    void sseReturnsEmitter() throws Exception {
        when(missionEngine.generateMissionId()).thenReturn("WMND-2026-0004");
        when(sseStreamingService.createEmitter("WMND-2026-0004"))
                .thenReturn(new SseEmitter(0L));

        String body = objectMapper.writeValueAsString(
                new MissionRequest("Test SSE", "FULL_AUTO", "/tmp/project", null, null, null, null, null, null));

        mockMvc.perform(post("/api/v1/missions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isAccepted());

        mockMvc.perform(get("/api/v1/missions/WMND-2026-0004/events"))
                .andExpect(status().isOk());
    }

    // ── POST /api/v1/missions/{id}/approve ───────────────────────────

    @Test
    @DisplayName("POST /missions/{id}/approve returns 404 for unknown mission")
    void approveNotFound() throws Exception {
        mockMvc.perform(post("/api/v1/missions/WMND-FAKE/approve"))
                .andExpect(status().isNotFound());
    }

    // ── POST /api/v1/missions/{id}/cancel ────────────────────────────

    @Test
    @DisplayName("POST /missions/{id}/cancel returns 404 for unknown mission")
    void cancelNotFound() throws Exception {
        mockMvc.perform(post("/api/v1/missions/WMND-FAKE/cancel"))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("POST /missions/{id}/cancel cancels a submitted mission")
    void cancelMission() throws Exception {
        when(missionEngine.generateMissionId()).thenReturn("WMND-2026-0005");

        String body = objectMapper.writeValueAsString(
                new MissionRequest("Cancel me", "FULL_AUTO", "/tmp/project", null, null, null, null, null, null));

        mockMvc.perform(post("/api/v1/missions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isAccepted());

        mockMvc.perform(post("/api/v1/missions/WMND-2026-0005/cancel"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));
    }

    // ── GET /api/v1/missions/{id}/timeline ───────────────────────────

    @Test
    @DisplayName("GET /missions/{id}/timeline returns 404 for unknown mission")
    void timelineNotFound() throws Exception {
        mockMvc.perform(get("/api/v1/missions/WMND-FAKE/timeline"))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("GET /missions/{id}/timeline returns empty list when no checkpoints")
    void timelineEmpty() throws Exception {
        when(missionEngine.generateMissionId()).thenReturn("WMND-2026-0006");
        when(checkpointSaver.list(any())).thenReturn(List.of());

        String body = objectMapper.writeValueAsString(
                new MissionRequest("Timeline test", "FULL_AUTO", "/tmp/project", null, null, null, null, null, null));

        mockMvc.perform(post("/api/v1/missions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isAccepted());

        mockMvc.perform(get("/api/v1/missions/WMND-2026-0006/timeline"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(0)));
    }

    // ── GET /api/v1/missions/{id}/tasks/{did} ───────────────────

    @Test
    @DisplayName("GET /missions/{id}/tasks/{did} returns 404 for unknown mission")
    void taskNotFound() throws Exception {
        mockMvc.perform(get("/api/v1/missions/WMND-FAKE/tasks/TASK-001"))
                .andExpect(status().isNotFound());
    }

    // ── POST /api/v1/missions/{id}/edit ──────────────────────────────

    @Test
    @DisplayName("POST /missions/{id}/edit returns 404 for unknown mission")
    void editNotFound() throws Exception {
        mockMvc.perform(post("/api/v1/missions/WMND-FAKE/edit")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isNotFound());
    }
}
