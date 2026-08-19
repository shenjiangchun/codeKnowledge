package com.huawei.hisi.knowledgegraph.aggregation.stage;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 类级分层违规检测器（标疑似，不硬判）。
 *
 * <p>输入类级依赖边（调用关系通过 HAS_METHOD+CALLS 间接查，import 类级落边）与各类职责，
 * 检测「下层依赖上层」的反向依赖，标为「疑似分层违规」。算法只输出疑似 + 反向依赖方向，
 * 由架构师看图复核终审，不输出"违规"结论。
 */
@Component
@RequiredArgsConstructor
public class ClassLayerViolationDetector {

    private final ClassLayerRoleDetector roleDetector;

    public record Violation(String source, String target, String sourceRole, String targetRole, String message) {}

    /**
     * 检测类级反向依赖（疑似分层违规）。
     *
     * @param edges  类级依赖边（source/target 为 className）
     * @param roleMap className → 类职责（三级回退 + LLM 补全后的结果）
     * @return 疑似分层违规列表（下层依赖上层）
     */
    public List<Violation> detect(List<TarjanSccDetector.Edge> edges, Map<String, String> roleMap) {
        List<Violation> violations = new ArrayList<>();
        for (TarjanSccDetector.Edge e : edges) {
            String srcRole = roleMap.getOrDefault(e.source(), ClassLayerRoleDetector.UNKNOWN);
            String tgtRole = roleMap.getOrDefault(e.target(), ClassLayerRoleDetector.UNKNOWN);
            int srcOrder = roleDetector.layerOrder(srcRole);
            int tgtOrder = roleDetector.layerOrder(tgtRole);
            // 两端已知职责 + 方向逆偏序（下层依赖上层）→ 疑似
            if (srcOrder >= 0 && tgtOrder >= 0 && tgtOrder > srcOrder) {
                violations.add(new Violation(
                    e.source(), e.target(), srcRole, tgtRole,
                    srcRole + " 反向依赖 " + tgtRole + "（疑似：" + simpleName(e.source())
                        + " → " + simpleName(e.target()) + "）"));
            }
        }
        return violations;
    }

    private String simpleName(String className) {
        if (className == null) return "";
        int idx = className.lastIndexOf('.');
        return idx > 0 ? className.substring(idx + 1) : className;
    }
}
