package com.huawei.hisi.ram.chat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.huawei.hisi.config.JwtTokenProvider;
import com.huawei.hisi.ram.model.AgentEvent;
import com.huawei.hisi.ram.model.EventType;
import com.huawei.hisi.ram.repository.AgentEventRepository;
import com.huawei.hisi.ram.repository.AgentSessionRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration;
import org.springframework.boot.autoconfigure.security.servlet.SecurityFilterAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Map;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Web-layer tests for the POST /api/ram/chat/{sid}/interrupt endpoint.
 */
@WebMvcTest(controllers = RamChatController.class,
        excludeAutoConfiguration = {
                SecurityAutoConfiguration.class,
                SecurityFilterAutoConfiguration.class
        })
@DisplayName("RamChatController interrupt endpoint")
class RamChatControllerInterruptTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;

    @MockBean private AgentSessionRepository sessionRepository;
    @MockBean private AgentEventRepository eventRepository;
    @MockBean private RamChatOrchestrator orchestrator;
    @MockBean private TurnRegistry turnRegistry;
    @MockBean private RamChatWebSocketHandler wsHandler;
    @MockBean private JwtTokenProvider jwtTokenProvider;

    @Test
    @DisplayName("interrupt: active turn returns 202 and publishes turn_interrupted event")
    void interrupt_activeTurn_returns202AndPublishesEvent() throws Exception {
        String turnId = "turn-xyz";
        String partial = "已完成一半的回答";
        when(turnRegistry.interrupt(1L))
                .thenReturn(Optional.of(new TurnRegistry.InterruptResult(turnId, partial)));

        AgentEvent persisted = AgentEvent.builder()
                .id(42L)
                .sessionId(1L)
                .seq(7L)
                .type(EventType.TURN_INTERRUPTED)
                .payload("{}")
                .createdAt(1_700_000_000L)
                .build();
        when(eventRepository.append(any(AgentEvent.class))).thenReturn(persisted);

        mockMvc.perform(post("/api/ram/chat/{sid}/interrupt", 1L))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.interrupted").value(true))
                .andExpect(jsonPath("$.turnId").value(turnId))
                .andExpect(jsonPath("$.partialText").value(partial));

        verify(eventRepository, times(1)).append(any(AgentEvent.class));
        verify(wsHandler, times(1)).pushEvent(eq(1L), anyMap());
    }

    @Test
    @DisplayName("interrupt: no active turn returns 200 with interrupted=false and no side effects")
    void interrupt_noActiveTurn_returns200InterruptedFalse() throws Exception {
        when(turnRegistry.interrupt(2L)).thenReturn(Optional.empty());

        mockMvc.perform(post("/api/ram/chat/{sid}/interrupt", 2L))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.interrupted").value(false));

        verify(eventRepository, never()).append(any(AgentEvent.class));
        verify(wsHandler, never()).pushEvent(eq(2L), any(Map.class));
    }
}
