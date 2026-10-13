package com.huawei.hisi.workflow.nodes;

import com.huawei.hisi.archdiagnosis.service.ArchDiagnosisReportService;
import com.huawei.hisi.knowledgegraph.controller.KnowledgeGraphController;
import com.huawei.hisi.model.ApiResponse;
import com.huawei.hisi.workflow.DagNode;
import com.huawei.hisi.workflow.NodeEventSink;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 证据收集节点（确定性，无 LLM）：
 * 生成不落库的架构诊断报告 + KG 只读补证（环上类的 ego-net），
 * 输出供下游根因分析节点使用的证据包。
 *
 * <p>降级语义：KG 缺失/查询失败时空集也算合法证据包（degraded=true）；
 * 报告生成本身失败则快速失败（工作流 FAILED，前端可见原因）。</p>
 */
@Slf4j
@Component
public class ArchEvidenceCollectNode implements DagNode {

    private final ArchDiagnosisReportService reportService;
    private final KnowledgeGraphController kgController;

    public ArchEvidenceCollectNode(ArchDiagnosisReportService reportService,
                                    KnowledgeGraphController kgController) {
        this.reportService = reportService;
        this.kgController = kgController;
    }

    @Override
    public String name() {
        return "arch_evidence_collect";
    }

    @Override
    public String agentId() {
        return "arch-evidence-collect-v1";
    }

    @Override
    public Map<String, Object> execute(Map<String, Object> input) {
        return execute(input, msg -> {});
    }

    @Override
    public Map<String, Object> execute(Map<String, Object> input, NodeEventSink sink) {
        List<String> projectPaths = extractProjectPaths(input);
        if (projectPaths.isEmpty()) {
            throw new IllegalArgumentException("projectPaths is required");
        }
        String language = (String) input.getOrDefault("language", "java");

        // Step 1: 生成诊断报告（persist=false，不落库），失败即快速失败
        sink.emit("正在生成实时诊断报告…");
        Map<String, Object> report = reportService.generate(projectPaths, language, false);
        sink.emit("诊断报告生成完成（健康分 " + report.get("healthScore") + " / " + report.get("grade") + "）");

        // Step 2: 从模块章节提取环上类 → 按其所在包做 ego-net 补证（可降级）
        List<String> cycleClasses = extractCycleNodes(report);
        Map<String, Object> egoNets = new LinkedHashMap<>();
        for (String cls : cycleClasses) {
            String pkg = cls.contains(".") ? cls.substring(0, cls.lastIndexOf('.')) : cls;
            sink.emit("正在查询 ego-net：" + pkg);
            try {
                ApiResponse<Map<String, Object>> resp =
                        kgController.getClassEgoNet(null, projectPaths, List.of(pkg));
                if (resp != null && resp.getCode() == 200 && resp.getData() != null) {
                    egoNets.put(pkg, resp.getData());
                }
            } catch (Exception e) {
                log.warn("[ArchEvidenceCollectNode] ego-net failed for package {}: {}", pkg, e.getMessage());
            }
        }
        sink.emit("证据收集完成，环上类 " + cycleClasses.size() + " 个");

        // 降级判定：环上类为空（KG 无环数据）或全部 ego-net 查询无有效结果
        boolean degraded = cycleClasses.isEmpty() || egoNets.isEmpty();

        Map<String, Object> evidencePack = new LinkedHashMap<>();
        evidencePack.put("projectPaths", projectPaths);
        evidencePack.put("language", language);
        evidencePack.put("healthScore", report.get("healthScore"));
        evidencePack.put("grade", report.get("grade"));
        evidencePack.put("chapters", report.getOrDefault("chapters", Map.of()));
        evidencePack.put("issues", report.getOrDefault("issues", List.of()));
        evidencePack.put("kpi", extractProjectKpis(report));
        evidencePack.put("egoNetPackages", cycleClasses);
        evidencePack.put("egoNets", egoNets);
        evidencePack.put("degraded", degraded);

        Map<String, Object> output = new LinkedHashMap<>();
        output.put("evidencePack", evidencePack);
        return output;
    }

    /** 从 chapters.module.cycles[].nodes 提取环上类名（去重，保持顺序）。 */
    private List<String> extractCycleNodes(Map<String, Object> report) {
        Object chaptersObj = report.get("chapters");
        if (!(chaptersObj instanceof Map<?, ?> chapters)) {
            return List.of();
        }
        Object moduleObj = chapters.get("module");
        if (!(moduleObj instanceof Map<?, ?> module)) {
            return List.of();
        }
        Object cyclesObj = module.get("cycles");
        if (!(cyclesObj instanceof List<?> cycles)) {
            return List.of();
        }
        List<String> packages = new ArrayList<>();
        for (Object cycleObj : cycles) {
            if (!(cycleObj instanceof Map<?, ?> cycle)) {
                continue;
            }
            Object nodesObj = cycle.get("nodes");
            if (!(nodesObj instanceof List<?> nodes)) {
                continue;
            }
            for (Object node : nodes) {
                if (node instanceof String s && !packages.contains(s)) {
                    packages.add(s);
                }
            }
        }
        return packages;
    }

    /** 从 chapters.project.kpis 提取 KPI（报告无顶层 kpi 字段）。 */
    private Object extractProjectKpis(Map<String, Object> report) {
        Object chaptersObj = report.get("chapters");
        if (!(chaptersObj instanceof Map<?, ?> chapters)) {
            return Map.of();
        }
        Object projectObj = chapters.get("project");
        if (!(projectObj instanceof Map<?, ?> project)) {
            return Map.of();
        }
        Object kpis = project.get("kpis");
        return kpis != null ? kpis : Map.of();
    }

    private static List<String> extractProjectPaths(Map<String, Object> input) {
        if (input == null) {
            return List.of();
        }
        Object obj = input.get("projectPaths");
        if (obj instanceof List<?> list) {
            return list.stream().filter(o -> o instanceof String).map(o -> (String) o).toList();
        }
        return List.of();
    }
}
