package com.huawei.hisi.archdiagnosis.service;

import com.huawei.hisi.knowledgegraph.aggregation.stage.ClassLayerRoleDetector;
import com.huawei.hisi.knowledgegraph.controller.KnowledgeGraphController;
import com.huawei.hisi.knowledgegraph.model.CallChainGraphResponse;
import com.huawei.hisi.knowledgegraph.model.GraphEdge;
import com.huawei.hisi.knowledgegraph.model.GraphNode;
import com.huawei.hisi.model.ApiResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 架构图生成器（确定性，无 LLM）：
 * 从 {@link ArchDiagnosisReportService} 产出的诊断报告 chapters 数据，
 * 确定性生成 mermaid DSL 图，供 AI 深度分析报告的「4+1 视图」引用。
 *
 * <p>6 类图：分层架构 / DSM 依赖矩阵 / 循环依赖环 / 领域边界 / 部署拓扑 / 关键调用链。
 * 每类图数据不足（字段缺失、空列表、KG 查询无结果）时跳过（不产出该图），不抛异常，
 * 保证在任意降级路径下都不会阻断下游 ReAct 节点。</p>
 *
 * <p>只读：唯一的外部依赖是 {@link KnowledgeGraphController}（关键调用链查询），
 * 不写库、不碰 KG 生成侧。</p>
 */
@Slf4j
@Component
public class ArchDiagramGenerator {

    /** 关键调用链图的最大节点/边数，防止热点方法展开过大。 */
    private static final int MAX_CALL_NODES = 30;
    private static final int MAX_CALL_EDGES = 40;
    /** 分层违规边、DSM 边、循环环的最大展示数量。 */
    private static final int MAX_VIOLATION_EDGES = 8;
    private static final int MAX_DSM_EDGES = 15;
    private static final int MAX_CYCLE_COUNT = 4;
    private static final int MAX_CYCLE_NODES = 5;

    private final KnowledgeGraphController kg;

    public ArchDiagramGenerator(KnowledgeGraphController kg) {
        this.kg = kg;
    }

    /**
     * 从诊断证据包（含 {@code chapters}）生成有序的图列表。每项为 {@code {id, name, mermaid}}。
     * 数据不足的图被跳过，故返回数量在 0..6 之间。
     * 分层偏序取 {@link ClassLayerRoleDetector#layerOrderList()} 常量，不依赖容器字段。
     */
    public List<Map<String, Object>> generate(Map<String, Object> report, List<String> projectPaths) {
        Map<String, Object> chapters = asMap(report.get("chapters"));
        List<String> layerOrder = ClassLayerRoleDetector.layerOrderList();

        List<Map<String, Object>> diagrams = new ArrayList<>();
        addDiagram(diagrams, "layered_architecture", "分层架构图",
                layeredArchitecture(layerOrder, chapters));
        addDiagram(diagrams, "dsm_matrix", "模块依赖矩阵",
                dsmMatrix(chapters));
        addDiagram(diagrams, "cycle_rings", "循环依赖环",
                cycleRings(chapters));
        addDiagram(diagrams, "domain_boundaries", "领域边界",
                domainBoundaries(chapters));
        addDiagram(diagrams, "deployment_topology", "部署拓扑",
                deploymentTopology(chapters));
        addDiagram(diagrams, "key_call_chain", "关键调用链",
                keyCallChain(chapters, projectPaths));
        return diagrams;
    }

    // ─── 图 1：分层架构 ──────────────────────────────────────────────────────

