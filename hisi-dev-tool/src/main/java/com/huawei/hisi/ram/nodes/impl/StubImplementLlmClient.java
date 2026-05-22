package com.huawei.hisi.ram.nodes.impl;

import com.huawei.hisi.ram.nodes.ImplementLlmClient;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Stub {@link ImplementLlmClient} used until the real Claude wiring lands.
 *
 * <p>Returns a deterministic 3-artifact draft (business / UI / tech) that
 * satisfies the {@code implement.output} JSON schema. Designed for tests
 * and local dry runs of the DAG.</p>
 */
@Primary
@Component
public class StubImplementLlmClient implements ImplementLlmClient {

    @Override
    public Map<String, Object> draft(Map<String, Object> impactOutput,
                                     List<String> acceptanceCriteria,
                                     String model) {
        Object involved = impactOutput == null ? null : impactOutput.get("involved");

        Map<String, Object> bizPlan = new LinkedHashMap<>();
        bizPlan.put("steps", List.of(
                "Step 1: Analyze " + involved,
                "Step 2: Modify per AC"
        ));
        bizPlan.put("data_flow", "User -> API -> Service -> Repository");

        Map<String, Object> uiPlan = new LinkedHashMap<>();
        uiPlan.put("screens", List.of("MainPage"));
        uiPlan.put("interactions", List.of("Click Submit"));

        Map<String, Object> techPlan = new LinkedHashMap<>();
        techPlan.put("files", List.of("OrderService.java", "OrderController.java"));
        techPlan.put("new_apis", List.of("POST /orders"));
        techPlan.put("schema_changes", List.of());

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("biz_plan", bizPlan);
        out.put("ui_plan", uiPlan);
        out.put("tech_plan", techPlan);
        return out;
    }
}
