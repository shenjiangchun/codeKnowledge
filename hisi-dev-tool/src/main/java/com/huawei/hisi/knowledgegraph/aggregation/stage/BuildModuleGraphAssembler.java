package com.huawei.hisi.knowledgegraph.aggregation.stage;

import com.huawei.hisi.neo4j.model.ModuleNode;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 构建模块依赖图拼装器（查询时内存拼边）。
 *
 * <p>读勾选项目的 build-module 节点，对每个节点的 {@code dependencyCoordinates} 坐标
 * 剥离 version 得 {@code groupId:artifactId}，与其它节点的 {@code moduleName} 精确匹配，
 * 匹配上则拼边。依赖边不落库，每次查询实时拼接。
 */
@Component
public class BuildModuleGraphAssembler {

    public record BuildEdge(String source, String target) {}

    public record BuildModuleGraph(List<ModuleNode> nodes, List<BuildEdge> edges) {}

    public BuildModuleGraph assemble(List<ModuleNode> modules) {
        Map<String, ModuleNode> byName = new HashMap<>();
        for (ModuleNode m : modules) {
            if (m.getModuleName() != null) byName.put(m.getModuleName(), m);
        }

        List<BuildEdge> edges = new ArrayList<>();
        for (ModuleNode m : modules) {
            if (m.getDependencyCoordinates() == null) continue;
            for (String coord : m.getDependencyCoordinates()) {
                String ga = BuildModuleDependencyAggregator.coordinateToGa(coord);
                if (byName.containsKey(ga)) {
                    edges.add(new BuildEdge(m.getModuleName(), ga));
                }
            }
        }
        return new BuildModuleGraph(modules, edges);
    }
}
