package com.huawei.hisi.knowledgegraph.aggregation.stage;

import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 端到端验证 extractionChatClient（anthropic 中转 deepseek）能否真正调通。
 * 依赖 application-local.yml 的 spring.ai.anthropic.* 配置。
 */
@Slf4j
@SpringBootTest
class ExtractionChatClientSmokeTest {

    @Autowired
    @Qualifier("extractionChatClient")
    private ChatClient extractionChatClient;

    @Test
    void extractionChatClient_canReachRelayAndExtractNouns() {
        String prompt =
            "为以下 Java 类名提取业务领域名词（2-4 字中文）。\n" +
            "每个类一行，格式：类名=业务名词。不要解释，只返回映射行，类名必须原样保留完整包名。\n\n" +
            "类列表：\ncom.huawei.hisi.agent.DiagnosticAgent\ncom.huawei.hisi.order.OrderService\ncom.huawei.hisi.pay.PaymentService";

        String content = extractionChatClient.prompt().user(prompt).call().content();

        log.info(">>> extractionChatClient 返回: {}", content);
        assertThat(content).isNotNull();
        assertThat(content).isNotBlank();
        // 应该包含真正的映射行（完整类名=xxx）
        assertThat(content).contains("com.huawei.hisi.order.OrderService");
    }
}
