package com.huawei.hisi.knowledgegraph.aggregation.stage;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * 分层规则引擎（Spring 技术分层偏序）。
 *
 * <p>定义通用 Spring 分层偏序：{@code controller → service → repository → model → util}，
 * 其中 util 是叶子层（可被任意层依赖，不依赖业务层）。
 * 违规 = 依赖方向逆偏序（下层依赖上层），即「宽松分层」（Loose layering）：
 * 上层可依赖任意下层（含跨层跳过，如 controller 直接依赖 repository），
 * 仅反向依赖判违规——与阿里手册 / Clean Architecture / Microsoft 指南的业界主流一致。
 *
 * <p>非 Spring 项目（layerRole 非 UNKNOWN 占比 &lt; 阈值）时跳过检测。
 */
@Slf4j
@Component
public class LayeredRuleEngine {

    /** 非 Spring 门控阈值：layerRole 非 UNKNOWN 的模块占比低于此值时跳过分层检测 */
    private static final double MIN_KNOWN_RATIO = 0.3;

    /**
     * 分层偏序：靠前的层允许依赖靠后的层（以及同层），不允许反向。
     * 顺序：controller(0) → service(1) → repository(2) → model(3) → util(4)
     */
    private static final Map<String, Integer> LAYER_ORDER = Map.of(
        "CONTROLLER", 0,
        "SERVICE", 1,
        "REPOSITORY", 2,
        "MODEL", 3,
        "UTILITY", 4
    );

    /** 违规结果记录 */
    public record Violation(String sourceModule, String sourceLayer,
                            String targetModule, String targetLayer, String reason) {}

    /**
     * 检测分层违规。
     *
     * @param modules 模块名 → layerRole 的映射
     * @param depends 依赖边列表（sourceModule → targetModule）
     * @return 违规列表；非 Spring 分层项目时返回空列表
     */
    public List<Violation> detect(Map<String, String> modules, List<String[]> depends) {
        if (modules.isEmpty()) return List.of();

        // 非 Spring 门控：layerRole 非 UNKNOWN 占比 < 阈值时跳过
        long knownCount = modules.values().stream()
            .filter(LAYER_ORDER::containsKey)
            .count();
        double knownRatio = (double) knownCount / modules.size();
        if (knownRatio < MIN_KNOWN_RATIO) {
            log.info("[LayeredRule] 非 Spring 分层架构（已知层占比 {:.0f}% < {:.0f}%），跳过检测",
                knownRatio * 100, MIN_KNOWN_RATIO * 100);
            return List.of();
        }

        List<Violation> violations = new ArrayList<>();
        for (String[] edge : depends) {
            String src = edge[0];
            String tgt = edge[1];
            String srcLayer = modules.getOrDefault(src, "UNKNOWN");
            String tgtLayer = modules.getOrDefault(tgt, "UNKNOWN");

            if (!LAYER_ORDER.containsKey(srcLayer) || !LAYER_ORDER.containsKey(tgtLayer)) {
                continue;  // 任一端 UNKNOWN 或 MAPPER 等未知层，不判违规
            }

            int srcOrder = LAYER_ORDER.get(srcLayer);
            int tgtOrder = LAYER_ORDER.get(tgtLayer);

            // util 是叶子层：允许被任意层依赖，即 tgt=UTILITY 时不判违规
            if ("UTILITY".equals(tgtLayer)) {
                continue;
            }

            // 反向依赖：src 依赖了更高层的 tgt（srcOrder > tgtOrder 表示 src 在更下层却依赖上层）。
            // 宽松分层：跨层跳过（controller 直接依赖 repository）不算违规，只有反向依赖违规。
            if (srcOrder > tgtOrder) {
                violations.add(new Violation(src, srcLayer, tgt, tgtLayer,
                    srcLayer + " 反向依赖了 " + tgtLayer + "（" + src + " → " + tgt + "）"));
            }
        }
        return violations;
    }
}
