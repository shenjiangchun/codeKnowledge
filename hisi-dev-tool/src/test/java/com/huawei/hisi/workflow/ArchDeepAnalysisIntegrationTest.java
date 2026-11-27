package com.huawei.hisi.workflow;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.huawei.hisi.agentbase.AgentRegistry;
import com.huawei.hisi.agentbase.ReActSession;
import com.huawei.hisi.agentflow.AgentFlowCancelRegistry;
import com.huawei.hisi.archdiagnosis.service.ArchDiagnosisReportService;
import com.huawei.hisi.archdiagnosis.service.ArchDiagramGenerator;
import com.huawei.hisi.archdiagnosis.service.ArchSourceScanService;
import com.huawei.hisi.knowledgegraph.controller.KnowledgeGraphController;
import com.huawei.hisi.ram.model.SessionStatus;
import com.huawei.hisi.ram.repository.AgentEventRepository;
import com.huawei.hisi.ram.repository.AgentSessionRepository;
import com.huawei.hisi.workflow.agent.ArchAgentToolRegistry;
import com.huawei.hisi.workflow.nodes.ArchEvidenceCollectNode;
import com.huawei.hisi.workflow.v2.PiReActAgentNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 两节点真实链路集成测试：证据收集 → pi ReAct 智能体（{@code arch-review} 统一声明，
 * ReActSession 以 mock 会话回放 final_answer），经 {@link DagExecutor} 线性执行后
 * 会话 DONE，末节点输出含四段式 markdownReport。
 *
 * <p>本测试直接暴露两节点间的数据契约（evidencePack 透传给 ReAct 节点），
 * 防止单节点各自为政导致 {@code previousOutput} 整体替换后契约断裂。</p>
 */
@ExtendWith(MockitoExtension.class)
class ArchDeepAnalysisIntegrationTest {

    private static final String VALID_FINAL_ANSWER_JSON = """
            {
              "thought": "证据充分，输出结论",
              "final_answer": {
                "issues": [
                  {"ref": "i-1", "title": "循环依赖", "severity": "HIGH",
                   "rootCause": "controller 直接依赖 repository",
                   "evidenceRefs": ["module.cycles"], "suggestion": "引入接口层",
                   "verdict": "confirmed", "reason": "跨层循环属实"}
                ],
                "report": {
                  "executive_summary": "整体健康度一般，核心风险是模块循环依赖",
                  "views": {
                    "logical": {"insight": "分层清晰，controller→service→repository", "diagramRefs": []},
                    "process": {"insight": "同步调用链，无异步", "diagramRefs": []},
                    "development": {"insight": "按模块分包", "diagramRefs": []},
                    "physical": {"insight": "单服务部署", "diagramRefs": []},
                    "scenario": {"insight": "订单创建主链路", "diagramRefs": []}
                  },
                  "roadmap": {
                    "p0": [{"action": "拆解循环依赖", "impact": "降低耦合", "benefit": "可维护性", "risk": "改动面大"}],
                    "p1": [],
                    "p2": []
                  },
                  "evidence_appendix": "证据 A"
                }
              }
            }
            """;

    private static final String GRAYSCALE_FINAL_ANSWER_JSON = """
            {
              "thought": "已逐条查证候选问题真伪",
              "final_answer": {
                "issues": [
                  {"ref": "i-1", "title": "实现类外泄", "severity": "HIGH",
                   "rootCause": "FooServiceImpl 被外部直接调用",
                   "evidenceRefs": ["module.implementationLeaks"], "suggestion": "改为接口注入",
                   "verdict": "false_positive", "reason": "外部调用方为测试类"},
                  {"ref": "i-2", "title": "循环依赖", "severity": "HIGH",
                   "rootCause": "controller 直接依赖 repository",
                   "evidenceRefs": ["module.cycles"], "suggestion": "引入接口层",
                   "verdict": "confirmed", "reason": "跨层循环属实"},
                  {"ref": "i-3", "title": "臃肿包", "severity": "LOW",
                   "rootCause": "包内类数方法数偏高",
                   "evidenceRefs": ["module.bloatedPackages"], "suggestion": "拆分包",
                   "verdict": "downgraded", "reason": "规模接近阈值，HIGH 虚高下调为 LOW"}
                ],
                "report": {
                  "executive_summary": "整体健康度一般，已过滤误报并下调部分严重度",
                  "views": {
                    "logical": {"insight": "分层边界清晰但存在跨层直接调用", "diagramRefs": []},
                    "process": {"insight": "同步调用链为主", "diagramRefs": []},
                    "development": {"insight": "按模块分包", "diagramRefs": []},
                    "physical": {"insight": "单服务部署", "diagramRefs": []},
                    "scenario": {"insight": "订单创建主链路", "diagramRefs": []}
                  },
                  "roadmap": {
                    "p0": [],
                    "p1": [{"action": "接口化隔离", "impact": "消除实现类外泄"}],
                    "p2": []
                  },
                  "evidence_appendix": "证据 A"
                }
              }
            }
            """;

