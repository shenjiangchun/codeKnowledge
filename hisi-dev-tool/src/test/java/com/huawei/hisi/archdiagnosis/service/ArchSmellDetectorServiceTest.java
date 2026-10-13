package com.huawei.hisi.archdiagnosis.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.neo4j.driver.Driver;
import org.neo4j.driver.Record;
import org.neo4j.driver.Result;
import org.neo4j.driver.Session;
import org.neo4j.driver.SessionConfig;
import org.neo4j.driver.Values;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 四层坏味道检测单测（只读 Cypher 实时聚合，不落库）。
 *
 * <p>覆盖 7 项免重建检测：臃肿包 / 实现类外泄 / 幽灵类 / 贫血数据类 /
 * 超长方法 / 过多参数 / 过深调用链，验证阈值与返回结构。</p>
 */
class ArchSmellDetectorServiceTest {

    @Mock private Driver driver;
    @Mock private Session session;
    private ArchSmellDetectorService service;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        service = new ArchSmellDetectorService(driver, SessionConfig.forDatabase("neo4j"));
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

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> items(Map<String, Object> envelope) {
        return (List<Map<String, Object>>) envelope.get("items");
    }

    @Test
    @DisplayName("臃肿包：类数与方法数双高（百分位）才标记")
    void bloatedPackages_flagsDoubleHigh() {
        Result classes = resultOf(List.of(
            Map.of("packageName", "a.big", "classCount", 50, "methodCount", 300),
            Map.of("packageName", "a.small", "classCount", 3, "methodCount", 9)));

        when(session.run(anyString(), anyMap())).thenReturn(classes);

        Map<String, Object> out = service.bloatedPackages(List.of("/test"));

        assertThat(items(out)).hasSize(1);
        assertThat(items(out).get(0)).containsEntry("packageName", "a.big");
        assertThat(out).containsKey("thresholds");
    }

    @Test
    @DisplayName("实现类外泄：接口与实现跨包且存在外部直接调用方")
    void implementationLeaks_flagsCrossPackageCallers() {
        Result leaks = resultOf(List.of(
            Map.of("implClass", "a.impl.OrderServiceImpl", "implPackage", "a.impl",
                "ifaceClass", "a.api.OrderService", "ifacePackage", "a.api",
                "externalCallers", List.of("b.web.OrderController", "c.web.UserController")),
            Map.of("implClass", "a.impl.InternalImpl", "implPackage", "a.impl",
                "ifaceClass", "a.api.InternalApi", "ifacePackage", "a.api",
                "externalCallers", List.of())));

        when(session.run(contains("IMPLEMENTS"), anyMap())).thenReturn(leaks);

        Map<String, Object> out = service.implementationLeaks(List.of("/test"));

        assertThat(items(out)).hasSize(1);
        assertThat(items(out).get(0)).containsEntry("implClass", "a.impl.OrderServiceImpl");
    }

    @Test
    @DisplayName("幽灵类：无方法或零出入度")
    void ghostClasses_flagsEmptyOrIsolated() {
        Result ghosts = resultOf(List.of(
            Map.of("className", "a.Empty", "packageName", "a",
                "methodCount", 0, "inDegree", 0, "outDegree", 0),
            Map.of("className", "a.Isolated", "packageName", "a",
                "methodCount", 2, "inDegree", 0, "outDegree", 0)));

        when(session.run(contains("HAS_METHOD"), anyMap())).thenReturn(ghosts);

        Map<String, Object> out = service.ghostClasses(List.of("/test"));

        assertThat(items(out)).hasSize(2);
        assertThat(items(out).get(0)).containsEntry("className", "a.Empty");
    }

    @Test
    @DisplayName("贫血数据类：≥3 个方法且全为 get/set/is/has 前缀（Cypher WHERE 过滤后）")
    void anemicDataClasses_flagsAllAccessors() {
        // mock 模拟 Cypher WHERE（size>=3 && all get/set/is）过滤后的结果
        Result anemic = resultOf(List.of(
            Map.of("className", "a.UserDto", "packageName", "a",
                "methodNames", List.of("getName", "setName", "isActive"))));

        when(session.run(contains("methodName"), anyMap())).thenReturn(anemic);

        Map<String, Object> out = service.anemicDataClasses(List.of("/test"));

        assertThat(items(out)).hasSize(1);
        assertThat(items(out).get(0)).containsEntry("className", "a.UserDto")
                .containsEntry("methodCount", 3);
    }

    @Test
    @DisplayName("超长方法：LOC 超过 max(50, p95)")
    void longMethods_flagsOverThreshold() {
        Result methods = resultOf(List.of(
            Map.of("nodeId", "a.A.m", "className", "a.A", "methodName", "m",
                "filePath", "A.java", "loc", 120),
            Map.of("nodeId", "a.B.n", "className", "a.B", "methodName", "n",
                "filePath", "B.java", "loc", 20)));

        when(session.run(contains("startLine"), anyMap())).thenReturn(methods);

        Map<String, Object> out = service.longMethods(List.of("/test"));

        assertThat(items(out)).hasSize(1);
        assertThat(items(out).get(0)).containsEntry("methodName", "m");
    }

