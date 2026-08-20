package com.huawei.hisi.knowledgegraph.aggregation.stage;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CommunityDetector 的 Louvain 碎片化修复验证：
 * resolution γ<1 + 社区级 mergeToTarget 应把稀疏多簇图的社区数压到目标上限内。
 */
@DisplayName("CommunityDetector Louvain 碎片化修复")
class CommunityDetectorTest {

    /** runLouvain 是纯函数（不依赖 neo4jDriver/checkpointManager），构造时传 null 即可 */
    private final CommunityDetector detector = new CommunityDetector(null, null, null);

    @Test
    @DisplayName("稀疏多簇图（40 簇弱连接）→ 社区数被压到 <= 目标上限")
    void sparseClusteredGraph_producesBoundedCommunityCount() {
        int clusters = 40;
        int perCluster = 5;
        int n = clusters * perCluster;  // 200 节点，模拟低度稀疏依赖图

        Map<String, Integer> nodeIndex = new LinkedHashMap<>();
        List<CommunityDetector.Edge> edges = new ArrayList<>();
        for (int i = 0; i < n; i++) nodeIndex.put("n" + i, i);

        // 每簇内部构成团（clique），簇间仅相邻一条弱边 → 默认 Louvain 会拆出 ~40 个碎片社区
        for (int c = 0; c < clusters; c++) {
            int base = c * perCluster;
            for (int i = 0; i < perCluster; i++) {
                for (int j = i + 1; j < perCluster; j++) {
                    edges.add(new CommunityDetector.Edge(base + i, base + j, 1.0));
                }
            }
        }
        for (int c = 0; c < clusters - 1; c++) {
            edges.add(new CommunityDetector.Edge(c * perCluster, (c + 1) * perCluster, 1.0));
        }

        Map<String, Integer> result = detector.runLouvain(
            new CommunityDetector.GraphData(nodeIndex, edges));

        int communityCount = (int) result.values().stream().distinct().count();
        // 修复前：40 个碎片社区；修复后：resolution + mergeToTarget 压到 <= 20
        assertThat(communityCount)
            .as("稀疏多簇图的社区数应被压到目标上限内")
            .isBetween(1, 20);
    }

    @Test
    @DisplayName("完全连通的单簇图 → 收敛为 1 个社区（不破坏正常聚类）")
    void denselyConnectedGraph_convergesToSingleCommunity() {
        int n = 50;
        Map<String, Integer> nodeIndex = new LinkedHashMap<>();
        List<CommunityDetector.Edge> edges = new ArrayList<>();
        for (int i = 0; i < n; i++) nodeIndex.put("n" + i, i);
        // 全连通 clique
        for (int i = 0; i < n; i++) {
            for (int j = i + 1; j < n; j++) {
                edges.add(new CommunityDetector.Edge(i, j, 1.0));
            }
        }

        Map<String, Integer> result = detector.runLouvain(
            new CommunityDetector.GraphData(nodeIndex, edges));

        int communityCount = (int) result.values().stream().distinct().count();
        assertThat(communityCount).isEqualTo(1);
    }
}
