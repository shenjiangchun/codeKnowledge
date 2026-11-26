package com.huawei.hisi.config;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 需求分析的澄清 / 实现 / 技术方案都用同一个挂了记忆 advisor 的 ChatClient 做一次性抽取，调用不带会话 ID。
 * 以前这种调用每次都抛「conversationId cannot be null」，被 catch 后回退到桩实现——线上一直是桩在跑。
 */
class OptionalMemoryAdvisorTest {

    private final ChatMemory memory = MessageWindowChatMemory.builder().maxMessages(20).build();
    private final OptionalMemoryAdvisor advisor = new OptionalMemoryAdvisor(memory);
    private final CallAdvisorChain chain = mock(CallAdvisorChain.class);
    private final ChatClientResponse response = new ChatClientResponse(new ChatResponse(List.of()), Map.of());

    private static ChatClientRequest request(String text, Map<String, Object> context) {
        return new ChatClientRequest(new Prompt(new UserMessage(text)), context);
    }

    @Test
    void aCallWithoutAConversationIdPassesThroughAndDoesNotTouchMemory() {
        ChatClientRequest request = request("抽取需求", new HashMap<>());
        when(chain.nextCall(any())).thenReturn(response);

        ChatClientResponse result = advisor.adviseCall(request, chain);

        assertSame(response, result);
        verify(chain).nextCall(request);
        assertTrue(memory.get("default").isEmpty(), "没有会话 ID 就不读写记忆");
    }

    @Test
    void aCallWithAConversationIdGoesThroughTheMemoryAdvisor() {
        Map<String, Object> context = new HashMap<>();
        context.put(ChatMemory.CONVERSATION_ID, "conv-1");
        ChatClientRequest request = request("你好", context);
        when(chain.nextCall(any())).thenReturn(response);

        advisor.adviseCall(request, chain);

        // 记忆 advisor 往请求里注入了系统提示，并在调用后把这次对话写进了会话 conv-1 的记忆
        ArgumentCaptor<ChatClientRequest> sent = ArgumentCaptor.forClass(ChatClientRequest.class);
        verify(chain).nextCall(sent.capture());
        assertTrue(sent.getValue().prompt().getInstructions()
                .stream().anyMatch(message -> message.getMessageType() == MessageType.SYSTEM));
        assertFalse(memory.get("conv-1").isEmpty());
    }
}
