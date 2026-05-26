package com.huawei.hisi.ram.mcp;

import com.huawei.hisi.ram.hitl.HitlQueue;
import com.huawei.hisi.ram.mcp.tools.AnalyzeRequirementTool;
import com.huawei.hisi.ram.mcp.tools.ResumeSessionTool;
import com.huawei.hisi.ram.mcp.tools.SubmitClarificationTool;
import com.huawei.hisi.ram.model.SessionStatus;
import com.huawei.hisi.ram.orchestrator.DagNode;
import com.huawei.hisi.ram.orchestrator.ExecutionResult;
import com.huawei.hisi.ram.orchestrator.RequirementAnalysisOrchestrator;
import com.huawei.hisi.ram.repository.AgentEventRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RamMcpServerTest {

    @Mock
    private RequirementAnalysisOrchestrator orchestrator;
    @Mock
    private AgentEventRepository eventRepository;

    private HitlQueue hitlQueue;
    private RamDagNodes ramNodes;

    @BeforeEach
    void setUp() {
        hitlQueue = new HitlQueue();
        ramNodes = new RamDagNodes(List.<DagNode>of());
    }

    @Test
    @DisplayName("invoke unknown tool returns error response")
    void invoke_unknownTool_returnsError() {
        RamMcpServer server = new RamMcpServer(List.of());

        McpResponse resp = server.invoke("nope", Map.of());

        assertThat(resp.ok()).isFalse();
        assertThat(resp.error()).contains("Unknown");
    }

    @Test
    @DisplayName("analyze_requirement starts session via orchestrator")
    void analyzeRequirement_startsSession() {
        ExecutionResult er = new ExecutionResult(
                42L, SessionStatus.DONE,
                List.of("clarify", "impact"),
                List.of(),
                Map.of("ok", true));
        when(orchestrator.start(anyString(), anyMap(), anyList())).thenReturn(er);

        AnalyzeRequirementTool tool = new AnalyzeRequirementTool(orchestrator, ramNodes);
        RamMcpServer server = new RamMcpServer(List.of(tool));

        McpResponse resp = server.invoke("analyze_requirement",
                Map.of("raw_input", "add login feature", "user_id", "alice"));

        assertThat(resp.ok()).isTrue();
        assertThat(resp.result().get("session_id")).isEqualTo(42L);
        assertThat(resp.result().get("status")).isEqualTo("DONE");

        ArgumentCaptor<Map<String, Object>> inputCap = ArgumentCaptor.forClass(Map.class);
        verify(orchestrator).start(eq("alice"), inputCap.capture(), any());
        assertThat(inputCap.getValue()).containsEntry("userRequirement", "add login feature");
        assertThat(inputCap.getValue()).containsEntry("mode", "interactive");
    }

    @Test
    @DisplayName("submit_clarification forwards answers to HITL queue and resume")
    void submitClarification_callsResume() {
        ExecutionResult er = new ExecutionResult(
                7L, SessionStatus.DONE, List.of("clarify"), List.of(), Map.of());
        when(orchestrator.resume(anyLong(), anyMap(), anyList())).thenReturn(er);

        SubmitClarificationTool tool = new SubmitClarificationTool(hitlQueue, orchestrator, ramNodes);
        RamMcpServer server = new RamMcpServer(List.of(tool));

        Map<String, Object> answers = Map.of("q1", "yes");
        McpResponse resp = server.invoke("submit_clarification",
                Map.of("session_id", 7L, "answers", answers));

        assertThat(resp.ok()).isTrue();
        assertThat(resp.result().get("session_id")).isEqualTo(7L);
        verify(orchestrator).resume(eq(7L), eq(answers), any());
        assertThat(hitlQueue.pollAnswers(7L)).contains(answers);
    }

    @Test
    @DisplayName("analyze_requirement without raw_input returns error")
    void analyzeRequirement_missingRawInput_returnsError() {
        AnalyzeRequirementTool tool = new AnalyzeRequirementTool(orchestrator, ramNodes);
        RamMcpServer server = new RamMcpServer(List.of(tool));

        McpResponse resp = server.invoke("analyze_requirement", Map.of("user_id", "alice"));

        assertThat(resp.ok()).isFalse();
        assertThat(resp.error()).contains("raw_input");
    }

    @Test
    @DisplayName("resume_session re-runs orchestrator with empty answers")
    void resumeSession_callsResumeWithEmptyAnswers() {
        ExecutionResult er = new ExecutionResult(
                9L, SessionStatus.DONE, List.of(), List.of(), Map.of());
        when(orchestrator.resume(anyLong(), anyMap(), anyList())).thenReturn(er);
        when(eventRepository.findBySessionId(9L)).thenReturn(List.of());

        ResumeSessionTool tool = new ResumeSessionTool(orchestrator, eventRepository, ramNodes);
        RamMcpServer server = new RamMcpServer(List.of(tool));

        McpResponse resp = server.invoke("resume_session", Map.of("session_id", 9L));

        assertThat(resp.ok()).isTrue();
        verify(orchestrator).resume(eq(9L), eq(Map.of()), any());
        verify(eventRepository).findBySessionId(9L);
    }
}
