package com.huawei.hisi.config;

import org.junit.jupiter.api.Test;
import org.springframework.ai.anthropic.AnthropicChatModel;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.openai.OpenAiChatModel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Verifies AgentConfig produces correct ChatClient beans without
 * requiring a full Spring Boot context (no Neo4j/Anthropic API key needed).
 */
class AgentConfigUnitTest {

    private final AgentConfig config = new AgentConfig();

    @Test
    void chatMemory_returnsMessageWindowChatMemory() {
        ChatMemory mem = config.chatMemory();
        assertThat(mem).isInstanceOf(MessageWindowChatMemory.class);
    }

    @Test
    void agentChatClient_created() {
        AnthropicChatModel model = mock(AnthropicChatModel.class);
        ChatMemory mem = config.chatMemory();
        ChatClient client = config.agentChatClient(model, mem);
        assertThat(client).isNotNull();
    }

    @Test
    void kgChatClient_created() {
        OpenAiChatModel model = mock(OpenAiChatModel.class);
        ChatClient client = config.kgChatClient(model);
        assertThat(client).isNotNull();
    }
}
