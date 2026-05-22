package com.huawei.hisi.ram.nodes;

import com.huawei.hisi.ram.contract.SchemaValidator;
import com.huawei.hisi.ram.contract.ValidationResult;
import com.huawei.hisi.ram.orchestrator.ClarifyRequiredException;
import com.huawei.hisi.ram.orchestrator.DagNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Clarify stage DAG node.
 *
 * <p>Pipeline:
 * <ol>
 *     <li>Read {@code userRequirement} from the input map (required).</li>
 *     <li>Ask {@link ClarifyLlmClient} to extract structured requirements.</li>
 *     <li>Validate the result against the {@code clarify.output} JSON schema.</li>
 *     <li>If validation fails, throw {@link ClarifyRequiredException} with
 *         one human-readable question per missing field — the orchestrator
 *         parks the session in {@code WAITING_CLARIFY} until the user
 *         supplies the missing data.</li>
 * </ol>
 */
@Slf4j
@Component
public class ClarifyNode implements DagNode {

    static final String INPUT_USER_REQUIREMENT = "userRequirement";
    private static final String SCHEMA_NAME = "clarify.output";

    private final SchemaValidator schemaValidator;
    private final ClarifyLlmClient clarifyLlmClient;

    public ClarifyNode(SchemaValidator schemaValidator, ClarifyLlmClient clarifyLlmClient) {
        this.schemaValidator = schemaValidator;
        this.clarifyLlmClient = clarifyLlmClient;
    }

    @Override
    public String name() {
        return "clarify";
    }

    @Override
    public String agentId() {
        return "clarify-v1";
    }

    @Override
    public Map<String, Object> execute(Map<String, Object> input) throws ClarifyRequiredException {
        if (input == null) {
            throw new IllegalArgumentException("ClarifyNode input must not be null");
        }
        Object raw = input.get(INPUT_USER_REQUIREMENT);
        if (!(raw instanceof String userRequirement) || userRequirement.isBlank()) {
            throw new IllegalArgumentException(
                    "ClarifyNode requires non-blank '" + INPUT_USER_REQUIREMENT + "' in input");
        }

        Map<String, Object> extracted = clarifyLlmClient.extractRequirements(userRequirement, input);
        log.debug("clarify llm extracted keys={}", extracted == null ? List.of() : extracted.keySet());

        ValidationResult result = schemaValidator.validate(SCHEMA_NAME, extracted);
        if (!result.passed()) {
            List<String> questions = buildQuestions(result);
            log.info("clarify validation failed, raising clarify-required questions={}", questions);
            throw new ClarifyRequiredException(questions);
        }
        return extracted;
    }

    private List<String> buildQuestions(ValidationResult result) {
        List<String> questions = new ArrayList<>();
        for (String missing : result.missingFields()) {
            questions.add("Please specify: " + missing);
        }
        for (String violation : result.violations()) {
            questions.add("Please clarify: " + violation);
        }
        if (questions.isEmpty()) {
            questions.add("Please provide more details about your requirement.");
        }
        return questions;
    }
}
