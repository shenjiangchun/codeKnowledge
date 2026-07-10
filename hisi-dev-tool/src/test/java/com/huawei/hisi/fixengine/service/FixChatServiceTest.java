package com.huawei.hisi.fixengine.service;

import com.huawei.hisi.fixengine.agent.FixAgent;
import com.huawei.hisi.fixengine.model.FixSession;
import com.huawei.hisi.fixengine.repository.FixSessionRepository;
import com.huawei.hisi.ram.model.AgentEvent;
import com.huawei.hisi.ram.model.EventType;
import com.huawei.hisi.ram.repository.AgentEventRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("FixChatService")
class FixChatServiceTest {

    @Mock
    private FixSessionRepository fixSessionRepository;

    @Mock
    private AgentEventRepository agentEventRepository;

    @Mock
    private FixAgent fixAgent;

    private FixChatService fixChatService;

    @BeforeEach
    void setUp() {
        fixChatService = new FixChatService(fixSessionRepository, agentEventRepository, fixAgent);
    }

    @Test
    @DisplayName("chat persists USER_MSG then calls handleFollowUp then persists ASSISTANT_DELTA")
    void chat_happyPath_persistsUserAndAssistantEvents() {
        String sessionId = "fix-123";
        long chatSessionId = 999L;
        FixSession session = FixSession.builder()
            .id(sessionId)
            .chatSessionId(String.valueOf(chatSessionId))
            .build();
        when(fixSessionRepository.findById(sessionId)).thenReturn(Optional.of(session));
        when(agentEventRepository.append(any())).thenAnswer(inv -> inv.getArgument(0));
        when(fixAgent.handleFollowUp(eq(chatSessionId), eq("what's the status?")))
            .thenReturn("step 4 running");

        String reply = fixChatService.chat(sessionId, "what's the status?");

        assertThat(reply).isEqualTo("step 4 running");

        ArgumentCaptor<AgentEvent> captor = ArgumentCaptor.forClass(AgentEvent.class);
        verify(agentEventRepository, times(2)).append(captor.capture());
        List<AgentEvent> appended = captor.getAllValues();
        assertThat(appended.get(0).getType()).isEqualTo(EventType.USER_MSG);
        assertThat(appended.get(1).getType()).isEqualTo(EventType.ASSISTANT_DELTA);
        assertThat(appended.get(0).getSessionId()).isEqualTo(chatSessionId);
        assertThat(appended.get(1).getSessionId()).isEqualTo(chatSessionId);
        assertThat(appended.get(0).getPayload()).contains("what's the status?");
        assertThat(appended.get(1).getPayload()).contains("step 4 running");
    }

    @Test
    @DisplayName("chat throws when FixSession not found")
    void chat_sessionNotFound_throws() {
        when(fixSessionRepository.findById("missing")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> fixChatService.chat("missing", "hi"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("FixSession not found: missing");

        verify(fixAgent, never()).handleFollowUp(anyLong(), anyString());
        verify(agentEventRepository, never()).append(any());
    }

    @Test
    @DisplayName("chat throws when chatSessionId is not numeric")
    void chat_invalidChatSessionId_throws() {
        FixSession session = FixSession.builder()
            .id("fix-456")
            .chatSessionId("not-a-number")
            .build();
        when(fixSessionRepository.findById("fix-456")).thenReturn(Optional.of(session));

        assertThatThrownBy(() -> fixChatService.chat("fix-456", "hi"))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("invalid chatSessionId");
    }

    @Test
    @DisplayName("chat throws when chatSessionId is null")
    void chat_nullChatSessionId_throws() {
        FixSession session = FixSession.builder()
            .id("fix-789")
            .chatSessionId(null)
            .build();
        when(fixSessionRepository.findById("fix-789")).thenReturn(Optional.of(session));

        assertThatThrownBy(() -> fixChatService.chat("fix-789", "hi"))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("invalid chatSessionId");
    }
}
