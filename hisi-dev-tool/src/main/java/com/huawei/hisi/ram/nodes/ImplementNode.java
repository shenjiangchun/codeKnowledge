package com.huawei.hisi.ram.nodes;

import com.huawei.hisi.ram.contract.SchemaValidator;
import com.huawei.hisi.ram.contract.ValidationResult;
import com.huawei.hisi.workflow.DagNode;
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
 *     <li>Extract any acceptance criteria carried forward from clarify.</li>
 *     <li>Ask {@link ImplementLlmClient} to draft business / UI / tech plans.
 *         Model selection is delegated to the LLM client, which reads from
 *         {@code anthropic.model} configuration (no hardcoded model names).</li>
 *     <li>Validate against the {@code implement.output} JSON schema. A failure
 *         is a hard error here (not clarify-recoverable).</li>
 * </ol>
 */
@Slf4j
@Component
public class ImplementNode implements DagNode {

    private static final String SCHEMA_NAME = "implement.output";

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
        // Pass null model — let the LLM client use its configured default from anthropic.model
        List<String> acceptanceCriteria = extractAcceptanceCriteria(input);

        log.debug("implement invoking llm acCount={}", acceptanceCriteria.size());
        Map<String, Object> output = llmClient.draft(input, acceptanceCriteria, null);

        ValidationResult validation = schemaValidator.validate(SCHEMA_NAME, output);
        if (!validation.passed()) {
            throw new IllegalStateException(
                    "implement.output schema validation failed: missing="
                            + validation.missingFields()
                            + " violations=" + validation.violations());
        }
        return output;
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
