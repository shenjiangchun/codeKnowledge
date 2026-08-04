package com.huawei.hisi.ram.chat;

import com.huawei.hisi.ram.model.AgentSession;
import com.huawei.hisi.ram.model.SessionType;
import com.huawei.hisi.ram.repository.AgentEventRepository;
import com.huawei.hisi.ram.repository.AgentSessionRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration;
import org.springframework.boot.autoconfigure.security.servlet.SecurityFilterAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Optional;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Regression tests for RamChatController after the UUID-safe String sid fix.
 *
 * <p>All session endpoints now accept String {@code sid}. Methods returning
 * {@code ApiResponse} use HTTP 200 + JSON error for invalid input.
 * Methods returning {@code ResponseEntity} use real HTTP error codes.
 */
@WebMvcTest(controllers = RamChatController.class,
        excludeAutoConfiguration = {
                SecurityAutoConfiguration.class,
                SecurityFilterAutoConfiguration.class
        })
@DisplayName("RamChatController String sid regression")
class RamChatControllerSidRegressionTest {

    @Autowired private MockMvc mockMvc;

    @MockBean private AgentSessionRepository sessionRepository;
    @MockBean private AgentEventRepository eventRepository;
    @MockBean private RamChatOrchestrator orchestrator;
    @MockBean private TurnRegistry turnRegistry;
    @MockBean private RamChatWebSocketHandler wsHandler;
    @MockBean private com.huawei.hisi.config.JwtTokenProvider jwtTokenProvider;

    // ── Long IDs (existing format) — return 200 ──

    @Test
    @DisplayName("GET events with Long session ID returns 200")
    void getEvents_withLongSid_returns200() throws Exception {
        when(eventRepository.findBySessionId(42L)).thenReturn(List.of());

        mockMvc.perform(get("/api/ram/chat/{sid}/events", "42"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
    }

    @Test
    @DisplayName("POST messages with Long session ID returns 200")
    void sendMessage_withLongSid_returns200() throws Exception {
        AgentSession session = AgentSession.newRunning("ram-chat", SessionType.STATUS);
        session.setProjectPaths("[\"C:\\\\test\"]");
        when(sessionRepository.findById(42L)).thenReturn(Optional.of(session));
        when(orchestrator.startTurnAsync(42L, "hello", List.of("C:\\test")))
                .thenReturn("turn-123");

        mockMvc.perform(post("/api/ram/chat/{sid}/messages", "42")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\":\"hello\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
    }

    @Test
    @DisplayName("DELETE with Long session ID returns 200")
    void deleteSession_withLongSid_returns200() throws Exception {
        mockMvc.perform(delete("/api/ram/chat/{sid}", "42"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
    }

    @Test
    @DisplayName("PATCH title with Long session ID returns 200")
    void renameSession_withLongSid_returns200() throws Exception {
        AgentSession session = AgentSession.newRunning("ram-chat", SessionType.STATUS);
        when(sessionRepository.findById(42L)).thenReturn(Optional.of(session));

        mockMvc.perform(patch("/api/ram/chat/{sid}/title", "42")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"New Title\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
    }

    // ── Non-numeric IDs — ApiResponse methods return HTTP 200 + error in JSON ──

    @Test
    @DisplayName("GET events with non-numeric ID: HTTP 200 + error in body (ApiResponse)")
    void getEvents_withNonNumericSid_returns200WithError() throws Exception {
        mockMvc.perform(get("/api/ram/chat/{sid}/events", "abc-not-a-number"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value("invalid session id: abc-not-a-number"));
    }

    @Test
    @DisplayName("POST messages with non-numeric ID: HTTP 200 + error in body")
    void sendMessage_withNonNumericSid_returns200WithError() throws Exception {
        mockMvc.perform(post("/api/ram/chat/{sid}/messages", "abc-not-a-number")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\":\"hello\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").isString());
    }

    @Test
    @DisplayName("DELETE with non-numeric ID: HTTP 200 + error in body")
    void deleteSession_withNonNumericSid_returns200WithError() throws Exception {
        mockMvc.perform(delete("/api/ram/chat/{sid}", "abc-not-a-number"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400));
    }

    @Test
    @DisplayName("PATCH title with non-numeric ID: HTTP 200 + error in body")
    void renameSession_withNonNumericSid_returns200WithError() throws Exception {
        mockMvc.perform(patch("/api/ram/chat/{sid}/title", "abc-not-a-number")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"New Title\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400));
    }

    // ── Non-numeric IDs — ResponseEntity methods return real HTTP 400 ──

    @Test
    @DisplayName("POST inject with non-numeric ID returns 400 (ResponseEntity)")
    void inject_withNonNumericSid_returns400() throws Exception {
        mockMvc.perform(post("/api/ram/chat/{sid}/inject", "abc-not-a-number")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"new message\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("POST interrupt with non-numeric ID returns 400 (ResponseEntity)")
    void interrupt_withNonNumericSid_returns400() throws Exception {
        mockMvc.perform(post("/api/ram/chat/{sid}/interrupt", "abc-not-a-number"))
                .andExpect(status().isBadRequest());
    }

    // ── Edge cases ──

    @Test
    @DisplayName("blank session ID returns 200 + error for ApiResponse endpoints")
    void blankSid_returns200WithError() throws Exception {
        mockMvc.perform(get("/api/ram/chat/{sid}/events", " "))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400));
    }

    @Test
    @DisplayName("missing text field in messages returns 200 + error")
    void sendMessage_missingText_returns200WithError() throws Exception {
        mockMvc.perform(post("/api/ram/chat/{sid}/messages", "1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400));
    }

    @Test
    @DisplayName("empty text field in messages returns 200 + error")
    void sendMessage_emptyText_returns200WithError() throws Exception {
        mockMvc.perform(post("/api/ram/chat/{sid}/messages", "1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\":\"\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400));
    }
}
