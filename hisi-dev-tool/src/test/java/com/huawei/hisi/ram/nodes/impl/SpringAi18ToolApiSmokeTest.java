package com.huawei.hisi.ram.nodes.impl;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.anthropic.AnthropicChatModel;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Smoke test verifying Spring AI 1.1.8 @Tool + ToolContext API works
 * before migrating the LLM clients.
 */
@DisplayName("Spring AI 1.1.8 Tool API smoke test")
class SpringAi18ToolApiSmokeTest {

    /** A bean with @Tool methods for Spring AI auto-discovery. */
    static class TestTools {
        @Tool(description = "Search code by query")
        String search(@ToolParam(description = "Search query") String query,
                      ToolContext ctx) {
            Object raw = ctx.getContext().get("projectPath");
            String projectPath = raw instanceof String s ? s : "";
            return "Found results for '" + query + "' in " + projectPath;
        }
    }

    @Test
    @DisplayName("@Tool annotation compiles and is usable")
    void toolAnnotation_compiles() {
        // Verify the @Tool and ToolContext classes are loadable
        assertThat(Tool.class).isNotNull();
        assertThat(ToolContext.class).isNotNull();
    }

    @Test
    @DisplayName("ChatClient builder accepts tools")
    void chatClient_acceptsTools() {
        var model = mock(AnthropicChatModel.class);
        var tools = new TestTools();

        // This is the key API check — does ChatClient.Builder.defaultTools() exist?
        ChatClient client = ChatClient.builder(model)
                .defaultTools(tools)
                .build();

        assertThat(client).isNotNull();
    }
}
