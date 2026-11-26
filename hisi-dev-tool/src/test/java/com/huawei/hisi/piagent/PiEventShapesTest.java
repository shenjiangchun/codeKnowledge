package com.huawei.hisi.piagent;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * pi 1.6 RPC 事件形状（取自包内 docs/json.md 的样例）：最终正文来自 {@code message_end.message}，
 * 流式增量来自 {@code message_update.assistantMessageEvent.delta}。按错形状取值会让每次会话都「没有产出」。
 */
class PiEventShapesTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static Map<String, Object> event(String json) throws Exception {
        return MAPPER.readValue(json, new TypeReference<Map<String, Object>>() {});
    }

    @Test
    void finalTextIsRebuiltFromDeltasThenReplacedByTheAuthoritativeMessage() throws Exception {
        StringBuilder text = new StringBuilder();
        Map<String, Object> delta = event(
                "{\"type\":\"message_update\",\"usage\":{\"input\":100},\"assistantMessageEvent\":{\"type\":\"text_delta\",\"contentIndex\":0,\"delta\":\"Hello \"}}");
        Map<String, Object> thinking = event(
                "{\"type\":\"message_update\",\"assistantMessageEvent\":{\"type\":\"thinking_delta\",\"contentIndex\":0,\"delta\":\"不该进正文\"}}");
        Map<String, Object> ended = event(
                "{\"type\":\"message_end\",\"message\":{\"role\":\"assistant\",\"content\":[{\"type\":\"thinking\",\"thinking\":\"…\"},{\"type\":\"text\",\"text\":\"Hello world\"}]}}");
        Map<String, Object> userMessage = event(
                "{\"type\":\"message_end\",\"message\":{\"role\":\"user\",\"content\":\"Review this repository\"}}");

        PiFlowClient.appendDelta(delta, text);
        PiFlowClient.appendDelta(thinking, text);
        assertEquals("Hello ", text.toString(), "只有文本增量进正文");

        PiFlowClient.replaceFinalText(userMessage, text);
        assertEquals("Hello ", text.toString(), "用户消息不覆盖助手正文");
        PiFlowClient.replaceFinalText(ended, text);
        assertEquals("Hello world", text.toString(), "message_end 的完整消息覆盖增量拼出来的文本");
    }

    @Test
    void stringContentAndOddShapesAreTolerated() throws Exception {
        StringBuilder text = new StringBuilder("旧内容");
        PiFlowClient.replaceFinalText(event("{\"type\":\"message_end\",\"message\":{\"role\":\"assistant\",\"content\":\"纯字符串\"}}"), text);
        assertEquals("纯字符串", text.toString());

        PiFlowClient.appendDelta(Map.of("type", "message_update"), text);
        PiFlowClient.replaceFinalText(Map.of("type", "message_end"), text);
        assertEquals("纯字符串", text.toString(), "缺字段的事件不改写已有正文");
        assertFalse(PiFlowClient.isAssistant(Map.of("type", "message_end")));
    }

    @Test
    void everyFlowIgnoresProjectLocalFilesAndReadOnlyFlowsSkipContextFiles() {
        List<String> dev = FlowToolManifest.forFlow("workbench-dev").cliArgs();
        assertTrue(dev.contains("--no-approve"), "不信任工作目录里的项目级扩展与 skill");
        assertFalse(dev.contains("--no-context-files"), "写流保留项目自己的 AGENTS.md / CLAUDE.md");

        for (String flow : List.of("arch-react", "log-analysis", "ram", "workbench-read", "unknown")) {
            List<String> args = FlowToolManifest.forFlow(flow).cliArgs();
            assertTrue(args.contains("--no-approve"), flow);
            assertTrue(args.contains("--no-context-files"), flow + "：被分析项目的说明文件不进系统上下文");
        }
    }
}
