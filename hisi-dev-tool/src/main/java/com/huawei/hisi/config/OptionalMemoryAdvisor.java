package com.huawei.hisi.config;

import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.PromptChatMemoryAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisor;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.ai.chat.memory.ChatMemory;
import reactor.core.publisher.Flux;

/**
 * 只对「显式带了 conversationId」的调用生效的会话记忆 advisor。
 *
 * <p>{@link PromptChatMemoryAdvisor} 要求每次调用都在上下文里带 conversationId，否则抛
 * {@code conversationId cannot be null}。而挂它的 ChatClient 还被很多一次性抽取调用复用
 * （需求分析的澄清 / 实现 / 技术方案、Agent 对话接口）——这些调用没有会话概念，以前每次都抛异常，
 * 需求分析的那几个 LLM 节点因此一直在悄悄回退到桩实现。没带会话 ID 的调用不读写记忆，直接放行。
 */
public final class OptionalMemoryAdvisor implements CallAdvisor, StreamAdvisor {

    private final PromptChatMemoryAdvisor delegate;

    public OptionalMemoryAdvisor(ChatMemory chatMemory) {
        this.delegate = PromptChatMemoryAdvisor.builder(chatMemory).build();
    }

    private static boolean hasConversation(ChatClientRequest request) {
        return request.context().get(ChatMemory.CONVERSATION_ID) != null;
    }

    @Override
    public int getOrder() {
        return delegate.getOrder();
    }

    @Override
    public String getName() {
        return "optionalMemory";
    }

    @Override
    public ChatClientResponse adviseCall(ChatClientRequest chatClientRequest, CallAdvisorChain callAdvisorChain) {
        if (hasConversation(chatClientRequest)) {
            return delegate.adviseCall(chatClientRequest, callAdvisorChain);
        }
        return callAdvisorChain.nextCall(chatClientRequest);
    }

    @Override
    public Flux<ChatClientResponse> adviseStream(ChatClientRequest chatClientRequest, StreamAdvisorChain streamAdvisorChain) {
        if (hasConversation(chatClientRequest)) {
            return delegate.adviseStream(chatClientRequest, streamAdvisorChain);
        }
        return streamAdvisorChain.nextStream(chatClientRequest);
    }
}
