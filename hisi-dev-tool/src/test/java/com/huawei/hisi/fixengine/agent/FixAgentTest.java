package com.huawei.hisi.fixengine.agent;

import com.huawei.hisi.apm.service.locator.LlmClient;
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

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("FixAgent")
class FixAgentTest {

    @Mock
    private LlmClient llm;

    @Mock
    private AgentEventRepository agentEventRepository;

    private FixAgent fixAgent;

    @BeforeEach
    void setUp() {
        fixAgent = new FixAgent(llm, agentEventRepository);
    }

    @Test
    @DisplayName("handleFollowUp pulls recent events and calls llm.chat with context")
    void handleFollowUp_buildsContextFromRecentEvents() {
        long sid = 100L;
        List<AgentEvent> events = List.of(
            buildEvent(sid, EventType.USER_MSG, "fix this NPE"),
            buildEvent(sid, EventType.ASSISTANT_DELTA, "looking into it"),
            buildEvent(sid, EventType.TOOL_USE, "git diff")
        );
        when(agentEventRepository.findBySessionId(sid)).thenReturn(events);
        when(llm.chat(contains("You are assisting"), contains("Recent events")))
            .thenReturn("Step 4 is running: generating repro test");

        String reply = fixAgent.handleFollowUp(sid, "what's the status?");

        assertThat(reply).isEqualTo("Step 4 is running: generating repro test");

        ArgumentCaptor<String> userPromptCaptor = ArgumentCaptor.forClass(String.class);
        verify(llm).chat(anyString(), userPromptCaptor.capture());
        String userPrompt = userPromptCaptor.getValue();
        assertThat(userPrompt)
            .contains("USER_MSG: fix this NPE")
            .contains("ASSISTANT_DELTA: looking into it")
            .contains("TOOL_USE: git diff")
            .contains("User question: what's the status?");
    }

    @Test
    @DisplayName("handleFollowUp truncates to last 10 events when more exist")
    void handleFollowUp_truncatesToLast10Events() {
        long sid = 200L;
        List<AgentEvent> events = new ArrayList<>();
        for (int i = 0; i < 15; i++) {
            events.add(buildEvent(sid, EventType.USER_MSG, "msg-" + i));
        }
        when(agentEventRepository.findBySessionId(sid)).thenReturn(events);
        when(llm.chat(anyString(), anyString())).thenReturn("ok");

        fixAgent.handleFollowUp(sid, "question");

        ArgumentCaptor<String> userPromptCaptor = ArgumentCaptor.forClass(String.class);
        verify(llm).chat(anyString(), userPromptCaptor.capture());
        String userPrompt = userPromptCaptor.getValue();
        assertThat(userPrompt)
            .contains("msg-14")
            .doesNotContain("msg-0")
            .doesNotContain("msg-4");   // boundary: 0..4 excluded, 5..14 included
        assertThat(userPrompt).contains("msg-5");
    }

    @Test
    @DisplayName("handleFollowUp with empty history still calls llm with empty context")
    void handleFollowUp_emptyHistory_stillCallsLlm() {
        long sid = 300L;
        when(agentEventRepository.findBySessionId(sid)).thenReturn(List.of());
        when(llm.chat(anyString(), anyString())).thenReturn("no events yet");

        String reply = fixAgent.handleFollowUp(sid, "anything?");

        assertThat(reply).isEqualTo("no events yet");
    }

    private static AgentEvent buildEvent(long sid, EventType type, String payload) {
        return AgentEvent.builder()
            .sessionId(sid)
            .seq(0)
            .type(type)
            .payload(payload)
            .idempotencyKey("test-" + sid + "-" + payload.hashCode())
            .build();
    }
}
