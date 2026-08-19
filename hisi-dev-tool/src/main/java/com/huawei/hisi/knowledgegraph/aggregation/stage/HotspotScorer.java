package com.huawei.hisi.knowledgegraph.aggregation.stage;

import com.huawei.hisi.knowledgegraph.aggregation.RiskScoreCalculator;
import com.huawei.hisi.knowledgegraph.aggregation.AggregationCheckpointManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.neo4j.driver.Driver;
import org.neo4j.driver.Session;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Stage 3: 文件级热点风险评分
 *
 * 按 filePath 聚合：每个文件一个 riskScore =
 *   文件内方法的最大圈复杂度×0.35 + 文件 Git 变更频率(ChurnNode.commitCount90d)×0.35
 *   + 文件方法入度×0.20 + 循环依赖惩罚×0.10
 *
 * riskScore 写入 ChurnNode（文件级唯一落点），不写 MethodNode。
 * inCycle 从真实 CALLS 边的 DFS 环检测结果读取（不再硬编码 false）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class HotspotScorer {

    private final Driver neo4jDriver;
    private final AggregationCheckpointManager checkpointManager;

    private static final int MAX_RETRIES = 3;
    private static final long[] RETRY_DELAYS_MS = {5_000, 15_000, 60_000};

    public void score(String projectPath, Set<String> dirtyFilePaths) {
        boolean isFull = (dirtyFilePaths == null || dirtyFilePaths.isEmpty());
        log.info("[Aggregation][Hotspot] score 开始, projectPath={}, isFull={}, dirtyFiles={}",
            projectPath, isFull, dirtyFilePaths != null ? dirtyFilePaths.size() : 0);

        for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
            try {
                doScore(projectPath, isFull ? null : dirtyFilePaths);
                checkpointManager.markSuccess(projectPath, "Hotspot", String.valueOf(System.currentTimeMillis()));
                log.info("[Aggregation] Stage=Hotspot 完成, projectPath={}, attempt={}", projectPath, attempt);
                return;
            } catch (Exception e) {
                log.warn("[Aggregation] Stage=Hotspot 失败 (attempt {}/{}): {}", attempt, MAX_RETRIES, e.getMessage());
                if (attempt == MAX_RETRIES) {
                    checkpointManager.markFailed(projectPath, "Hotspot", e.getMessage());
                    return;
                }
                try { Thread.sleep(RETRY_DELAYS_MS[attempt - 1]); } catch (InterruptedException ignored) {}
            }
        }
    }

    private void doScore(String projectPath, Set<String> dirtyFilePaths) {
        List<MethodInput> methods = loadMethods(projectPath, dirtyFilePaths);
        if (methods.isEmpty()) return;

        Map<String, Integer> churnByFile = loadChurnByFile(projectPath);
        Set<String> inCycleNodes = detectCycleNodes(projectPath);

        // 按 filePath 分组，聚合文件级指标
        Map<String, List<MethodInput>> byFile = methods.stream()
            .collect(Collectors.groupingBy(MethodInput::filePath));

        Map<String, Integer> fileComplexity = new LinkedHashMap<>();
        Map<String, Integer> fileInDegree = new LinkedHashMap<>();
        Map<String, Boolean> fileInCycle = new LinkedHashMap<>();
        for (var entry : byFile.entrySet()) {
            String file = entry.getKey();
            int maxComplexity = entry.getValue().stream()
                .mapToInt(MethodInput::complexity).max().orElse(0);
            int sumInDegree = entry.getValue().stream()
                .mapToInt(MethodInput::inDegree).sum();
            boolean inCycle = entry.getValue().stream()
                .anyMatch(m -> inCycleNodes.contains(m.nodeId()));
            fileComplexity.put(file, maxComplexity);
            fileInDegree.put(file, sumInDegree);
            fileInCycle.put(file, inCycle);
        }

        // 变更频率 + 入度 用百分位排名归一化（跨文件）
        Map<String, Double> churnNorms = RiskScoreCalculator.percentileRank(
            byFile.keySet().stream().collect(Collectors.toMap(
                f -> f, f -> churnByFile.getOrDefault(f, 0))));
        Map<String, Double> inDegreeNorms = RiskScoreCalculator.percentileRank(fileInDegree);

        try (Session session = neo4jDriver.session()) {
            for (String file : byFile.keySet()) {
                double score = RiskScoreCalculator.calculate(
                    fileComplexity.get(file),
                    churnNorms.getOrDefault(file, 0.0),
                    inDegreeNorms.getOrDefault(file, 0.0),
                    fileInCycle.getOrDefault(file, false));
                session.run(
                    "MERGE (c:ChurnNode {nodeId: $id})\n" +
                    "SET c.filePath = $fp, c.projectPath = $path, c.riskScore = $score",
                    Map.of("id", projectPath + ":" + file, "fp", file,
                        "path", projectPath, "score", score));
            }
        }
    }

    // ── 数据加载 ──

    private List<MethodInput> loadMethods(String projectPath, Set<String> dirtyFilePaths) {
        List<MethodInput> result = new ArrayList<>();
        String cypher;
        Map<String, Object> params = new HashMap<>();
        params.put("projectPath", projectPath);

        if (dirtyFilePaths != null && !dirtyFilePaths.isEmpty()) {
            cypher =
                "MATCH (m:Method {projectPath: $projectPath})\n" +
                "WHERE m.complexity IS NOT NULL AND m.filePath IS NOT NULL AND m.filePath IN $dirtyPaths\n" +
                "RETURN m.nodeId AS nodeId, m.filePath AS filePath, m.complexity AS complexity,\n" +
                "       coalesce(m.inDegree, 0) AS inDegree";
            params.put("dirtyPaths", new ArrayList<>(dirtyFilePaths));
        } else {
            cypher =
                "MATCH (m:Method {projectPath: $projectPath})\n" +
                "WHERE m.complexity IS NOT NULL AND m.filePath IS NOT NULL\n" +
                "RETURN m.nodeId AS nodeId, m.filePath AS filePath, m.complexity AS complexity,\n" +
                "       coalesce(m.inDegree, 0) AS inDegree";
        }

        try (Session session = neo4jDriver.session()) {
            var records = session.run(cypher, params);
            while (records.hasNext()) {
                var r = records.next();
                result.add(new MethodInput(
                    r.get("nodeId").asString(),
                    r.get("filePath").asString(),
                    r.get("complexity").asInt(0),
                    r.get("inDegree").asInt(0)));
            }
        }
        return result;
    }

    private Map<String, Integer> loadChurnByFile(String projectPath) {
        Map<String, Integer> churn = new HashMap<>();
        try (Session session = neo4jDriver.session()) {
            var records = session.run(
                "MATCH (c:ChurnNode {projectPath: $projectPath})\n" +
                "RETURN c.filePath AS filePath, coalesce(c.commitCount90d, 0) AS cnt",
                Map.of("projectPath", projectPath));
            while (records.hasNext()) {
                var r = records.next();
                churn.put(r.get("filePath").asString(""), r.get("cnt").asInt(0));
            }
        }
        return churn;
    }

    // ── 循环依赖检测（DFS，来自真实 CALLS 边） ──

    private Set<String> detectCycleNodes(String projectPath) {
        Map<String, List<String>> adjList = new HashMap<>();
        try (Session session = neo4jDriver.session()) {
            var records = session.run(
                "MATCH (a:Method {projectPath: $projectPath})-[r:CALLS]->(b:Method {projectPath: $projectPath})\n" +
                "RETURN a.nodeId AS source, b.nodeId AS target",
                Map.of("projectPath", projectPath));
            while (records.hasNext()) {
                var r = records.next();
                adjList.computeIfAbsent(r.get("source").asString(), k -> new ArrayList<>())
                    .add(r.get("target").asString());
            }
        }

        Set<String> visited = new HashSet<>();
        Set<String> recursionStack = new HashSet<>();
        Set<String> inCycle = new HashSet<>();
        for (String node : adjList.keySet()) {
            if (!visited.contains(node)) {
                dfs(node, adjList, visited, recursionStack, inCycle);
            }
        }
        return inCycle;
    }

    private void dfs(String node, Map<String, List<String>> adjList,
                     Set<String> visited, Set<String> recursionStack, Set<String> inCycle) {
        visited.add(node);
        recursionStack.add(node);
        for (String neighbor : adjList.getOrDefault(node, Collections.emptyList())) {
            if (recursionStack.contains(neighbor)) {
                inCycle.add(node);
                inCycle.add(neighbor);
            } else if (!visited.contains(neighbor)) {
                dfs(neighbor, adjList, visited, recursionStack, inCycle);
                if (inCycle.contains(neighbor)) inCycle.add(node);
            } else if (inCycle.contains(neighbor)) {
                inCycle.add(node);
            }
        }
        recursionStack.remove(node);
    }

    private record MethodInput(String nodeId, String filePath, int complexity, int inDegree) {}
}
