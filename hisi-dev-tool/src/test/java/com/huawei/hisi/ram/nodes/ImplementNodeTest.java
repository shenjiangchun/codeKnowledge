package com.huawei.hisi.ram.nodes;

import com.huawei.hisi.ram.contract.SchemaValidator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
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
                        "steps", List.of("修改syncReqStatus方法", "更新状态枚举"),
                        "data_flow", "前端 → Controller → Service → Repository",
                        "acceptance_mapping", Map.of("AC1", List.of("修改syncReqStatus方法"))
                ),
                "api_changes", List.of(Map.of(
                        "endpoint", "POST /api/req/deliver",
                        "current_behavior", "交付后状态不变",
                        "new_behavior", "交付后若下游状态>上游则回卷",
                        "method_ref", "ReqController#deliver"
                )),
                "state_machine_changes", List.of(),
                "data_model_changes", List.of(),
                "config_changes", List.of()
        );
    }

    @Test
    @DisplayName("produces valid output with new structured change specs")
    void implement_producesValidStructuredOutput() {
        when(llmClient.draft(any(), anyList(), nullable(String.class))).thenReturn(validDraft());

        Map<String, Object> input = Map.of(
                "risk", Map.of("level", "MEDIUM"),
                "acceptance_criteria", List.of("AC1", "AC2"),
                "affected_entries", Map.of("direct", List.of()),
                "validation", Map.of()
        );

        Map<String, Object> result = implementNode.execute(input);

        @SuppressWarnings("unchecked")
        Map<String, Object> biz = (Map<String, Object>) result.get("biz_plan");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> apiChanges = (List<Map<String, Object>>) result.get("api_changes");
        assertThat((List<?>) biz.get("steps")).isNotEmpty();
        assertThat(apiChanges).isNotEmpty();
        assertThat(apiChanges.get(0)).containsKey("endpoint");
        assertThat(apiChanges.get(0)).containsKey("current_behavior");
        assertThat(apiChanges.get(0)).containsKey("new_behavior");
        // Old keys must NOT be present
        assertThat(result).doesNotContainKey("tech_plan");
        assertThat(result).doesNotContainKey("ui_plan");
        assertThat(schemaValidator.validate("implement.output", result).passed()).isTrue();
    }

    @Test
    @DisplayName("throws IllegalStateException when llm output fails implement.output schema")
    void implement_throwsWhenLlmOutputInvalid() {
        when(llmClient.draft(any(), anyList(), nullable(String.class)))
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
    @DisplayName("passes null model to llm client by default")
    void implement_passesNullModelByDefault() {
        when(llmClient.draft(any(), anyList(), nullable(String.class))).thenReturn(validDraft());

        Map<String, Object> input = Map.of(
                "risk", Map.of("level", "HIGH"),
                "acceptance_criteria", List.of("AC1")
        );

        implementNode.execute(input);

        ArgumentCaptor<String> modelCaptor = ArgumentCaptor.forClass(String.class);
        verify(llmClient).draft(any(), anyList(), modelCaptor.capture());
        assertThat(modelCaptor.getValue()).isNull();
    }
}
