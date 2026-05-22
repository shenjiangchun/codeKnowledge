package com.huawei.hisi.ram.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.huawei.hisi.ram.mcp.McpResponse;
import com.huawei.hisi.ram.mcp.RamMcpServer;
import com.huawei.hisi.ram.model.AgentEvent;
import com.huawei.hisi.ram.model.AgentSession;
import com.huawei.hisi.ram.model.EventType;
import com.huawei.hisi.ram.model.SessionStatus;
import com.huawei.hisi.ram.repository.AgentEventRepository;
import com.huawei.hisi.ram.repository.AgentSessionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
@DisplayName("RamController REST endpoints")
class RamControllerTest {

    @Mock
    private RamMcpServer ramMcpServer;
    @Mock
    private AgentEventRepository eventRepository;
    @Mock
    private AgentSessionRepository sessionRepository;

    private ObjectMapper objectMapper;
    private RamController controller;
    private MockMvc mockMvc;
    private ScheduledExecutorService scheduler;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        // Synchronous executor so the async call observed within the test thread.
        Executor sameThread = Runnable::run;
        scheduler = Executors.newSingleThreadScheduledExecutor();
        controller = new RamController(ramMcpServer, eventRepository, sessionRepository,
                objectMapper, sameThread, scheduler);
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    @DisplayName("POST /sessions returns a UUID handle and triggers analyze_requirement")
    void startSession_dispatchesAnalyze() throws Exception {
        when(ramMcpServer.invoke(eq("analyze_requirement"), anyMap()))
                .thenReturn(McpResponse.ok(Map.of("session_id", 42L, "status", "DONE")));

        String body = "{\"rawInput\":\"add login\",\"projectPath\":\"/tmp/proj\",\"userId\":\"alice\"}";
        var result = mockMvc.perform(post("/api/ram/sessions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.sessionId").exists())
                .andReturn();

        // Async dispatch should have called analyze_requirement.
        ArgumentCaptor<Map<String, Object>> argsCap = ArgumentCaptor.forClass(Map.class);
        verify(ramMcpServer, timeout(2000).times(1))
                .invoke(eq("analyze_requirement"), argsCap.capture());
        assertThat(argsCap.getValue())
                .containsEntry("raw_input", "add login")
                .containsEntry("user_id", "alice")
                .containsEntry("mode", "interactive")
                .containsEntry("project_path", "/tmp/proj");

        String json = result.getResponse().getContentAsString();
        assertThat(json).contains("sessionId");
    }

    @Test
    @DisplayName("POST /sessions/{sid}/clarify forwards answers and reports nextSeq")
    void clarify_delegatesToServer() throws Exception {
        String handle = UUID.randomUUID().toString();
        controller.registerSessionMapping(handle, 7L);
        when(ramMcpServer.invoke(eq("submit_clarification"), anyMap()))
                .thenReturn(McpResponse.ok(Map.of("session_id", 7L, "status", "DONE")));
        when(eventRepository.findMaxSeq(7L)).thenReturn(11L);

        String body = "{\"answers\":{\"q1\":\"yes\"}}";
        mockMvc.perform(post("/api/ram/sessions/" + handle + "/clarify")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.accepted").value(true))
                .andExpect(jsonPath("$.data.nextSeq").value(11));

        ArgumentCaptor<Map<String, Object>> argsCap = ArgumentCaptor.forClass(Map.class);
        verify(ramMcpServer).invoke(eq("submit_clarification"), argsCap.capture());
        assertThat(argsCap.getValue()).containsEntry("session_id", 7L);
        assertThat(argsCap.getValue().get("answers")).isInstanceOf(Map.class);
    }

    @Test
    @DisplayName("POST /sessions/{sid}/resume returns resumed=true")
    void resume_delegatesToServer() throws Exception {
        String handle = UUID.randomUUID().toString();
        controller.registerSessionMapping(handle, 9L);
        when(ramMcpServer.invoke(eq("resume_session"), anyMap()))
                .thenReturn(McpResponse.ok(Map.of("session_id", 9L, "status", "DONE")));

        mockMvc.perform(post("/api/ram/sessions/" + handle + "/resume"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.resumed").value(true));

        verify(ramMcpServer).invoke(eq("resume_session"), anyMap());
    }

    @Test
    @DisplayName("POST /sessions/{sid}/abort appends ERROR event and marks session ABORTED")
    void abort_marksSessionAborted() throws Exception {
        String handle = UUID.randomUUID().toString();
        controller.registerSessionMapping(handle, 5L);
        AgentEvent appended = AgentEvent.builder()
                .id(1L).sessionId(5L).seq(1L).type(EventType.ERROR)
                .idempotencyKey("abort-5-1").createdAt(0L).build();
        when(eventRepository.append(any(AgentEvent.class))).thenReturn(appended);

        mockMvc.perform(post("/api/ram/sessions/" + handle + "/abort"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.aborted").value(true));

        ArgumentCaptor<AgentEvent> evCap = ArgumentCaptor.forClass(AgentEvent.class);
        verify(eventRepository).append(evCap.capture());
        assertThat(evCap.getValue().getType()).isEqualTo(EventType.ERROR);
        assertThat(evCap.getValue().getPayload()).contains("RUN_ABORTED");
        verify(sessionRepository).updateStatus(5L, SessionStatus.ABORTED);
    }

    @Test
    @DisplayName("POST /sessions/{unknown}/clarify returns 200 with error envelope when handle missing")
    void clarify_unknownHandle_returnsErrorEnvelope() throws Exception {
        mockMvc.perform(post("/api/ram/sessions/missing-handle/clarify")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"answers\":{}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(404))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("session not found")));
    }
}
