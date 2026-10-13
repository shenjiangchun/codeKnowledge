package com.huawei.hisi.workflow.nodes;

import com.huawei.hisi.archdiagnosis.service.ArchDiagnosisReportService;
import com.huawei.hisi.knowledgegraph.controller.KnowledgeGraphController;
import com.huawei.hisi.model.ApiResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * 证据收集节点单测：证据包结构完整（chapters/issues/kpis/egoNets）；KG 全空时降级不抛异常。
 */
@ExtendWith(MockitoExtension.class)
class ArchEvidenceCollectNodeTest {

    @Mock
    private ArchDiagnosisReportService reportService;
    @Mock
    private KnowledgeGraphController v1;

    private ArchEvidenceCollectNode node;

    @BeforeEach
    void setUp() {
        node = new ArchEvidenceCollectNode(reportService, v1);
    }

    private Map<String, Object> reportOf(Map<String, Object> chapters, List<Map<String, Object>> issues) {
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("healthScore", 72);
        report.put("grade", "C");
        report.put("chapters", chapters);
        report.put("issues", issues);
        return report;
    }

    private Map<String, Object> chaptersOf() {
        Map<String, Object> chapters = new LinkedHashMap<>();
        Map<String, Object> module = new LinkedHashMap<>();
        List<Map<String, Object>> cycles = new ArrayList<>();
        Map<String, Object> cycle = new LinkedHashMap<>();
        cycle.put("level", "CROSS_LAYER");
        cycle.put("message", "controller → service → repository → controller");
        cycle.put("nodes", List.of("a.controller", "a.service", "a.repository"));
        cycles.add(cycle);
        module.put("cycles", cycles);
        chapters.put("module", module);
        return chapters;
    }

    @Test
    @DisplayName("节点元数据：name/agentId")
    void nodeMetadata() {
        assertThat(node.name()).isEqualTo("arch_evidence_collect");
        assertThat(node.agentId()).isNotBlank();
    }

    @Test
    @DisplayName("产出完整证据包：chapters/issues/KPI/KG 摘录，环节点触发 ego-net 补证")
    void producesCompleteEvidencePack() {
        when(reportService.generate(anyList(), anyString(), eq(false)))
                .thenReturn(reportOf(chaptersOf(), List.of()));
        when(v1.getClassEgoNet(isNull(), anyList(), anyList()))
                .thenReturn(ApiResponse.success(Map.of("nodes", List.of(), "edges", List.of())));

        Map<String, Object> input = Map.of("projectPaths", List.of("p1"), "language", "java");
        Map<String, Object> out = node.execute(input);
        Map<String, Object> pack = asMap(out, "evidencePack");

        assertThat(pack).containsKeys("projectPaths", "healthScore", "grade", "chapters", "issues", "egoNets");
        assertThat(pack.get("healthScore")).isEqualTo(72);
        // 环节点包 → 按包名触发 ego-net 查询
        assertThat(asStringList(pack, "egoNetPackages")).contains("a.controller");
        assertThat(pack.get("degraded")).isEqualTo(false);
    }

    @Test
    @DisplayName("多项目证据收集：projectPaths 多值透传进证据包")
    void multiProjectEvidence() {
        when(reportService.generate(anyList(), anyString(), eq(false)))
                .thenReturn(reportOf(new LinkedHashMap<>(), List.of()));
        lenient().when(v1.getClassEgoNet(isNull(), anyList(), anyList()))
                .thenReturn(ApiResponse.success(Map.of()));

        Map<String, Object> out = node.execute(Map.of("projectPaths", List.of("p1", "p2")));
        Map<String, Object> pack = asMap(out, "evidencePack");

        assertThat(asStringList(pack, "projectPaths")).containsExactly("p1", "p2");
    }

    @Test
    @DisplayName("KG 为空/查询失败：降级不抛异常，空集也是合法证据包")
    void degradesWhenKgEmpty() {        when(reportService.generate(anyList(), anyString(), eq(false)))
                .thenReturn(reportOf(new LinkedHashMap<>(), List.of()));
        lenient().when(v1.getClassEgoNet(isNull(), anyList(), anyList()))
                .thenReturn(ApiResponse.error(500, "kg down"));

        Map<String, Object> input = Map.of("projectPaths", List.of("p1"));
        Map<String, Object> out = node.execute(input);
        Map<String, Object> pack = asMap(out, "evidencePack");

        assertThat(pack).containsKey("chapters");
        assertThat(asStringList(pack, "egoNetPackages")).isEmpty();
        assertThat(asMap(pack, "egoNets")).isEmpty();
        assertThat(pack.get("degraded")).isEqualTo(true);
    }

    @Test
    @DisplayName("报告生成本身失败：明确异常终止（工作流 FAILED，前端可见原因）")
    void failsFastWhenReportGenerationFails() {
        when(reportService.generate(anyList(), anyString(), eq(false)))
                .thenThrow(new IllegalStateException("kg not built"));

        Map<String, Object> input = Map.of("projectPaths", List.of("p1"));
        assertThatCode(() -> node.execute(input))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("kg not built");
    }

    @Test
    @DisplayName("从 chapters.project.kpis 提取 KPI（报告无顶层 kpi 字段）")
    void extractsKpiFromProjectChapter() {
        Map<String, Object> chapters = new LinkedHashMap<>();
        Map<String, Object> project = new LinkedHashMap<>();
        Map<String, Object> kpis = Map.of("modules", 12, "services", 3);
        project.put("kpis", kpis);
        chapters.put("project", project);

        when(reportService.generate(anyList(), anyString(), eq(false)))
                .thenReturn(reportOf(chapters, List.of()));

        Map<String, Object> out = node.execute(Map.of("projectPaths", List.of("p1")));
        Map<String, Object> pack = asMap(out, "evidencePack");

        assertThat(pack.get("kpi")).isEqualTo(kpis);
    }

    @Test
    @DisplayName("执行期间通过 sink 产出过程消息（报告生成 + ego-net 补证 + 完成）")
    void emitsProgressMessages() {
        when(reportService.generate(anyList(), anyString(), eq(false)))
                .thenReturn(reportOf(chaptersOf(), List.of()));
        when(v1.getClassEgoNet(isNull(), anyList(), anyList()))
                .thenReturn(ApiResponse.success(Map.of("nodes", List.of(), "edges", List.of())));

        List<String> messages = new ArrayList<>();
        node.execute(Map.of("projectPaths", List.of("p1"), "language", "java"), messages::add);

        assertThat(messages).anyMatch(m -> m.contains("诊断报告"));
        assertThat(messages).anyMatch(m -> m.contains("ego-net"));
        assertThat(messages).anyMatch(m -> m.contains("证据收集完成"));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Map<String, Object> out, String key) {
        return (Map<String, Object>) out.get(key);
    }

    @SuppressWarnings("unchecked")
    private static List<String> asStringList(Map<String, Object> out, String key) {
        return (List<String>) out.get(key);
    }
}
