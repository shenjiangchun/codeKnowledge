package com.huawei.hisi.knowledgegraph.aggregation.stage;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * module 级分层规则引擎（查询时实时）。
 *
 * <p>基于拼出的依赖图，检测两类分层违规：
 * <ul>
 *   <li><strong>已知职责违规</strong>：{@code src} 依赖了层级更高的 {@code tgt}（反向/跨层）。</li>
 *   <li><strong>相对层级约束（职责未知兜底）</strong>：职责未知的 module，用已知邻居推导
 *       层级区间 {@code [下界, 上界]}，下界 &gt; 上界即层级矛盾。</li>
 * </ul>
 */
@Component
@RequiredArgsConstructor
public class ModuleLayerRuleEngine {

    private final ModuleLayerRoleDetector roleDetector;

    public record Violation(String source, String target, String type,
                            String sourceLayer, String targetLayer, String message) {}

    /**
     * 检测分层违规。
     *
     * @param edges        拼出的依赖边（source/target 为 moduleName = groupId:artifactId）
     * @param nodes        build-module 节点列表（含 groupId/artifactId 用于职责识别）
     * @return 违规列表
     */
    public List<Violation> detect(List<BuildModuleGraphAssembler.BuildEdge> edges,
                                  List<com.huawei.hisi.neo4j.model.ModuleNode> nodes) {
        // moduleName → 层级
        Map<String, Integer> layerByModule = new HashMap<>();
        for (var n : nodes) {
            int layer = roleDetector.detect(n.getArtifactId());
            layerByModule.put(n.getModuleName(), layer);
        }

        List<Violation> violations = new ArrayList<>();

        // 1. 已知职责违规：src 依赖更高层级 tgt
        for (var e : edges) {
            int srcLayer = layerByModule.getOrDefault(e.source(), ModuleLayerRoleDetector.UNKNOWN);
            int tgtLayer = layerByModule.getOrDefault(e.target(), ModuleLayerRoleDetector.UNKNOWN);
            if (srcLayer == ModuleLayerRoleDetector.UNKNOWN || tgtLayer == ModuleLayerRoleDetector.UNKNOWN) {
                continue;  // 职责未知交给相对层级约束
            }
            if (tgtLayer > srcLayer) {
                String type = tgtLayer > srcLayer + 1 ? "CROSS_LAYER" : "REVERSE";
                violations.add(new Violation(e.source(), e.target(), type,
                    layerName(srcLayer), layerName(tgtLayer),
                    e.source() + " 依赖上层 " + e.target() + "（" + layerName(srcLayer) + " → " + layerName(tgtLayer) + "）"));
            }
        }

        // 2. 相对层级约束：职责未知 module 的层级区间推导
        Map<String, Integer> lowerBound = new HashMap<>();  // 下界 = max(依赖的已知层级)
        Map<String, Integer> upperBound = new HashMap<>();  // 上界 = min(被依赖的已知层级)
        for (var e : edges) {
            int srcLayer = layerByModule.getOrDefault(e.source(), ModuleLayerRoleDetector.UNKNOWN);
            int tgtLayer = layerByModule.getOrDefault(e.target(), ModuleLayerRoleDetector.UNKNOWN);
            if (srcLayer == ModuleLayerRoleDetector.UNKNOWN && tgtLayer != ModuleLayerRoleDetector.UNKNOWN) {
                // 未知 src 依赖已知 tgt → src 下界 >= tgt 层级
                lowerBound.merge(e.source(), tgtLayer, Math::max);
            }
            if (tgtLayer == ModuleLayerRoleDetector.UNKNOWN && srcLayer != ModuleLayerRoleDetector.UNKNOWN) {
                // 已知 src 依赖未知 tgt → tgt 上界 <= src 层级
                upperBound.merge(e.target(), srcLayer, Math::min);
            }
        }

        // 收集所有职责未知的 module，逐一判层级矛盾（避免重复）
        for (var n : nodes) {
            if (layerByModule.getOrDefault(n.getModuleName(), ModuleLayerRoleDetector.UNKNOWN) == ModuleLayerRoleDetector.UNKNOWN) {
                Integer lo = lowerBound.get(n.getModuleName());
                Integer hi = upperBound.get(n.getModuleName());
                if (lo != null && hi != null && lo > hi) {
                    violations.add(new Violation(n.getModuleName(), n.getModuleName(), "CONTRADICTION",
                        "UNKNOWN", "UNKNOWN",
                        n.getModuleName() + " 层级矛盾（下界 " + layerName(lo) + " > 上界 " + layerName(hi) + "）"));
                }
            }
        }
        return violations;
    }

    private String layerName(int layer) {
        return switch (layer) {
            case 1 -> "L1(model/util)";
            case 2 -> "L2(client)";
            case 3 -> "L3(service)";
            case 4 -> "L4(api)";
            case 5 -> "L5(gw)";
            default -> "UNKNOWN";
        };
    }
}
