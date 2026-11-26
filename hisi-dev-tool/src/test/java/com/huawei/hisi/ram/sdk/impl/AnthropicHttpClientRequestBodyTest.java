package com.huawei.hisi.ram.sdk.impl;

import com.huawei.hisi.config.ProxyConfig;
import com.huawei.hisi.ram.sdk.SendOptions;
import com.huawei.hisi.ram.sdk.ToolDefinition;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/** 请求体的关键开关：模型名用配置里的真名；单轮调用默认不思考，多轮工具调用沿用调用方的选择。 */
class AnthropicHttpClientRequestBodyTest {

    private static final List<Map<String, Object>> MESSAGES = List.of(Map.of("role", "user", "content", "hi"));
    private static final List<ToolDefinition> TOOLS = List.of(new ToolDefinition("grep", "search", "{\"type\":\"object\"}"));

    private final AnthropicHttpClient client = new AnthropicHttpClient(
            mock(ProxyConfig.class), "test-key", "http://gateway.invalid", "real-model-name", false);

    private Map<String, Object> body(SendOptions options, List<ToolDefinition> tools) {
        return client.buildRequestBody(MESSAGES, tools, options);
    }

    @Test
    void theModelNameComesFromConfigurationNotFromTheLogicalKey() {
        Map<String, Object> body = body(new SendOptions("logical-key", 4096, 0.2, "system"), List.of());

        assertEquals("real-model-name", body.get("model"), "逻辑模型名不上送，上送配置里的真实模型名");
        assertEquals(4096, body.get("max_tokens"));
        assertEquals("system", body.get("system"));
    }

    @Test
    void aSingleTurnCallDoesNotThinkByDefault() {
        // 思考 token 算在 max_tokens 里：一次性结构化调用提示词一长正文就为空（真机上每次约 95 秒后返回空）
        Map<String, Object> body = body(new SendOptions("logical-key", 4096, 0.2, "system"), List.of());

        assertEquals(Map.of("type", "disabled"), body.get("thinking"));
    }

    @Test
    void aToolCallingLoopKeepsTheCallersChoice() {
        assertFalse(body(new SendOptions("logical-key", 4096, 0.2, "system"), TOOLS).containsKey("thinking"),
                "多轮工具调用默认不动模型的思考设置");
        assertEquals(Map.of("type", "disabled"),
                body(new SendOptions("logical-key", 4096, 0.2, "system").withoutThinking(), TOOLS).get("thinking"),
                "调用方明确要求关闭时一律关闭");
    }

    @Test
    void singleTurnThinkingCanBeSwitchedBackOn() {
        AnthropicHttpClient thinking = new AnthropicHttpClient(
                mock(ProxyConfig.class), "test-key", "http://gateway.invalid", "real-model-name", true);

        assertFalse(thinking.buildRequestBody(MESSAGES, List.of(), new SendOptions("k", 4096, 0.2, null)).containsKey("thinking"));
    }
}
