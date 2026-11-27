package com.huawei.hisi.ram.nodes.impl;

import com.huawei.hisi.ram.nodes.ImplementLlmClient;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Stub {@link ImplementLlmClient} — 确定性兜底：LLM 不可用/输出不合契约时归一化回退。
 *
 * <p>{@link PiImplementLlmClient}（{@code @Primary}）经基类
 * {@link ClaudeImplementLlmClient} 注入本桩做 normalize 兜底。本 bean 不得标
 * {@code @Primary}，否则与 pi 实现歧义。</p>
 */
@Component
public class StubImplementLlmClient implements ImplementLlmClient {

    @Override
    public Map<String, Object> draft(Map<String, Object> impactOutput,
                                     List<String> acceptanceCriteria,
                                     String model) {
        Map<String, Object> bizPlan = new LinkedHashMap<>();
        bizPlan.put("steps", List.of(
                "Step 1: 分析影响范围",
                "Step 2: 按验收标准实施修改"
        ));
        bizPlan.put("data_flow", "User -> API -> Service -> Repository");
        bizPlan.put("acceptance_mapping", new LinkedHashMap<>());

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("biz_plan", bizPlan);
        out.put("api_changes", List.of());
        out.put("state_machine_changes", List.of());
        out.put("data_model_changes", List.of());
        out.put("config_changes", List.of());
        return out;
    }
}
