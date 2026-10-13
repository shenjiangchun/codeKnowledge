package com.huawei.hisi.archdiagnosis.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.huawei.hisi.archdiagnosis.ArchDiagnosisScorer;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 诊断报告导出为 Markdown 文本（零依赖，可进评审文档）。
 *
 * <p>四层坏味道检测明细展开为表格；领域/DSM/依赖等原始聚合数据保持计数，避免导出体膨胀。
 * 精简策略：去除冗余空表（空章节不输出标题）、折叠重复行（相同行合并为一条并标注 ×N）、
 * 同源计数聚合（按包/文件等容器列聚合，多条同源明细折叠为「源（N 项）」）。</p>
 */
@Service
public class ArchReportMarkdownExporter {

    private final ObjectMapper objectMapper;

    public ArchReportMarkdownExporter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @SuppressWarnings("unchecked")
    public String toMarkdown(Map<String, Object> report) {
        StringBuilder sb = new StringBuilder();
        sb.append("# 架构诊断报告\n\n");
        sb.append("- **健康总分**: ").append(report.get("healthScore")).append('\n');
        sb.append("- **等级**: ").append(report.get("grade")).append('\n');
        sb.append("- **生成时间**: ").append(report.getOrDefault("generatedAt", "")).append('\n');
        sb.append("- **项目范围**: ").append(String.join(", ", (List<String>) report.getOrDefault("projectPaths", List.of()))).append("\n\n");

        appendDeductions(sb, (List<Map<String, Object>>) report.getOrDefault("deductions", List.of()));
        appendIssues(sb, (List<Map<String, Object>>) report.getOrDefault("issues", List.of()));

        Map<String, Object> chapters = (Map<String, Object>) report.getOrDefault("chapters", Map.of());
        appendProjectChapter(sb, (Map<String, Object>) chapters.get("project"));
        appendModuleChapter(sb, (Map<String, Object>) chapters.get("module"));
        appendClassChapter(sb, (Map<String, Object>) chapters.get("class"));
        appendMethodChapter(sb, (Map<String, Object>) chapters.get("method"));

        return sb.toString();
    }

    /** 扣分汇总：按严重度计数并折算原始扣分，让读者看清健康分构成。 */
    private void appendDeductions(StringBuilder sb, List<Map<String, Object>> deductions) {
        if (deductions.isEmpty()) {
            return;
        }
        int high = 0, medium = 0, low = 0;
        for (Map<String, Object> d : deductions) {
            String sev = String.valueOf(d.getOrDefault("severity", ""));
            if ("HIGH".equals(sev)) {
                high++;
            } else if ("MEDIUM".equals(sev)) {
                medium++;
            } else {
                low++;
            }
        }
        int raw = high * ArchDiagnosisScorer.POINTS_HIGH
            + medium * ArchDiagnosisScorer.POINTS_MEDIUM
            + low * ArchDiagnosisScorer.POINTS_LOW;
        sb.append("## 扣分汇总\n\n");
        sb.append("- HIGH: ").append(high).append(" 项 × -").append(ArchDiagnosisScorer.POINTS_HIGH)
            .append(" = -").append(high * ArchDiagnosisScorer.POINTS_HIGH).append('\n');
        sb.append("- MEDIUM: ").append(medium).append(" 项 × -").append(ArchDiagnosisScorer.POINTS_MEDIUM)
            .append(" = -").append(medium * ArchDiagnosisScorer.POINTS_MEDIUM).append('\n');
        sb.append("- LOW: ").append(low).append(" 项 × -").append(ArchDiagnosisScorer.POINTS_LOW)
            .append(" = -").append(low * ArchDiagnosisScorer.POINTS_LOW).append('\n');
        sb.append("- 原始扣分合计: -").append(raw).append("（每维度封顶 ").append(ArchDiagnosisScorer.DIMENSION_CAP)
            .append(" 分后得出健康总分）\n\n");
    }

