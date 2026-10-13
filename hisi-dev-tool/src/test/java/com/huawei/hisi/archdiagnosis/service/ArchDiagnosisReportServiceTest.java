package com.huawei.hisi.archdiagnosis.service;

import com.huawei.hisi.archdiagnosis.domain.VerticalDomain;
import com.huawei.hisi.archdiagnosis.domain.VerticalDomainAggregator;
import com.huawei.hisi.archdiagnosis.domain.VerticalDomainNamer;
import com.huawei.hisi.archdiagnosis.domain.VerticalDomainResult;
import com.huawei.hisi.archdiagnosis.repository.ArchDiagnosisSnapshotRepository;
import com.huawei.hisi.knowledgegraph.controller.KnowledgeGraphController;
import com.huawei.hisi.model.ApiResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * 模块级章节完整性单测：环数据写入 chapters.module.cycles（与 issues 同源）。
 */
@ExtendWith(MockitoExtension.class)
class ArchDiagnosisReportServiceTest {

    @Mock
    private KnowledgeGraphController v1;
    @Mock
    private MethodClassMetricsService metricsService;
    @Mock
    private ArchSmellDetectorService smellDetector;
    @Mock
    private VerticalDomainAggregator verticalDomainAggregator;
    @Mock
    private VerticalDomainNamer verticalDomainNamer;
    @Mock
    private ArchDiagnosisSnapshotRepository snapshotRepository;

    private ArchDiagnosisReportService service() {
        return new ArchDiagnosisReportService(v1, metricsService, smellDetector,
                verticalDomainAggregator, verticalDomainNamer, snapshotRepository, new ObjectMapper());
    }

    /** 所有底层查询默认返回空成功响应，避免 NPE；单个用例再覆盖关注项。 */
    private void stubEmptyDefaults() {
        lenient().when(v1.getDashboard(isNull(), anyList(), any())).thenReturn(ApiResponse.success(empty()));
        lenient().when(v1.getServiceTopology(isNull(), anyList(), any())).thenReturn(ApiResponse.success(empty()));
        lenient().when(v1.getDsm(isNull(), anyList(), any(), any())).thenReturn(ApiResponse.success(empty()));
        lenient().when(v1.getPackageCycles(isNull(), anyList())).thenReturn(ApiResponse.success(cyclesOf(List.of())));
        lenient().when(v1.getModuleCycles(isNull(), anyList())).thenReturn(ApiResponse.success(cyclesOf(List.of())));
        lenient().when(v1.getBuildModuleLayerViolations(isNull(), anyList())).thenReturn(ApiResponse.success(empty()));
        lenient().when(v1.getClassLayerViolations(isNull(), anyList())).thenReturn(ApiResponse.success(empty()));
        lenient().when(v1.getClassDependencies(isNull(), anyList(), any())).thenReturn(ApiResponse.success(empty()));
        lenient().when(metricsService.classMetrics(anyList())).thenReturn(empty());
        lenient().when(metricsService.classCallGraph(anyList())).thenReturn(empty());
        lenient().when(metricsService.methodHotspots(anyList(), org.mockito.ArgumentMatchers.anyInt())).thenReturn(List.of());
        lenient().when(smellDetector.bloatedPackages(anyList())).thenReturn(emptyItems());
        lenient().when(smellDetector.implementationLeaks(anyList())).thenReturn(emptyItems());
        lenient().when(smellDetector.ghostClasses(anyList())).thenReturn(emptyItems());
        lenient().when(smellDetector.anemicDataClasses(anyList())).thenReturn(emptyItems());
        lenient().when(smellDetector.longMethods(anyList())).thenReturn(emptyItems());
        lenient().when(smellDetector.tooManyParams(anyList())).thenReturn(emptyItems());
        lenient().when(smellDetector.deepCallChains(anyList())).thenReturn(emptyItems());
        lenient().when(verticalDomainAggregator.loadClassRoles(anyList())).thenReturn(List.of());
        lenient().when(verticalDomainNamer.nameDomains(anyList())).thenReturn(List.of());
    }

    private Map<String, Object> empty() {
        return new LinkedHashMap<>();
    }

    private Map<String, Object> emptyItems() {
        return Map.of("items", List.of());
    }

