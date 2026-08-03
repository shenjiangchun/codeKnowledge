package com.huawei.hisi.apm.service.locator;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.huawei.hisi.apm.config.ApmDiagnoseProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

class LlmDiagnoseAdapterTest {

    private LlmClient client;
    private LlmDiagnoseAdapter adapter;
    private ApmDiagnoseProperties props;

    @BeforeEach
    void setUp() {
        client = Mockito.mock(LlmClient.class);
        props = new ApmDiagnoseProperties();
        props.setLlmTimeoutSeconds(5);
        props.setTimeoutSeconds(60);
        adapter = new LlmDiagnoseAdapter(
                new FailureLocatorPromptBuilder(),
                client,
                props,
                new ObjectMapper());
    }

    @Test
    @DisplayName("happy path: parses rootCauseMarkdown and confidence")
    void happyPath() {
        when(client.chat(anyString(), anyString())).thenReturn(
                "{\"rootCauseMarkdown\":\"## md\",\"confidence\":0.85,\"summary\":\"x\"}");
        var r = adapter.diagnose("/p", List.of(), List.of(), null);
        assertThat(r.rootCauseMarkdown()).isEqualTo("## md");
        assertThat(r.confidence()).isEqualTo(0.85);
    }

    @Test
    @DisplayName("wrapped in ```json fences still parses")
    void codeFencesStripped() {
        when(client.chat(anyString(), anyString())).thenReturn(
                "```json\n{\"rootCauseMarkdown\":\"## fenced\",\"confidence\":0.5}\n```");
        var r = adapter.diagnose("/p", List.of(), List.of(), null);
        assertThat(r.rootCauseMarkdown()).isEqualTo("## fenced");
        assertThat(r.confidence()).isEqualTo(0.5);
    }

    @Test
    @DisplayName("malformed JSON throws DiagnoseLlmInvalidResponseException")
    void malformedJsonThrows() {
        when(client.chat(anyString(), anyString())).thenReturn("not json at all { ! }");
        assertThatThrownBy(() -> adapter.diagnose("/p", List.of(), List.of(), null))
                .isInstanceOf(DiagnoseLlmInvalidResponseException.class)
                .hasMessageContaining("unparseable");
    }

    @Test
    @DisplayName("confidence > 1 clamped to 1.0")
    void confidenceClampedHigh() {
        when(client.chat(anyString(), anyString())).thenReturn(
                "{\"rootCauseMarkdown\":\"md\",\"confidence\":1.7}");
        var r = adapter.diagnose("/p", List.of(), List.of(), null);
        assertThat(r.confidence()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("confidence missing defaults to 0.3")
    void confidenceDefaults() {
        when(client.chat(anyString(), anyString())).thenReturn(
                "{\"rootCauseMarkdown\":\"only md\"}");
        var r = adapter.diagnose("/p", List.of(), List.of(), null);
        assertThat(r.confidence()).isEqualTo(0.3);
        assertThat(r.rootCauseMarkdown()).isEqualTo("only md");
    }

    @Test
    @DisplayName("upstream call throws → wrapped in DiagnoseLlmException")
    void upstreamThrowsWrapped() {
        when(client.chat(anyString(), anyString())).thenThrow(new RuntimeException("boom"));
        assertThatThrownBy(() -> adapter.diagnose("/p", List.of(), List.of(), null))
                .isInstanceOf(DiagnoseLlmException.class)
                .hasMessageContaining("LLM call failed");
    }

    @Test
    @DisplayName("call exceeding timeout throws DiagnoseLlmTimeoutException")
    void timeoutTranslatesToDomainException() {
        when(client.chat(anyString(), anyString())).thenAnswer(inv -> {
            Thread.sleep(2500);
            return "{}";
        });
        props.setLlmTimeoutSeconds(1);
        assertThatThrownBy(() -> adapter.diagnose("/p", List.of(), List.of(), null))
                .isInstanceOf(DiagnoseLlmTimeoutException.class);
    }
}
