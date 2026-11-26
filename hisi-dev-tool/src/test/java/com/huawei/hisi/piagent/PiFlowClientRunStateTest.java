package com.huawei.hisi.piagent;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 底座上下文管理的第二层——pi 自带的会话压缩：底座把它的触发阈值显式配置好，
 * 运行中把压缩事件透给监听器，没拿到完整结论时把原因说清楚，而不是只留一句「未产出结果」。
 */
@DisplayName("pi agent base: session compaction wiring")
class PiFlowClientRunStateTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @TempDir
    Path agentDir;

    @Test
    @DisplayName("压缩事件原样透给监听器；压缩失败的原因记在结果里")
    void compactionEventsReachTheListenerAndFailuresAreReported() {
        List<String> seen = new ArrayList<>();
        PiFlowClient.RunState state = new PiFlowClient.RunState(event -> seen.add(String.valueOf(event.get("type"))));

        state.accept(event("{\"type\":\"auto_compaction_start\",\"tokensBefore\":118000}"));
        state.accept(event("{\"type\":\"auto_compaction_end\",\"tokensBefore\":118000,\"tokensAfter\":42000}"));
        assertThat(seen).containsExactly("auto_compaction_start", "auto_compaction_end");
        assertThat(state.note()).isNull();

        state.accept(event("{\"type\":\"auto_compaction_end\",\"errorMessage\":\"summary truncated\",\"willRetry\":false}"));
        assertThat(state.note()).isEqualTo("pi 会话压缩失败：summary truncated");
    }

    @Test
    @DisplayName("最后一条回复被截断且没有正文：说明原因，并用之前的正文而不是空串")
    void aTruncatedFinalMessageIsExplained() {
        PiFlowClient.RunState state = new PiFlowClient.RunState(null);
        state.accept(event("{\"type\":\"message_end\",\"message\":{\"role\":\"assistant\",\"content\":[{\"type\":\"text\",\"text\":\"{\\\"final_answer\\\":{}}\"}],\"stopReason\":\"toolUse\"}}"));
        state.accept(event("{\"type\":\"message_end\",\"message\":{\"role\":\"assistant\",\"content\":[{\"type\":\"thinking\",\"thinking\":\"…\"}],\"stopReason\":\"length\"}}"));

        PiFlowClient.PiResult result = state.result(PiFlowClient.PiResult.SETTLED, false);
        assertThat(result.finalText()).isEqualTo("{\"final_answer\":{}}");
        assertThat(result.note()).contains("stop=length");
    }

    @Test
    @DisplayName("正常结束：结果里没有异常说明")
    void aCleanRunHasNoNote() {
        PiFlowClient.RunState state = new PiFlowClient.RunState(null);
        state.accept(event("{\"type\":\"message_end\",\"message\":{\"role\":\"assistant\",\"content\":\"结论\",\"stopReason\":\"stop\"}}"));

        assertThat(state.note()).isNull();
        assertThat(state.result(PiFlowClient.PiResult.SETTLED, false).finalText()).isEqualTo("结论");
    }

    @Test
    @DisplayName("pi 的 settings.json：写入压缩阈值，文件里其它配置保留")
    void settingsJsonCarriesTheCompactionThresholdAndKeepsOtherSettings() throws Exception {
        Files.writeString(agentDir.resolve("settings.json"), "{\"quietStartup\":true,\"compaction\":{\"enabled\":false}}", StandardCharsets.UTF_8);
        PiAgentProperties.Context context = new PiAgentProperties.Context();
        context.setCompactionReserveTokens(32000);
        context.setCompactionKeepRecentTokens(20000);

        PiAgentInstaller.writeSettingsJson(agentDir, context, MAPPER);

        String written = Files.readString(agentDir.resolve("settings.json"));
        assertThat(written).contains("\"quietStartup\" : true").contains("\"reserveTokens\" : 32000")
                .contains("\"keepRecentTokens\" : 20000").contains("\"enabled\" : true");
    }

    private static Map<String, Object> event(String json) {
        try {
            return MAPPER.readValue(json, Map.class);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
