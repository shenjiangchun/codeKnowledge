package com.huawei.hisi.ram.nodes;

import com.huawei.hisi.ram.contract.SchemaValidator;
import com.huawei.hisi.workflow.ClarifyRequiredException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ClarifyNodeTest {

    @Mock
    private ClarifyLlmClient clarifyLlmClient;

    private ClarifyNode clarifyNode;

    @BeforeEach
    void setUp() {
        clarifyNode = new ClarifyNode(new SchemaValidator(), clarifyLlmClient);
    }

    @Test
    @DisplayName("throws ClarifyRequiredException with question mentioning the missing field")
    void clarify_throwsClarifyRequired_whenProjectPathsMissing() {
        when(clarifyLlmClient.extractRequirements(anyString(), any()))
                .thenReturn(Map.of(
                        "intent", "X",
                        "acceptance_criteria", List.of("a1")
                ));

        assertThatThrownBy(() -> clarifyNode.execute(Map.of("userRequirement", "do X")))
                .isInstanceOf(ClarifyRequiredException.class)
                .satisfies(ex -> {
                    ClarifyRequiredException cre = (ClarifyRequiredException) ex;
                    assertThat(cre.getClarifyQuestions())
                            .anyMatch(q -> q.contains("project_paths"));
                });
    }

    @Test
    @DisplayName("returns the LLM output unchanged when all required fields are present")
    void clarify_returnsOutput_whenAllFieldsPresent() {
        Map<String, Object> llmOutput = Map.of(
                "intent", "X",
                "project_paths", List.of("repo1"),
                "acceptance_criteria", List.of("a1")
        );
        when(clarifyLlmClient.extractRequirements(anyString(), any())).thenReturn(llmOutput);

        Map<String, Object> result = clarifyNode.execute(Map.of("userRequirement", "do X"));

        assertThat(result).isEqualTo(llmOutput);
    }

    @Test
    @DisplayName("throws IllegalArgumentException when userRequirement input is missing")
    void clarify_throwsIllegalArgument_whenUserRequirementMissing() {
        assertThatThrownBy(() -> clarifyNode.execute(Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("userRequirement");
    }

    @Test
    @DisplayName("metadata is stable: name=clarify, agentId=clarify-v1")
    void clarify_metadata() {
        assertThat(clarifyNode.name()).isEqualTo("clarify");
        assertThat(clarifyNode.agentId()).isEqualTo("clarify-v1");
    }
}
