package com.huawei.hisi.ram.nodes;

import com.huawei.hisi.ram.contract.SchemaValidator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ImplementNodeTest {

    @Mock
    private ImplementLlmClient llmClient;

    private SchemaValidator schemaValidator;
    private ImplementNode implementNode;

    @BeforeEach
    void setUp() {
        schemaValidator = new SchemaValidator();
        implementNode = new ImplementNode(llmClient, schemaValidator);
    }

    private Map<String, Object> validDraft() {
        return Map.of(
                "biz_plan", Map.of(
                        "steps", List.of("s1", "s2"),
                        "data_flow", "User -> API"
                ),
                "ui_plan", Map.of(
                        "screens", List.of("Main"),
                        "interactions", List.of("submit")
                ),
                "tech_plan", Map.of(
                        "files", List.of("Order.java"),
                        "new_apis", List.of("POST /orders"),
                        "schema_changes", List.of()
                )
        );
    }

    @Test
    @DisplayName("produces valid 3-artifact output validating against implement.output schema")
    void implement_producesValidThreeArtifacts() {
        when(llmClient.draft(any(), anyList(), anyString())).thenReturn(validDraft());

        Map<String, Object> input = Map.of(
                "risk", Map.of("level", "MEDIUM"),
                "acceptance_criteria", List.of("AC1", "AC2"),
                "involved", List.of("OrderService.create"),
                "modified", List.of(),
                "impacted", List.of(),
                "validation", Map.of()
        );

        Map<String, Object> result = implementNode.execute(input);

        @SuppressWarnings("unchecked")
        Map<String, Object> biz = (Map<String, Object>) result.get("biz_plan");
        @SuppressWarnings("unchecked")
        Map<String, Object> tech = (Map<String, Object>) result.get("tech_plan");
        assertThat((List<?>) biz.get("steps")).isNotEmpty();
        assertThat((List<?>) tech.get("files")).isNotEmpty();
        assertThat(schemaValidator.validate("implement.output", result).passed()).isTrue();
    }

    @Test
    @DisplayName("throws IllegalStateException when llm output fails implement.output schema")
    void implement_throwsWhenLlmOutputInvalid() {
        when(llmClient.draft(any(), anyList(), anyString()))
                .thenReturn(Map.of("biz_plan", Map.of()));

        Map<String, Object> input = Map.of(
                "risk", Map.of("level", "LOW"),
                "acceptance_criteria", List.of()
        );

        assertThatThrownBy(() -> implementNode.execute(input))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("implement.output");
    }

    @Test
    @DisplayName("selects claude-opus-4-6 when risk.level is HIGH")
    void implement_selectsOpus_whenRiskHigh() {
        when(llmClient.draft(any(), anyList(), anyString())).thenReturn(validDraft());

        Map<String, Object> input = Map.of(
                "risk", Map.of("level", "HIGH"),
                "acceptance_criteria", List.of("AC1")
        );

        implementNode.execute(input);

        ArgumentCaptor<String> modelCaptor = ArgumentCaptor.forClass(String.class);
        verify(llmClient).draft(any(), anyList(), modelCaptor.capture());
        assertThat(modelCaptor.getValue()).isEqualTo("claude-opus-4-6");
    }
}
