package com.huawei.hisi.ram.nodes;

import com.huawei.hisi.ram.contract.SchemaValidator;
import com.huawei.hisi.ram.contract.ValidationResult;
import com.huawei.hisi.ram.orchestrator.DagNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Verify stage DAG node — runs 3 deterministic cross-checks across the
 * {@code implement} and {@code impact} stage outputs.
 *
 * <p>Checks:
 * <ol>
 *     <li>Every file in {@code implement.tech_plan.files} is referenced somewhere
 *         in the {@code impact} payload (substring match on stringified values).</li>
 *     <li>Every acceptance criterion is referenced (lenient substring match) by
 *         at least one step in {@code implement.biz_plan.steps}.</li>
 *     <li>{@code impact.validation.passed} is {@code true}.</li>
 * </ol>
 *
 * <p>Output conforms to the {@code verify.output} JSON schema:
 * {@code {"checks":[...], "pass":bool, "blockers":[...]}}.
 */
@Slf4j
@Component
public class VerifyNode implements DagNode {

    private static final String SCHEMA_NAME = "verify.output";

    private final SchemaValidator schemaValidator;

    public VerifyNode(SchemaValidator schemaValidator) {
        this.schemaValidator = schemaValidator;
    }

    @Override
    public String name() {
        return "verify";
    }

    @Override
    public String agentId() {
        return "verify-v1";
    }

    @Override
    public Map<String, Object> execute(Map<String, Object> input) {
        Map<String, Object> implement = asMap(input == null ? null : input.get("implement"));
        Map<String, Object> impact = asMap(input == null ? null : input.get("impact"));
        List<String> acceptanceCriteria = asStringList(input == null ? null : input.get("acceptance_criteria"));

        List<Map<String, Object>> checks = new ArrayList<>();
        List<String> blockers = new ArrayList<>();

        runFilesCheck(implement, impact, checks, blockers);
        runAcceptanceCriteriaCheck(implement, acceptanceCriteria, checks, blockers);
        runValidationCheck(impact, checks, blockers);

        boolean pass = checks.stream().allMatch(c -> Boolean.TRUE.equals(c.get("passed")));

        Map<String, Object> output = new LinkedHashMap<>();
        output.put("checks", List.copyOf(checks));
        output.put("pass", pass);
        output.put("blockers", List.copyOf(blockers));

        ValidationResult validation = schemaValidator.validate(SCHEMA_NAME, output);
        if (!validation.passed()) {
            throw new IllegalStateException(
                    "verify.output schema validation failed: missing="
                            + validation.missingFields()
                            + " violations=" + validation.violations());
        }
        return output;
    }

    private void runFilesCheck(Map<String, Object> implement,
                               Map<String, Object> impact,
                               List<Map<String, Object>> checks,
                               List<String> blockers) {
        List<String> files = extractTechPlanFiles(implement);
        StringBuilder haystack = new StringBuilder();
        collectStrings(impact, haystack);
        String impactDump = haystack.toString();

        List<String> missing = new ArrayList<>();
        for (String file : files) {
            String stem = stripExtension(file);
            if (!impactDump.contains(file) && !impactDump.contains(stem)) {
                missing.add(file);
            }
        }
        boolean passed = missing.isEmpty();
        blockers.addAll(missing);
        checks.add(check(
                "tech_plan_files_in_impact",
                passed,
                passed
                        ? "All tech_plan.files referenced by impact"
                        : "Files not referenced by impact: " + missing
        ));
    }

    private void runAcceptanceCriteriaCheck(Map<String, Object> implement,
                                            List<String> acceptanceCriteria,
                                            List<Map<String, Object>> checks,
                                            List<String> blockers) {
        List<String> steps = extractBizPlanSteps(implement);
        List<String> missing = new ArrayList<>();
        for (String ac : acceptanceCriteria) {
            boolean matched = false;
            for (String step : steps) {
                if (step.contains(ac) || ac.contains(step)) {
                    matched = true;
                    break;
                }
            }
            if (!matched) {
                missing.add(ac);
            }
        }
        boolean passed = missing.isEmpty();
        for (String ac : missing) {
            blockers.add("AC not addressed: " + ac);
        }
        checks.add(check(
                "acceptance_criteria_addressed",
                passed,
                passed
                        ? "All acceptance criteria referenced by biz_plan.steps"
                        : "Unaddressed acceptance criteria: " + missing
        ));
    }

    private void runValidationCheck(Map<String, Object> impact,
                                    List<Map<String, Object>> checks,
                                    List<String> blockers) {
        Map<String, Object> validation = asMap(impact.get("validation"));
        Object passedRaw = validation.get("passed");
        boolean passed = passedRaw == null || Boolean.TRUE.equals(passedRaw);
        if (!passed) {
            List<String> violations = asStringList(validation.get("violations"));
            blockers.addAll(violations);
        }
        checks.add(check(
                "impact_validation_passed",
                passed,
                passed
                        ? "impact.validation.passed is true"
                        : "impact.validation reported violations"
        ));
    }

    private Map<String, Object> check(String name, boolean passed, String detail) {
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("name", name);
        c.put("passed", passed);
        c.put("detail", detail);
        return c;
    }

    @SuppressWarnings("unchecked")
    private List<String> extractTechPlanFiles(Map<String, Object> implement) {
        Map<String, Object> tech = asMap(implement.get("tech_plan"));
        Object files = tech.get("files");
        if (files instanceof List<?> list) {
            List<String> out = new ArrayList<>(list.size());
            for (Object o : list) {
                if (o instanceof String s && !s.isBlank()) {
                    out.add(s);
                }
            }
            return out;
        }
        return List.of();
    }

    private List<String> extractBizPlanSteps(Map<String, Object> implement) {
        Map<String, Object> biz = asMap(implement.get("biz_plan"));
        Object steps = biz.get("steps");
        if (steps instanceof List<?> list) {
            List<String> out = new ArrayList<>(list.size());
            for (Object o : list) {
                if (o instanceof String s) {
                    out.add(s);
                }
            }
            return out;
        }
        return List.of();
    }

    private void collectStrings(Object value, StringBuilder sink) {
        if (value == null) {
            return;
        }
        if (value instanceof String s) {
            sink.append(s).append('\n');
        } else if (value instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> e : map.entrySet()) {
                collectStrings(e.getKey(), sink);
                collectStrings(e.getValue(), sink);
            }
        } else if (value instanceof Collection<?> coll) {
            for (Object o : coll) {
                collectStrings(o, sink);
            }
        } else {
            sink.append(value).append('\n');
        }
    }

    private String stripExtension(String file) {
        int dot = file.lastIndexOf('.');
        return dot > 0 ? file.substring(0, dot) : file;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> asMap(Object raw) {
        if (raw instanceof Map<?, ?> map) {
            return (Map<String, Object>) map;
        }
        return Map.of();
    }

    private List<String> asStringList(Object raw) {
        if (raw instanceof List<?> list) {
            List<String> out = new ArrayList<>(list.size());
            for (Object o : list) {
                if (o instanceof String s) {
                    out.add(s);
                }
            }
            return out;
        }
        return List.of();
    }
}
