package com.huawei.hisi.config;

import org.springframework.ai.anthropic.AnthropicChatModel;
import org.springframework.ai.anthropic.AnthropicChatOptions;
import org.springframework.ai.anthropic.api.AnthropicApi;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.PromptChatMemoryAdvisor;
import org.springframework.ai.chat.client.advisor.SimpleLoggerAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

@Configuration
public class AgentConfig {

    @Bean
    ChatMemory chatMemory() {
        return MessageWindowChatMemory.builder()
                .maxMessages(20)
                .build();
    }

    @Bean
    @Primary
    ChatClient agentChatClient(AnthropicChatModel anthropicModel,
                                ChatMemory chatMemory) {
        return ChatClient.builder(anthropicModel)
                .defaultAdvisors(
                        PromptChatMemoryAdvisor.builder(chatMemory).build(),
                        new SimpleLoggerAdvisor())
                .build();
    }

    @Bean
    ChatClient kgChatClient(@Qualifier("openAiChatModel") OpenAiChatModel zhipuModel) {
        return ChatClient.builder(zhipuModel).build();
    }

    /**
     * 无记忆 advisor 的干净 ChatClient，专用于聚合管道的批量抽取（如领域业务名词提取）。
     *
     * <p>区别于 {@link #agentChatClient}：不挂 {@link PromptChatMemoryAdvisor}，
     * 因此调用时无需传 conversationId。模型走 anthropic 中转（deepseek-v4-pro-cc），
     * 配置读 {@code spring.ai.anthropic.*}。maxTokens 调大，避免推理模型思考链吃光预算。
     */
    @Bean
    ChatClient extractionChatClient(AnthropicChatModel anthropicModel,
                                    @Value("${spring.ai.anthropic.chat.options.model:claude-sonnet-4-20250514}") String model,
                                    @Value("${spring.ai.anthropic.chat.options.max-tokens:16384}") int maxTokens) {
        return ChatClient.builder(anthropicModel)
                .defaultOptions(AnthropicChatOptions.builder()
                        .model(model)
                        .maxTokens(maxTokens)
                        .temperature(0.1)
                        .thinking(AnthropicApi.ThinkingType.DISABLED, null)
                        .build())
                .build();
    }
}