    /** 分层偏序（高→低）+ 分层违规（红色虚线）。layerOrder 缺失则跳过。 */
    private String layeredArchitecture(List<String> layerOrder, Map<String, Object> chapters) {
        if (layerOrder.isEmpty()) {
            return null;
        }
        List<Map<String, Object>> violations = asMapList(asMap(chapters.get("module")).get("layerViolations"));
        List<Map<String, Object>> classViolations = asMapList(asMap(chapters.get("class")).get("layerViolations"));

        Map<String, Integer> idx = new LinkedHashMap<>();
        StringBuilder sb = new StringBuilder("flowchart TB\n");
        for (String layer : layerOrder) {
            idx.put(layer, idx.size());
            sb.append("  n").append(idx.get(layer)).append("[\"").append(esc(layer)).append("\"]\n");
        }
        for (int i = 0; i < layerOrder.size() - 1; i++) {
            sb.append("  n").append(i).append(" -->|依赖| n").append(i + 1).append("\n");
        }

        // 违规边：violations 本身已是引擎判定的违规，直接按层角色画红色虚线，限量
        Set<String> seen = new LinkedHashSet<>();
        for (List<Map<String, Object>> list : List.of(violations, classViolations)) {
            for (Map<String, Object> v : list) {
                Integer s = idx.get(String.valueOf(v.get("sourceLayer")));
                Integer t = idx.get(String.valueOf(v.get("targetLayer")));
                if (s == null || t == null || s.equals(t)) {
                    continue;
                }
                String key = s + "->" + t;
                if (seen.size() >= MAX_VIOLATION_EDGES || !seen.add(key)) {
                    continue;
                }
                sb.append("  n").append(s).append(" -.->|违规| n").append(t).append("\n");
            }
        }
        return sb.toString();
    }

    // ─── 图 2：DSM 依赖矩阵 ──────────────────────────────────────────────────

    /** 取 weight 最高的依赖边（限量），模块名截断。 */
    private String dsmMatrix(Map<String, Object> chapters) {
        Map<String, Object> dsm = asMap(asMap(chapters.get("module")).get("dsm"));
        List<String> modules = asStringList(dsm.get("modules"));
        List<Map<String, Object>> cells = asMapList(dsm.get("cells"));
        if (modules.isEmpty() || cells.isEmpty()) {
            return null;
        }

        List<Map<String, Object>> top = cells.stream()
                .sorted(Comparator.comparingInt(c -> -intOf(c.get("weight"))))
                .limit(MAX_DSM_EDGES)
                .toList();
        if (top.isEmpty()) {
            return null;
        }

        Set<Integer> used = new LinkedHashSet<>();
        for (Map<String, Object> c : top) {
            used.add(intOf(c.get("sourceIdx")));
            used.add(intOf(c.get("targetIdx")));
        }
        Map<Integer, Integer> id = new LinkedHashMap<>();
        StringBuilder sb = new StringBuilder("flowchart LR\n");
        for (int i : used) {
            id.put(i, id.size());
            sb.append("  n").append(id.get(i)).append("[\"")
                    .append(esc(shortName(modules.get(i), 24))).append("\"]\n");
        }
        for (Map<String, Object> c : top) {
            int s = id.get(intOf(c.get("sourceIdx")));
            int t = id.get(intOf(c.get("targetIdx")));
            sb.append("  n").append(s).append(" -->|\"").append(intOf(c.get("weight"))).append("\"| n").append(t).append("\n");
        }
        return sb.toString();
    }

    // ─── 图 3：循环依赖环 ────────────────────────────────────────────────────

    /** 每个 cycle 的节点列表画成一个闭环（subgraph 隔离，限量）。 */
    private String cycleRings(Map<String, Object> chapters) {
        List<Map<String, Object>> cycles = asMapList(asMap(chapters.get("module")).get("cycles"));
        if (cycles.isEmpty()) {
            return null;
        }
        StringBuilder sb = new StringBuilder("graph LR\n");
        int g = 0;
        int n = 0;
        for (Map<String, Object> cycle : cycles) {
            List<String> nodes = asStringList(cycle.get("nodes"));
            if (nodes.isEmpty() || g >= MAX_CYCLE_COUNT) {
                continue;
            }
            List<String> ring = nodes.subList(0, Math.min(nodes.size(), MAX_CYCLE_NODES));
            sb.append("  subgraph g").append(g).append("[\"环").append(g + 1)
                    .append(" · ").append(esc(String.valueOf(cycle.getOrDefault("level", "")))).append("\"]\n");
            for (int i = 0; i < ring.size(); i++) {
                sb.append("    n").append(n + i).append("[\"")
                        .append(esc(shortName(ring.get(i), 20))).append("\"]\n");
            }
            for (int i = 0; i < ring.size(); i++) {
                int next = (i + 1) % ring.size();
                sb.append("    n").append(n + i).append(" --> n").append(n + next).append("\n");
            }
            sb.append("  end\n");
            n += ring.size();
            g++;
        }
        return g == 0 ? null : sb.toString();
    }

