package com.huawei.hisi.knowledgegraph.aggregation.stage;

import com.huawei.hisi.knowledgegraph.aggregation.AggregationCheckpointManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.neo4j.driver.Driver;
import org.neo4j.driver.Session;
import org.springframework.stereotype.Component;

import java.util.*;

@Slf4j
@Component
@RequiredArgsConstructor
public class CommunityDetector {

    private final Driver neo4jDriver;
    private final AggregationCheckpointManager checkpointManager;

    private static final double MODULARITY_THRESHOLD = 1e-6;

    /** resolution γ：<1 得到更少更大的社区（对齐 Neo4j GDS Louvain 的 resolution 语义）。稀疏代码依赖图默认 1.0 会拆出数百个小社区。 */
    private static final double RESOLUTION = 0.5;
    /** 社区级合并后的目标社区数上限（领域划分粒度，超出则按社区间边权贪心合并） */
    private static final int TARGET_COMMUNITIES = 20;

    public void detect(String projectPath) {
        log.info("[Aggregation][Community] detect 开始, projectPath={}", projectPath);
        GraphData graph = loadGraph(projectPath);
        log.info("[Aggregation][Community] 图加载完毕, nodes={}, edges={}", graph.nodeCount(), graph.edges().size());
        if (graph.nodeCount() == 0) {
            checkpointManager.markSuccess(projectPath, "Community", "no-nodes");
            return;
        }

        Map<String, Integer> result = runLouvain(graph);
        Map<String, Integer> previousAssignment = loadPreviousCommunity(projectPath);
        writeCommunityIds(projectPath, result);

        double driftRatio = computeDrift(previousAssignment, result);
        checkpointManager.markSuccess(projectPath, "Community",
            "communities=" + result.values().stream().distinct().count() + ";drift=" + driftRatio);
        log.info("[Aggregation] Stage=Community 完成, communities={}, drift={}",
            result.values().stream().distinct().count(), String.format("%.2f%%", driftRatio * 100));
    }

    private GraphData loadGraph(String projectPath) {
        Map<String, Integer> nodeIndex = new LinkedHashMap<>();
        List<Edge> edges = new ArrayList<>();

        try (Session session = neo4jDriver.session()) {
            var records = session.run(
                "MATCH (a:Method {projectPath: $projectPath})-[r:CALLS]->(b:Method {projectPath: $projectPath})\n" +
                "WHERE a.nodeId IS NOT NULL AND b.nodeId IS NOT NULL\n" +
                "RETURN a.nodeId AS source, b.nodeId AS target,\n" +
                "       count(r) AS weight",
                Map.of("projectPath", projectPath));

            while (records.hasNext()) {
                var r = records.next();
                String source = r.get("source").asString();
                String target = r.get("target").asString();
                double weight = r.get("weight").asDouble();

                int si = nodeIndex.computeIfAbsent(source, k -> nodeIndex.size());
                int ti = nodeIndex.computeIfAbsent(target, k -> nodeIndex.size());

                edges.add(new Edge(si, ti, weight));
            }
        }
        return new GraphData(nodeIndex, edges);
    }

