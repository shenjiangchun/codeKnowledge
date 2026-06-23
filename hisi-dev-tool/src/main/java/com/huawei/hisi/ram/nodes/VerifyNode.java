package com.huawei.hisi.ram.nodes;

import com.huawei.hisi.ram.contract.SchemaValidator;
import com.huawei.hisi.ram.contract.ValidationResult;
import com.huawei.hisi.workflow.DagNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.LinkedHashSet;

/**
 * Verify stage DAG node — runs 6 deterministic cross-checks across the
 * {@code implement} and {@code impact} stage outputs.
 *
 * <p>Checks:
 * <ol>
 *     <li>{@code acceptance_criteria_addressed} — each AC is referenced by a biz_plan step.</li>
 *     <li>{@code api_changes_consistent} — each direct affected_entry has a corresponding api_change.</li>
 *     <li>{@code state_changes_complete} — if impact mentions enum/state changes, implement has state_machine_changes.</li>
 *     <li>{@code data_migration_covered} — if state_machine_changes exist, must have migration_note or data_model_changes.</li>
 *     <li>{@code impact_validation_passed} — impact.validation.passed is true.</li>
 *     <li>{@code change_coverage_ratio} — ratio of methods_to_modify covered by api/state/data changes.</li>
 * </ol>
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

        runAcceptanceCriteriaCheck(implement, acceptanceCriteria, checks, blockers);
        runApiChangesConsistentCheck(implement, impact, checks, blockers);
        runStateChangesCompleteCheck(implement, input, checks, blockers);
        runDataMigrationCoveredCheck(implement, checks, blockers);
        runValidationCheck(impact, checks, blockers);
        runChangeCoverageRatioCheck(implement, impact, checks, blockers);

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

    // ─── Check 1: acceptance_criteria_addressed ───

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

    // ─── Check 2: api_changes_consistent ───

    private void runApiChangesConsistentCheck(Map<String, Object> implement,
                                              Map<String, Object> impact,
                                              List<Map<String, Object>> checks,
                                              List<String> blockers) {
        // Collect method_refs from api_changes
        Set<String> apiChangeRefs = new LinkedHashSet<>();
        Object apiChanges = implement.get("api_changes");
        if (apiChanges instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof Map<?, ?> map) {
                    Object ref = map.get("method_ref");
                    if (ref instanceof String s && !s.isBlank()) {
                        apiChangeRefs.add(s);
                    }
                }
            }
        }

        // Collect direct affected entries
        List<String> directRefs = new ArrayList<>();
        Map<String, Object> affectedEntries = asMap(impact.get("affected_entries"));
        Object direct = affectedEntries.get("direct");
        if (direct instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof Map<?, ?> map) {
                    String ref = formatMethodRef(map.get("className"), map.get("methodName"));
                    if (ref != null) directRefs.add(ref);
                }
            }
        }

        List<String> missing = new ArrayList<>();
        for (String directRef : directRefs) {
            boolean found = false;
            for (String apiRef : apiChangeRefs) {
                if (apiRef.equals(directRef) || apiRef.contains(directRef) || directRef.contains(apiRef)) {
                    found = true;
                    break;
                }
            }
            if (!found) {
                missing.add(directRef);
            }
        }
        boolean passed = missing.isEmpty();
        for (String m : missing) {
            blockers.add("Direct entry not in api_changes: " + m);
        }
        checks.add(check(
                "api_changes_consistent",
                passed,
                passed
                        ? "All direct affected entries have corresponding api_changes"
                        : "Missing api_changes for: " + missing
        ));
    }

    // ─── Check 3: state_changes_complete ───

    private void runStateChangesCompleteCheck(Map<String, Object> implement,
                                              Map<String, Object> input,
                                              List<Map<String, Object>> checks,
                                              List<String> blockers) {
        // Detect if the intent mentions state/enum changes
        String intent = input.get("intent") instanceof String s ? s : "";
        boolean mentionsStateChange = intent.contains("状态")
                && (intent.contains("变更") || intent.contains("修改") || intent.contains("调整")
                    || intent.contains("回卷") || intent.contains("替换") || intent.contains("新增"));

        List<?> stateChanges = asList(implement.get("state_machine_changes"));
        boolean passed = !mentionsStateChange || !stateChanges.isEmpty();

        if (!passed) {
            blockers.add("Intent mentions state changes but state_machine_changes is empty");
        }
        checks.add(check(
                "state_changes_complete",
                passed,
                passed
                        ? (mentionsStateChange ? "State changes properly specified" : "No state changes detected in intent")
                        : "Intent mentions state changes but state_machine_changes is missing"
        ));
    }

    // ─── Check 4: data_migration_covered ───

    private void runDataMigrationCoveredCheck(Map<String, Object> implement,
                                              List<Map<String, Object>> checks,
                                              List<String> blockers) {
        List<?> stateChanges = asList(implement.get("state_machine_changes"));
        List<?> dataModelChanges = asList(implement.get("data_model_changes"));

        if (stateChanges.isEmpty()) {
            checks.add(check("data_migration_covered", true, "No state_machine_changes — migration check N/A"));
            return;
        }

        // Check if any state_machine_change has migration_note
        boolean hasMigrationNote = false;
        for (Object item : stateChanges) {
            if (item instanceof Map<?, ?> map) {
                Object note = map.get("migration_note");
                if (note instanceof String s && !s.isBlank()) {
                    hasMigrationNote = true;
                    break;
                }
            }
        }

        boolean passed = hasMigrationNote || !dataModelChanges.isEmpty();
        if (!passed) {
            blockers.add("state_machine_changes exist but no migration_note or data_model_changes");
        }
        checks.add(check(
                "data_migration_covered",
                passed,
                passed
                        ? "State changes have migration coverage"
                        : "Missing migration plan for state changes"
        ));
    }

    // ─── Check 5: impact_validation_passed ───

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

    // ─── Check 6: change_coverage_ratio ───

    private void runChangeCoverageRatioCheck(Map<String, Object> implement,
                                             Map<String, Object> impact,
                                             List<Map<String, Object>> checks,
                                             List<String> blockers) {
        // Collect method refs from all change specs
        Set<String> coveredRefs = new LinkedHashSet<>();

        // From api_changes
        Object apiChanges = implement.get("api_changes");
        if (apiChanges instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof Map<?, ?> map) {
                    Object ref = map.get("method_ref");
                    if (ref instanceof String s && !s.isBlank()) coveredRefs.add(s);
                }
            }
        }

        // From state_machine_changes — these cover enum-related methods
        Object stateChanges = implement.get("state_machine_changes");
        if (stateChanges instanceof List<?> list && !list.isEmpty()) {
            // state changes are method-agnostic, but we count them as coverage
            coveredRefs.add("(state_machine_changes)");
        }

        // From data_model_changes
        Object dataModelChanges = implement.get("data_model_changes");
        if (dataModelChanges instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof Map<?, ?> map) {
                    Object entity = map.get("entity");
                    Object field = map.get("field");
                    if (entity instanceof String s) coveredRefs.add(s + "." + (field instanceof String f ? f : ""));
                }
            }
        }

        // Collect methods_to_modify from impact
        List<String> methodsToModify = new ArrayList<>();
        Object mtm = impact.get("methods_to_modify");
        if (mtm instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof Map<?, ?> map) {
                    String ref = formatMethodRef(map.get("className"), map.get("methodName"));
                    if (ref != null) methodsToModify.add(ref);
                }
            }
        }

        if (methodsToModify.isEmpty()) {
            checks.add(check("change_coverage_ratio", true, "No methods_to_modify — ratio check N/A"));
            return;
        }

        // Count how many methods_to_modify are covered
        long covered = methodsToModify.stream()
                .filter(m -> coveredRefs.stream().anyMatch(cr -> cr.contains(m) || m.contains(cr)))
                .count();

        double ratio = (double) covered / methodsToModify.size();
        boolean passed = ratio >= 0.5; // At least half should be covered

        if (!passed) {
            blockers.add(String.format("Low change coverage: %.0f%% of methods_to_modify covered", ratio * 100));
        }
        checks.add(check(
                "change_coverage_ratio",
                passed,
                String.format("%.0f%% (%d/%d) of methods_to_modify covered by change specs",
                        ratio * 100, covered, methodsToModify.size())
        ));
    }

    // ─── Helpers ───

    private Map<String, Object> check(String name, boolean passed, String detail) {
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("name", name);
        c.put("passed", passed);
        c.put("detail", detail);
        return c;
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

    private String formatMethodRef(Object className, Object methodName) {
        String cls = className instanceof String s ? s : null;
        String meth = methodName instanceof String s ? s : null;
        if (cls == null && meth == null) return null;
        String shortClass = cls;
        if (cls != null && cls.contains(".")) {
            shortClass = cls.substring(cls.lastIndexOf('.') + 1);
        }
        if (shortClass != null && meth != null) return shortClass + "#" + meth;
        if (meth != null) return meth;
        return shortClass;
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

    @SuppressWarnings("unchecked")
    private List<Object> asList(Object raw) {
        if (raw instanceof List<?> list) {
            return (List<Object>) list;
        }
        return List.of();
    }
}
