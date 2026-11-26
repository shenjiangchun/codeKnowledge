package com.huawei.hisi.piagent.context;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.huawei.hisi.piagent.PiAgentProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 底座的通用上下文管理——提交前预算：提示词超预算时做保形压缩，完整原文落盘；
 * 不超预算时原样放行。真机上架构分析把 41 万字符的证据包塞进首条消息，
 * pi 没有更早的轮次可压缩，只能把输出预算裁到 1 个 token，分析因此降级。
 */
@DisplayName("PromptBudget keeps every flow's prompt inside the context budget")
class PromptBudgetTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @TempDir
    Path sessionDir;

    @Test
    @DisplayName("不超预算：提示词一个字符都不改")
    void aPromptInsideTheBudgetIsUntouched() {
        PromptBudget budget = new PromptBudget(config(32000));
        String user = "{\"evidencePack\":{\"issues\":" + toJson(items(20, "问题")) + "}}";

        PromptBudget.Fitted fitted = budget.fit(sessionDir, "系统提示词", user);

        assertThat(fitted.report().compacted()).isFalse();
        assertThat(fitted.userPrompt()).isEqualTo(user);
        assertThat(fitted.systemPrompt()).isEqualTo("系统提示词");
    }

    @Test
    @DisplayName("超预算：长列表按预算截短并标注数量，结构保留，压缩后落在预算内，原文落盘")
    void anOversizedPromptIsCompactedIntoTheBudget() throws Exception {
        PromptBudget budget = new PromptBudget(config(2000));
        // 约 6 万字符：浅层的问题清单 300 条 + 深层的明细 600 条
        Map<String, Object> pack = new LinkedHashMap<>();
        pack.put("kpi", Map.of("totalMethods", 2147));
        pack.put("issues", issueList(300));
        pack.put("chapters", Map.of("method", Map.of("hotspots", items(600, "热点方法 com.example.Service.handleRequest 的圈复杂度偏高"))));
        String user = MAPPER.writeValueAsString(Map.of("evidencePack", pack, "history", List.of()));
        assertThat(budget.estimateTokens("", user)).isGreaterThan(2000);

        PromptBudget.Fitted fitted = budget.fit(sessionDir, "", user);

        assertThat(fitted.report().compacted()).isTrue();
        assertThat(fitted.report().structured()).isTrue();
        assertThat(fitted.report().truncatedLists()).isEqualTo(2);
        assertThat(budget.estimateTokens("", fitted.userPrompt()))
                .as("压缩后的输入必须落在预算内").isLessThanOrEqualTo(2000);

        JsonNode compacted = MAPPER.readTree(fitted.userPrompt());
        assertThat(compacted.path("evidencePack").path("kpi").path("totalMethods").asInt()).isEqualTo(2147);
        assertThat(compacted.path("history").isArray()).isTrue();
        JsonNode issues = compacted.path("evidencePack").path("issues");
        JsonNode hotspots = compacted.path("evidencePack").path("chapters").path("method").path("hotspots");
        assertThat(issues.size()).as("浅层的问题清单保留得更多").isGreaterThan(2);
        assertThat(hotspots.size()).isGreaterThan(0);
        assertThat(issues.get(issues.size() - 1).path("_truncated").asText()).contains("+").contains("条未展示");
        assertThat(hotspots.get(hotspots.size() - 1).path("_truncated").asText()).contains("条未展示");
        assertThat(compacted.path("_contextNotice").asText())
                .contains("超出单次输入预算").contains("完整原文已保存");

        Path saved = fitted.report().fullPromptFile();
        assertThat(saved).isNotNull().exists();
        assertThat(Files.readString(saved, StandardCharsets.UTF_8)).isEqualTo(user);
        assertThat(saved.getParent()).isEqualTo(sessionDir.resolve("context"));
    }

    @Test
    @DisplayName("浅层列表的预算比深层大：问题清单比逐条明细保留得多")
    void shallowerListsGetABiggerShare() throws Exception {
        Map<String, Object> pack = new LinkedHashMap<>();
        pack.put("issues", issueList(400));
        pack.put("chapters", Map.of("class", Map.of("metrics", Map.of("godClasses", items(400, "com.example.SomeVeryLongClassName")))));
        String compacted = new PromptBudget(config(1500)).fit(sessionDir, "", MAPPER.writeValueAsString(pack)).userPrompt();
        JsonNode root = MAPPER.readTree(compacted);

        assertThat(root.path("issues").size()).isGreaterThan(root.path("chapters").path("class").path("metrics").path("godClasses").size() * 2);
    }

    @Test
    @DisplayName("提示词里只有一段是 JSON：压缩那一段，前后的说明文字不动")
    void onlyTheEmbeddedJsonPayloadIsCompacted() {
        PromptBudget budget = new PromptBudget(config(1500));
        String user = "请分析下面这份证据，给出结论。\n\n证据：\n" + "{\"issues\":" + toJson(issueList(300)) + "}\n\n请用中文回答。";

        PromptBudget.Fitted fitted = budget.fit(sessionDir, "", user);

        assertThat(fitted.userPrompt()).startsWith("请分析下面这份证据，给出结论。\n\n证据：\n{\"issues\":[")
                .contains("_truncated").contains("请用中文回答。");
        assertThat(fitted.report().structured()).isTrue();
    }

    @Test
    @DisplayName("没有可压缩的结构：原文落盘，只保留放得下的开头，并如实说明")
    void plainTextThatCannotBeCompactedKeepsOnlyTheHead() throws Exception {
        String user = "x".repeat(9000);
        PromptBudget.Fitted fitted = new PromptBudget(config(1000)).fit(sessionDir, "", user);

        assertThat(fitted.report().compacted()).isTrue();
        assertThat(fitted.report().structured()).isFalse();
        assertThat(fitted.userPrompt()).startsWith("xxxx").contains("只保留了开头").contains("完整原文");
        assertThat(Files.readString(fitted.report().fullPromptFile())).isEqualTo(user);
    }

    @Test
    @DisplayName("落盘失败不影响流程：照样压缩，只是提示词里不给出原文路径")
    void aFailedSpillDoesNotBreakTheFlow() throws Exception {
        Path blocker = sessionDir.resolve("blocker");     // 同名位置是个普通文件：建不了 context 子目录
        Files.writeString(blocker, "占位");
        String user = "{\"issues\":" + toJson(issueList(300)) + "}";

        PromptBudget.Fitted fitted = new PromptBudget(config(800)).fit(blocker, "", user);

        assertThat(fitted.report().compacted()).isTrue();
        assertThat(fitted.report().fullPromptFile()).isNull();
        assertThat(fitted.userPrompt()).contains("_truncated");
    }

    @Test
    @DisplayName("定位载荷：整条是 JSON、内嵌一段、字符串里的括号、没有 JSON")
    void locatingThePayloadToleratesCommonShapes() {
        assertThat(JsonPayload.locate("{\"a\":1}").map(p -> p.wholeText()).orElse(false)).isTrue();
        assertThat(JsonPayload.locate("  [1,2]  ").map(p -> p.wholeText()).orElse(false)).isTrue();

        Optional<JsonPayload> embedded = JsonPayload.locate("说明 {\"not\":\"json\" 文本 {\"a\":\"}{\",\"b\":[1,2]} 结尾");
        assertThat(embedded).isPresent();
        assertThat(embedded.get().wholeText()).isFalse();
        assertThat(embedded.get().node().path("a").asText()).isEqualTo("}{");
        assertThat(JsonPayload.locate("没有任何结构化内容").map(p -> p.node())).isEmpty();
        assertThat(JsonPayload.locate("{\"broken\": ")).isEmpty();
        assertThat(JsonPayload.locate("整数 42")).isEmpty();
    }

    @Test
    @DisplayName("默认配置：pi 的会话压缩保留量对中文调大，单次输入留出运行空间")
    void defaultsLeaveRoomForTheRunItself() {
        PiAgentProperties.Context defaults = new PiAgentProperties.Context();

        assertThat(defaults.getCompactionReserveTokens()).isGreaterThan(16384);
        assertThat(defaults.getMaxInputTokens()).isLessThan(128000 - defaults.getCompactionReserveTokens());
    }

    private static PiAgentProperties.Context config(int maxInputTokens) {
        PiAgentProperties.Context context = new PiAgentProperties.Context();
        context.setMaxInputTokens(maxInputTokens);
        return context;
    }

    private static List<Map<String, Object>> issueList(int count) {
        List<Map<String, Object>> issues = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            issues.add(Map.of("source", "com.example.service.impl.OrderServiceImpl", "sourceLevel", "impl-leak",
                    "severity", "HIGH", "message", "实现类外泄：OrderServiceImpl 被 com.example.web.OrderController 直接依赖"));
        }
        return issues;
    }

    private static List<Map<String, Object>> items(int count, String text) {
        List<Map<String, Object>> items = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            items.add(Map.of("className", "com.example.Service", "methodName", "handleRequest", "detail", text + " #" + i));
        }
        return items;
    }

    private static String toJson(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
