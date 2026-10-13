package com.huawei.hisi.archdiagnosis.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.neo4j.driver.Driver;
import org.neo4j.driver.Record;
import org.neo4j.driver.Result;
import org.neo4j.driver.Session;
import org.neo4j.driver.SessionConfig;
import org.neo4j.driver.Values;

import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 方法级热点 + 类级度量排行单测（Mock Session.run）。
 */
class MethodClassMetricsServiceTest {

    @Mock private Driver driver;
    @Mock private Session session;
    private MethodClassMetricsService service;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        service = new MethodClassMetricsService(driver, SessionConfig.forDatabase("neo4j"));
        lenient().when(driver.session(any(SessionConfig.class))).thenReturn(session);
    }

    private Result resultOf(List<Map<String, Object>> rows) {
        Result result = mock(Result.class);
        List<Record> recs = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            Record rec = mock(Record.class);
            for (var e : row.entrySet()) {
                when(rec.get(e.getKey())).thenReturn(Values.value(e.getValue()));
            }
            recs.add(rec);
        }
        Iterator<Record> it = recs.iterator();
        when(result.hasNext()).thenAnswer(inv -> it.hasNext());
        when(result.next()).thenAnswer(inv -> it.next());
        return result;
    }

    @Test
    @DisplayName("方法级热点：按 riskScore 降序返回")
    void methodHotspots_sortedByRisk() {
        Result methods = resultOf(List.of(
            Map.of("nodeId", "a.OrderService.place", "className", "OrderService",
                "methodName", "place", "signature", "(Order)", "filePath", "src/OrderService.java",
                "complexity", 30, "inDegree", 5, "commitCount", 40),
            Map.of("nodeId", "a.PaymentService.pay", "className", "PaymentService",
                "methodName", "pay", "signature", "(Long)", "filePath", "src/PaymentService.java",
                "complexity", 20, "inDegree", 7, "commitCount", 10)));
        Result cycles = resultOf(List.of());

        when(session.run(contains("m.complexity IS NOT NULL"), anyMap())).thenReturn(methods);
        when(session.run(contains("[r:CALLS]"), anyMap())).thenReturn(cycles);

        List<Map<String, Object>> hotspots = service.methodHotspots(List.of("/test"), 10);

        assertThat(hotspots).hasSize(2);
        // 复杂度 30 + churn 40 的方法风险分更高
        assertThat(hotspots.get(0).get("methodName")).isEqualTo("place");
        assertThat(hotspots.get(0).get("riskScore")).isNotNull();
    }

    @Test
    @DisplayName("方法级热点：limit 截断")
    void methodHotspots_limit() {
        Result methods = resultOf(List.of(
            Map.of("nodeId", "a.A", "className", "A", "methodName", "m1",
                "signature", "(int)", "filePath", "f1", "complexity", 10, "inDegree", 1, "commitCount", 1),
            Map.of("nodeId", "a.B", "className", "B", "methodName", "m2",
                "signature", "(int)", "filePath", "f2", "complexity", 9, "inDegree", 2, "commitCount", 2)));
        Result cycles = resultOf(List.of());

        when(session.run(contains("m.complexity IS NOT NULL"), anyMap())).thenReturn(methods);
        when(session.run(contains("[r:CALLS]"), anyMap())).thenReturn(cycles);

        List<Map<String, Object>> hotspots = service.methodHotspots(List.of("/test"), 1);

        assertThat(hotspots).hasSize(1);
    }

    @Test
    @DisplayName("方法级热点：同方法多 nodeId 去重（保留复杂度最高者）")
    void methodHotspots_dedupMultiNodeId() {
        Result methods = resultOf(List.of(
            Map.of("nodeId", "java:A.m", "className", "A", "methodName", "m",
                "signature", "(int)", "filePath", "A.java", "complexity", 30, "inDegree", 1, "commitCount", 1),
            Map.of("nodeId", "cg:A.m", "className", "A", "methodName", "m",
                "signature", "(int)", "filePath", "A.java", "complexity", 15, "inDegree", 2, "commitCount", 2)));
        Result cycles = resultOf(List.of());

        when(session.run(contains("m.complexity IS NOT NULL"), anyMap())).thenReturn(methods);
        when(session.run(contains("[r:CALLS]"), anyMap())).thenReturn(cycles);

        List<Map<String, Object>> hotspots = service.methodHotspots(List.of("/test"), 10);

        assertThat(hotspots).hasSize(1);
        assertThat(hotspots.get(0).get("nodeId")).isEqualTo("java:A.m");
        assertThat(hotspots.get(0).get("complexity")).isEqualTo(30);
    }

    @Test
    @DisplayName("方法级热点：签名格式不一致（全限定名 vs 简单名）但参数个数相同 → 仍去重为一条")
    void methodHotspots_dedupSignatureFormatMismatch() {
        // Java 扫描器（javaparser 全限定名）与 codegraph 管线（简单名）对同一方法签名字符串不同
        Result methods = resultOf(List.of(
            Map.of("nodeId", "java:A.m", "className", "A", "methodName", "m",
                "signature", "(java.lang.String a, int b)", "filePath", "A.java",
                "complexity", 30, "inDegree", 1, "commitCount", 1),
            Map.of("nodeId", "cg:A.m", "className", "A", "methodName", "m",
                "signature", "(String a, int b)", "filePath", "A.java",
                "complexity", 15, "inDegree", 2, "commitCount", 2)));
        Result cycles = resultOf(List.of());

        when(session.run(contains("m.complexity IS NOT NULL"), anyMap())).thenReturn(methods);
        when(session.run(contains("[r:CALLS]"), anyMap())).thenReturn(cycles);

        List<Map<String, Object>> hotspots = service.methodHotspots(List.of("/test"), 10);

        assertThat(hotspots).hasSize(1);
        assertThat(hotspots.get(0).get("nodeId")).isEqualTo("java:A.m");
    }

    @Test
    @DisplayName("类级度量：上帝类标记")
    void classMetrics_godClass() {
        Result god = resultOf(List.of(
            Map.of("className", "com.example.GodService", "methodCount", 50, "wmc", 500, "avgComplexity", 10.0),
            Map.of("className", "com.example.SmallService", "methodCount", 5, "wmc", 20, "avgComplexity", 4.0)));
        Result outDeg = resultOf(List.of(
            Map.of("className", "com.example.GodService", "degree", 10)));
        Result inDeg = resultOf(List.of(
            Map.of("className", "com.example.GodService", "degree", 8)));
        Result cycles = resultOf(List.of());

        when(session.run(contains("HAS_METHOD"), anyMap())).thenReturn(god);
        when(session.run(contains("c1.className"), anyMap())).thenReturn(outDeg);
        when(session.run(contains("c2.className"), anyMap())).thenReturn(inDeg);
        when(session.run(contains("[r:CALLS]"), anyMap())).thenReturn(cycles);

        Map<String, Object> result = service.classMetrics(List.of("/test"));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> godClasses = (List<Map<String, Object>>) result.get("godClasses");
        assertThat(godClasses).isNotEmpty();
        assertThat(godClasses.get(0).get("className")).isEqualTo("com.example.GodService");
    }

    @Test
    @DisplayName("类调用图：nodes 含 packageName，edges 含精确调用次数")
    void classCallGraph_exactWeightAndPackage() {
        Result nodes = resultOf(List.of(
            Map.of("className", "com.a.OrderService", "packageName", "com.a"),
            Map.of("className", "com.b.PaymentService", "packageName", "com.b")));
        Result edges = resultOf(List.of(
            Map.of("source", "com.a.OrderService", "target", "com.b.PaymentService", "weight", 3L)));

        when(session.run(contains("coalesce(m.packageName"), anyMap())).thenReturn(nodes);
        when(session.run(contains("count(r)"), anyMap())).thenReturn(edges);

        Map<String, Object> result = service.classCallGraph(List.of("/test"));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> outNodes = (List<Map<String, Object>>) result.get("nodes");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> outEdges = (List<Map<String, Object>>) result.get("edges");
        assertThat(outNodes).hasSize(2);
        assertThat(outNodes.get(0)).containsEntry("packageName", "com.a");
        assertThat(outEdges).hasSize(1);
        assertThat(outEdges.get(0)).containsEntry("weight", 3);
    }

    @Test
    @DisplayName("类调用图：同 className 多 method 行去重为一条节点")
    void classCallGraph_dedupClassName() {
        Result nodes = resultOf(List.of(
            Map.of("className", "com.a.OrderService", "packageName", "com.a"),
            Map.of("className", "com.a.OrderService", "packageName", "com.a")));
        Result edges = resultOf(List.of());

        when(session.run(contains("coalesce(m.packageName"), anyMap())).thenReturn(nodes);
        when(session.run(contains("count(r)"), anyMap())).thenReturn(edges);

        Map<String, Object> result = service.classCallGraph(List.of("/test"));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> outNodes = (List<Map<String, Object>>) result.get("nodes");
        assertThat(outNodes).hasSize(1);
    }

    @Test
    @DisplayName("无数据返回空列表")
    void emptyData() {
        Result empty = resultOf(List.of());
        when(session.run(anyString(), anyMap())).thenReturn(empty);

        assertThat(service.methodHotspots(List.of("/test"), 10)).isEmpty();
        Map<String, Object> metrics = service.classMetrics(List.of("/test"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> god = (List<Map<String, Object>>) metrics.get("godClasses");
        assertThat(god).isEmpty();
    }
}