    @Test
    @DisplayName("过多参数：忽略泛型逗号，参数数超过 max(5, p95)")
    void tooManyParams_ignoresGenericCommas() {
        Result methods = resultOf(List.of(
            Map.of("nodeId", "a.A.m", "className", "a.A", "methodName", "m",
                "signature", "(Map<String,Integer> a, List<String> b, int c, int d, int e, int f)"),
            Map.of("nodeId", "a.B.n", "className", "a.B", "methodName", "n",
                "signature", "(int a)")));

        when(session.run(contains("signature"), anyMap())).thenReturn(methods);

        Map<String, Object> out = service.tooManyParams(List.of("/test"));

        assertThat(items(out)).hasSize(1);
        assertThat(items(out).get(0)).containsEntry("paramCount", 6);
    }

    @Test
    @DisplayName("过深调用链：从根出发 BFS 层级超过 max(5, p95)")
    void deepCallChains_flagsDeepNodes() {
        Result methods = resultOf(List.of(
            Map.of("nodeId", "a.A.m0", "className", "a.A", "methodName", "m0"),
            Map.of("nodeId", "a.A.m1", "className", "a.A", "methodName", "m1"),
            Map.of("nodeId", "a.A.m2", "className", "a.A", "methodName", "m2")));
        // 链 m0 → m1 → m2（m0 为根，m2 深度 2；p95=2 → 阈值为 max(8,2)=8，不标记）
        Result edges = resultOf(List.of(
            Map.of("source", "a.A.m0", "target", "a.A.m1"),
            Map.of("source", "a.A.m1", "target", "a.A.m2")));

        when(session.run(contains("m.nodeId AS nodeId"), anyMap())).thenReturn(methods);
        when(session.run(contains("[r:CALLS]"), anyMap())).thenReturn(edges);

        Map<String, Object> out = service.deepCallChains(List.of("/test"));

        // 阈值 8，深度最大 2 → 无标记
        assertThat(items(out)).isEmpty();
    }

    @Test
    @DisplayName("超长方法：Cypher 排除测试类（不把 *Test 的长 setup 方法当坏味道）")
    void longMethods_excludesTestClasses() {
        Result empty = resultOf(List.of());
        when(session.run(anyString(), anyMap())).thenReturn(empty);

        service.longMethods(List.of("/test"));

        ArgumentCaptor<String> c = ArgumentCaptor.forClass(String.class);
        verify(session).run(c.capture(), anyMap());
        assertThat(c.getValue()).contains("NOT m.className =~ '.*(Test|Tests)$'");
    }

    @Test
    @DisplayName("过多参数：Cypher 排除测试类")
    void tooManyParams_excludesTestClasses() {
        Result empty = resultOf(List.of());
        when(session.run(anyString(), anyMap())).thenReturn(empty);

        service.tooManyParams(List.of("/test"));

        ArgumentCaptor<String> c = ArgumentCaptor.forClass(String.class);
        verify(session).run(c.capture(), anyMap());
        assertThat(c.getValue()).contains("NOT m.className =~ '.*(Test|Tests)$'");
    }

    @Test
    @DisplayName("过深调用链：方法节点查询排除测试类（边查询无 className 不适用）")
    void deepCallChains_excludesTestClasses() {
        Result empty = resultOf(List.of());
        when(session.run(anyString(), anyMap())).thenReturn(empty);

        service.deepCallChains(List.of("/test"));

        ArgumentCaptor<String> c = ArgumentCaptor.forClass(String.class);
        verify(session, atLeastOnce()).run(c.capture(), anyMap());
        assertThat(c.getAllValues()).anyMatch(q -> q.contains("m.projectPath IN $paths")
                && q.contains("NOT m.className =~ '.*(Test|Tests)$'"));
    }

    @Test
    @DisplayName("无数据返回空 items")
    void emptyData() {
        Result empty = resultOf(List.of());
        when(session.run(anyString(), anyMap())).thenReturn(empty);

        assertThat(items(service.bloatedPackages(List.of("/test")))).isEmpty();
        assertThat(items(service.implementationLeaks(List.of("/test")))).isEmpty();
        assertThat(items(service.ghostClasses(List.of("/test")))).isEmpty();
        assertThat(items(service.anemicDataClasses(List.of("/test")))).isEmpty();
        assertThat(items(service.longMethods(List.of("/test")))).isEmpty();
        assertThat(items(service.tooManyParams(List.of("/test")))).isEmpty();
        assertThat(items(service.deepCallChains(List.of("/test")))).isEmpty();
    }
}
