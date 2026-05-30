package com.huawei.hisi.ram.nodes;

import com.huawei.hisi.ram.contract.SchemaValidator;
import com.huawei.hisi.ram.contract.ValidationResult;
import com.huawei.hisi.ram.orchestrator.DagNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

@Slf4j
@Component
public class TechPlanNode implements DagNode {

    private static final String SCHEMA_NAME = "tech_plan.output";

    private final TechPlanLlmClient llmClient;
    private final SchemaValidator schemaValidator;

    public TechPlanNode(TechPlanLlmClient llmClient, SchemaValidator schemaValidator) {
        this.llmClient = llmClient;
        this.schemaValidator = schemaValidator;
    }

    @Override
    public String name() { return "tech_plan"; }

    @Override
    public String agentId() { return "tech-plan-v1"; }

    @Override
    public Map<String, Object> execute(Map<String, Object> input) {
        if (input == null) {
            throw new IllegalArgumentException("TechPlanNode input must not be null");
        }

        Map<String, Object> impactOutput = asMap(input.get("impact"));
        Map<String, Object> implementOutput = asMap(input.get("implement"));
        String intent = input.get("intent") instanceof String s ? s : "";
        String projectPath = input.get("projectPath") instanceof String s ? s : "";

        log.info("[RAM][TechPlanNode] execute intent={} projectPath={}", intent, projectPath);

        Map<String, Object> output = llmClient.generate(impactOutput, implementOutput, intent, projectPath);

        ValidationResult validation = schemaValidator.validate(SCHEMA_NAME, output);
        if (!validation.passed()) {
            throw new IllegalStateException(
                    "tech_plan.output schema validation failed: missing="
                            + validation.missingFields()
                            + " violations=" + validation.violations());
        }
        return output;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> asMap(Object raw) {
        if (raw instanceof Map<?, ?> map) {
            return (Map<String, Object>) map;
        }
        return Map.of();
    }
}
