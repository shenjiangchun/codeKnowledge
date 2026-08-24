package com.huawei.hisi.knowledgegraph.aggregation.stage;

import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 端到端验证 extractionChatClient 的结构化输出（.entity()，anthropic tool use 强制）。
 * 若成功，说明可以用 JSON Schema 根治领域名词提取的文本解析脆弱性。
 */
@Slf4j
@SpringBootTest
class ExtractionStructuredOutputSmokeTest {

    @Autowired
    @Qualifier("extractionChatClient")
    private ChatClient extractionChatClient;

    public record NounMapping(String className, String noun) {}
    public record NounExtraction(List<NounMapping> mappings) {}

    @Test
    void structuredOutput_viaEntity() {
        String prompt =
            "提取以下 Java 类名的业务领域名词（2-4 字中文）。\n" +
            "com.huawei.hisi.order.OrderService 的业务名词是订单；\n" +
            "com.huawei.hisi.pay.PaymentService 的业务名词是支付；\n" +
            "com.huawei.hisi.user.UserController 的业务名词是用户。";

        NounExtraction result = extractionChatClient.prompt()
                .user(prompt)
                .call()
                .entity(NounExtraction.class);

        log.info(">>> 结构化输出结果: {}", result);
        assertThat(result).isNotNull();
        assertThat(result.mappings()).isNotNull();
        assertThat(result.mappings()).hasSizeGreaterThanOrEqualTo(2);
        // 验证字段正确映射
        assertThat(result.mappings().get(0).className()).contains("OrderService");
        assertThat(result.mappings().get(0).noun()).isNotBlank();
    }
}