    private void appendIssues(StringBuilder sb, List<Map<String, Object>> issues) {
        sb.append("## 问题清单\n\n");
        if (issues.isEmpty()) {
            sb.append("_未发现架构问题。_\n\n");
            return;
        }
        List<Object[]> rows = new ArrayList<>();
        for (Map<String, Object> issue : issues) {
            rows.add(new Object[]{issue.get("severity"), issue.get("sourceLevel"), issue.get("source"), issue.get("message")});
        }
        emitTable(sb, new String[]{"严重度", "层级", "来源", "描述"}, collapseDuplicates(rows));
    }

    @SuppressWarnings("unchecked")
    private void appendProjectChapter(StringBuilder sb, Map<String, Object> chapter) {
        sb.append("## 第 1 章 项目级\n\n");
        if (chapter == null) {
            sb.append("_该章节无数据。_\n\n");
            return;
        }
        Map<String, Object> mud = (Map<String, Object>) chapter.get("bigBallOfMud");
        if (mud != null) {
            sb.append("### 大泥球\n\n");
            sb.append("- **耦合密度**: ").append(mud.get("couplingDensity")).append('\n');
            sb.append("- **跨层依赖占比**: ").append(mud.get("crossLayerRatio")).append('\n');
            sb.append("- **模块数**: ").append(mud.get("moduleCount")).append('\n');
            sb.append("- **判定**: ").append(Boolean.TRUE.equals(mud.get("isMud")) ? "是" : "否").append("\n\n");
        }
        Map<String, Object> gc = (Map<String, Object>) chapter.get("globalCycles");
        if (gc != null) {
            sb.append("### 全局循环依赖\n\n");
            sb.append("- **领域环**: ").append(gc.get("domainCycles")).append('\n');
            sb.append("- **包环**: ").append(gc.get("packageCycles")).append('\n');
            sb.append("- **模块环**: ").append(gc.get("moduleCycles")).append('\n');
            sb.append("- **总环数**: ").append(gc.get("totalCycles")).append('\n');
            sb.append("- **涉及模块数**: ").append(gc.get("involvedModuleCount")).append("\n\n");
        }
        appendSummary(sb, chapter, "domains", "kpis", "risks", "serviceTopology");
    }

    @SuppressWarnings("unchecked")
    private void appendModuleChapter(StringBuilder sb, Map<String, Object> chapter) {
        sb.append("## 第 2 章 模块级\n\n");
        if (chapter == null) {
            sb.append("_该章节无数据。_\n\n");
            return;
        }

        List<Object[]> hubRows = new ArrayList<>();
        for (Map<String, Object> h : (List<Map<String, Object>>) chapter.getOrDefault("moduleHubs", List.of())) {
            hubRows.add(new Object[]{h.get("module"), h.get("fanIn"), h.get("fanOut")});
        }
        appendTable(sb, "模块枢纽", new String[]{"模块", "扇入", "扇出"}, hubRows);

        List<Object[]> revRows = new ArrayList<>();
        for (Map<String, Object> v : (List<Map<String, Object>>) chapter.getOrDefault("reverseDependencies", List.of())) {
            revRows.add(new Object[]{v.get("source"), v.get("sourceLayer"), v.get("targetLayer"), v.get("message")});
        }
        appendTable(sb, "反向依赖", new String[]{"来源", "源层", "目标层", "描述"}, revRows);

        List<Object[]> bloatedRows = new ArrayList<>();
        for (Map<String, Object> it : itemsOf((Map<String, Object>) chapter.get("bloatedPackages"))) {
            bloatedRows.add(new Object[]{it.get("packageName"), it.get("classCount"), it.get("methodCount")});
        }
        appendTable(sb, "臃肿包", new String[]{"包名", "类数", "方法数"}, bloatedRows);

        List<Object[]> leakRows = new ArrayList<>();
        for (Map<String, Object> it : itemsOf((Map<String, Object>) chapter.get("implementationLeaks"))) {
            Object callers = it.get("externalCallers");
            int callerCount = callers instanceof List<?> l ? l.size() : 0;
            leakRows.add(new Object[]{it.get("implClass"), it.get("implPackage"), it.get("ifaceClass"), callerCount});
        }
        appendGroupedTable(sb, "实现类外泄", new String[]{"实现类", "实现包", "接口类", "外部调用方数"}, leakRows, 1);

        appendSummary(sb, chapter, "dsm", "cycles", "layerViolations");
    }

