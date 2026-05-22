package com.huawei.hisi.ram.nodes;

import com.huawei.hisi.ram.contract.SchemaValidator;
import com.huawei.hisi.ram.contract.ValidationResult;
import com.huawei.hisi.ram.orchestrator.DagNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Implement stage DAG node.
 *
 * <p>Pipeline:
 * <ol>
 *     <li>Pick a Claude model based on the upstream risk level:
 *         {@code HIGH}/{@code CRITICAL} → {@code claude-opus-4-6};
 *         otherwise → {@code claude-sonnet-4-5}.</li>
 *     <li>Extract any acceptance criteria carried forward from clarify.</li>
 *     <li>Ask {@link ImplementLlmClient} to draft business / UI / tech plans.</li>
 *     <li>Validate against the {@code implement.output} JSON schema. A failure
 *         is a hard error here (not clarify-recoverable).</li>
 * </ol>
 */
@Slf4j
@Component
public class ImplementNode implements DagNode {

    private static final String SCHEMA_NAME = "implement.output";
    private static final String MODEL_HIGH_RISK = "claude-opus-4-6";
    private static final String MODEL_DEFAULT = "claude-sonnet-4-5";

    private final ImplementLlmClient llmClient;
    private final SchemaValidator schemaValidator;

    public ImplementNode(ImplementLlmClient llmClient, SchemaValidator schemaValidator) {
        this.llmClient = llmClient;
        this.schemaValidator = schemaValidator;
    }

    @Override
    public String name() {
        return "implement";
    }

    @Override
    public String agentId() {
        return "implement-v1";
    }

    @Override
    public Map<String, Object> execute(Map<String, Object> input) {
        if (input == null) {
            throw new IllegalArgumentException("ImplementNode input must not be null");
        }
        String model = selectModel(input);
        List<String> acceptanceCriteria = extractAcceptanceCriteria(input);

        log.debug("implement invoking llm model={} acCount={}", model, acceptanceCriteria.size());
        Map<String, Object> output = llmClient.draft(input, acceptanceCriteria, model);

        ValidationResult validation = schemaValidator.validate(SCHEMA_NAME, output);
        if (!validation.passed()) {
            throw new IllegalStateException(
                    "implement.output schema validation failed: missing="
                            + validation.missingFields()
                            + " violations=" + validation.violations());
        }
        return output;
    }

    private String selectModel(Map<String, Object> input) {
        Object risk = input.get("risk");
        if (risk instanceof Map<?, ?> riskMap) {
            Object level = riskMap.get("level");
            if (level instanceof String levelStr) {
                String upper = levelStr.toUpperCase();
                if ("HIGH".equals(upper) || "CRITICAL".equals(upper)) {
                    return MODEL_HIGH_RISK;
                }
            }
        }
        return MODEL_DEFAULT;
    }

    @SuppressWarnings("unchecked")
    private List<String> extractAcceptanceCriteria(Map<String, Object> input) {
        Object raw = input.get("acceptance_criteria");
        if (raw instanceof List<?> list) {
            List<String> out = new ArrayList<>(list.size());
            for (Object item : list) {
                if (item instanceof String s) {
                    out.add(s);
                }
            }
            return List.copyOf(out);
        }
        return List.of();
    }
}
