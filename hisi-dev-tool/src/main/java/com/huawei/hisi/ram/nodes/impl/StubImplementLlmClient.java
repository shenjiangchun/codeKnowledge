package com.huawei.hisi.ram.nodes.impl;

import com.huawei.hisi.ram.nodes.ImplementLlmClient;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Stub {@link ImplementLlmClient} — fallback used when Claude API key is absent.
 *
 * <p>{@link ClaudeImplementLlmClient} is {@code @Primary} and delegates to this
 * stub internally when the API key is not configured. This bean must NOT be
 * {@code @Primary} to avoid ambiguity.</p>
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