    @SuppressWarnings("unchecked")
    private void appendClassChapter(StringBuilder sb, Map<String, Object> chapter) {
        sb.append("## 第 3 章 类级\n\n");
        if (chapter == null) {
            sb.append("_该章节无数据。_\n\n");
            return;
        }

        List<Object[]> ghostRows = new ArrayList<>();
        for (Map<String, Object> it : itemsOf((Map<String, Object>) chapter.get("ghostClasses"))) {
            ghostRows.add(new Object[]{it.get("className"), it.get("packageName"), it.get("reason")});
        }
        appendGroupedTable(sb, "幽灵类", new String[]{"类名", "包名", "原因"}, ghostRows, 1);

        List<Object[]> anemicRows = new ArrayList<>();
        for (Map<String, Object> it : itemsOf((Map<String, Object>) chapter.get("anemicDataClasses"))) {
            anemicRows.add(new Object[]{it.get("className"), it.get("packageName"), it.get("methodCount")});
        }
        appendGroupedTable(sb, "贫血数据类", new String[]{"类名", "包名", "访问器数"}, anemicRows, 1);

        Map<String, Object> metrics = (Map<String, Object>) chapter.get("metrics");
        if (metrics != null) {
            List<Object[]> godRows = new ArrayList<>();
            for (Map<String, Object> g : (List<Map<String, Object>>) metrics.getOrDefault("godClasses", List.of())) {
                godRows.add(new Object[]{g.get("className"), g.get("wmc"), g.get("methodCount")});
            }
            appendTable(sb, "上帝类", new String[]{"类名", "WMC", "方法数"}, godRows);

            List<Object[]> classHubRows = new ArrayList<>();
            for (Map<String, Object> g : (List<Map<String, Object>>) metrics.getOrDefault("hubClasses", List.of())) {
                classHubRows.add(new Object[]{g.get("className"), g.get("inDegree"), g.get("outDegree")});
            }
            appendTable(sb, "枢纽类", new String[]{"类名", "扇入", "扇出"}, classHubRows);
        }

        appendSummary(sb, chapter, "dependencies", "layerViolations");
    }

    @SuppressWarnings("unchecked")
    private void appendMethodChapter(StringBuilder sb, Map<String, Object> chapter) {
        sb.append("## 第 4 章 方法级\n\n");
        if (chapter == null) {
            sb.append("_该章节无数据。_\n\n");
            return;
        }

        List<Object[]> longRows = new ArrayList<>();
        for (Map<String, Object> it : itemsOf((Map<String, Object>) chapter.get("longMethods"))) {
            longRows.add(new Object[]{methodLabel(it), it.get("filePath"), it.get("loc")});
        }
        appendGroupedTable(sb, "超长方法", new String[]{"方法", "文件", "行数"}, longRows, 1);

        List<Object[]> paramRows = new ArrayList<>();
        for (Map<String, Object> it : itemsOf((Map<String, Object>) chapter.get("tooManyParams"))) {
            paramRows.add(new Object[]{methodLabel(it), it.get("paramCount")});
        }
        appendTable(sb, "过多参数", new String[]{"方法", "参数数"}, paramRows);

        List<Object[]> chainRows = new ArrayList<>();
        for (Map<String, Object> it : itemsOf((Map<String, Object>) chapter.get("deepCallChains"))) {
            chainRows.add(new Object[]{methodLabel(it), it.get("depth")});
        }
        appendTable(sb, "过深调用链", new String[]{"方法", "深度"}, chainRows);

        List<Object[]> hotspotRows = new ArrayList<>();
        for (Map<String, Object> h : (List<Map<String, Object>>) chapter.getOrDefault("hotspots", List.of())) {
            hotspotRows.add(new Object[]{methodLabel(h), h.get("complexity"), h.get("riskScore")});
        }
        appendTable(sb, "方法级热点", new String[]{"方法", "复杂度", "风险分"}, hotspotRows);
    }

