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
        log.info("[RAM][ClarifyNode] execute input.keys={}", input == null ? "null" : input.keySet());
        if (input == null) {
            throw new IllegalArgumentException("ClarifyNode input must not be null");
        }
        Object raw = input.get(INPUT_USER_REQUIREMENT);
        if (!(raw instanceof String userRequirement) || userRequirement.isBlank()) {
            throw new IllegalArgumentException(
                    "ClarifyNode requires non-blank '" + INPUT_USER_REQUIREMENT + "' in input");
        }

        Map<String, Object> extracted = clarifyLlmClient.extractRequirements(userRequirement, input);
        log.info("[RAM][ClarifyNode] llm extracted keys={} intent.len={} project_paths={} acceptance_criteria.size={}",
                extracted == null ? List.of() : extracted.keySet(),
                extracted == null ? 0 : String.valueOf(extracted.getOrDefault("intent", "")).length(),
                extracted == null ? null : extracted.get("project_paths"),
                extracted == null || !(extracted.get("acceptance_criteria") instanceof List<?> ac) ? 0 : ac.size());

        ValidationResult result = schemaValidator.validate(SCHEMA_NAME, extracted);
        if (!result.passed()) {
            List<String> questions = buildQuestions(result);
            log.info("[RAM][ClarifyNode] schema validation FAILED missing={} violations={} questions={}",
                    result.missingFields(), result.violations(), questions);
            throw new ClarifyRequiredException(questions);
        }
        log.info("[RAM][ClarifyNode] OK schema passed");
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
