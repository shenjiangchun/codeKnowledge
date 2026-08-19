package com.huawei.hisi.knowledgegraph.aggregation.stage;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 循环依赖坏味道分级判定器。
 *
 * <p>按粒度分级：
 * <ul>
 *   <li><strong>module（pom 构建模块）级</strong>：架构坏味道定性标准，环必报（破坏构建拓扑序）。</li>
 *   <li><strong>包级</strong>：跨层环（controller↔service）必报；同层环（工具类互引）降级提示。</li>
 *   <li><strong>类级</strong>：排除，不判（Spring 支持循环依赖注入，是特性非坏味道）。</li>
 * </ul>
 */
@Component
public class CycleClassifier {

    /** 循环依赖分级结果 */
    public record ClassifiedCycle(List<String> nodes, String level, String message) {}

    /**
     * 对包级 SCC 簇按跨层/同层分级。
     *
     * @param clusters        Tarjan SCC 检测出的环簇（包级 moduleName 序列）
     * @param moduleLayerRole 包名 → layerRole 映射（如 controller/service/repository/model/util）
     * @return 分级结果列表
     */
    public List<ClassifiedCycle> classifyPackageCycles(
            List<TarjanSccDetector.CycleCluster> clusters,
            Map<String, String> moduleLayerRole) {
        List<ClassifiedCycle> result = new ArrayList<>();
        for (TarjanSccDetector.CycleCluster cluster : clusters) {
            boolean crossLayer = false;
            String prevLayer = null;
            for (String node : cluster.nodes()) {
                String layer = moduleLayerRole.getOrDefault(node, "UNKNOWN");
                if (prevLayer != null && !prevLayer.equals(layer)) {
                    crossLayer = true;
                    break;
                }
                prevLayer = layer;
            }
            if (crossLayer) {
                result.add(new ClassifiedCycle(cluster.nodes(), "CROSS_LAYER",
                    "跨层循环依赖：" + String.join(" ↔ ", cluster.nodes())));
            } else {
                result.add(new ClassifiedCycle(cluster.nodes(), "SAME_LAYER",
                    "同层循环依赖（技术债）：" + String.join(" ↔ ", cluster.nodes())));
            }
        }
        return result;
    }

    /**
     * 对 module 级 SCC 簇定性（架构坏味道，必报）。
     */
    public List<ClassifiedCycle> classifyModuleCycles(List<TarjanSccDetector.CycleCluster> clusters) {
        List<ClassifiedCycle> result = new ArrayList<>();
        for (TarjanSccDetector.CycleCluster cluster : clusters) {
            result.add(new ClassifiedCycle(cluster.nodes(), "MODULE",
                "构建模块循环依赖（架构坏味道）：" + String.join(" → ", cluster.nodes())));
        }
        return result;
    }

    /** 构建边列表到邻接表的辅助（供 Tarjan 输入）。 */
    public static List<TarjanSccDetector.Edge> toEdges(List<String[]> depends) {
        List<TarjanSccDetector.Edge> edges = new ArrayList<>();
        for (String[] d : depends) {
            edges.add(new TarjanSccDetector.Edge(d[0], d[1]));
        }
        return edges;
    }
}
