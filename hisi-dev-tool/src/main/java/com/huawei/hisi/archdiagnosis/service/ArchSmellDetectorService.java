package com.huawei.hisi.archdiagnosis.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.neo4j.driver.Driver;
import org.neo4j.driver.Session;
import org.neo4j.driver.SessionConfig;
import org.neo4j.driver.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 四层坏味道检测（只读 Cypher 实时聚合，不落库、不碰生成侧）。
 *
 * <p>为架构诊断报告补齐项目/模块/类/方法四层的 11 项检测中需原始查询的 7 项：
 * 臃肿包、实现类外泄、幽灵类、贫血数据类、超长方法、过多参数、过深调用链。
 * 每个方法返回 {@code {items: [...], thresholds: {...}}} 结构，供报告服务与
 * ReAct 智能体工具注册表复用。阈值走百分位（相对项目规模自适应）+ 绝对值下限。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ArchSmellDetectorService {

    private final Driver neo4jDriver;
    private final SessionConfig neo4jSessionConfig;

    /** 绝对下限：低于此值不判坏味道（避免小项目百分位过低误报）。 */
    static final int MIN_LOC = 50;
    static final int MIN_PARAMS = 5;
    static final int MIN_CHAIN_DEPTH = 8;
    static final int MIN_CLASS_COUNT = 3;

    /** 模块级：包内类数与方法数双高（百分位 95）→ 臃肿包。 */
    public Map<String, Object> bloatedPackages(List<String> paths) {
        Map<String, Map<String, Object>> byPackage = new LinkedHashMap<>();
        try (Session session = neo4jDriver.session(neo4jSessionConfig)) {
            var recs = session.run(
                "MATCH (c:Class) WHERE c.projectPath IN $paths AND c.packageName IS NOT NULL\n" +
                "OPTIONAL MATCH (c)-[:HAS_METHOD]->(m:Method)\n" +
                "RETURN c.packageName AS packageName, count(DISTINCT c) AS classCount, count(m) AS methodCount\n" +
                "ORDER BY classCount DESC",
                Map.of("paths", paths));
            while (recs.hasNext()) {
                var r = recs.next();
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("packageName", r.get("packageName").asString());
                item.put("classCount", r.get("classCount").asInt(0));
                item.put("methodCount", r.get("methodCount").asInt(0));
                byPackage.put((String) item.get("packageName"), item);
            }
        }
        List<Integer> cc = byPackage.values().stream().map(v -> (int) v.get("classCount")).sorted().toList();
        List<Integer> mc = byPackage.values().stream().map(v -> (int) v.get("methodCount")).sorted().toList();
        int cc95 = percentile(cc, 95);
        int mc95 = percentile(mc, 95);

        List<Map<String, Object>> items = new ArrayList<>();
        for (Map<String, Object> item : byPackage.values()) {
            int classCount = (int) item.get("classCount");
            int methodCount = (int) item.get("methodCount");
            if (classCount >= Math.max(MIN_CLASS_COUNT, cc95) && methodCount >= mc95) {
                items.add(item);
            }
        }
        items.sort((a, b) -> Integer.compare((int) b.get("classCount"), (int) a.get("classCount")));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("items", items);
        result.put("thresholds", Map.of("classCount95", cc95, "methodCount95", mc95));
        return result;
    }

    /** 模块级：接口与实现跨包、且存在外部直接调用实现类 → 实现类外泄。 */
    public Map<String, Object> implementationLeaks(List<String> paths) {
        List<Map<String, Object>> items = new ArrayList<>();
        try (Session session = neo4jDriver.session(neo4jSessionConfig)) {
            var recs = session.run(
                "MATCH (impl:Method)-[r:IMPLEMENTS]->(iface:Method)\n" +
                "WHERE impl.projectPath IN $paths AND iface.projectPath IN $paths\n" +
                "  AND coalesce(r.implType, 'LOCAL') = 'LOCAL'\n" +
                "  AND impl.packageName IS NOT NULL AND iface.packageName IS NOT NULL\n" +
                "  AND impl.packageName <> iface.packageName\n" +
                "OPTIONAL MATCH (caller:Method)-[c:CALLS]->(impl)\n" +
                "WHERE caller.projectPath IN $paths\n" +
                "  AND caller.packageName IS NOT NULL AND caller.packageName <> impl.packageName\n" +
                "  AND coalesce(c.callType, 'DIRECT') <> 'IMPL_DISPATCH'\n" +
                "  AND coalesce(c.callType, 'DIRECT') <> 'FEIGN_BRIDGE'\n" +
                "RETURN impl.className AS implClass, impl.packageName AS implPackage,\n" +
                "       iface.className AS ifaceClass, iface.packageName AS ifacePackage,\n" +
                "       collect(DISTINCT caller.className) AS externalCallers",
                Map.of("paths", paths));
            while (recs.hasNext()) {
                var r = recs.next();
                List<String> callers = r.get("externalCallers").asList(Value::asString);
                if (callers.isEmpty()) {
                    continue;
                }
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("implClass", r.get("implClass").asString());
                item.put("implPackage", r.get("implPackage").asString());
                item.put("ifaceClass", r.get("ifaceClass").asString());
                item.put("ifacePackage", r.get("ifacePackage").asString());
                item.put("externalCallers", callers);
                items.add(item);
            }
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("items", items);
        return result;
    }

    /** 类级：无方法，或方法整体零出入度（无调用也无被调）→ 幽灵类。 */
    public Map<String, Object> ghostClasses(List<String> paths) {
        List<Map<String, Object>> items = new ArrayList<>();
        try (Session session = neo4jDriver.session(neo4jSessionConfig)) {
            var recs = session.run(
                "MATCH (c:Class) WHERE c.projectPath IN $paths\n" +
                "  AND NOT c.className =~ '.*(Test|Tests)$'\n" +
                "OPTIONAL MATCH (c)-[:HAS_METHOD]->(m:Method)\n" +
                "WITH c, count(m) AS methodCount,\n" +
                "     coalesce(sum(coalesce(m.inDegree, 0)), 0) AS inDegree,\n" +
                "     coalesce(sum(coalesce(m.outDegree, 0)), 0) AS outDegree\n" +
                "WHERE methodCount = 0 OR (inDegree = 0 AND outDegree = 0)\n" +
                "RETURN c.className AS className, c.packageName AS packageName,\n" +
                "       methodCount, inDegree, outDegree",
                Map.of("paths", paths));
            while (recs.hasNext()) {
                var r = recs.next();
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("className", r.get("className").asString());
                item.put("packageName", r.get("packageName").asString(""));
                item.put("methodCount", r.get("methodCount").asInt(0));
                item.put("inDegree", r.get("inDegree").asInt(0));
                item.put("outDegree", r.get("outDegree").asInt(0));
                item.put("reason", r.get("methodCount").asInt(0) == 0 ? "no_method" : "isolated");
                items.add(item);
            }
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("items", items);
        return result;
    }

    /** 类级：≥3 个方法且全为 get/set/is/has 前缀 → 贫血数据类（Java 约定启发式，排除测试类与无实现体的接口）。 */
    public Map<String, Object> anemicDataClasses(List<String> paths) {
        List<Map<String, Object>> items = new ArrayList<>();
        try (Session session = neo4jDriver.session(neo4jSessionConfig)) {
            var recs = session.run(
                "MATCH (c:Class) WHERE c.projectPath IN $paths\n" +
                "  AND NOT c.className =~ '.*(Test|Tests)$'\n" +
                "OPTIONAL MATCH (c)-[:HAS_METHOD]->(m:Method)\n" +
                "WITH c, collect(m) AS ms\n" +
                "WHERE size(ms) >= 3\n" +
                "  AND all(m IN ms WHERE m.methodName =~ '^(get|set|is|has)[A-Z].*')\n" +
                "  AND NOT all(m IN ms WHERE m.methodBody IS NULL OR m.methodBody = '' OR NOT m.methodBody CONTAINS '{')\n" +
                "RETURN c.className AS className, c.packageName AS packageName,\n" +
                "       [m IN ms | m.methodName] AS methodNames",
                Map.of("paths", paths));
            while (recs.hasNext()) {
                var r = recs.next();
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("className", r.get("className").asString());
                item.put("packageName", r.get("packageName").asString(""));
                item.put("methodCount", r.get("methodNames").size());
                item.put("methodNames", r.get("methodNames").asList(Value::asString));
                items.add(item);
            }
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("items", items);
        return result;
    }

    /** 方法级：LOC（endLine-startLine+1）超过 max(50, p95) → 超长方法。 */
    public Map<String, Object> longMethods(List<String> paths) {
        List<Map<String, Object>> rows = new ArrayList<>();
        try (Session session = neo4jDriver.session(neo4jSessionConfig)) {
            var recs = session.run(
                "MATCH (m:Method) WHERE m.projectPath IN $paths\n" +
                "  AND NOT m.className =~ '.*(Test|Tests)$'\n" +
                "  AND m.startLine IS NOT NULL AND m.endLine IS NOT NULL\n" +
                "RETURN m.nodeId AS nodeId, m.className AS className, m.methodName AS methodName,\n" +
                "       m.filePath AS filePath, (m.endLine - m.startLine + 1) AS loc",
                Map.of("paths", paths));
            while (recs.hasNext()) {
                var r = recs.next();
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("nodeId", r.get("nodeId").asString());
                item.put("className", r.get("className").asString(""));
                item.put("methodName", r.get("methodName").asString(""));
                item.put("filePath", r.get("filePath").asString(""));
                item.put("loc", r.get("loc").asInt(0));
                rows.add(item);
            }
        }
        List<Integer> locs = rows.stream().map(v -> (int) v.get("loc")).sorted().toList();
        int loc95 = percentile(locs, 95);
        int threshold = Math.max(MIN_LOC, loc95);

        List<Map<String, Object>> items = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            if ((int) row.get("loc") >= threshold) {
                items.add(row);
            }
        }
        items.sort((a, b) -> Integer.compare((int) b.get("loc"), (int) a.get("loc")));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("items", items);
        result.put("thresholds", Map.of("loc95", loc95, "locMin", MIN_LOC));
        return result;
    }

    /** 方法级：参数数（signature 首组括号内顶层逗号 + 1）超过 max(5, p95) → 过多参数。 */
    public Map<String, Object> tooManyParams(List<String> paths) {
        List<Map<String, Object>> rows = new ArrayList<>();
        try (Session session = neo4jDriver.session(neo4jSessionConfig)) {
            var recs = session.run(
                "MATCH (m:Method) WHERE m.projectPath IN $paths\n" +
                "  AND NOT m.className =~ '.*(Test|Tests)$'\n" +
                "  AND m.signature IS NOT NULL\n" +
                "RETURN m.nodeId AS nodeId, m.className AS className, m.methodName AS methodName, m.signature AS signature",
                Map.of("paths", paths));
            while (recs.hasNext()) {
                var r = recs.next();
                int paramCount = countParams(r.get("signature").asString(""));
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("nodeId", r.get("nodeId").asString());
                item.put("className", r.get("className").asString(""));
                item.put("methodName", r.get("methodName").asString(""));
                item.put("paramCount", paramCount);
                rows.add(item);
            }
        }
        List<Integer> counts = rows.stream().map(v -> (int) v.get("paramCount")).sorted().toList();
        int param95 = percentile(counts, 95);
        int threshold = Math.max(MIN_PARAMS, param95);

        List<Map<String, Object>> items = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            if ((int) row.get("paramCount") >= threshold) {
                items.add(row);
            }
        }
        items.sort((a, b) -> Integer.compare((int) b.get("paramCount"), (int) a.get("paramCount")));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("items", items);
        result.put("thresholds", Map.of("paramCount95", param95, "paramMin", MIN_PARAMS));
        return result;
    }

    /** 方法级：从根方法（无上游调用者）出发的多源 BFS 层级（最短跳数）超过 max(8, p95) → 过深调用链。 */
    public Map<String, Object> deepCallChains(List<String> paths) {
        Map<String, String[]> methodInfo = new LinkedHashMap<>();
        Map<String, List<String>> adj = new HashMap<>();
        Map<String, Integer> inDegree = new HashMap<>();
        try (Session session = neo4jDriver.session(neo4jSessionConfig)) {
            var mrecs = session.run(
                "MATCH (m:Method) WHERE m.projectPath IN $paths\n" +
                "  AND NOT m.className =~ '.*(Test|Tests)$'\n" +
                "RETURN m.nodeId AS nodeId, m.className AS className, m.methodName AS methodName",
                Map.of("paths", paths));
            while (mrecs.hasNext()) {
                var r = mrecs.next();
                methodInfo.put(r.get("nodeId").asString(),
                    new String[]{r.get("className").asString(""), r.get("methodName").asString("")});
            }
            var erecs = session.run(
                "MATCH (a:Method)-[r:CALLS]->(b:Method)\n" +
                "WHERE a.projectPath IN $paths AND b.projectPath IN $paths\n" +
                "RETURN a.nodeId AS source, b.nodeId AS target",
                Map.of("paths", paths));
            while (erecs.hasNext()) {
                var r = erecs.next();
                String src = r.get("source").asString();
                String tgt = r.get("target").asString();
                adj.computeIfAbsent(src, k -> new ArrayList<>()).add(tgt);
                inDegree.merge(tgt, 1, Integer::sum);
                inDegree.putIfAbsent(src, 0);
            }
        }

        Map<String, Integer> level = new HashMap<>();
        Deque<String> queue = new ArrayDeque<>();
        for (String node : methodInfo.keySet()) {
            if (inDegree.getOrDefault(node, 0) == 0) {
                queue.add(node);
                level.put(node, 0);
            }
        }
        while (!queue.isEmpty()) {
            String u = queue.poll();
            int lvl = level.get(u);
            for (String v : adj.getOrDefault(u, List.of())) {
                if (!level.containsKey(v)) {
                    level.put(v, lvl + 1);
                    queue.add(v);
                }
            }
        }

        List<Integer> depths = methodInfo.keySet().stream()
            .map(n -> level.getOrDefault(n, 0)).sorted().toList();
        int depth95 = percentile(depths, 95);
        int threshold = Math.max(MIN_CHAIN_DEPTH, depth95);

        List<Map<String, Object>> items = new ArrayList<>();
        for (String node : methodInfo.keySet()) {
            int depth = level.getOrDefault(node, 0);
            if (depth >= threshold) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("nodeId", node);
                item.put("className", methodInfo.get(node)[0]);
                item.put("methodName", methodInfo.get(node)[1]);
                item.put("depth", depth);
                items.add(item);
            }
        }
        items.sort((a, b) -> Integer.compare((int) b.get("depth"), (int) a.get("depth")));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("items", items);
        result.put("thresholds", Map.of("depth95", depth95, "depthMin", MIN_CHAIN_DEPTH));
        return result;
    }

    /** 百分位（p ∈ [0,100]），沿用 {@link MethodClassMetricsService} 口径。 */
    private int percentile(List<Integer> sorted, int p) {
        if (sorted.isEmpty()) {
            return 0;
        }
        int idx = (int) Math.ceil(sorted.size() * p / 100.0) - 1;
        return sorted.get(Math.max(0, Math.min(idx, sorted.size() - 1)));
    }

    /** 参数计数：取首组 {@code (...)} 内文本，按顶层逗号数 + 1（忽略泛型 {@code <...>} 与数组 {@code [...]}）。 */
    static int countParams(String signature) {
        if (signature == null || signature.isBlank()) {
            return 0;
        }
        String body;
        int open = signature.indexOf('(');
        if (open >= 0) {
            int close = matchingClose(signature, open);
            body = close > open ? signature.substring(open + 1, close) : "";
        } else {
            body = signature;
        }
        if (body.isBlank()) {
            return 0;
        }
        int commas = 0;
        int depth = 0;
        for (char ch : body.toCharArray()) {
            if (ch == '<' || ch == '[') {
                depth++;
            } else if (ch == '>' || ch == ']') {
                depth--;
            } else if (ch == ',' && depth == 0) {
                commas++;
            }
        }
        return commas + 1;
    }

    private static int matchingClose(String s, int open) {
        int depth = 0;
        for (int i = open; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
                if (depth == 0) {
                    return i;
                }
            }
        }
        return s.length() - 1;
    }
}