    private static String methodLabel(Map<String, Object> item) {
        return item.get("className") + "." + item.get("methodName");
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> itemsOf(Map<String, Object> envelope) {
        if (envelope == null) {
            return List.of();
        }
        return (List<Map<String, Object>>) envelope.getOrDefault("items", List.of());
    }

    /** 原始聚合数据按计数展示（domains/dsm/依赖等矩阵与图数据不展开明细）；空值跳过。 */
    private void appendSummary(StringBuilder sb, Map<String, Object> chapter, String... keys) {
        boolean any = false;
        for (String key : keys) {
            if (chapter.containsKey(key) && !isEmpty(chapter.get(key))) {
                sb.append("- **").append(key).append("**: ").append(summarize(chapter.get(key))).append('\n');
                any = true;
            }
        }
        if (any) {
            sb.append('\n');
        }
    }

    /** 输出表格章节；空表跳过（去冗余空表），相同行折叠计数（折叠重复行）。 */
    private void appendTable(StringBuilder sb, String title, String[] headers, List<Object[]> rows) {
        List<Object[]> collapsed = collapseDuplicates(rows);
        if (collapsed.isEmpty()) {
            return;
        }
        sb.append("### ").append(title).append("\n\n");
        emitTable(sb, headers, collapsed);
    }

    /** 同源计数聚合：按 sourceCol 容器列分组，多条同源明细折叠为「源（N 项）」。 */
    private void appendGroupedTable(StringBuilder sb, String title, String[] headers, List<Object[]> rows, int sourceCol) {
        Map<String, List<Object[]>> groups = new LinkedHashMap<>();
        for (Object[] row : rows) {
            groups.computeIfAbsent(String.valueOf(row[sourceCol]), k -> new ArrayList<>()).add(row);
        }
        List<Object[]> aggregated = new ArrayList<>();
        for (List<Object[]> group : groups.values()) {
            if (group.size() == 1) {
                aggregated.add(group.get(0));
            } else {
                Object[] first = group.get(0);
                Object[] row = new Object[first.length];
                for (int i = 0; i < first.length; i++) {
                    row[i] = i == sourceCol ? first[i] + "（" + group.size() + " 项）" : "";
                }
                aggregated.add(row);
            }
        }
        appendTable(sb, title, headers, aggregated);
    }

    /** 折叠重复行：完全相同的行合并为一条，末列追加「×N」。 */
    private static List<Object[]> collapseDuplicates(List<Object[]> rows) {
        Map<String, Object[]> first = new LinkedHashMap<>();
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (Object[] row : rows) {
            String key = Arrays.toString(row);
            first.putIfAbsent(key, row);
            counts.merge(key, 1, Integer::sum);
        }
        List<Object[]> out = new ArrayList<>();
        for (Map.Entry<String, Object[]> e : first.entrySet()) {
            int n = counts.get(e.getKey());
            if (n == 1) {
                out.add(e.getValue());
            } else {
                Object[] row = e.getValue().clone();
                row[row.length - 1] = String.valueOf(row[row.length - 1]) + "（×" + n + "）";
                out.add(row);
            }
        }
        return out;
    }

    private void emitTable(StringBuilder sb, String[] headers, List<Object[]> rows) {
        sb.append("| ").append(String.join(" | ", headers)).append(" |\n");
        sb.append("|").append("---|".repeat(headers.length)).append('\n');
        for (Object[] row : rows) {
            sb.append("| ");
            for (int i = 0; i < row.length; i++) {
                if (i > 0) {
                    sb.append(" | ");
                }
                sb.append(row[i] == null ? "" : row[i]);
            }
            sb.append(" |\n");
        }
        sb.append('\n');
    }

    private String summarize(Object v) {
        if (v == null) {
            return "∅";
        }
        if (v instanceof Number) {
            return v.toString();
        }
        if (v instanceof String s) {
            return s;
        }
        if (v instanceof List<?> list) {
            return list.size() + " 项";
        }
        if (v instanceof Map<?, ?> map) {
            return map.size() + " 项";
        }
        return v.toString();
    }

    private static boolean isEmpty(Object v) {
        if (v == null) {
            return true;
        }
        if (v instanceof List<?> list) {
            return list.isEmpty();
        }
        if (v instanceof Map<?, ?> map) {
            return map.isEmpty();
        }
        return false;
    }
}