    @Mock
    private ArchDiagnosisReportService reportService;
    @Mock
    private KnowledgeGraphController kgController;
    @Mock
    private ReActSession reactSession;
    @Mock
    private AgentFlowCancelRegistry cancelRegistry;
    @Mock
    private AgentEventRepository eventRepo;
    @Mock
    private AgentSessionRepository sessionRepo;

    private DagExecutor executor;
    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        executor = new DagExecutor(eventRepo, sessionRepo, new ObjectMapper());
    }

    private PiReActAgentNode piNode() {
        return new PiReActAgentNode(
                new ArchAgentToolRegistry(kgController, new ArchSourceScanService()),
                new ArchDiagramGenerator(kgController), reactSession, cancelRegistry);
    }

    private void replyWith(String finalAnswerJson) throws Exception {
        Map<String, Object> json = mapper.readValue(finalAnswerJson, new TypeReference<>() {});
        when(reactSession.run(any(AgentRegistry.ReactAgent.class), anyString(), any(),
                anyString(), anyString(), any(), any(), any()))
                .thenReturn(ReActSession.Result.of("", json, null));
    }

    private static Map<String, Object> report() {
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("healthScore", 72);
        report.put("grade", "C");
        report.put("chapters", Map.of("module", Map.of("cycles", List.of())));
        report.put("issues", List.of());
        return report;
    }

    @Test
    @DisplayName("两节点完整链路：会话 DONE，末节点输出含四段式 markdownReport")
    void fullPipelineReachesDoneWithReport() throws Exception {
        when(reportService.generate(anyList(), anyString(), eq(false))).thenReturn(report());
        replyWith(VALID_FINAL_ANSWER_JSON);
        when(sessionRepo.findById(anyLong())).thenReturn(Optional.empty());
        when(eventRepo.findBySessionId(anyLong())).thenReturn(List.of());

        List<DagNode> nodes = List.of(
                new ArchEvidenceCollectNode(reportService, kgController), piNode());
        ExecutionPolicy policy = ExecutionPolicy.fromMetadata(Map.of("skipHitl", true));

        ExecutionResult result = executor.run(nodes, 1L,
                Map.of("projectPaths", List.of("p1"), "language", "java"), policy);

        assertThat(result.status()).isEqualTo(SessionStatus.DONE);
        assertThat(result.executedNodes()).containsExactly(
                "arch_evidence_collect", "arch_react_agent");
        assertThat(result.lastNodeOutput()).containsKey("markdownReport");
        String md = (String) result.lastNodeOutput().get("markdownReport");
        assertThat(md).contains("## 执行摘要")
                .contains("## 逻辑视图").contains("## 过程视图")
                .contains("## 开发视图").contains("## 物理视图")
                .contains("## 场景视图")
                .contains("## 重构路线图")
                .contains("## 证据附录");
    }

    @Test
    @DisplayName("灰度验证：verdict 逐条透传到 AI 报告，结果不回写看板（persist=false）")
    void grayscaleVerdictsFlowToAiReportOnly() throws Exception {
        Map<String, Object> report = report();
        report.put("issues", List.of(
                Map.of("source", "a.impl.FooServiceImpl", "sourceLevel", "impl-leak", "severity", "HIGH",
                        "message", "实现类外泄", "className", "a.impl.FooServiceImpl")));
        when(reportService.generate(anyList(), anyString(), eq(false))).thenReturn(report);
        replyWith(GRAYSCALE_FINAL_ANSWER_JSON);
        when(sessionRepo.findById(anyLong())).thenReturn(Optional.empty());
        when(eventRepo.findBySessionId(anyLong())).thenReturn(List.of());

        List<DagNode> nodes = List.of(
                new ArchEvidenceCollectNode(reportService, kgController), piNode());
        ExecutionPolicy policy = ExecutionPolicy.fromMetadata(Map.of("skipHitl", true));

        ExecutionResult result = executor.run(nodes, 1L,
                Map.of("projectPaths", List.of("p1"), "language", "java"), policy);

        assertThat(result.status()).isEqualTo(SessionStatus.DONE);
        @SuppressWarnings("unchecked")
        Map<String, Object> rc = (Map<String, Object>) result.lastNodeOutput().get("rootCauseResult");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> issues = (List<Map<String, Object>>) rc.get("issues");
        assertThat(issues).extracting(i -> i.get("verdict"))
                .containsExactlyInAnyOrder("false_positive", "confirmed", "downgraded");
        // 灰度结果只进 AI 报告，不回写看板：报告服务仅以 persist=false 生成，无快照写回
        verify(reportService).generate(anyList(), anyString(), eq(false));
    }
}