    // ─── 图 4：领域边界 ──────────────────────────────────────────────────────

    /** 每个垂直领域一个 subgraph，内列分层角色。 */
    private String domainBoundaries(Map<String, Object> chapters) {
        List<Map<String, Object>> domains = asMapList(asMap(chapters.get("project")).get("domains"));
        if (domains.isEmpty()) {
            return null;
        }
        StringBuilder sb = new StringBuilder("flowchart TB\n");
        int n = 0;
        int g = 0;
        for (Map<String, Object> d : domains) {
            String name = String.valueOf(d.getOrDefault("name", ""));
            if (name.isEmpty()) {
                continue;
            }
            int count = intOf(d.get("memberCount"));
            sb.append("  subgraph g").append(g).append("[\"")
                    .append(esc(name)).append(" · ").append(count).append(" 类\"]\n");
            for (String role : asStringList(d.get("roles"))) {
                sb.append("    n").append(n).append("[\"").append(esc(role)).append("\"]\n");
                n++;
            }
            sb.append("  end\n");
            g++;
        }
        return g == 0 ? null : sb.toString();
    }

    // ─── 图 5：部署拓扑 ──────────────────────────────────────────────────────

    /** 服务节点 + 跨服务桥接边（FEIGN/MQ/HTTP）。单体（无跨服务边）返回 null。 */
    private String deploymentTopology(Map<String, Object> chapters) {
        Map<String, Object> topo = asMap(asMap(chapters.get("project")).get("serviceTopology"));
        List<Map<String, Object>> services = asMapList(topo.get("services"));
        List<Map<String, Object>> edges = asMapList(topo.get("edges"));
        if (services.isEmpty() || edges.isEmpty()) {
            return null;
        }
        Map<String, Integer> id = new LinkedHashMap<>();
        StringBuilder sb = new StringBuilder("flowchart TB\n");
        for (Map<String, Object> s : services) {
            String name = String.valueOf(s.getOrDefault("name", ""));
            if (name.isEmpty()) {
                continue;
            }
            id.put(name, id.size());
            sb.append("  n").append(id.get(name)).append("[\"").append(esc(name))
                    .append("<br/>").append(intOf(s.get("methodCount"))).append(" 方法\"]\n");
        }
        for (Map<String, Object> e : edges) {
            Integer s = id.get(String.valueOf(e.get("source")));
            Integer t = id.get(String.valueOf(e.get("target")));
            if (s == null || t == null) {
                continue;
            }
            sb.append("  n").append(s).append(" -->|\"")
                    .append(esc(String.valueOf(e.getOrDefault("type", "")))).append("\"| n").append(t).append("\n");
        }
        return id.isEmpty() ? null : sb.toString();
    }

    // ─── 图 6：关键调用链 ────────────────────────────────────────────────────

    /** 取风险分最高的热点方法，查其下游调用树并转 flowchart。查询失败返回 null。 */
    private String keyCallChain(Map<String, Object> chapters, List<String> projectPaths) {
        List<Map<String, Object>> hotspots = asMapList(asMap(chapters.get("method")).get("hotspots"));
        String[] entry = topHotspot(hotspots);
        if (entry == null || projectPaths == null || projectPaths.isEmpty()) {
            return null;
        }
        try {
            ApiResponse<CallChainGraphResponse> resp =
                    kg.getCalleesTree(entry[0], entry[1], null, projectPaths, 4);
            if (resp == null || !resp.isSuccess() || resp.getData() == null) {
                return null;
            }
            return calleesTreeToFlowchart(resp.getData());
        } catch (Exception e) {
            log.warn("[ArchDiagramGenerator] 调用链查询失败 {}.{}: {}", entry[0], entry[1], e.getMessage());
            return null;
        }
    }

