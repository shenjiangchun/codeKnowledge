package com.huawei.hisi.workflow;

import com.huawei.hisi.model.ApiResponse;
import com.huawei.hisi.ram.model.AgentEvent;
import com.huawei.hisi.ram.model.EventType;
import com.huawei.hisi.ram.model.SessionStatus;
import com.huawei.hisi.ram.repository.AgentEventRepository;
import com.huawei.hisi.ram.repository.AgentSessionRepository;
import com.huawei.hisi.ram.service.SessionMappingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * 深度分析报告查询单测（复用既有 GET /sessions/{sid}/report?nodeName=… 端点）：
 * 工作流完成后可按 arch_react_agent 节点回读 Markdown 报告。
 */
@ExtendWith(MockitoExtension.class)
class WorkflowControllerReportQueryTest {

    @Mock
    private WorkflowRegistry registry;
    @Mock
    private SessionMappingService sessionMappingService;
    @Mock
    private AgentSessionRepository sessionRepository;
    @Mock
    private AgentEventRepository eventRepository;
    @Mock
    private DagExecutor dagExecutor;

    private WorkflowController controller;

    @BeforeEach
    void setUp() {
        controller = new WorkflowController(registry, sessionMappingService,
                sessionRepository, eventRepository, dagExecutor);
    }

    @Test
    @DisplayName("完成的会话：按 arch_react_agent 节点回读 Markdown 报告")
    void completedWorkflowReportIsQueryable() {
        String handle = "h-1";
        when(sessionMappingService.resolveBackendId(handle)).thenReturn(42L);
        when(sessionRepository.findById(42L)).thenReturn(Optional.of(
                com.huawei.hisi.ram.model.AgentSession.newRunning("anonymous", null)));
        // 三个节点的 CHECKPOINT，report 节点在最前 —— 验证按 nodeName 定位而非"最新"
        AgentEvent evidenceCkpt = checkpoint("arch_evidence_collect");
        AgentEvent rootCauseCkpt = checkpoint("arch_root_cause");
        AgentEvent reportCkpt = checkpoint("arch_react_agent",
                Map.of("markdownReport", "## 现状总览 (demo)", "degraded", false));
        when(eventRepository.findBySessionId(42L))
                .thenReturn(List.of(reportCkpt, rootCauseCkpt, evidenceCkpt));

        ApiResponse<WorkflowController.ReportResponse> resp =
                controller.getReport(handle, "arch_react_agent");

        assertThat(resp.getCode()).isEqualTo(200);
        Map<String, Object> report = resp.getData().report();
        assertThat(report).isNotNull();
        assertThat(String.valueOf(report.get("markdownReport"))).contains("现状总览");
        assertThat(report.get("degraded")).isEqualTo(false);
    }

    @Test
    @DisplayName("未指定 nodeName：返回最新 checkpoint（末节点输出）")
    void latestCheckpointReturnedByDefault() {
        String handle = "h-2";
        when(sessionMappingService.resolveBackendId(handle)).thenReturn(7L);
        when(sessionRepository.findById(7L)).thenReturn(Optional.of(
                com.huawei.hisi.ram.model.AgentSession.newRunning("anonymous", null)));
        when(eventRepository.findBySessionId(7L)).thenReturn(List.of(
                checkpoint("arch_evidence_collect"),
                checkpoint("arch_react_agent", Map.of("markdownReport", "md"))));

        ApiResponse<WorkflowController.ReportResponse> resp = controller.getReport(handle, null);

        assertThat(resp.getCode()).isEqualTo(200);
        assertThat(resp.getData().report()).containsKey("markdownReport");
    }

    @Test
    @DisplayName("会话不存在：404")
    void unknownSessionReturns404() {
        when(sessionMappingService.resolveBackendId("nope")).thenReturn(null);
        ApiResponse<WorkflowController.ReportResponse> resp = controller.getReport("nope", null);
        assertThat(resp.getCode()).isEqualTo(404);
    }

    private static AgentEvent checkpoint(String nodeName) {
        return checkpoint(nodeName, Map.of());
    }

    private static AgentEvent checkpoint(String nodeName, Map<String, Object> output) {
        String payload = String.format(
                "{\"nodeName\":\"%s\",\"inputsHash\":\"x\",\"output\":%s}",
                nodeName, toJson(output));
        return AgentEvent.builder()
                .sessionId(1L)
                .seq(nextSeq.incrementAndGet())
                .type(EventType.CHECKPOINT)
                .payload(payload)
                .circuitState("OK")
                .validatorStatus("OK")
                .createdAt(0L)
                .build();
    }

    private static final java.util.concurrent.atomic.AtomicLong nextSeq = new java.util.concurrent.atomic.AtomicLong();

    private static String toJson(Map<String, Object> map) {
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, Object> e : map.entrySet()) {
            if (!first) sb.append(",");
            first = false;
            Object v = e.getValue();
            if (v instanceof String s) {
                sb.append("\"").append(e.getKey()).append("\":\"").append(s).append("\"");
            } else {
                sb.append("\"").append(e.getKey()).append("\":").append(String.valueOf(v));
            }
        }
        return sb.append("}").toString();
    }
}