    Map<String, Integer> runLouvain(GraphData graph) {
        int n = graph.nodeCount();
        if (n <= 1) {
            Map<String, Integer> result = new LinkedHashMap<>();
            if (n == 1) result.put(graph.nodes()[0], 0);
            return result;
        }

        // 构建邻接表和节点索引
        int[] communities = new int[n];
        for (int i = 0; i < n; i++) communities[i] = i;

        List<List<WeightedNeighbor>> adj = new ArrayList<>(n);
        for (int i = 0; i < n; i++) adj.add(new ArrayList<>());

        double totalWeight = 0;
        for (Edge e : graph.edges()) {
            adj.get(e.source()).add(new WeightedNeighbor(e.target(), e.weight()));
            adj.get(e.target()).add(new WeightedNeighbor(e.source(), e.weight()));
            totalWeight += e.weight() * 2;
        }
        if (totalWeight == 0) totalWeight = 1.0;

        // 节点度数（加权）
        double[] degreeWeight = new double[n];
        for (int i = 0; i < n; i++) {
            for (WeightedNeighbor nb : adj.get(i)) {
                degreeWeight[i] += nb.weight();
            }
        }

        // ── Louvain 两阶段迭代 ──
        boolean improved = true;
        int maxIter = 50;
        int iter = 0;

        while (improved && iter < maxIter) {
            iter++;
            improved = false;

            // Phase 1: Local Moving
            boolean localMoved = true;
            int localIter = 0;
            while (localMoved && localIter < 100) {
                localMoved = false;
                localIter++;
                // 随机顺序避免偏差
                int[] order = new int[n];
                for (int ii = 0; ii < n; ii++) order[ii] = ii;
                java.util.Collections.shuffle(java.util.Arrays.asList(
                    java.util.Arrays.stream(order).boxed().toArray(Integer[]::new)));

                // 社区总度数：每轮计算一次（O(n)），节点移动时增量更新，避免每节点 O(n) 重算导致 O(n³)
                double[] commDegree = new double[n];
                for (int j = 0; j < n; j++) {
                    commDegree[communities[j]] += degreeWeight[j];
                }

                for (int idx : order) {
                    int currentComm = communities[idx];
                    double bestDelta = 0;
                    int bestCommunity = currentComm;

                    // 邻居社区权重
                    Map<Integer, Double> neighborCommWeights = new HashMap<>();
                    for (WeightedNeighbor nb : adj.get(idx)) {
                        int cj = communities[nb.node()];
                        neighborCommWeights.merge(cj, nb.weight(), Double::sum);
                    }

                    double ki = degreeWeight[idx];

                    for (var entry : neighborCommWeights.entrySet()) {
                        int cj = entry.getKey();
                        if (cj == currentComm) continue;
                        double wToCj = entry.getValue();

                        double sigmaInTarget = commDegree[cj];

                        // 模块度增量 ΔQ（resolution γ 缩放惩罚项，γ<1 合并更激进）
                        double delta = (wToCj / totalWeight)
                            - RESOLUTION * ki * (sigmaInTarget - (cj == currentComm ? ki : 0))
                              / (2.0 * totalWeight * totalWeight);

                        if (delta > bestDelta + MODULARITY_THRESHOLD) {
                            bestDelta = delta;
                            bestCommunity = cj;
                        }
                    }

                    if (bestCommunity != currentComm && bestDelta > 0) {
                        communities[idx] = bestCommunity;
                        // 增量更新社区总度数
                        commDegree[currentComm] -= ki;
                        commDegree[bestCommunity] += ki;
                        localMoved = true;
                        improved = true;
                    }
                }
            }

            // Phase 2: 聚合 —— 小社区合并到最大的邻居社区
            improved |= mergeSmallCommunities(communities, adj, n, totalWeight);
        }

        // 后处理：社区级层次合并，把社区数压到目标以内（消除 Louvain 在稀疏图上的碎片化）
        mergeToTarget(communities, adj, n, TARGET_COMMUNITIES);

        // 紧凑化社区 ID
        Map<Integer, Integer> idMap = new HashMap<>();
        int nextId = 0;
        int[] finalCommunities = new int[n];
        for (int i = 0; i < n; i++) {
            int cid = communities[i];
            if (!idMap.containsKey(cid)) {
                idMap.put(cid, nextId++);
            }
            finalCommunities[i] = idMap.get(cid);
        }

        Map<String, Integer> result = new LinkedHashMap<>();
        for (var entry : graph.nodeIndex().entrySet()) {
            result.put(entry.getKey(), finalCommunities[entry.getValue()]);
        }
        return result;
    }

    /**
     * 小社区合并：size < 5 的社区，用 Best-Fit 策略合并到与之连接最多的邻居大社区。
     * 标准 Louvain Phase 2 的简化替代方案。
     */
    private boolean mergeSmallCommunities(int[] communities,
                                           List<List<WeightedNeighbor>> adj,
                                           int n, double totalWeight) {
        // 统计社区大小
        Map<Integer, Integer> commSizes = new HashMap<>();
        for (int i = 0; i < n; i++) {
            commSizes.merge(communities[i], 1, Integer::sum);
        }

        boolean changed = false;
        for (int i = 0; i < n; i++) {
            int cid = communities[i];
            if (commSizes.getOrDefault(cid, 0) >= 5) continue; // 只处理小社区

            // 找与该节点连接最多的大社区
            Map<Integer, Double> neighborWeights = new HashMap<>();
            for (WeightedNeighbor nb : adj.get(i)) {
                int nc = communities[nb.node()];
                if (nc != cid) {
                    neighborWeights.merge(nc, nb.weight(), Double::sum);
                }
            }

            int bestTarget = -1;
            double bestW = -1;
            for (var entry : neighborWeights.entrySet()) {
                int tc = entry.getKey();
                double w = entry.getValue();
                if (w > bestW && commSizes.getOrDefault(tc, 0) >= 3) {
                    bestW = w;
                    bestTarget = tc;
                }
            }

            if (bestTarget >= 0) {
                communities[i] = bestTarget;
                commSizes.merge(cid, -1, Integer::sum);
                commSizes.merge(bestTarget, 1, Integer::sum);
                changed = true;
            }
        }

        // 最终迭代：所有 size=1 的孤立节点直接合并到最强邻居
        for (int i = 0; i < n; i++) {
            int cid = communities[i];
            if (commSizes.getOrDefault(cid, 0) > 1) continue;

            int strongestNeighbor = -1;
            double maxW = -1;
            for (WeightedNeighbor nb : adj.get(i)) {
                if (nb.weight() > maxW && communities[nb.node()] != cid) {
                    maxW = nb.weight();
                    strongestNeighbor = communities[nb.node()];
                }
            }
            if (strongestNeighbor >= 0) {
                communities[i] = strongestNeighbor;
                changed = true;
            }
        }

        return changed;
    }

