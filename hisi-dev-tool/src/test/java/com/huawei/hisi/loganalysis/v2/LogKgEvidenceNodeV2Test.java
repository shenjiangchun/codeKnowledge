package com.huawei.hisi.loganalysis.v2;

import com.huawei.hisi.agentflow.EvidenceAnchor;
import com.huawei.hisi.agentflow.EvidencePack;
import com.huawei.hisi.ram.kg.KgMcpClient;
import com.huawei.hisi.ram.kg.dto.Bridge;
import com.huawei.hisi.ram.kg.dto.CallTreeNode;
import com.huawei.hisi.ram.kg.dto.Entry;
import com.huawei.hisi.ram.kg.dto.MethodBodyInfo;
import com.huawei.hisi.ram.kg.dto.Seed;
import com.huawei.hisi.ram.kg.dto.SqlMapping;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.ObjectProvider;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LogKgEvidenceNodeV2Test {

    private static final String PATH = "D:/repo/svc";

    private KgMcpClient kg;
    private ObjectProvider<KgMcpClient> provider;
    private LogKgEvidenceNodeV2 node;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        kg = mock(KgMcpClient.class);
        provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(kg);
        node = new LogKgEvidenceNodeV2(provider, 8, 6, 1500, 4);
        // Mockito 默认对 List 返回值返回空列表，这里显式声明以便阅读
        when(kg.representativeMethod(anyString(), anyList(), anyInt())).thenReturn(List.of());
        when(kg.hybridSearch(anyString(), anyList(), anyInt())).thenReturn(List.of());
        when(kg.affecting(anyString(), anyString(), anyList(), anyInt())).thenReturn(List.of());
        when(kg.rootEntries(anyString(), anyString(), anyList())).thenReturn(List.of());
    }

    @Test
    @SuppressWarnings("unchecked")
    void exactMatchWinsAndFuzzySearchIsNotCalled() {
        when(kg.representativeMethod(eq("com.svc.order.OrderService"), anyList(), anyInt())).thenReturn(List.of(
                new Seed(PATH + ":com.svc.order.OrderService.deduct.1a2b", 1.0, "扣减库存")));
        when(kg.loadMethodBodies(anyList(), anyList())).thenReturn(List.of(
                new MethodBodyInfo(PATH + ":com.svc.order.OrderService.deduct.1a2b", "com.svc.order.OrderService",
                        "deduct", "扣减", "int left = stock - n;", "OrderService.java")));
        when(kg.affecting(eq("com.svc.order.OrderService"), eq("deduct"), anyList(), anyInt())).thenReturn(List.of(
                new Entry(PATH + ":com.svc.web.OrderController.submit.9f", "com.svc.web.OrderController", "submit", "HTTP"),
                new Entry(PATH + ":com.svc.order.OrderFacade.create.7c", "com.svc.order.OrderFacade", "create", "HTTP")));

        Map<String, Object> output = node.execute(input());

        EvidencePack pack = (EvidencePack) output.get(LogKgEvidenceNodeV2.KEY_EVIDENCE_PACK);
        // 精确命中的帧不再做模糊检索；未命中的业务帧才回退
        verify(kg, never()).hybridSearch(contains("OrderService"), anyList(), anyInt());
        verify(kg).hybridSearch(contains("OrderController"), anyList(), anyInt());
        assertTrue(pack.hasGraphMethod("com.svc.order.OrderService", "deduct"));
        EvidenceAnchor method = pack.list().stream().filter(a -> "method".equals(a.kind())).findFirst().orElseThrow();
        assertTrue(method.exact());
        // 上游调用链被登记为证据，且挂在目标方法上，供验证节点判定因果方向
        EvidenceAnchor upstream = pack.list().stream().filter(a -> "upstream".equals(a.kind())).findFirst().orElseThrow();
        assertEquals("com.svc.order.OrderService.deduct", upstream.methodRef());
        assertTrue(upstream.summary().contains("OrderController.submit"));
        // 源码证据与帧证据都在
        assertTrue(pack.list().stream().anyMatch(a -> "source".equals(a.kind())));
        assertTrue(pack.list().stream().anyMatch(a -> "frame".equals(a.kind())));
        // 落库用的扁平结构同步产出
        assertEquals(pack.size(), ((List<?>) output.get(LogKgEvidenceNodeV2.KEY_EVIDENCE_ANCHORS)).size());
    }

    @Test
    void fallsBackToFuzzySearchAndFlagsItAsInexact() {
        when(kg.hybridSearch(anyString(), anyList(), anyInt())).thenReturn(List.of(
                new Seed(PATH + ":com.svc.order.OrderRepo.find.3d", 0.7, "相近方法")));

        Map<String, Object> output = node.execute(input());

        EvidencePack pack = (EvidencePack) output.get(LogKgEvidenceNodeV2.KEY_EVIDENCE_PACK);
        EvidenceAnchor fuzzy = pack.list().stream().filter(a -> "semantic".equals(a.kind())).findFirst().orElseThrow();
        assertFalse(fuzzy.exact());
        assertTrue(fuzzy.summary().contains("未精确命中"));
        // 模糊命中不能被算作「根因方法在图谱中」
        assertFalse(pack.hasGraphMethod("com.svc.order.OrderService", "deduct"));
        verify(kg, never()).loadMethodBodies(anyList(), anyList());
    }

    @Test
    @SuppressWarnings("unchecked")
    void worksWithoutGraphAndOnlyRegistersFrames() {
        when(provider.getIfAvailable()).thenReturn(null);

        Map<String, Object> output = node.execute(input());

        EvidencePack pack = (EvidencePack) output.get(LogKgEvidenceNodeV2.KEY_EVIDENCE_PACK);
        assertEquals(2, pack.size());
        assertTrue(pack.list().stream().allMatch(a -> "frame".equals(a.kind())));
        verify(kg, never()).representativeMethod(anyString(), anyList(), anyInt());
    }

    @Test
    void graphFailureDoesNotBreakTheFlow() {
        when(kg.representativeMethod(anyString(), anyList(), anyInt())).thenThrow(new RuntimeException("neo4j down"));
        when(kg.hybridSearch(anyString(), anyList(), anyInt())).thenThrow(new RuntimeException("neo4j down"));

        Map<String, Object> output = node.execute(input());

        assertFalse(output.containsKey("errorNode"));
        assertEquals(2, ((EvidencePack) output.get(LogKgEvidenceNodeV2.KEY_EVIDENCE_PACK)).size());
    }

    @Test
    void skipsGraphWhenProjectPathMissing() {
        Map<String, Object> input = input();
        input.remove("projectPath");

        Map<String, Object> output = node.execute(input);

        verify(kg, never()).representativeMethod(anyString(), anyList(), anyInt());
        assertEquals(2, ((EvidencePack) output.get(LogKgEvidenceNodeV2.KEY_EVIDENCE_PACK)).size());
    }

    @Test
    void collectsSqlMappingsAndBridgesWhenRelevant() {
        when(kg.representativeMethod(eq("com.svc.order.OrderMapper"), anyList(), anyInt())).thenReturn(List.of(
                new Seed(PATH + ":com.svc.order.OrderMapper.update.5e", 1.0, null)));
        when(kg.mybatisSql(eq("com.svc.order.OrderMapper"), anyList())).thenReturn(List.of(
                new SqlMapping("com.svc.order.OrderMapper", "UPDATE", List.of("status", "version"))));
        when(kg.bridges(anyString(), anyList())).thenReturn(List.of(
                new Bridge(PATH + ":com.svc.order.OrderMapper.update.5e", "HTTP", "inventory-svc")));

        Map<String, Object> sqlInput = new LinkedHashMap<>();
        sqlInput.put("projectPath", PATH);
        sqlInput.put("message", "org.springframework.jdbc.BadSqlGrammarException: update failed");
        sqlInput.put("rootCauseFrames", List.of(frame("com.svc.order.OrderMapper", "update", "30")));

        Map<String, Object> output = node.execute(sqlInput);

        EvidencePack pack = (EvidencePack) output.get(LogKgEvidenceNodeV2.KEY_EVIDENCE_PACK);
        List<Map<String, Object>> anchors = pack.toMaps();
        assertTrue(anchors.stream().anyMatch(a -> "sql".equals(a.get("kind")) && String.valueOf(a.get("summary")).contains("UPDATE")),
                () -> "未取到 SQL 映射证据: " + anchors);
        assertTrue(anchors.stream().anyMatch(a -> "bridge".equals(a.get("kind")) && String.valueOf(a.get("summary")).contains("inventory-svc")),
                () -> "未取到跨服务调用证据: " + anchors);
    }

    @Test
    void collectsDownstreamCallees() {
        when(kg.representativeMethod(eq("com.svc.order.OrderService"), anyList(), anyInt())).thenReturn(List.of(
                new Seed(PATH + ":com.svc.order.OrderService.deduct.1a2b", 1.0, null)));
        when(kg.calleesTree(eq("com.svc.order.OrderService"), eq("deduct"), anyList(), anyInt())).thenReturn(
                new CallTreeNode(PATH + ":com.svc.order.OrderService.deduct.1a2b", "com.svc.order.OrderService", "deduct", 0,
                        List.of(new CallTreeNode(PATH + ":com.svc.order.OrderRepo.save.4a", "com.svc.order.OrderRepo", "save", 1,
                                List.of()))));

        Map<String, Object> output = node.execute(input());

        EvidencePack pack = (EvidencePack) output.get(LogKgEvidenceNodeV2.KEY_EVIDENCE_PACK);
        assertTrue(pack.list().stream().anyMatch(a -> "downstream".equals(a.kind())
                && a.summary().contains("OrderRepo.save")));
    }

    @Test
    @SuppressWarnings("unchecked")
    void ordersFramesByStackTraceAndMarksTheThrowSite() {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("projectPath", PATH);
        input.put("message", "outer failed");
        input.put("stackTrace", String.join("\n",
                "java.lang.IllegalStateException: outer",
                "\tat com.svc.web.OrderController.submit(OrderController.java:40)",
                "\tat com.svc.Filter.doFilter(Filter.java:9)",
                "Caused by: java.lang.NullPointerException: inner",
                "\tat com.svc.order.OrderService.deduct(OrderService.java:88)",
                "\tat com.svc.order.OrderFacade.create(OrderFacade.java:12)"));
        // v1 ParseNode 的分组：这里模拟「没有传项目包前缀」以外的常规情形
        input.put("businessFrames", List.of(frame("com.svc.web.OrderController", "submit", "40")));
        input.put("rootCauseFrames", List.of(frame("com.svc.order.OrderService", "deduct", "88"),
                frame("com.svc.order.OrderFacade", "create", "12")));

        Map<String, Object> output = node.execute(input);

        // 最深层异常排在最前，第一帧是抛出点；其后是本次调用的上游；再往外是外层异常的帧
        assertEquals("com.svc.order.OrderService.deduct", output.get(LogKgEvidenceNodeV2.KEY_THROW_SITE));
        assertEquals(List.of("com.svc.order.OrderFacade.create", "com.svc.web.OrderController.submit"),
                output.get(LogKgEvidenceNodeV2.KEY_STACK_CALLERS));
        EvidencePack pack = (EvidencePack) output.get(LogKgEvidenceNodeV2.KEY_EVIDENCE_PACK);
        List<String> summaries = pack.list().stream()
                .filter(a -> "frame".equals(a.kind())).map(EvidenceAnchor::summary).toList();
        assertEquals(3, summaries.size());
        assertTrue(summaries.get(0).endsWith("[抛出点]"));
        assertTrue(summaries.get(1).endsWith("[调用方]"));
        assertTrue(summaries.get(2).endsWith("[外层异常调用方]"));
        // ParseNode 已判定为框架帧的不会混进来
        assertFalse(summaries.stream().anyMatch(s -> s.contains("Filter")));
    }

    @Test
    void marksTheOnlyFrameAsThrowSiteEvenWithoutPackagePrefixes() {
        // 不传项目包前缀时 ParseNode 分不出业务帧与根因帧，帧全部落在 keyFrames 里
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("projectPath", PATH);
        input.put("stackTrace", "java.lang.NullPointerException: x\n\tat com.svc.order.OrderService.deduct(OrderService.java:88)");
        input.put("keyFrames", List.of(frame("com.svc.order.OrderService", "deduct", "88")));

        Map<String, Object> output = node.execute(input);

        assertEquals("com.svc.order.OrderService.deduct", output.get(LogKgEvidenceNodeV2.KEY_THROW_SITE));
        assertEquals(List.of(), output.get(LogKgEvidenceNodeV2.KEY_STACK_CALLERS));
    }

    @Test
    void loadsSourceOfTheThrowSitesDirectCalleesForCrossChecking() {
        String deductId = PATH + ":com.svc.order.OrderService.deduct.1a2b";
        String saveId = PATH + ":com.svc.order.OrderRepo.save.4a";
        when(kg.representativeMethod(eq("com.svc.order.OrderService"), anyList(), anyInt()))
                .thenReturn(List.of(new Seed(deductId, 1.0, null)));
        when(kg.calleesTree(eq("com.svc.order.OrderService"), eq("deduct"), anyList(), anyInt())).thenReturn(
                new CallTreeNode(deductId, "com.svc.order.OrderService", "deduct", 0,
                        List.of(new CallTreeNode(saveId, "com.svc.order.OrderRepo", "save", 1, List.of()))));
        when(kg.loadMethodBodies(anyList(), anyList())).thenAnswer(inv -> {
            List<MethodBodyInfo> out = new ArrayList<>();
            for (Object id : (List<?>) (Object) inv.getArgument(0)) {
                String nodeId = String.valueOf(id);
                out.add(new MethodBodyInfo(nodeId, LogKgEvidenceNodeV2.classOf(nodeId),
                        LogKgEvidenceNodeV2.methodOf(nodeId), null, "body", null));
            }
            return out;
        });

        Map<String, Object> output = node.execute(input());

        EvidencePack pack = (EvidencePack) output.get(LogKgEvidenceNodeV2.KEY_EVIDENCE_PACK);
        EvidenceAnchor calleeSource = pack.list().stream()
                .filter(a -> "source".equals(a.kind()) && "com.svc.order.OrderRepo".equals(a.className()))
                .findFirst().orElseThrow();
        assertTrue(calleeSource.summary().contains("抛出点的直接下游方法"));
        // 下游方法的源码同样算作「在图谱中精确存在」，验证节点据此判断相关说法有没有依据
        assertTrue(pack.hasGraphMethod("com.svc.order.OrderRepo", "save"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void readsTheThrowSitesSourceFromLocalFilesWhenTheGraphHasNoBody(@TempDir Path project) throws Exception {
        // 图谱能精确命中，但库里没有这个方法的源码
        when(kg.representativeMethod(eq("com.svc.web.OrderController"), anyList(), anyInt())).thenReturn(List.of(
                new Seed("p:com.svc.web.OrderController.submit.9f", 1.0, null)));
        Path source = project.resolve("src/main/java/com/svc/web/OrderController.java");
        Files.createDirectories(source.getParent());
        Files.writeString(source, String.join("\n",
                "public class OrderController {",
                "    public Order submit(OrderRequest req) {",
                "        return service.place(req);",
                "    }",
                "}",
                ""), StandardCharsets.UTF_8);
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("projectPath", project.toString());
        input.put("stackTrace", "java.lang.NullPointerException: x\n\tat com.svc.web.OrderController.submit(OrderController.java:3)");
        input.put("keyFrames", List.of(frame("com.svc.web.OrderController", "submit", "3")));

        Map<String, Object> output = node.execute(input);

        EvidencePack pack = (EvidencePack) output.get(LogKgEvidenceNodeV2.KEY_EVIDENCE_PACK);
        EvidenceAnchor local = pack.list().stream()
                .filter(a -> "source".equals(a.kind()) && a.summary().contains("按堆栈行号从本地文件截取"))
                .findFirst().orElseThrow();
        assertTrue(local.summary().contains("第 3 行是抛出点"), local.summary());
        // 本地读到的片段带行号，并标出了抛出点所在的那一行
        MethodBodyInfo body = ((List<MethodBodyInfo>) output.get(LogKgEvidenceNodeV2.KEY_CODE_BODIES)).stream()
                .filter(b -> "submit".equals(b.methodName())).findFirst().orElseThrow();
        assertTrue(body.methodBody().contains("   3|         return service.place(req);   // <-- 异常在这里抛出"), body.methodBody());
        // 图谱里的精确命中照样登记
        assertTrue(pack.hasGraphMethod("com.svc.web.OrderController", "submit"));
    }

    @Test
    void doesNotReadLocalSourceAgainWhenTheGraphAlreadyProvidedIt(@TempDir Path project) throws Exception {
        // 本地确实有这个文件：如果去读了，就会出现第二条 source 证据
        Path source = project.resolve("src/main/java/com/svc/order/OrderService.java");
        Files.createDirectories(source.getParent());
        Files.writeString(source, "public class OrderService {\n    void deduct() {\n    }\n}\n", StandardCharsets.UTF_8);
        when(kg.representativeMethod(eq("com.svc.order.OrderService"), anyList(), anyInt())).thenReturn(List.of(
                new Seed("p:com.svc.order.OrderService.deduct.1a2b", 1.0, null)));
        when(kg.loadMethodBodies(anyList(), anyList())).thenReturn(List.of(
                new MethodBodyInfo("p:com.svc.order.OrderService.deduct.1a2b", "com.svc.order.OrderService",
                        "deduct", "描述", "int left = stock - n;", "OrderService.java")));
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("projectPath", project.toString());
        input.put("stackTrace", "java.lang.NullPointerException: x\n\tat com.svc.order.OrderService.deduct(OrderService.java:2)");
        input.put("keyFrames", List.of(frame("com.svc.order.OrderService", "deduct", "2")));

        Map<String, Object> output = node.execute(input);

        EvidencePack pack = (EvidencePack) output.get(LogKgEvidenceNodeV2.KEY_EVIDENCE_PACK);
        List<EvidenceAnchor> sources = pack.list().stream().filter(a -> "source".equals(a.kind())).toList();
        assertEquals(1, sources.size());
        assertFalse(sources.get(0).summary().contains("按堆栈行号从本地文件截取"));
    }

    @Test
    void parsesNodeIdDefensively() {
        // 盘符路径里也有冒号，不能按第一个冒号切
        assertEquals("deduct", LogKgEvidenceNodeV2.methodOf(PATH + ":com.svc.order.OrderService.deduct.1a2b"));
        assertEquals("com.svc.order.OrderService", LogKgEvidenceNodeV2.classOf(PATH + ":com.svc.order.OrderService.deduct.1a2b"));
        assertEquals("com.svc.order.OrderService.deduct",
                LogKgEvidenceNodeV2.methodRef(PATH + ":com.svc.order.OrderService.deduct.1a2b"));
        assertNull(LogKgEvidenceNodeV2.methodOf("python-node-42"));
        assertNull(LogKgEvidenceNodeV2.methodOf(null));
    }

    @Test
    void parsesCommaSeparatedProjectPaths() {
        assertEquals(List.of("D:/a", "D:/b"), LogKgEvidenceNodeV2.parseProjectPaths(" D:/a, D:/b ;D:/a"));
        assertTrue(LogKgEvidenceNodeV2.parseProjectPaths(null).isEmpty());
    }

    private static Map<String, Object> input() {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("projectPath", PATH);
        input.put("message", "java.lang.NullPointerException: stock is null");
        input.put("rootCauseFrames", List.of(frame("com.svc.order.OrderService", "deduct", "88")));
        input.put("businessFrames", List.of(frame("com.svc.web.OrderController", "submit", "40")));
        return input;
    }

    private static Map<String, Object> frame(String className, String methodName, String line) {
        Map<String, Object> f = new LinkedHashMap<>();
        f.put("className", className);
        f.put("methodName", methodName);
        f.put("lineNumber", line);
        f.put("fileName", className.substring(className.lastIndexOf('.') + 1) + ".java");
        f.put("simpleClassName", className.substring(className.lastIndexOf('.') + 1));
        return f;
    }
}
