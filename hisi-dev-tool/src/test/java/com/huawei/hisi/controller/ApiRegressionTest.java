package com.huawei.hisi.controller;

import com.huawei.hisi.config.AdminOnlyInterceptor;
import com.huawei.hisi.config.AgentTypeRegistry;
import com.huawei.hisi.config.JwtTokenProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import reactor.core.publisher.Flux;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * API regression tests for all conversation-flow controllers.
 *
 * <p>Tests the HTTP contract: routing, validation, error codes, and SSE format.
 * LLM backends are mocked; this is NOT an integration test against real AI models.
 */
@WebMvcTest(controllers = AgentChatController.class)
@AutoConfigureMockMvc(addFilters = false)
@DisplayName("API Regression — AgentChatController SSE")
class ApiRegressionTest {

    @Autowired private MockMvc mockMvc;

    @MockBean private ChatClient agentChatClient;
    @MockBean private AgentTypeRegistry agentTypeRegistry;
    @MockBean private AdminOnlyInterceptor adminOnlyInterceptor;
    @MockBean private JwtTokenProvider jwtTokenProvider;

    @BeforeEach
    void setUp() throws Exception {
        when(adminOnlyInterceptor.preHandle(any(), any(), any())).thenReturn(true);
    }

    @Test
    @DisplayName("POST /api/chat/apm-diagnose with valid message returns 200 SSE")
    void knownAgentType_returns200Sse() throws Exception {
        when(agentTypeRegistry.get("apm-diagnose"))
                .thenReturn(new AgentTypeRegistry.AgentTypeConfig("You are APM expert.", "anthropic", null));

        var reqSpec = mock(ChatClient.ChatClientRequestSpec.class);
        var streamSpec = mock(ChatClient.StreamResponseSpec.class);
        when(agentChatClient.prompt()).thenReturn(reqSpec);
        when(reqSpec.system(anyString())).thenReturn(reqSpec);
        when(reqSpec.user(anyString())).thenReturn(reqSpec);
        when(reqSpec.stream()).thenReturn(streamSpec);
        when(streamSpec.chatResponse()).thenReturn(Flux.empty());

        mockMvc.perform(post("/api/chat/apm-diagnose")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                            {"message":"分析NullPointerException","sessionId":"s1"}
                            """))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("POST /api/chat/nonexistent returns 404")
    void unknownAgentType_returns404() throws Exception {
        // Don't stub get("nonexistent") — Mockito returns null, controller returns 404
        mockMvc.perform(post("/api/chat/nonexistent")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"hello\"}"))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("POST with blank message returns 400")
    void blankMessage_returns400() throws Exception {
        when(agentTypeRegistry.get("dialog"))
                .thenReturn(new AgentTypeRegistry.AgentTypeConfig("Dialog prompt.", "anthropic", null));

        mockMvc.perform(post("/api/chat/dialog")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"\",\"sessionId\":\"s1\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("POST with null message returns 400")
    void nullMessage_returns400() throws Exception {
        when(agentTypeRegistry.get("dialog"))
                .thenReturn(new AgentTypeRegistry.AgentTypeConfig("Dialog prompt.", "anthropic", null));

        mockMvc.perform(post("/api/chat/dialog")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sessionId\":\"s1\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("all 6 known agent types return 200")
    void allAgentTypes_return200() throws Exception {
        String[] types = {"apm-diagnose", "call-chain-analysis", "log-analysis", "code-analysis", "dialog", "fix"};
        var reqSpec = mock(ChatClient.ChatClientRequestSpec.class);
        var streamSpec = mock(ChatClient.StreamResponseSpec.class);
        when(agentChatClient.prompt()).thenReturn(reqSpec);
        when(reqSpec.system(anyString())).thenReturn(reqSpec);
        when(reqSpec.user(anyString())).thenReturn(reqSpec);
        when(reqSpec.stream()).thenReturn(streamSpec);
        when(streamSpec.chatResponse()).thenReturn(Flux.empty());

        for (String type : types) {
            when(agentTypeRegistry.get(type))
                    .thenReturn(new AgentTypeRegistry.AgentTypeConfig("Prompt for " + type, "anthropic", null));

            mockMvc.perform(post("/api/chat/" + type)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"message\":\"test\",\"sessionId\":\"s-test\"}"))
                    .andExpect(status().isOk());
        }
    }

    @Test
    @DisplayName("POST /api/chat/_list returns 200 with agentTypes array")
    void listEndpoint_returnsAgentTypes() throws Exception {
        when(agentTypeRegistry.keys()).thenReturn(
                java.util.Set.of("apm-diagnose", "dialog", "fix"));

        mockMvc.perform(post("/api/chat/_list"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.agentTypes").isArray());
    }
}
