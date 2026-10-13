package com.huawei.hisi.archdiagnosis.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.huawei.hisi.archdiagnosis.ArchDiagnosisScorer;
import com.huawei.hisi.archdiagnosis.domain.ClassRoleRecord;
import com.huawei.hisi.archdiagnosis.domain.VerticalDomain;
import com.huawei.hisi.archdiagnosis.domain.VerticalDomainAggregator;
import com.huawei.hisi.archdiagnosis.domain.VerticalDomainNamer;
import com.huawei.hisi.archdiagnosis.domain.VerticalDomainResult;
import com.huawei.hisi.archdiagnosis.model.ArchDiagnosisSnapshot;
import com.huawei.hisi.archdiagnosis.repository.ArchDiagnosisSnapshotRepository;
import com.huawei.hisi.knowledgegraph.aggregation.stage.ClassLayerRoleDetector;
import com.huawei.hisi.knowledgegraph.controller.KnowledgeGraphController;
import com.huawei.hisi.knowledgegraph.util.ProjectPathResolver;
import com.huawei.hisi.model.ApiResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 架构诊断报告聚合：组合 V1 端点 + 方法/类实时聚合 + 四层坏味道检测，按扣分制算健康分，落快照。
 *
 * <p>报告生成为确定性业务逻辑（规则 + 只读查询聚合）；领域边界来自垂直领域识别（纯读计算），
 * 领域中文名由 {@link VerticalDomainNamer} 缓存复用（LLM 失败回退领域词原文）。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ArchDiagnosisReportService {

    private final KnowledgeGraphController v1;
    private final MethodClassMetricsService metricsService;
    private final ArchSmellDetectorService smellDetector;
    private final VerticalDomainAggregator verticalDomainAggregator;
    private final VerticalDomainNamer verticalDomainNamer;
    private final ArchDiagnosisSnapshotRepository snapshotRepository;
    private final ObjectMapper objectMapper;

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** 单类检测计入 issue 的数量上限（避免超大类集导致 issue 列表膨胀）。 */
    private static final int ISSUE_CAP = 20;

    /** 生成诊断报告并落快照；返回报告体。 */
    public Map<String, Object> generate(List<String> projectPaths, String language) {
        return generate(projectPaths, language, true);
    }

    /** 生成诊断报告；persist=false 时不落快照（即时导出用，避免污染历史）。 */
    public Map<String, Object> generate(List<String> projectPaths, String language, boolean persist) {
        List<String> paths = normalizePaths(projectPaths);
        ReportBuilder builder = new ReportBuilder(paths);

        collectProjectChapter(builder, paths, language);
        collectModuleChapter(builder, paths);
        collectClassChapter(builder, paths);
        collectMethodChapter(builder, paths);
        collectProjectAggregations(builder);

        Map<String, Object> report = builder.build();
        if (persist) {
            saveSnapshots(paths, report, builder.issues());
        }
        return report;
    }

    /** 归一化项目路径（反斜杠→正斜杠、去空白），与 Neo4j 存储一致，避免 raw-Cypher 查询 projectPath 失配。 */
    private static List<String> normalizePaths(List<String> projectPaths) {
        if (projectPaths == null) {
            return List.of();
        }
        return projectPaths.stream()
            .map(ProjectPathResolver::normalize)
            .filter(p -> !p.isEmpty())
            .toList();
    }

    /** 项目级章节：垂直领域、KPI、服务拓扑、领域循环 + 分层违规（risks）归入问题。 */
    private void collectProjectChapter(ReportBuilder b, List<String> paths, String language) {
        Map<String, Object> chapter = new LinkedHashMap<>();
        chapter.putAll(verticalDomains(paths));

        ApiResponse<Map<String, Object>> dashboard = v1.getDashboard(null, paths, language);
        if (dashboard.isSuccess() && dashboard.getData() != null) {
            Map<String, Object> data = dashboard.getData();
            chapter.put("kpis", data.get("kpis"));
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> risks = (List<Map<String, Object>>) data.getOrDefault("risks", List.of());
            chapter.put("risks", risks);
            for (Map<String, Object> risk : risks) {
                String type = String.valueOf(risk.getOrDefault("type", ""));
                String severity = "cyclic".equals(type) ? "HIGH" : "MEDIUM";
                b.addIssue("project", "domain-" + type, severity,
                    String.valueOf(risk.getOrDefault("source", "")),
                    String.valueOf(risk.getOrDefault("message", "")));
            }
        }
        ApiResponse<Map<String, Object>> topo = v1.getServiceTopology(null, paths, language);
        if (topo.isSuccess() && topo.getData() != null) {
            chapter.put("serviceTopology", topo.getData());
        }
        b.chapter("project", chapter);
    }

    /**
     * 垂直领域识别（诊断侧纯读）：读 ClassNode 的 classRole 聚合领域 + LLM 命名，
     * 替换社区检测 DomainNode 作为项目级领域数据源，不写回、不碰生成侧。
     */
    private Map<String, Object> verticalDomains(List<String> paths) {
        Map<String, Object> result = new LinkedHashMap<>();
        try {
            List<ClassRoleRecord> records = verticalDomainAggregator.loadClassRoles(paths);
            VerticalDomainResult aggregated = verticalDomainAggregator.aggregate(records);
            List<VerticalDomain> named = verticalDomainNamer.nameDomains(aggregated.domains());

            List<Map<String, Object>> domains = new ArrayList<>();
            for (VerticalDomain domain : named) {
                domains.add(domainToMap(domain));
            }
            List<Map<String, Object>> isolated = new ArrayList<>();
            for (VerticalDomain domain : aggregated.isolatedDataObjects()) {
                isolated.add(domainToMap(domain));
            }
            result.put("domains", domains);
            result.put("isolatedDataObjects", isolated);
        } catch (Exception e) {
            log.warn("[ArchDiagnosis] 垂直领域识别失败: {}", e.getMessage());
            result.put("domains", List.of());
            result.put("isolatedDataObjects", List.of());
        }
        return result;
    }

    /** 领域转报告结构：中文名 + 领域词 + 分层角色（按层序）+ 成员数 + 骨架判定。 */
    private Map<String, Object> domainToMap(VerticalDomain domain) {
        List<String> order = ClassLayerRoleDetector.layerOrderList();
        List<String> roles = new ArrayList<>(domain.roles());
        roles.sort(Comparator.comparingInt(r -> {
            int idx = order.indexOf(r);
            return idx < 0 ? Integer.MAX_VALUE : idx;
        }));
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("name", domain.name());
        map.put("domainWord", domain.domainWord());
        map.put("roles", roles);
        map.put("memberCount", domain.members().size());
        map.put("complete", domain.complete());
        return map;
    }

    /** 模块级章节：DSM 矩阵 + 包环/模块环/分层违规 + 模块枢纽/反向依赖 + 臃肿包/实现类外泄。 */
    private void collectModuleChapter(ReportBuilder b, List<String> paths) {
        Map<String, Object> chapter = new LinkedHashMap<>();

        ApiResponse<Map<String, Object>> dsm = v1.getDsm(null, paths, null, "package");
        if (dsm.isSuccess() && dsm.getData() != null) {
            chapter.put("dsm", dsm.getData());
        }

        List<Map<String, Object>> cycles = new ArrayList<>();
        collectCycles(b, "module", cycles, v1.getPackageCycles(null, paths), "package-cycle");
        collectCycles(b, "module", cycles, v1.getModuleCycles(null, paths), "module-cycle");
        chapter.put("cycles", cycles);

        ApiResponse<Map<String, Object>> layerViol = v1.getBuildModuleLayerViolations(null, paths);
        List<Map<String, Object>> layerItems = new ArrayList<>();
        if (layerViol.isSuccess() && layerViol.getData() != null) {
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> items = (List<Map<String, Object>>) layerViol.getData().getOrDefault("violations", List.of());
            layerItems.addAll(items);
            chapter.put("layerViolations", items);
            for (Map<String, Object> item : items) {
                b.addIssue("module", "module-layer-violation", "MEDIUM",
                    String.valueOf(item.getOrDefault("source", "")),
                    String.valueOf(item.getOrDefault("message", "")));
            }
        }

        // 模块枢纽（DSM 扇入扇出）与反向依赖（分层违规归类）
        List<Map<String, Object>> hubs = moduleHubs(dsm.isSuccess() && dsm.getData() != null ? dsm.getData() : Map.of());
        chapter.put("moduleHubs", hubs);
        chapter.put("reverseDependencies", reverseDependencies(layerItems));
        for (Map<String, Object> hub : hubs) {
            b.addIssue("module", "module-hub", "MEDIUM",
                String.valueOf(hub.getOrDefault("module", "")),
                "模块枢纽：扇入 " + hub.get("fanIn") + "、扇出 " + hub.get("fanOut"));
        }

        // 原始查询检测：臃肿包 + 实现类外泄
        Map<String, Object> bloated = smellDetector.bloatedPackages(paths);
        chapter.put("bloatedPackages", bloated);
        addDetectionIssues(b, "module", "bloated-package", "MEDIUM", bloated,
            item -> String.valueOf(item.getOrDefault("packageName", "")),
            item -> "臃肿包：类数 " + item.get("classCount") + "、方法数 " + item.get("methodCount"));

        Map<String, Object> leaks = smellDetector.implementationLeaks(paths);
        chapter.put("implementationLeaks", leaks);
        addDetectionIssues(b, "module", "impl-leak", "HIGH", leaks,
            item -> String.valueOf(item.getOrDefault("implClass", "")),
            item -> "实现类外泄：" + item.get("implClass") + " 被 " + item.get("ifacePackage") + " 外直接调用");

        b.chapter("module", chapter);
    }

    private void collectCycles(ReportBuilder b, String dimension, List<Map<String, Object>> cyclesOut,
                               ApiResponse<Map<String, Object>> resp, String sourceLevel) {
        if (!resp.isSuccess() || resp.getData() == null) {
            return;
        }
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> cycles = (List<Map<String, Object>>) resp.getData().getOrDefault("cycles", List.of());
        for (Map<String, Object> c : cycles) {
            cyclesOut.add(c);
            String level = String.valueOf(c.getOrDefault("level", ""));
            String severity = "CROSS_LAYER".equalsIgnoreCase(level) ? "HIGH" : "MEDIUM";
            b.addIssue(dimension, sourceLevel, severity,
                String.valueOf(c.getOrDefault("nodes", "")),
                String.valueOf(c.getOrDefault("message", "")));
        }
    }

    /** 类级章节：类分层违规疑似 + 上帝类/枢纽类度量 + 幽灵类/贫血数据类。 */
    private void collectClassChapter(ReportBuilder b, List<String> paths) {
        Map<String, Object> chapter = new LinkedHashMap<>();

        ApiResponse<Map<String, Object>> classViol = v1.getClassLayerViolations(null, paths);
        if (classViol.isSuccess() && classViol.getData() != null) {
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> items = (List<Map<String, Object>>) classViol.getData().getOrDefault("violations", List.of());
            chapter.put("layerViolations", items);
            for (Map<String, Object> item : items) {
                b.addIssue("class", "class-layer-violation", "MEDIUM",
                    String.valueOf(item.getOrDefault("source", "")),
                    String.valueOf(item.getOrDefault("message", "")));
            }
        }

        chapter.put("metrics", metricsService.classMetrics(paths));

        ApiResponse<Map<String, Object>> deps = v1.getClassDependencies(null, paths, null);
        if (deps.isSuccess() && deps.getData() != null) {
            chapter.put("dependencies", deps.getData());
        }

        // 全量类调用图（含精确调用次数 + packageName）：供快照离线类级下钻还原
        chapter.put("callGraph", metricsService.classCallGraph(paths));

        Map<String, Object> ghosts = smellDetector.ghostClasses(paths);
        chapter.put("ghostClasses", ghosts);
        addDetectionIssues(b, "class", "ghost-class", "LOW", ghosts,
            item -> String.valueOf(item.getOrDefault("className", "")),
            item -> "幽灵类：" + item.get("className") + "（" + item.get("reason") + "）");

        Map<String, Object> anemic = smellDetector.anemicDataClasses(paths);
        chapter.put("anemicDataClasses", anemic);
        addDetectionIssues(b, "class", "anemic-class", "LOW", anemic,
            item -> String.valueOf(item.getOrDefault("className", "")),
            item -> "贫血数据类：" + item.get("className") + "（" + item.get("methodCount") + " 个访问器）");

        b.chapter("class", chapter);
    }

    /** 方法级章节：方法级热点 + 超长方法/过多参数/过深调用链。 */
    private void collectMethodChapter(ReportBuilder b, List<String> paths) {
        Map<String, Object> chapter = new LinkedHashMap<>();
        List<Map<String, Object>> hotspots = metricsService.methodHotspots(paths, 50);
        chapter.put("hotspots", hotspots);
        chapter.put("total", hotspots.size());
        for (Map<String, Object> h : hotspots) {
            double score = (double) h.get("riskScore");
            String severity = score >= 0.6 ? "MEDIUM" : "LOW";
            b.addIssue("method", "method-hotspot", severity,
                String.valueOf(h.get("className")) + "." + h.get("methodName"),
                "方法级热点（复杂度 " + h.get("complexity") + "，风险分 " + h.get("riskScore") + "）",
                locationOf(h));
        }

        Map<String, Object> longMethods = smellDetector.longMethods(paths);
        chapter.put("longMethods", longMethods);
        addDetectionIssues(b, "method", "long-method", "MEDIUM", longMethods,
            item -> String.valueOf(item.get("className")) + "." + item.get("methodName"),
            item -> "超长方法：" + item.get("loc") + " 行");

        Map<String, Object> manyParams = smellDetector.tooManyParams(paths);
        chapter.put("tooManyParams", manyParams);
        addDetectionIssues(b, "method", "many-params", "LOW", manyParams,
            item -> String.valueOf(item.get("className")) + "." + item.get("methodName"),
            item -> "过多参数：" + item.get("paramCount") + " 个");

        Map<String, Object> deepChains = smellDetector.deepCallChains(paths);
        chapter.put("deepCallChains", deepChains);
        addDetectionIssues(b, "method", "deep-call-chain", "MEDIUM", deepChains,
            item -> String.valueOf(item.get("className")) + "." + item.get("methodName"),
            item -> "过深调用链：深度 " + item.get("depth"));

        b.chapter("method", chapter);
    }

    /** 项目级聚合检测（复用已拉取数据，不重复查询）：大泥球 + 全局循环依赖。 */
    private void collectProjectAggregations(ReportBuilder b) {
        Map<String, Object> project = b.chapter("project");
        Map<String, Object> module = b.chapter("module");

        @SuppressWarnings("unchecked")
        Map<String, Object> dsm = (Map<String, Object>) module.getOrDefault("dsm", Map.of());
        @SuppressWarnings("unchecked")
        List<String> modules = (List<String>) dsm.getOrDefault("modules", List.of());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> cells = (List<Map<String, Object>>) dsm.getOrDefault("cells", List.of());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> layerViol = (List<Map<String, Object>>) module.getOrDefault("layerViolations", List.of());

        int n = modules.size();
        int edges = cells.size();
        double density = n > 1 ? (double) edges / (n * (double) (n - 1)) : 0.0;
        double crossLayerRatio = edges > 0 ? (double) layerViol.size() / edges : 0.0;
        boolean mud = density >= 0.5 || (density >= 0.3 && crossLayerRatio >= 0.3);

        Map<String, Object> mudData = new LinkedHashMap<>();
        mudData.put("couplingDensity", Math.round(density * 1000.0) / 1000.0);
        mudData.put("crossLayerRatio", Math.round(crossLayerRatio * 1000.0) / 1000.0);
        mudData.put("moduleCount", n);
        mudData.put("isMud", mud);
        project.put("bigBallOfMud", mudData);
        if (mud) {
            b.addIssue("project", "big-ball-of-mud", "HIGH", "project",
                "耦合密度 " + mudData.get("couplingDensity") + "、跨层依赖占比 " + mudData.get("crossLayerRatio") + "，项目呈大泥球结构");
        }

        int domainCycles = countIssuesBySourceLevel(b, "domain-cyclic");
        int packageCycles = countIssuesBySourceLevel(b, "package-cycle");
        int moduleCycles = countIssuesBySourceLevel(b, "module-cycle");
        Set<String> involved = new LinkedHashSet<>();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> cycles = (List<Map<String, Object>>) module.getOrDefault("cycles", List.of());
        for (Map<String, Object> c : cycles) {
            @SuppressWarnings("unchecked")
            List<String> nodes = (List<String>) c.getOrDefault("nodes", List.of());
            involved.addAll(nodes);
        }
        Map<String, Object> gc = new LinkedHashMap<>();
        gc.put("domainCycles", domainCycles);
        gc.put("packageCycles", packageCycles);
        gc.put("moduleCycles", moduleCycles);
        gc.put("totalCycles", domainCycles + packageCycles + moduleCycles);
        gc.put("involvedModuleCount", involved.size());
        project.put("globalCycles", gc);
    }

    /** 模块枢纽：DSM 依赖矩阵中扇入扇出双高（百分位 95）的包。 */
    private List<Map<String, Object>> moduleHubs(Map<String, Object> dsm) {
        @SuppressWarnings("unchecked")
        List<String> modules = (List<String>) dsm.getOrDefault("modules", List.of());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> cells = (List<Map<String, Object>>) dsm.getOrDefault("cells", List.of());
        if (modules.isEmpty()) {
            return List.of();
        }
        Map<Integer, Integer> fanIn = new HashMap<>();
        Map<Integer, Integer> fanOut = new HashMap<>();
        for (int i = 0; i < modules.size(); i++) {
            fanIn.put(i, 0);
            fanOut.put(i, 0);
        }
        for (Map<String, Object> cell : cells) {
            int s = ((Number) cell.get("sourceIdx")).intValue();
            int t = ((Number) cell.get("targetIdx")).intValue();
            fanOut.merge(s, 1, Integer::sum);
            fanIn.merge(t, 1, Integer::sum);
        }
        List<Integer> inVals = fanIn.values().stream().sorted().toList();
        List<Integer> outVals = fanOut.values().stream().sorted().toList();
        int in95 = percentile(inVals, 95);
        int out95 = percentile(outVals, 95);

        List<Map<String, Object>> hubs = new ArrayList<>();
        for (int i = 0; i < modules.size(); i++) {
            int in = fanIn.get(i);
            int out = fanOut.get(i);
            if (in >= in95 && out >= out95 && (in > 0 || out > 0)) {
                Map<String, Object> h = new LinkedHashMap<>();
                h.put("module", modules.get(i));
                h.put("fanIn", in);
                h.put("fanOut", out);
                hubs.add(h);
            }
        }
        hubs.sort((a, b) -> Integer.compare((int) b.get("fanIn"), (int) a.get("fanIn")));
        return hubs;
    }

    /** 反向依赖：分层违规按方向归类为「下层 → 上层」（layerOrderList 越靠前层级越高）。 */
    private List<Map<String, Object>> reverseDependencies(List<Map<String, Object>> violations) {
        List<String> order = ClassLayerRoleDetector.layerOrderList();
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> v : violations) {
            String srcLayer = String.valueOf(v.getOrDefault("sourceLayer", ""));
            String tgtLayer = String.valueOf(v.getOrDefault("targetLayer", ""));
            int srcIdx = order.indexOf(srcLayer);
            int tgtIdx = order.indexOf(tgtLayer);
            if (srcIdx > tgtIdx && srcIdx >= 0 && tgtIdx >= 0) {
                Map<String, Object> item = new LinkedHashMap<>(v);
                item.put("direction", "reverse");
                result.add(item);
            }
        }
        return result;
    }

    /** 将检测结果（envelope.items）的前 ISSUE_CAP 项计入 issue。 */
    private void addDetectionIssues(ReportBuilder b, String dimension, String sourceLevel, String severity,
                                    Map<String, Object> envelope,
                                    java.util.function.Function<Map<String, Object>, String> sourceFn,
                                    java.util.function.Function<Map<String, Object>, String> messageFn) {
        int n = 0;
        for (Map<String, Object> item : itemsOf(envelope)) {
            if (n++ >= ISSUE_CAP) {
                break;
            }
            b.addIssue(dimension, sourceLevel, severity, sourceFn.apply(item), messageFn.apply(item), locationOf(item));
        }
    }

    /** 提取可查证定位信息（className/methodName/nodeId），供 ReAct 智能体灰度验证逐条查证。 */
    private static Map<String, Object> locationOf(Map<String, Object> item) {
        Map<String, Object> loc = new LinkedHashMap<>();
        putNonBlank(loc, "className", firstNonBlank(item.get("className"), item.get("implClass")));
        putNonBlank(loc, "methodName", item.get("methodName"));
        putNonBlank(loc, "nodeId", item.get("nodeId"));
        return loc.isEmpty() ? null : loc;
    }

    private static String firstNonBlank(Object a, Object b) {
        String sa = a == null ? null : String.valueOf(a);
        return (sa != null && !sa.isBlank()) ? sa : (b == null ? null : String.valueOf(b));
    }

    private static void putNonBlank(Map<String, Object> map, String key, Object value) {
        if (value == null) {
            return;
        }
        String s = String.valueOf(value);
        if (!s.isBlank()) {
            map.put(key, s);
        }
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> itemsOf(Map<String, Object> envelope) {
        return (List<Map<String, Object>>) envelope.getOrDefault("items", List.of());
    }

    private static int countIssuesBySourceLevel(ReportBuilder b, String sourceLevel) {
        int count = 0;
        for (Map<String, Object> issue : b.issues()) {
            if (sourceLevel.equals(String.valueOf(issue.getOrDefault("sourceLevel", "")))) {
                count++;
            }
        }
        return count;
    }

    private static int percentile(List<Integer> sorted, int p) {
        if (sorted.isEmpty()) {
            return 0;
        }
        int idx = (int) Math.ceil(sorted.size() * p / 100.0) - 1;
        return sorted.get(Math.max(0, Math.min(idx, sorted.size() - 1)));
    }

    private void saveSnapshots(List<String> paths, Map<String, Object> report, List<Map<String, Object>> issues) {
        int high = 0;
        int medium = 0;
        int low = 0;
        for (Map<String, Object> issue : issues) {
            String sev = String.valueOf(issue.getOrDefault("severity", ""));
            if ("HIGH".equals(sev)) {
                high++;
            } else if ("MEDIUM".equals(sev)) {
                medium++;
            } else {
                low++;
            }
        }
        String reportJson;
        try {
            reportJson = objectMapper.writeValueAsString(report);
        } catch (Exception e) {
            log.warn("[ArchDiagnosis] 序列化报告体失败: {}", e.getMessage());
            reportJson = "{}";
        }
        for (String path : paths) {
            try {
                snapshotRepository.save(ArchDiagnosisSnapshot.builder()
                    .scope(path)
                    .healthScore((Integer) report.get("healthScore"))
                    .grade((String) report.get("grade"))
                    .highCount(high)
                    .mediumCount(medium)
                    .lowCount(low)
                    .reportJson(reportJson)
                    .build());
            } catch (Exception e) {
                log.warn("[ArchDiagnosis] 落快照失败 scope={}: {}", path, e.getMessage());
            }
        }
    }

    /** 报告体构造器：聚合扣分明细，按维度封顶算总分。 */
    private static final class ReportBuilder {
        private final List<String> projectPaths;
        private final Map<String, Map<String, Integer>> pointsByDimension = new LinkedHashMap<>();
        private final List<Map<String, Object>> deductions = new ArrayList<>();
        private final List<Map<String, Object>> issues = new ArrayList<>();
        private final Map<String, Object> chapters = new LinkedHashMap<>();

        ReportBuilder(List<String> projectPaths) {
            this.projectPaths = projectPaths;
        }

        void addIssue(String dimension, String sourceLevel, String severity, String source, String message) {
            addIssue(dimension, sourceLevel, severity, source, message, null);
        }

        void addIssue(String dimension, String sourceLevel, String severity, String source, String message,
                      Map<String, Object> location) {
            int points = ArchDiagnosisScorer.points(severity);
            Map<String, Object> deduction = new LinkedHashMap<>();
            deduction.put("source", source);
            deduction.put("sourceLevel", sourceLevel);
            deduction.put("severity", severity);
            deduction.put("points", points);
            deduction.put("reason", message);
            deductions.add(deduction);

            pointsByDimension.computeIfAbsent(dimension, k -> new LinkedHashMap<>())
                .merge(sourceLevel, points, Integer::sum);

            Map<String, Object> issue = new LinkedHashMap<>();
            issue.put("source", source);
            issue.put("sourceLevel", sourceLevel);
            issue.put("severity", severity);
            issue.put("message", message);
            if (location != null) {
                issue.putAll(location);
            }
            issues.add(issue);
        }

        void chapter(String name, Map<String, Object> data) {
            chapters.put(name, data);
        }

        /** 读回已写入的章节（活引用，供项目级聚合检测补写）。 */
        @SuppressWarnings("unchecked")
        Map<String, Object> chapter(String name) {
            return (Map<String, Object>) chapters.computeIfAbsent(name, k -> new LinkedHashMap<>());
        }

        List<Map<String, Object>> issues() {
            return issues;
        }

        Map<String, Object> build() {
            Map<String, Integer> dimensionTotal = new LinkedHashMap<>();
            for (var e : pointsByDimension.entrySet()) {
                int sum = e.getValue().values().stream().mapToInt(Integer::intValue).sum();
                dimensionTotal.put(e.getKey(), sum);
            }
            int score = ArchDiagnosisScorer.healthScore(dimensionTotal);

            issues.sort((a, b) -> sevRank(b) - sevRank(a));

            Map<String, Object> report = new LinkedHashMap<>();
            report.put("healthScore", score);
            report.put("grade", ArchDiagnosisScorer.grade(score));
            report.put("deductions", deductions);
            report.put("issues", issues);
            report.put("chapters", chapters);
            report.put("layerOrder", ClassLayerRoleDetector.layerOrderList());
            report.put("projectPaths", projectPaths);
            report.put("generatedAt", LocalDateTime.now().format(TS));
            return report;
        }

        private static int sevRank(Map<String, Object> issue) {
            return switch (String.valueOf(issue.getOrDefault("severity", ""))) {
                case "HIGH" -> 3;
                case "MEDIUM" -> 2;
                default -> 1;
            };
        }
    }
}
