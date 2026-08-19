package com.huawei.hisi.knowledgegraph.controller;

import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.ParameterizedTypeReference;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 端到端验证：GenerationController 两个真实业务场景的结构化输出（.entity 强制 tool use）。
 *
 * 目的：验证 enum 字段 + @JsonClassDescription 的 JSON Schema 约束能正确生成并反序列化。
 * 场景 1（test-suggestions）：输出 List<TestSuggestion>（type/priority 为 enum）
 * 场景 2（refactor-suggestions）：输出 List<RefactorSuggestion>（priority 为 enum）
 */
@Slf4j
@SpringBootTest
class GenerationStructuredOutputSmokeTest {

    @Autowired
    @Qualifier("extractionChatClient")
    private ChatClient extractionChatClient;

    @Test
    void testSuggestions_structuredOutput() {
        String prompt =
            "你是一个架构分析专家。基于以下方法信息和爆炸半径数据，生成 3 条测试建议。\n" +
            "每条建议包含：scenario（场景描述一句话）、type（测试类型 UNIT/INTEGRATION/EXCEPTION/BOUNDARY）、priority（HIGH/MEDIUM/LOW）。\n\n" +
            "方法: com.huawei.hisi.order.OrderService.placeOrder(OrderRequest)\n" +
            "描述: 处理订单下单逻辑，校验库存后创建订单并调用支付\n" +
            "文件: src/main/java/com/huawei/hisi/order/OrderService.java\n" +
            "复杂度: 18\n" +
            "爆炸半径摘要: 下游 12 个方法，上游 3 个 API 入口";

        List<GenerationController.TestSuggestion> result = extractionChatClient.prompt()
                .user(prompt)
                .call()
                .entity(new ParameterizedTypeReference<List<GenerationController.TestSuggestion>>() {});

        log.info(">>> test-suggestions 结构化输出: {}", result);
        assertThat(result).isNotNull();
        assertThat(result).hasSizeGreaterThanOrEqualTo(2);
        // 验证 enum 字段反序列化成功（schema enum 约束生效）
        for (GenerationController.TestSuggestion s : result) {
            assertThat(s.scenario()).isNotBlank();
            assertThat(s.type()).isIn(GenerationController.TestType.values());
            assertThat(s.priority()).isIn(GenerationController.Priority.values());
        }
    }

    @Test
    void refactorSuggestions_structuredOutput() {
        String prompt =
            "你是一个架构重构专家。基于模块 \"knowledgegraph\" 及其 DSM 矩阵和热点数据，生成 3 条重构建议。\n" +
            "每条建议包含：issue（问题描述一句话）、direction（重构方向，如:拆分类/提取接口/解耦依赖/消除循环依赖）、impact（影响范围估计）、priority（HIGH/MEDIUM/LOW）。\n\n" +
            "DSM: knowledgegraph 模块有 171 个类，被 5 个模块依赖\n" +
            "Hotspots: KnowledgeGraphBuilder.java 风险分 0.57，复杂度 46";

        List<GenerationController.RefactorSuggestion> result = extractionChatClient.prompt()
                .user(prompt)
                .call()
                .entity(new ParameterizedTypeReference<List<GenerationController.RefactorSuggestion>>() {});

        log.info(">>> refactor-suggestions 结构化输出: {}", result);
        assertThat(result).isNotNull();
        assertThat(result).hasSizeGreaterThanOrEqualTo(2);
        for (GenerationController.RefactorSuggestion r : result) {
            assertThat(r.issue()).isNotBlank();
            assertThat(r.direction()).isNotBlank();
            assertThat(r.priority()).isIn(GenerationController.Priority.values());
        }
    }
}
