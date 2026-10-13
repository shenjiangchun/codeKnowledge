package com.huawei.hisi.archdiagnosis.service;

import com.huawei.hisi.knowledgegraph.aggregation.RiskScoreCalculator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.neo4j.driver.Driver;
import org.neo4j.driver.Session;
import org.neo4j.driver.SessionConfig;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 方法级热点 + 类级上帝类/枢纽类度量（只读 Cypher 实时聚合，不落库）。
 *
 * <p>为架构诊断报告「方法级」「类级」两章提供数据；与聚合管线（7 Stage）解耦，仅派生值计算。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MethodClassMetricsService {

    private final Driver neo4jDriver;
    private final SessionConfig neo4jSessionConfig;

    /** 方法级热点：复杂度 + churn（按 filePath join ChurnNode）+ 入度 + 循环惩罚。 */
    public List<Map<String, Object>> methodHotspots(List<String> paths, int limit) {
        List<MethodRow> rows = loadMethodRows(paths);
        if (rows.isEmpty()) {
            return List.of();
        }
        Set<String> inCycle = detectCycleNodes(paths);

        Map<String, Integer> churnRaw = new LinkedHashMap<>();
        Map<String, Integer> inDegRaw = new LinkedHashMap<>();
        for (MethodRow r : rows) {
            churnRaw.put(r.nodeId(), r.commitCount());
            inDegRaw.put(r.nodeId(), r.inDegree());
        }
        Map<String, Double> churnNorm = RiskScoreCalculator.percentileRank(churnRaw);
        Map<String, Double> inDegNorm = RiskScoreCalculator.percentileRank(inDegRaw);

        List<Map<String, Object>> hotspots = new ArrayList<>();
        for (MethodRow r : rows) {
            double score = RiskScoreCalculator.calculate(
                r.complexity(),
                churnNorm.getOrDefault(r.nodeId(), 0.0),
                inDegNorm.getOrDefault(r.nodeId(), 0.0),
                inCycle.contains(r.nodeId()));
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("nodeId", r.nodeId());
            item.put("className", r.className());
            item.put("methodName", r.methodName());
            item.put("filePath", r.filePath());
            item.put("complexity", r.complexity());
            item.put("commitCount90d", r.commitCount());
            item.put("inDegree", r.inDegree());
            item.put("riskScore", Math.round(score * 1000.0) / 1000.0);
            hotspots.add(item);
        }
        hotspots.sort((a, b) -> Double.compare(
            (double) b.get("riskScore"), (double) a.get("riskScore")));
        return hotspots.size() > limit ? hotspots.subList(0, limit) : hotspots;
    }

    /** 类级度量排行：上帝类（WMC+方法数双高）与枢纽类（扇入扇出双高）。 */
    public Map<String, Object> classMetrics(List<String> paths) {
        Map<String, Map<String, Object>> byClass = new LinkedHashMap<>();

        try (Session session = neo4jDriver.session(neo4jSessionConfig)) {
            var godRecs = session.run(
                "MATCH (c:Class) WHERE c.projectPath IN $paths\n" +
                "  AND NOT c.className =~ '.*(Test|Tests)$'\n" +
                "OPTIONAL MATCH (c)-[:HAS_METHOD]->(m:Method)\n" +
                "RETURN c.className AS className, count(m) AS methodCount,\n" +
                "       coalesce(sum(m.complexity), 0) AS wmc,\n" +
                "       coalesce(round(avg(m.complexity), 1), 0) AS avgComplexity",
                Map.of("paths", paths));
            while (godRecs.hasNext()) {
                var r = godRecs.next();
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("className", r.get("className").asString());
                item.put("methodCount", r.get("methodCount").asInt(0));
                item.put("wmc", r.get("wmc").asInt(0));
                item.put("avgComplexity", r.get("avgComplexity").asDouble(0.0));
                byClass.put((String) item.get("className"), item);
            }
            mergeDegree(paths, "outDegree",
                "MATCH (c1:Class)-[:HAS_METHOD]->(:Method)-[:CALLS]->(m2:Method)<-[:HAS_METHOD]-(c2:Class)\n" +
                "WHERE c1.projectPath IN $paths AND c2.projectPath IN $paths AND c1.className <> c2.className\n" +
                "RETURN c1.className AS className, count(DISTINCT c2.className) AS degree",
                byClass);
            mergeDegree(paths, "inDegree",
                "MATCH (c1:Class)-[:HAS_METHOD]->(:Method)-[:CALLS]->(m2:Method)<-[:HAS_METHOD]-(c2:Class)\n" +
                "WHERE c1.projectPath IN $paths AND c2.projectPath IN $paths AND c1.className <> c2.className\n" +
                "RETURN c2.className AS className, count(DISTINCT c1.className) AS degree",
                byClass);
        }

        List<Integer> wmc = byClass.values().stream().map(v -> (int) v.get("wmc")).sorted().toList();
        List<Integer> mc = byClass.values().stream().map(v -> (int) v.get("methodCount")).sorted().toList();
        List<Integer> inDeg = byClass.values().stream().map(v -> (int) v.getOrDefault("inDegree", 0)).sorted().toList();
        List<Integer> outDeg = byClass.values().stream().map(v -> (int) v.getOrDefault("outDegree", 0)).sorted().toList();
        int wmc95 = percentile(wmc, 95);
        int mc95 = percentile(mc, 95);
        int in95 = percentile(inDeg, 95);
        int out95 = percentile(outDeg, 95);

        List<Map<String, Object>> godClasses = new ArrayList<>();
        List<Map<String, Object>> hubClasses = new ArrayList<>();
        for (Map<String, Object> item : byClass.values()) {
            int w = (int) item.get("wmc");
            int m = (int) item.get("methodCount");
            int in = (int) item.getOrDefault("inDegree", 0);
            int out = (int) item.getOrDefault("outDegree", 0);
            if (w >= wmc95 && m >= mc95) {
                godClasses.add(copy(item));
            }
            if (in >= in95 && out >= out95 && (in > 0 || out > 0)) {
                hubClasses.add(copy(item));
            }
        }
        godClasses.sort((a, b) -> Integer.compare((int) b.get("wmc"), (int) a.get("wmc")));
        hubClasses.sort((a, b) -> Integer.compare((int) b.get("inDegree"), (int) a.get("inDegree")));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("godClasses", godClasses);
        result.put("hubClasses", hubClasses);
        result.put("thresholds", Map.of("wmc95", wmc95, "methodCount95", mc95, "in95", in95, "out95", out95));
        return result;
    }

    /**
     * 全量类调用图：类节点（含 packageName）+ 类间调用边（精确调用次数）。
     *
     * <p>口径与 {@code KnowledgeGraphController.getDsmDrillDown} 一致（基于 Method 节点的
     * {@code packageName}/{@code className} 聚合 {@code CALLS}），供快照离线类级下钻还原，
     * 不落库、只读。</p>
     */
    public Map<String, Object> classCallGraph(List<String> paths) {
        Map<String, Object> result = new LinkedHashMap<>();
        List<Map<String, Object>> nodes = new ArrayList<>();
        List<Map<String, Object>> edges = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        try (Session session = neo4jDriver.session(neo4jSessionConfig)) {
            var nodeRecs = session.run(
                "MATCH (m:Method) WHERE m.projectPath IN $paths\n" +
                "  AND NOT m.className =~ '.*(Test|Tests)$'\n" +
                "RETURN m.className AS className, coalesce(m.packageName, '') AS packageName",
                Map.of("paths", paths));
            while (nodeRecs.hasNext()) {
                var r = nodeRecs.next();
                String className = r.get("className").asString();
                if (!seen.add(className)) {
                    continue;
                }
                Map<String, Object> n = new LinkedHashMap<>();
                n.put("className", className);
                n.put("packageName", r.get("packageName").asString(""));
                nodes.add(n);
            }
            var edgeRecs = session.run(
                "MATCH (a:Method)-[r:CALLS]->(b:Method)\n" +
                "WHERE a.projectPath IN $paths AND b.projectPath IN $paths\n" +
                "  AND a.className <> b.className\n" +
                "  AND NOT a.className =~ '.*(Test|Tests)$' AND NOT b.className =~ '.*(Test|Tests)$'\n" +
                "RETURN a.className AS source, b.className AS target, count(r) AS weight",
                Map.of("paths", paths));
            while (edgeRecs.hasNext()) {
                var r = edgeRecs.next();
                Map<String, Object> e = new LinkedHashMap<>();
                e.put("source", r.get("source").asString());
                e.put("target", r.get("target").asString());
                e.put("weight", r.get("weight").asInt(0));
                edges.add(e);
            }
        }
        result.put("nodes", nodes);
        result.put("edges", edges);
        return result;
    }

    // ── 内部工具 ──

    private List<MethodRow> loadMethodRows(List<String> paths) {
        Map<String, MethodRow> dedup = new LinkedHashMap<>();
        try (Session session = neo4jDriver.session(neo4jSessionConfig)) {
            var recs = session.run(
                "MATCH (m:Method) WHERE m.projectPath IN $paths AND m.complexity IS NOT NULL\n" +
                "  AND NOT m.className =~ '.*(Test|Tests)$'\n" +
                "OPTIONAL MATCH (c:ChurnNode) WHERE c.projectPath = m.projectPath AND c.filePath = m.filePath\n" +
                "RETURN m.nodeId AS nodeId, m.className AS className, m.methodName AS methodName,\n" +
                "       m.signature AS signature, m.filePath AS filePath, m.complexity AS complexity,\n" +
                "       coalesce(m.inDegree, 0) AS inDegree, coalesce(c.commitCount90d, 0) AS commitCount",
                Map.of("paths", paths));
            while (recs.hasNext()) {
                var r = recs.next();
                MethodRow row = new MethodRow(
                    r.get("nodeId").asString(),
                    r.get("className").asString(""),
                    r.get("methodName").asString(""),
                    r.get("signature").asString(""),
                    r.get("filePath").asString(""),
                    r.get("complexity").asInt(0),
                    r.get("inDegree").asInt(0),
                    r.get("commitCount").asInt(0));
                // 同一方法可能被 Java 扫描器与 codegraph 管线各建一个 nodeId。
                // 两者 signature 字符串格式不一致（javaparser 全限定名 vs 简单名），
                // 故去重 key 用类名+方法名+参数个数（格式无关），保留复杂度最高者。
                String key = row.className() + "." + row.methodName() + "."
                        + ArchSmellDetectorService.countParams(row.signature());
                MethodRow existing = dedup.get(key);
                if (existing == null || row.complexity() > existing.complexity()) {
                    dedup.put(key, row);
                }
            }
        }
        return new ArrayList<>(dedup.values());
    }

    private Set<String> detectCycleNodes(List<String> paths) {
        Map<String, List<String>> adj = new HashMap<>();
        try (Session session = neo4jDriver.session(neo4jSessionConfig)) {
            var recs = session.run(
                "MATCH (a:Method)-[r:CALLS]->(b:Method)\n" +
                "WHERE a.projectPath IN $paths AND b.projectPath IN $paths\n" +
                "RETURN a.nodeId AS source, b.nodeId AS target",
                Map.of("paths", paths));
            while (recs.hasNext()) {
                var r = recs.next();
                adj.computeIfAbsent(r.get("source").asString(), k -> new ArrayList<>())
                    .add(r.get("target").asString());
            }
        }
        Set<String> visited = new HashSet<>();
        Set<String> stack = new HashSet<>();
        Set<String> inCycle = new HashSet<>();
        for (String node : adj.keySet()) {
            if (!visited.contains(node)) {
                dfs(node, adj, visited, stack, inCycle);
            }
        }
        return inCycle;
    }

    private void dfs(String node, Map<String, List<String>> adj,
                     Set<String> visited, Set<String> stack, Set<String> inCycle) {
        visited.add(node);
        stack.add(node);
        for (String neighbor : adj.getOrDefault(node, Collections.emptyList())) {
            if (stack.contains(neighbor)) {
                inCycle.add(node);
                inCycle.add(neighbor);
            } else if (!visited.contains(neighbor)) {
                dfs(neighbor, adj, visited, stack, inCycle);
                if (inCycle.contains(neighbor)) {
                    inCycle.add(node);
                }
            } else if (inCycle.contains(neighbor)) {
                inCycle.add(node);
            }
        }
        stack.remove(node);
    }

    private void mergeDegree(List<String> paths, String key, String cypher,
                             Map<String, Map<String, Object>> byClass) {
        try (Session session = neo4jDriver.session(neo4jSessionConfig)) {
            var recs = session.run(cypher, Map.of("paths", paths));
            while (recs.hasNext()) {
                var r = recs.next();
                String className = r.get("className").asString();
                if (isTestClass(className)) {
                    continue;
                }
                int degree = r.get("degree").asInt(0);
                byClass.computeIfAbsent(className, k -> {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("className", k);
                    item.put("methodCount", 0);
                    item.put("wmc", 0);
                    item.put("avgComplexity", 0.0);
                    return item;
                }).put(key, degree);
            }
        }
    }

    private static boolean isTestClass(String className) {
        return className != null && (className.endsWith("Test") || className.endsWith("Tests"));
    }

    private int percentile(List<Integer> sorted, int p) {
        if (sorted.isEmpty()) {
            return 0;
        }
        int idx = (int) Math.ceil(sorted.size() * p / 100.0) - 1;
        return sorted.get(Math.max(0, Math.min(idx, sorted.size() - 1)));
    }

    private Map<String, Object> copy(Map<String, Object> m) {
        return new LinkedHashMap<>(m);
    }

    private record MethodRow(String nodeId, String className, String methodName, String signature,
                             String filePath, int complexity, int inDegree, int commitCount) {}
}