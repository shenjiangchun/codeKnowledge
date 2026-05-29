package com.huawei.hisi.ram.nodes;

import com.huawei.hisi.ram.contract.SchemaValidator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@SuppressWarnings("unchecked")
@ExtendWith(MockitoExtension.class)
class VerifyNodeTest {

    private SchemaValidator schemaValidator;
    private VerifyNode verifyNode;

    @BeforeEach
    void setUp() {
        schemaValidator = new SchemaValidator();
        verifyNode = new VerifyNode(schemaValidator);
    }

    private Map<String, Object> implementWith(List<String> steps,
                                               List<Map<String, Object>> apiChanges) {
        Map<String, Object> biz = new java.util.LinkedHashMap<>();
        biz.put("steps", steps);
        biz.put("data_flow", "x");
        return Map.of(
                "biz_plan", biz,
                "api_changes", apiChanges,
                "state_machine_changes", List.of(),
                "data_model_changes", List.of(),
                "config_changes", List.of()
        );
    }

    private Map<String, Object> impactWith(boolean validationPassed, List<String> violations) {
        return Map.of(
                "validation", Map.of("passed", validationPassed, "violations", violations),
                "affected_entries", Map.of("direct", List.of(), "indirect", List.of()),
                "methods_to_modify", List.of()
        );
    }

    @Test
    @DisplayName("runs 6 checks and passes when all are green")
    void verify_runs6ChecksAndPassesWhenAllGreen() {
        Map<String, Object> impact = Map.of(
                "validation", Map.of("passed", true, "violations", List.of()),
                "affected_entries", Map.of(
                        "direct", List.of(Map.of("className", "ReqController", "methodName", "deliver", "type", "CONTROLLER")),
                        "indirect", List.of()
                ),
                "methods_to_modify", List.of(
                        Map.of("className", "RequireStatusServiceImpl", "methodName", "syncReqStatus")
                )
        );
        Map<String, Object> implement = Map.of(
                "biz_plan", Map.of("steps", List.of("修改syncReqStatus方法，满足AC1要求"), "data_flow", "x"),
                "api_changes", List.of(
                        Map.of("endpoint", "POST /api/req/deliver",
                               "current_behavior", "不变", "new_behavior", "回卷",
                               "method_ref", "ReqController#deliver"),
                        Map.of("endpoint", "internal",
                               "current_behavior", "只同步", "new_behavior", "同步+回卷",
                               "method_ref", "RequireStatusServiceImpl#syncReqStatus")
                ),
                "state_machine_changes", List.of(Map.of(
                        "enum_type", "ReqStatus",
                        "old_values", List.of("初始", "已发行"),
                        "new_values", List.of("初始", "设计"),
                        "migration_note", "存量已发行→设计"
                )),
                "data_model_changes", List.of(),
                "config_changes", List.of()
        );
        Map<String, Object> input = Map.of(
                "implement", implement,
                "impact", impact,
                "acceptance_criteria", List.of("AC1"),
                "intent", "需求状态回卷修改"
        );

        Map<String, Object> out = verifyNode.execute(input);

        assertThat((Boolean) out.get("pass")).isTrue();
        assertThat((List<?>) out.get("blockers")).isEmpty();
        List<Map<String, Object>> checks = (List<Map<String, Object>>) out.get("checks");
        assertThat(checks).hasSize(6);
        assertThat(checks.stream().map(c -> c.get("name")).toList())
                .containsExactly(
                        "acceptance_criteria_addressed",
                        "api_changes_consistent",
                        "state_changes_complete",
                        "data_migration_covered",
                        "impact_validation_passed",
                        "change_coverage_ratio"
                );
    }

    @Test
    @DisplayName("fails when acceptance criteria not addressed by biz_plan.steps")
    void verify_failsWhenACNotAddressed() {
        Map<String, Object> input = Map.of(
                "implement", implementWith(List.of("Generic step"), List.of()),
                "impact", impactWith(true, List.of()),
                "acceptance_criteria", List.of("AC1", "AC2"),
                "intent", "fix bug"
        );

        Map<String, Object> out = verifyNode.execute(input);

        assertThat((Boolean) out.get("pass")).isFalse();
        assertThat((List<Object>) out.get("blockers"))
                .contains("AC not addressed: AC1", "AC not addressed: AC2");
    }

    @Test
    @DisplayName("fails when impact.validation.passed is false")
    void verify_failsWhenImpactValidationViolated() {
        Map<String, Object> input = Map.of(
                "implement", implementWith(List.of("Address AC1"), List.of()),
                "impact", impactWith(false, List.of("EntryX not in KG")),
                "acceptance_criteria", List.of("AC1"),
                "intent", "fix bug"
        );

        Map<String, Object> out = verifyNode.execute(input);

        assertThat((Boolean) out.get("pass")).isFalse();
        assertThat((List<Object>) out.get("blockers")).contains("EntryX not in KG");
    }

