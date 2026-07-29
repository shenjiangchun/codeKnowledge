package com.huawei.hisi.controller;

import com.huawei.hisi.config.AdminOnlyInterceptor;
import com.huawei.hisi.config.JwtTokenProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import reactor.core.publisher.Flux;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = AgentChatController.class)
@AutoConfigureMockMvc(addFilters = false)
class AgentChatControllerTest {

    @Autowired private MockMvc mockMvc;

    @MockBean private org.springframework.ai.chat.client.ChatClient agentChatClient;
    @MockBean private JwtTokenProvider jwtTokenProvider;
    @MockBean private AdminOnlyInterceptor adminOnlyInterceptor;
    @MockBean private AgentChatController.AgentTypeRegistry agentTypeRegistry;

    @BeforeEach
    void setUp() throws Exception {
        when(adminOnlyInterceptor.preHandle(any(), any(), any())).thenReturn(true);
        // Provide a sample agent type
        when(agentTypeRegistry.get("apm-diagnose"))
                .thenReturn(new AgentChatController.AgentTypeConfig("You are an APM expert.", "anthropic", null));
        // Stub the streaming chain
        var reqSpec = mock(org.springframework.ai.chat.client.ChatClient.ChatClientRequestSpec.class);
        var streamSpec = mock(org.springframework.ai.chat.client.ChatClient.StreamResponseSpec.class);
        when(agentChatClient.prompt()).thenReturn(reqSpec);
        when(reqSpec.system(any(String.class))).thenReturn(reqSpec);
        when(reqSpec.user(any(String.class))).thenReturn(reqSpec);
        when(reqSpec.stream()).thenReturn(streamSpec);
        when(streamSpec.chatResponse()).thenReturn(Flux.empty());
    }

    @Test
    @DisplayName("POST /api/chat/apm-diagnose returns 200")
    void knownAgentType_returns200() throws Exception {
        mockMvc.perform(post("/api/chat/apm-diagnose")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                            {"message":"分析异常","sessionId":"s1"}
                            """))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("POST /api/chat/unknown-type returns 404")
    void unknownAgentType_returns404() throws Exception {
        mockMvc.perform(post("/api/chat/nonexistent")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                            {"message":"hello"}
                            """))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("POST with blank message returns 400")
    void blankMessage_returns400() throws Exception {
        mockMvc.perform(post("/api/chat/apm-diagnose")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                            {"message":"","sessionId":"s1"}
                            """))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("POST without message returns 400")
    void missingMessage_returns400() throws Exception {
        mockMvc.perform(post("/api/chat/apm-diagnose")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                            {"sessionId":"s1"}
                            """))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("AgentTypeRegistry default constructor works")
    void agentTypeRegistry_defaultCtor() {
        var registry = new AgentChatController.AgentTypeRegistry();
        assertNotNull(registry.getAgents());
    }
}
