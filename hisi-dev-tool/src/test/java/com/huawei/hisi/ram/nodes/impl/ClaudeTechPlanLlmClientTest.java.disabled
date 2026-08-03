package com.huawei.hisi.ram.nodes.impl;

import com.huawei.hisi.ram.sdk.SendOptions;
import com.huawei.hisi.ram.sdk.ToolDefinition;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ClaudeTechPlanLlmClientTest {

    @Mock
    RamClaudeJsonClient claude;
    @Mock
    KgToolRegistry kgToolRegistry;

    @Test
    void generate_callsClaudeWithToolsAndReturnsStructuredOutput() {
        when(claude.isAvailable()).thenReturn(true);
        when(kgToolRegistry.isAvailable()).thenReturn(true);
        when(kgToolRegistry.buildToolDefinitions(anyString())).thenReturn(List.of());
        when(kgToolRegistry.buildToolHandlers(anyString())).thenReturn(Map.of());

        Map<String, Object> aiResult = new LinkedHashMap<>();
        aiResult.put("target_methods_detail", List.of(Map.of(
                "method", "Service#method",
                "file", "Service.java",
                "lines", "10-20",
                "current_logic", "current",
                "change_spec", "change",
                "pseudocode", "if (x) { y() }"
        )));
        aiResult.put("sequence_diagrams", List.of());
        aiResult.put("flow_diagrams", List.of());
        aiResult.put("test_scope", Map.of(
                "unit_tests", List.of("test1"),
                "integration_tests", List.of(),
                "data_migration", List.of()
        ));
        aiResult.put("risk_mitigations", List.of());
        aiResult.put("reasoning", "分析过程");

        when(claude.callJsonWithToolsAndReasoning(anyString(), anyString(), any(), any(), any(SendOptions.class)))
                .thenReturn(new RamClaudeJsonClient.JsonCallResult(aiResult, List.of("step1")));

        ClaudeTechPlanLlmClient client = new ClaudeTechPlanLlmClient(claude, kgToolRegistry);
        Map<String, Object> result = client.generate(Map.of(), Map.of(), "需求", "/p");

        assertThat(result).containsKey("target_methods_detail");
        assertThat(result).containsKey("sequence_diagrams");
        assertThat(result).containsKey("flow_diagrams");
        assertThat(result).containsKey("test_scope");
        assertThat(result).containsKey("risk_mitigations");
        assertThat(result).containsKey("reasoning");
        assertThat(result).containsKey("markdown_report");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> methods = (List<Map<String, Object>>) result.get("target_methods_detail");
        assertThat(methods).hasSize(1);
        assertThat(methods.get(0).get("method")).isEqualTo("Service#method");
    }

    @Test
    void generate_returnsMinimalOutput_whenClaudeUnavailable() {
        when(claude.isAvailable()).thenReturn(false);

        ClaudeTechPlanLlmClient client = new ClaudeTechPlanLlmClient(claude, kgToolRegistry);
        Map<String, Object> result = client.generate(Map.of(), Map.of(), "需求", "/p");

        assertThat(result).containsKey("target_methods_detail");
        assertThat((List<?>) result.get("target_methods_detail")).isEmpty();
        assertThat((String) result.get("reasoning")).contains("不可用");
    }

    @Test
    void isAvailable_delegatesToClaude() {
        when(claude.isAvailable()).thenReturn(true);
        ClaudeTechPlanLlmClient client = new ClaudeTechPlanLlmClient(claude, kgToolRegistry);
        assertThat(client.isAvailable()).isTrue();

        when(claude.isAvailable()).thenReturn(false);
        assertThat(client.isAvailable()).isFalse();
    }
}