    @Test
    @DisplayName("fails when direct entries lack api_changes")
    void verify_failsWhenDirectEntriesLackApiChanges() {
        Map<String, Object> impact = Map.of(
                "validation", Map.of("passed", true, "violations", List.of()),
                "affected_entries", Map.of(
                        "direct", List.of(Map.of("className", "OrderController", "methodName", "createOrder", "type", "HTTP")),
                        "indirect", List.of()
                ),
                "methods_to_modify", List.of()
        );
        Map<String, Object> implement = Map.of(
                "biz_plan", Map.of("steps", List.of("修改Order"), "data_flow", "x"),
                "api_changes", List.of(), // Empty — no api_change for OrderController#createOrder
                "state_machine_changes", List.of(),
                "data_model_changes", List.of(),
                "config_changes", List.of()
        );
        Map<String, Object> input = Map.of(
                "implement", implement,
                "impact", impact,
                "acceptance_criteria", List.of("Order"),
                "intent", "modify order"
        );

        Map<String, Object> out = verifyNode.execute(input);

        assertThat((Boolean) out.get("pass")).isFalse();
        assertThat((List<Object>) out.get("blockers"))
                .anyMatch(b -> b.toString().contains("Direct entry not in api_changes"));
    }

    @Test
    @DisplayName("fails when intent mentions state changes but state_machine_changes is empty")
    void verify_failsWhenStateChangesMissing() {
        Map<String, Object> input = Map.of(
                "implement", implementWith(List.of("修改状态逻辑"), List.of()),
                "impact", impactWith(true, List.of()),
                "acceptance_criteria", List.of("状态"),
                "intent", "需求状态变更回卷逻辑修改"
        );

        Map<String, Object> out = verifyNode.execute(input);

        List<Map<String, Object>> checks = (List<Map<String, Object>>) out.get("checks");
        Map<String, Object> stateCheck = checks.stream()
                .filter(c -> "state_changes_complete".equals(c.get("name")))
                .findFirst().orElseThrow();
        assertThat(stateCheck.get("passed")).isEqualTo(false);
    }

    @Test
    @DisplayName("fails when state_machine_changes lack migration_note or data_model_changes")
    void verify_failsWhenStateChangesLackMigration() {
        Map<String, Object> implement = Map.of(
                "biz_plan", Map.of("steps", List.of("修改状态"), "data_flow", "x"),
                "api_changes", List.of(),
                "state_machine_changes", List.of(Map.of(
                        "enum_type", "ReqStatus",
                        "old_values", List.of("初始"),
                        "new_values", List.of("设计"),
                        "migration_note", "" // Empty — no migration plan
                )),
                "data_model_changes", List.of(), // Also empty
                "config_changes", List.of()
        );
        Map<String, Object> input = Map.of(
                "implement", implement,
                "impact", impactWith(true, List.of()),
                "acceptance_criteria", List.of("修改状态"),
                "intent", "状态变更"
        );

        Map<String, Object> out = verifyNode.execute(input);

        List<Map<String, Object>> checks = (List<Map<String, Object>>) out.get("checks");
        Map<String, Object> migrationCheck = checks.stream()
                .filter(c -> "data_migration_covered".equals(c.get("name")))
                .findFirst().orElseThrow();
        assertThat(migrationCheck.get("passed")).isEqualTo(false);
    }

    @Test
    @DisplayName("change_coverage_ratio calculates correctly")
    void verify_changeCoverageRatioCalculatesCorrectly() {
        Map<String, Object> impact = Map.of(
                "validation", Map.of("passed", true, "violations", List.of()),
                "affected_entries", Map.of("direct", List.of(), "indirect", List.of()),
                "methods_to_modify", List.of(
                        Map.of("className", "RequireStatusServiceImpl", "methodName", "syncReqStatus"),
                        Map.of("className", "ReqController", "methodName", "deliver")
                )
        );
        // api_change only covers ReqController#deliver, not RequireStatusServiceImpl#syncReqStatus
        Map<String, Object> implement = Map.of(
                "biz_plan", Map.of("steps", List.of("修改回卷"), "data_flow", "x"),
                "api_changes", List.of(Map.of(
                        "endpoint", "POST /api/deliver",
                        "current_behavior", "不变",
                        "new_behavior", "回卷",
                        "method_ref", "ReqController#deliver"
                )),
                "state_machine_changes", List.of(),
                "data_model_changes", List.of(),
                "config_changes", List.of()
        );
        Map<String, Object> input = Map.of(
                "implement", implement,
                "impact", impact,
                "acceptance_criteria", List.of("回卷"),
                "intent", "回卷逻辑"
        );

        Map<String, Object> out = verifyNode.execute(input);

        List<Map<String, Object>> checks = (List<Map<String, Object>>) out.get("checks");
        Map<String, Object> ratioCheck = checks.stream()
                .filter(c -> "change_coverage_ratio".equals(c.get("name")))
                .findFirst().orElseThrow();
        String detail = (String) ratioCheck.get("detail");
        assertThat(detail).contains("50%"); // 1 out of 2 methods covered
    }
}