    private Map<String, Object> cyclesOf(List<Map<String, Object>> cycles) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("cycles", cycles);
        return m;
    }

    private Map<String, Object> cycle(String level, String message, List<String> nodes) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("level", level);
        m.put("message", message);
        m.put("nodes", nodes);
        return m;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> moduleChapter(Map<String, Object> report) {
        Map<String, Object> chapters = (Map<String, Object>) report.get("chapters");
        return (Map<String, Object>) chapters.get("module");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> projectChapter(Map<String, Object> report) {
        Map<String, Object> chapters = (Map<String, Object>) report.get("chapters");
        return (Map<String, Object>) chapters.get("project");
    }

    private Map<String, Object> dsmOf(List<String> modules, List<Map<String, Object>> cells) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("modules", modules);
        m.put("cells", cells);
        m.put("level", "package");
        return m;
    }

    @Test
    @DisplayName("有环时 chapters.module.cycles 含全部环（含 level/message/nodes）")
    void cyclesWrittenToChapter() {
        stubEmptyDefaults();
        when(v1.getPackageCycles(isNull(), anyList())).thenReturn(ApiResponse.success(cyclesOf(List.of(
                cycle("CROSS_LAYER", "controller → service → repository → controller",
                        List.of("a.controller", "a.service", "a.repository"))))));
        when(v1.getModuleCycles(isNull(), anyList())).thenReturn(ApiResponse.success(cyclesOf(List.of(
                cycle("SAME_LAYER", "service.a ⇄ service.b", List.of("x.service.a", "x.service.b"))))));

        Map<String, Object> report = service().generate(List.of("p1"), "java", false);

        List<Map<String, Object>> cycles = (List<Map<String, Object>>) moduleChapter(report).get("cycles");
        assertThat(cycles).hasSize(2);
        assertThat(cycles.get(0)).containsEntry("level", "CROSS_LAYER")
                .containsEntry("message", "controller → service → repository → controller");
        assertThat((List<Object>) cycles.get(0).get("nodes")).hasSize(3);
        // 与 issues 同源
        assertThat((List<Object>) report.get("issues")).anySatisfy(i ->
                assertThat(i).asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
                        .containsEntry("sourceLevel", "package-cycle"));
    }

    @Test
    @DisplayName("无环时 cycles=[] 且键存在，章节结构完整")
    void cyclesKeyPresentWhenEmpty() {
        stubEmptyDefaults();

        Map<String, Object> report = service().generate(List.of("p1"), "java", false);

        Map<String, Object> module = moduleChapter(report);
        assertThat(module).containsKey("cycles");
        assertThat((List<Object>) module.get("cycles")).isEmpty();
    }

    @Test
    @DisplayName("persist=false 不落快照")
    void persistFalseSkipsSnapshot() {
        stubEmptyDefaults();

        service().generate(List.of("p1"), "java", false);

        org.mockito.Mockito.verify(snapshotRepository, org.mockito.Mockito.never())
                .save(any());
    }

    @Test
    @DisplayName("项目级聚合：大泥球 + 全局循环依赖写入 project 章节")
    void projectAggregationsWritten() {
        stubEmptyDefaults();
        // 3 模块全连接 → 密度 1.0 → 大泥球
        when(v1.getDsm(isNull(), anyList(), any(), any())).thenReturn(ApiResponse.success(dsmOf(
                List.of("a", "b", "c"),
                List.of(
                        Map.of("sourceIdx", 0, "targetIdx", 1),
                        Map.of("sourceIdx", 1, "targetIdx", 0),
                        Map.of("sourceIdx", 0, "targetIdx", 2),
                        Map.of("sourceIdx", 2, "targetIdx", 0),
                        Map.of("sourceIdx", 1, "targetIdx", 2),
                        Map.of("sourceIdx", 2, "targetIdx", 1)))));

        Map<String, Object> report = service().generate(List.of("p1"), "java", false);

        Map<String, Object> project = projectChapter(report);
        assertThat(project).containsKeys("bigBallOfMud", "globalCycles");
        Map<String, Object> mud = (Map<String, Object>) project.get("bigBallOfMud");
        assertThat(mud).containsEntry("isMud", true);
    }

    @Test
    @DisplayName("原始检测：臃肿包结果写入 module 章节并计入 issue")
    void smellDetectionWrittenToChapter() {
        stubEmptyDefaults();
        when(smellDetector.bloatedPackages(anyList())).thenReturn(Map.of("items", List.of(
                Map.of("packageName", "a.big", "classCount", 50, "methodCount", 300))));

        Map<String, Object> report = service().generate(List.of("p1"), "java", false);

        Map<String, Object> module = moduleChapter(report);
        assertThat(module).containsKey("bloatedPackages");
        assertThat((List<Object>) report.get("issues")).anySatisfy(i ->
                assertThat(i).asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
                        .containsEntry("sourceLevel", "bloated-package"));
    }

    @Test
    @DisplayName("项目级领域切换为垂直领域识别：domains + isolatedDataObjects 写入 project 章节")
    void verticalDomainsWrittenToChapter() {
        stubEmptyDefaults();
        VerticalDomain order = new VerticalDomain("Order", List.of(),
                Set.of("CONTROLLER", "SERVICE"), true).withName("订单域");
        VerticalDomain isolated = new VerticalDomain("OrderEntity", List.of(),
                Set.of("MODEL"), false);
        when(verticalDomainAggregator.aggregate(anyList()))
                .thenReturn(new VerticalDomainResult(List.of(order), List.of(isolated)));
        when(verticalDomainNamer.nameDomains(anyList())).thenReturn(List.of(order));

        Map<String, Object> report = service().generate(List.of("p1"), "java", false);

        Map<String, Object> project = projectChapter(report);
        assertThat(project).containsKeys("domains", "isolatedDataObjects");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> domains = (List<Map<String, Object>>) project.get("domains");
        assertThat(domains).hasSize(1);
        assertThat(domains.get(0)).containsEntry("name", "订单域")
                .containsEntry("domainWord", "Order")
                .containsEntry("memberCount", 0)
                .containsEntry("complete", true);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> isolatedList = (List<Map<String, Object>>) project.get("isolatedDataObjects");
        assertThat(isolatedList).hasSize(1);
        assertThat(isolatedList.get(0)).containsEntry("domainWord", "OrderEntity");
    }

    @Test
    @DisplayName("证据可查证：检测类 issue 条目携带 className/methodName/nodeId")
    void detectionIssuesCarryLocation() {
        stubEmptyDefaults();
        when(smellDetector.longMethods(anyList())).thenReturn(Map.of("items", List.of(
                Map.of("nodeId", "n1", "className", "a.b.FooService", "methodName", "doWork",
                        "filePath", "FooService.java", "loc", 120))));
        when(smellDetector.ghostClasses(anyList())).thenReturn(Map.of("items", List.of(
                Map.of("className", "a.b.Orphan", "packageName", "a.b",
                        "methodCount", 0, "inDegree", 0, "outDegree", 0, "reason", "no_method"))));
        when(smellDetector.implementationLeaks(anyList())).thenReturn(Map.of("items", List.of(
                Map.of("implClass", "a.impl.FooServiceImpl", "ifaceClass", "a.api.FooService",
                        "implPackage", "a.impl", "ifacePackage", "a.api",
                        "externalCallers", List.of("a.test.FooTest")))));

        Map<String, Object> report = service().generate(List.of("p1"), "java", false);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> issues = (List<Map<String, Object>>) report.get("issues");
        assertThat(issues).anySatisfy(i -> assertThat(i)
                .containsEntry("sourceLevel", "long-method")
                .containsEntry("className", "a.b.FooService")
                .containsEntry("methodName", "doWork")
                .containsEntry("nodeId", "n1"));
        assertThat(issues).anySatisfy(i -> assertThat(i)
                .containsEntry("sourceLevel", "ghost-class")
                .containsEntry("className", "a.b.Orphan"));
        assertThat(issues).anySatisfy(i -> assertThat(i)
                .containsEntry("sourceLevel", "impl-leak")
                .containsEntry("className", "a.impl.FooServiceImpl"));
    }

    @Test
    @DisplayName("路径归一化：反斜杠项目路径转正斜杠后再查询，避免 Neo4j projectPath 失配致四层空")
    void backslashPathsNormalizedBeforeQuery() {
        stubEmptyDefaults();

        service().generate(List.of("C:\\projects\\demo"), "java", false);

        org.mockito.Mockito.verify(v1).getDashboard(isNull(),
                org.mockito.ArgumentMatchers.eq(List.of("C:/projects/demo")), any());
        org.mockito.Mockito.verify(verticalDomainAggregator).loadClassRoles(List.of("C:/projects/demo"));
        org.mockito.Mockito.verify(metricsService).classMetrics(List.of("C:/projects/demo"));
        org.mockito.Mockito.verify(smellDetector).longMethods(List.of("C:/projects/demo"));
    }

    @Test
    @DisplayName("证据可查证：方法级热点 issue 携带 className/methodName/nodeId")
    void methodHotspotIssuesCarryLocation() {
        stubEmptyDefaults();
        when(metricsService.methodHotspots(anyList(), org.mockito.ArgumentMatchers.anyInt()))
                .thenReturn(List.of(Map.of(
                        "nodeId", "n9", "className", "a.b.HotService", "methodName", "handle",
                        "filePath", "HotService.java", "complexity", 40,
                        "commitCount90d", 5, "inDegree", 3, "riskScore", 0.8)));

        Map<String, Object> report = service().generate(List.of("p1"), "java", false);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> issues = (List<Map<String, Object>>) report.get("issues");
        assertThat(issues).anySatisfy(i -> assertThat(i)
                .containsEntry("sourceLevel", "method-hotspot")
                .containsEntry("className", "a.b.HotService")
                .containsEntry("methodName", "handle")
                .containsEntry("nodeId", "n9"));
    }

    @Test
    @DisplayName("类调用图（精确调用次数）写入 chapters.class.callGraph，供快照离线类级下钻")
    void classCallGraphWrittenToChapter() {
        stubEmptyDefaults();
        Map<String, Object> callGraph = new LinkedHashMap<>();
        callGraph.put("nodes", List.of(Map.of("className", "com.a.OrderService", "packageName", "com.a")));
        callGraph.put("edges", List.of(Map.of("source", "com.a.OrderService", "target", "com.b.PaymentService", "weight", 3)));
        when(metricsService.classCallGraph(anyList())).thenReturn(callGraph);

        Map<String, Object> report = service().generate(List.of("p1"), "java", false);

        Map<String, Object> chapters = (Map<String, Object>) report.get("chapters");
        Map<String, Object> classChapter = (Map<String, Object>) chapters.get("class");
        assertThat(classChapter).containsKey("callGraph");
        Map<String, Object> written = (Map<String, Object>) classChapter.get("callGraph");
        assertThat((List<Object>) written.get("edges")).hasSize(1);
    }
}
