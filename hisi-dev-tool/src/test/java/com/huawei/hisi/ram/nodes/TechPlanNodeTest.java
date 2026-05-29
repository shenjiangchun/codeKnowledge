package com.huawei.hisi.ram.nodes;

import com.huawei.hisi.ram.contract.SchemaValidator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class TechPlanNodeTest {

    @Mock
    private TechPlanLlmClient llmClient;

    private SchemaValidator schemaValidator;
    private TechPlanNode techPlanNode;

    @BeforeEach
    void setUp() {
        schemaValidator = new SchemaValidator();
        techPlanNode = new TechPlanNode(llmClient, schemaValidator);
    }

    @Test
    @DisplayName("produces valid output when llm returns structured result")
    void execute_producesValidOutput() {
        when(llmClient.generate(any(), any(), anyString(), anyString())).thenReturn(Map.of(
                "target_methods_detail", List.of(Map.of(
                        "method", "Service#method",
                        "file", "Service.java",
                        "lines", "10-20",
                        "current_logic", "current",
                        "change_spec", "change",
                        "pseudocode", "if (x) { y() }"
                )),
                "sequence_diagrams", List.of(),
                "flow_diagrams", List.of(),
                "test_scope", Map.of(
                        "unit_tests", List.of("test1"),
                        "integration_tests", List.of(),
                        "data_migration", List.of()
                ),
                "risk_mitigations", List.of(),
                "reasoning", "test reasoning",
                "markdown_report", ""
        ));

        Map<String, Object> result = techPlanNode.execute(Map.of(
                "impact", Map.of(),
                "implement", Map.of(),
                "intent", "需求",
                "projectPath", "/p"
        ));

        assertThat(result).containsKey("target_methods_detail");
        assertThat(techPlanNode.name()).isEqualTo("tech_plan");
    }

    @Test
    @DisplayName("throws when input is null")
    void execute_throwsWhenInputNull() {
        assertThatThrownBy(() -> techPlanNode.execute(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("throws when llm output fails schema validation")
    void execute_throwsWhenSchemaValidationFails() {
        when(llmClient.generate(any(), any(), anyString(), anyString()))
                .thenReturn(Map.of("reasoning", "no detail"));

        assertThatThrownBy(() -> techPlanNode.execute(Map.of(
                "impact", Map.of(),
                "implement", Map.of(),
                "intent", "需求",
                "projectPath", "/p"
        ))).isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("tech_plan.output");
    }
}
