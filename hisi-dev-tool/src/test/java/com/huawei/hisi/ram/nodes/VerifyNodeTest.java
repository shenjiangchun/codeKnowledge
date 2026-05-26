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

    private Map<String, Object> implementWith(List<String> files, List<String> steps) {
        return Map.of(
                "biz_plan", Map.of("steps", steps, "data_flow", "x"),
                "ui_plan", Map.of("screens", List.of("Main"), "interactions", List.of("submit")),
                "tech_plan", Map.of(
                        "files", files,
                        "new_apis", List.of(),
                        "schema_changes", List.of()
                )
        );
    }

    private Map<String, Object> impactWith(String className, boolean validationPassed, List<String> violations) {
        return Map.of(
                "involved", Map.of(
                        "seeds", List.of(),
                        "entries", List.of(Map.of("className", className, "methodName", "m")),
                        "impls", List.of()
                ),
                "modified", Map.of("tree", Map.of()),
                "impacted", Map.of(
                        "upstream", List.of(),
                        "downstream", List.of()
                ),
                "validation", Map.of(
                        "passed", validationPassed,
                        "violations", violations
                )
        );
    }

    @Test
    @DisplayName("fails when tech_plan.files not referenced by impact")
    void verify_failsWhenTechPlanFilesNotInImpactedSet() {
        Map<String, Object> input = Map.of(
                "implement", implementWith(List.of("UnrelatedFile.java"), List.of("step")),
                "impact", impactWith("com.example.OrderService", true, List.of()),
                "acceptance_criteria", List.of()
        );

        Map<String, Object> out = verifyNode.execute(input);

        assertThat((Boolean) out.get("pass")).isFalse();
        assertThat((List<Object>) out.get("blockers")).contains("UnrelatedFile.java");
    }

    @Test
    @DisplayName("passes when all 3 checks are green")
    void verify_passesWhenAllChecksGreen() {
        Map<String, Object> input = Map.of(
                "implement", implementWith(List.of("OrderService.java"), List.of("Address AC1")),
                "impact", impactWith("com.example.OrderService", true, List.of()),
                "acceptance_criteria", List.of("AC1")
        );

        Map<String, Object> out = verifyNode.execute(input);

        assertThat((Boolean) out.get("pass")).isTrue();
        assertThat((List<?>) out.get("blockers")).isEmpty();
        assertThat((List<?>) out.get("checks")).hasSize(3);
    }

    @Test
    @DisplayName("fails when acceptance criteria not referenced by biz_plan.steps")
    void verify_failsWhenAcceptanceCriteriaNotAddressed() {
        Map<String, Object> input = Map.of(
                "implement", implementWith(List.of("OrderService.java"), List.of("Generic step")),
                "impact", impactWith("com.example.OrderService", true, List.of()),
                "acceptance_criteria", List.of("AC1", "AC2")
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
                "implement", implementWith(List.of("OrderService.java"), List.of("Address AC1")),
                "impact", impactWith("com.example.OrderService", false, List.of("EntryX not in KG")),
                "acceptance_criteria", List.of("AC1")
        );

        Map<String, Object> out = verifyNode.execute(input);

        assertThat((Boolean) out.get("pass")).isFalse();
        assertThat((List<Object>) out.get("blockers")).contains("EntryX not in KG");
    }
}
