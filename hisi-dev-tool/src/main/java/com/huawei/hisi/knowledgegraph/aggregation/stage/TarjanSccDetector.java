package com.huawei.hisi.knowledgegraph.aggregation.stage;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 强连通分量（SCC）检测器 —— Tarjan 算法。
 *
 * <p>用于循环依赖坏味道判定：输入有向边，输出「环内节点集合」（每个含 ≥2 节点或自环的
 * SCC 是一个循环依赖簇）。O(V+E) 一次遍历，不枚举环路径。
 *
 * <p>相比 Johnson 穷举所有简单环（复杂度随环数爆炸），Tarjan SCC 只回答「哪些节点在环里」，
 * 这正是架构坏味道判定需要的粒度。
 */
@Component
public class TarjanSccDetector {

    public record Edge(String source, String target) {}

    /** 一个循环依赖簇（SCC，含 ≥2 节点或自环） */
    public record CycleCluster(List<String> nodes) {}

    /**
     * 检测所有循环依赖簇。
     *
     * @param edges 有向边列表
     * @return 循环依赖簇列表（每个簇含 ≥2 节点或自环）
     */
    public List<CycleCluster> detect(List<Edge> edges) {
        // 构建邻接表
        Map<String, List<String>> adj = new HashMap<>();
        for (Edge e : edges) {
            adj.computeIfAbsent(e.source(), k -> new ArrayList<>()).add(e.target());
        }

        Map<String, Integer> index = new HashMap<>();
        Map<String, Integer> low = new HashMap<>();
        Map<String, Boolean> onStack = new HashMap<>();
        List<String> stack = new ArrayList<>();
        List<CycleCluster> clusters = new ArrayList<>();
        int[] counter = {0};

        for (String node : adj.keySet()) {
            if (!index.containsKey(node)) {
                strongConnect(node, adj, index, low, onStack, stack, clusters, counter);
            }
        }
        return clusters;
    }

    private void strongConnect(String v, Map<String, List<String>> adj,
                               Map<String, Integer> index, Map<String, Integer> low,
                               Map<String, Boolean> onStack, List<String> stack,
                               List<CycleCluster> clusters, int[] counter) {
        index.put(v, counter[0]);
        low.put(v, counter[0]);
        counter[0]++;
        stack.add(v);
        onStack.put(v, true);

        for (String w : adj.getOrDefault(v, List.of())) {
            if (!index.containsKey(w)) {
                strongConnect(w, adj, index, low, onStack, stack, clusters, counter);
                low.put(v, Math.min(low.get(v), low.get(w)));
            } else if (onStack.getOrDefault(w, false)) {
                low.put(v, Math.min(low.get(v), index.get(w)));
            }
        }

        // 根节点：弹出 SCC
        if (low.get(v).equals(index.get(v))) {
            List<String> scc = new ArrayList<>();
            String w;
            do {
                w = stack.remove(stack.size() - 1);
                onStack.put(w, false);
                scc.add(w);
            } while (!w.equals(v));

            // 自环（节点指向自己）也算环
            boolean selfLoop = adj.getOrDefault(v, List.of()).contains(v);
            if (scc.size() >= 2 || selfLoop) {
                clusters.add(new CycleCluster(scc));
            }
        }
    }
}
