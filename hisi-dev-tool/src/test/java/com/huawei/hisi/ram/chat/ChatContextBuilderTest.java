package com.huawei.hisi.ram.chat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ChatContextBuilderTest {

    // buildSystemPrompt does not touch the repository or ObjectMapper fields, so nulls are safe here.
    private final ChatContextBuilder builder = new ChatContextBuilder(null, null);

    @Test
    @DisplayName("system prompt no longer instructs JSON schema output")
    void systemPrompt_noJsonSchema() {
        String prompt = builder.buildSystemPrompt(List.of());

        assertThat(prompt)
            .doesNotContain("JSON 对象")
            .doesNotContain("{answer")
            .doesNotContain("key_findings");
    }

    @Test
    @DisplayName("system prompt instructs markdown output")
    void systemPrompt_hasMarkdownInstruction() {
        String prompt = builder.buildSystemPrompt(List.of());

        assertThat(prompt).containsIgnoringCase("markdown");
    }
}