    /**
     * 社区级层次合并：反复把「跨社区边权最大」的社区对合并，直到社区数 <= target。
     *
     * <p>Louvain 在稀疏代码依赖图（平均度 ~2.7）上天然产生大量小社区（本次 216 个），
     * 而节点级 {@link #mergeSmallCommunities} 只处理 size&lt;5 的社区，对中等社区（size 5-20）无感。
     * 此方法按「社区间调用权重」做贪心层次聚类，把社区数压到领域划分可读的范围（~20）。
     */
    private void mergeToTarget(int[] communities, List<List<WeightedNeighbor>> adj, int n, int target) {
        Set<Integer> active = new HashSet<>();
        for (int i = 0; i < n; i++) active.add(communities[i]);
        if (active.size() <= target) return;

        while (active.size() > target) {
            // 构建社区间边权（无向，按 pair key 去重累加）
            Map<Long, Double> interWeight = new HashMap<>();
            for (int i = 0; i < n; i++) {
                int ci = communities[i];
                for (WeightedNeighbor nb : adj.get(i)) {
                    int cj = communities[nb.node()];
                    if (ci != cj) {
                        interWeight.merge(communityPairKey(ci, cj), nb.weight(), Double::sum);
                    }
                }
            }
            if (interWeight.isEmpty()) break;  // 无跨社区边，无法继续合并

            long bestKey = -1L;
            double bestW = -1.0;
            for (var e : interWeight.entrySet()) {
                if (e.getValue() > bestW) {
                    bestW = e.getValue();
                    bestKey = e.getKey();
                }
            }
            if (bestW <= 0) break;

            int ca = (int) (bestKey >>> 32);
            int cb = (int) (bestKey & 0xFFFFFFFFL);
            for (int i = 0; i < n; i++) {
                if (communities[i] == cb) communities[i] = ca;
            }
            active.remove(cb);
        }
    }

    /** 两个非负社区 ID 打包成一个 long key（lo=较小值在高 32 位，hi=较大值在低 32 位） */
    private long communityPairKey(int a, int b) {
        int lo = Math.min(a, b);
        int hi = Math.max(a, b);
        return ((long) lo << 32) | (hi & 0xFFFFFFFFL);
    }

    private Map<String, Integer> loadPreviousCommunity(String projectPath) {
        Map<String, Integer> prev = new LinkedHashMap<>();
        try (Session session = neo4jDriver.session()) {
            var records = session.run(
                "MATCH (m:Method {projectPath: $projectPath})\n" +
                "WHERE m.communityId IS NOT NULL\n" +
                "RETURN m.nodeId AS nodeId, m.communityId AS cid",
                Map.of("projectPath", projectPath));
            while (records.hasNext()) {
                var r = records.next();
                prev.put(r.get("nodeId").asString(), r.get("cid").asInt());
            }
        }
        return prev;
    }

    private void writeCommunityIds(String projectPath, Map<String, Integer> communities) {
        try (Session session = neo4jDriver.session()) {
            for (var entry : communities.entrySet()) {
                session.run(
                    "MATCH (m:Method {nodeId: $nodeId, projectPath: $projectPath})\n" +
                    "SET m.communityId = $cid",
                    Map.of("nodeId", entry.getKey(), "projectPath", projectPath, "cid", entry.getValue()));
            }
        }
    }

    private double computeDrift(Map<String, Integer> prev, Map<String, Integer> current) {
        if (prev.isEmpty()) return 0.0;
        int total = current.size();
        int shifted = 0;
        for (var entry : current.entrySet()) {
            Integer oldCid = prev.get(entry.getKey());
            if (oldCid != null && !oldCid.equals(entry.getValue())) {
                shifted++;
            }
        }
        return (double) shifted / total;
    }

    record GraphData(Map<String, Integer> nodeIndex, List<Edge> edges) {
        int nodeCount() { return nodeIndex.size(); }
        String[] nodes() { return nodeIndex.keySet().toArray(new String[0]); }
    }

    record Edge(int source, int target, double weight) {}
    record WeightedNeighbor(int node, double weight) {}
}