    /** 从热点列表中取 riskScore 最高且类名/方法名齐全的方法。 */
    private String[] topHotspot(List<Map<String, Object>> hotspots) {
        return hotspots.stream()
                .filter(h -> !String.valueOf(h.get("className")).isBlank()
                        && !String.valueOf(h.get("methodName")).isBlank())
                .sorted(Comparator.comparingDouble(h -> -doubleOf(h.get("riskScore"))))
                .map(h -> new String[]{String.valueOf(h.get("className")), String.valueOf(h.get("methodName"))})
                .findFirst().orElse(null);
    }

    private String calleesTreeToFlowchart(CallChainGraphResponse tree) {
        List<GraphNode> nodes = tree.getNodes() == null ? List.of() : tree.getNodes();
        List<GraphEdge> edges = tree.getEdges() == null ? List.of() : tree.getEdges();
        if (nodes.isEmpty()) {
            return null;
        }
        Map<String, Integer> id = new LinkedHashMap<>();
        StringBuilder sb = new StringBuilder("flowchart TD\n");
        int limit = Math.min(nodes.size(), MAX_CALL_NODES);
        for (int i = 0; i < limit; i++) {
            GraphNode node = nodes.get(i);
            id.put(node.getId(), i);
            sb.append("  n").append(i).append("[\"").append(esc(label(node))).append("\"]\n");
            if (Boolean.TRUE.equals(node.getInCycle())) {
                sb.append("  style n").append(i).append(" stroke:#f56c6c,stroke-width:2px\n");
            }
        }
        int drawn = 0;
        for (GraphEdge e : edges) {
            Integer s = id.get(e.getSource());
            Integer t = id.get(e.getTarget());
            if (s == null || t == null || drawn >= MAX_CALL_EDGES) {
                continue;
            }
            sb.append("  n").append(s).append(" -->|\"")
                    .append(esc(e.getCallType() == null ? "" : e.getCallType())).append("\"| n").append(t).append("\n");
            drawn++;
        }
        return sb.toString();
    }

    private String label(GraphNode node) {
        String cls = node.getClassName() == null ? "" : shortName(node.getClassName(), 28);
        String name = node.getName() == null ? "" : node.getName();
        if (cls.isEmpty()) {
            return name;
        }
        return cls + "." + name;
    }

    // ─── 辅助 ────────────────────────────────────────────────────────────────

    private static void addDiagram(List<Map<String, Object>> out, String id, String name, String mermaid) {
        if (mermaid == null || mermaid.isBlank()) {
            return;
        }
        Map<String, Object> diagram = new LinkedHashMap<>();
        diagram.put("id", id);
        diagram.put("name", name);
        diagram.put("mermaid", mermaid);
        out.add(diagram);
    }

    /** mermaid 节点显示文本转义：去引号/换行，特殊结构字符转全角，避免破坏 mermaid 语法。 */
    static String esc(String text) {
        if (text == null) {
            return "";
        }
        return text
                .replace("\"", "'")
                .replace("`", "'")
                .replace("\n", " ")
                .replace("<", "〈").replace(">", "〉")
                .replace("[", "【").replace("]", "】")
                .replace("(", "（").replace(")", "）")
                .replace("|", "｜")
                .replace("&", "＆")
                .replace(";", "；")
                .replace("#", "＃");
    }

    /** 截断长全限定名，保留末尾（更有辨识度）。 */
    private static String shortName(String name, int max) {
        if (name == null || name.length() <= max) {
            return name == null ? "" : name;
        }
        return "…" + name.substring(name.length() - max + 1);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object o) {
        return o instanceof Map ? (Map<String, Object>) o : Map.of();
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> asMapList(Object o) {
        if (!(o instanceof List<?> list)) {
            return List.of();
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object item : list) {
            if (item instanceof Map<?, ?> m) {
                out.add((Map<String, Object>) m);
            }
        }
        return out;
    }

    private static List<String> asStringList(Object o) {
        if (!(o instanceof List<?> list)) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (Object item : list) {
            out.add(String.valueOf(item));
        }
        return out;
    }

    private static int intOf(Object o) {
        if (o instanceof Number n) {
            return n.intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(o));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static double doubleOf(Object o) {
        if (o instanceof Number n) {
            return n.doubleValue();
        }
        try {
            return Double.parseDouble(String.valueOf(o));
        } catch (NumberFormatException e) {
            return 0.0;
        }
    }
}
