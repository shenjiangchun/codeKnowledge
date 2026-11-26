package com.huawei.hisi.controller;

import com.huawei.hisi.config.AdminOnlyInterceptor;
import com.huawei.hisi.config.AgentTypeRegistry;
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
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = AgentChatController.class)
@AutoConfigureMockMvc(addFilters = false)
class AgentChatControllerTest {

    @Autowired private MockMvc mockMvc;

    @MockBean private com.huawei.hisi.agentbase.ReActSession reactSession;
    @MockBean private JwtTokenProvider jwtTokenProvider;
    @MockBean private AdminOnlyInterceptor adminOnlyInterceptor;
    @MockBean private AgentTypeRegistry agentTypeRegistry;

    @BeforeEach
    void setUp() throws Exception {
        when(adminOnlyInterceptor.preHandle(any(), any(), any())).thenReturn(true);
        // Provide sample agent types for known routes
        when(agentTypeRegistry.get("apm-diagnose"))
                .thenReturn(new AgentTypeRegistry.AgentTypeConfig("You are an APM expert.", "anthropic", null));
        when(agentTypeRegistry.get("call-chain-analysis"))
                .thenReturn(new AgentTypeRegistry.AgentTypeConfig("You are a call chain expert.", "anthropic", null));
        when(agentTypeRegistry.get("log-analysis"))
                .thenReturn(new AgentTypeRegistry.AgentTypeConfig("You are a log analysis expert.", "anthropic", null));
        when(agentTypeRegistry.get("code-analysis"))
                .thenReturn(new AgentTypeRegistry.AgentTypeConfig("You are a code analysis expert.", "anthropic", null));
        when(agentTypeRegistry.get("dialog"))
                .thenReturn(new AgentTypeRegistry.AgentTypeConfig("You are a dialog assistant.", "anthropic", null));
        // 会话：无事件、空正文即结束（增量经事件监听器，测试里不发）
        when(reactSession.run(any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(com.huawei.hisi.agentbase.ReActSession.Result.of("", null, null));
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
    @DisplayName("POST /api/chat/call-chain-analysis returns 200")
    void callChainAnalysisType_returns200() throws Exception {
        mockMvc.perform(post("/api/chat/call-chain-analysis")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                            {"message":"分析调用链","sessionId":"s2"}
                            """))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("POST /api/chat/log-analysis returns 200")
    void logAnalysisType_returns200() throws Exception {
        mockMvc.perform(post("/api/chat/log-analysis")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                            {"message":"分析日志","sessionId":"s3"}
                            """))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("POST /api/chat/code-analysis returns 200")
    void codeAnalysisType_returns200() throws Exception {
        mockMvc.perform(post("/api/chat/code-analysis")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                            {"message":"分析代码变更","sessionId":"s4"}
                            """))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("POST /api/chat/dialog returns 200")
    void dialogType_returns200() throws Exception {
        mockMvc.perform(post("/api/chat/dialog")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                            {"message":"帮我理解这个项目","sessionId":"s5"}
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
        var registry = new AgentTypeRegistry();
        assertNotNull(registry.getAgents());
    }
}
