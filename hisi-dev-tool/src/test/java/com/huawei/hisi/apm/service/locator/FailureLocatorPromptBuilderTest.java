package com.huawei.hisi.apm.service.locator;

import com.huawei.hisi.apm.model.ApmSpanEntity;
import com.huawei.hisi.apm.model.DiagnoseReport.EvidenceAnchor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class FailureLocatorPromptBuilderTest {

    private final FailureLocatorPromptBuilder builder = new FailureLocatorPromptBuilder();

    private static ApmSpanEntity span(String name, String exType, String exMsg, String trace) {
        return ApmSpanEntity.builder()
                .spanKind("INTERNAL")
                .operationName(name)
                .attributes(Map.of(
                        "exception.type", exType,
                        "exception.message", exMsg,
                        "exception.stacktrace", trace == null ? "" : trace))
                .build();
    }

    private static EvidenceAnchor anchor(String cls, String mth, String file, int line, String snip) {
        return new EvidenceAnchor("kg_method", cls, mth, file, line, null, snip);
    }

    @Test
    @DisplayName("system prompt is the documented constant")
    void systemPromptIsConstant() {
        var p = builder.build("/proj", List.of(), List.of(), null);
        assertThat(p.systemPrompt()).isEqualTo(FailureLocatorPromptBuilder.SYSTEM_PROMPT);
        assertThat(p.systemPrompt()).contains("JSON").contains("rootCauseMarkdown").contains("confidence");
    }

    @Test
    @DisplayName("user prompt contains projectPath")
    void userPromptContainsProjectPath() {
        var p = builder.build("/abs/my-proj", List.of(), List.of(), null);
        assertThat(p.userPrompt()).contains("PROJECT: /abs/my-proj");
    }

    @Test
    @DisplayName("exception span attributes appear in user prompt")
    void exceptionSpanAttributesAppear() {
        var s = span("doWork", "java.lang.NullPointerException", "boom!", "at Foo.bar(Foo.java:10)");
        var p = builder.build("/p", List.of(s), List.of(), null);
        assertThat(p.userPrompt())
                .contains("exception.type: java.lang.NullPointerException")
                .contains("exception.message: boom!")
                .contains("at Foo.bar(Foo.java:10)")
                .contains("doWork");
    }

    @Test
    @DisplayName("KG evidence anchors appear with className#methodName and snippet")
    void kgEvidenceAppears() {
        var a = anchor("com.foo.Bar", "execute", "Bar.java", 42, "return repo.find()");
        var p = builder.build("/p", List.of(), List.of(a), null);
        assertThat(p.userPrompt())
                .contains("com.foo.Bar#execute")
                .contains("Bar.java:42")
                .contains("return repo.find()");
    }

    @Test
    @DisplayName("null userNote renders '(none)' placeholder")
    void nullUserNoteRendersPlaceholder() {
        var p = builder.build("/p", List.of(), List.of(), null);
        assertThat(p.userPrompt()).contains("USER NOTE: (none)");
    }

    @Test
    @DisplayName("evidence list capped at 10 even when 100 supplied")
    void evidenceListCappedAt10() {
        List<EvidenceAnchor> anchors = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            anchors.add(anchor("com.foo.C" + i, "m" + i, "C.java", i, "snippet" + i));
        }
        var p = builder.build("/p", List.of(), anchors, null);
        assertThat(p.userPrompt()).contains("100 anchors");
        assertThat(p.userPrompt()).contains("com.foo.C0#m0");
        assertThat(p.userPrompt()).contains("com.foo.C9#m9");
        assertThat(p.userPrompt()).doesNotContain("com.foo.C10#m10");
    }

    @Test
    @DisplayName("final userPrompt length capped at MAX_USER_PROMPT_CHARS")
    void userPromptCapped() {
        List<EvidenceAnchor> anchors = new ArrayList<>();
        String hugeSnippet = "x".repeat(5000);
        for (int i = 0; i < 10; i++) {
            anchors.add(anchor("com.foo.C" + i, "m" + i, "C.java", i, hugeSnippet));
        }
        var p = builder.build("/p", List.of(), anchors, null);
        assertThat(p.userPrompt().length()).isLessThanOrEqualTo(FailureLocatorPromptBuilder.MAX_USER_PROMPT_CHARS);
    }
}
