package com.huawei.hisi.ram.nodes.impact;

import com.huawei.hisi.neo4j.model.IntentType;
import com.huawei.hisi.neo4j.model.SubQuery;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * GLM-4.7-Flash 实际返回过：逐行美化的 JSON、尾随解释文字、丢掉最外层大括号的 JSON 体——
 * 旧的兜底按行切分把 JSON 语法碎片（如 {@code "query": "xxx",}）当成子查询送进检索，
 * 15 个碎片 0 结果，影响面节点直接失败。这里锁死解析器对每种真实格式的行为。
 */
@DisplayName("QueryDecomposer 解析 GLM 的各种真实响应格式")
class QueryDecomposerParseTest {

    private final QueryDecomposer decomposer = new QueryDecomposer(null, null);

    @Test
    @DisplayName("逐行美化 JSON + 尾随解释文字 → 解析出真实子查询（2026-11-26 线上故障格式）")
    void prettyPrintedJsonWithTrailingProseParses() {
        String raw = """
                "queries": [
                {
                "query": "图谱构建与生成逻辑",
                "type": "GENERAL",
                "confidence": 0.9
                },
                {
                "query": "graph construction generation build",
                "type": "GENERAL",
                "confidence": 0.7
                },
                {
                "query": "图谱检索召回算法",
                "type": "GENERAL",
                "confidence": 0.9
                }
                ]
                希望这些子查询对您有帮助
                """;

        List<SubQuery> queries = decomposer.parseJsonSubQueries(raw);

        assertThat(queries).hasSize(3);
        assertThat(queries.get(0).query()).isEqualTo("图谱构建与生成逻辑");
        assertThat(queries.get(0).intentType()).isEqualTo(IntentType.GENERAL);
        assertThat(queries.get(0).confidence()).isEqualTo(0.9);
        assertThat(queries.get(1).query()).isEqualTo("graph construction generation build");
        assertThat(queries.get(2).query()).isEqualTo("图谱检索召回算法");
    }

    @Test
    @DisplayName("单行紧凑 JSON 正常解析（预期主路径）")
    void compactSingleLineJsonParses() {
        String raw = """
                {"queries": [
                  {"query": "支付回调处理方法", "type": "LISTENER", "confidence": 0.9},
                  {"query": "payment callback @EventListener", "type": "LISTENER", "confidence": 0.85}
                ]}""";

        List<SubQuery> queries = decomposer.parseJsonSubQueries(raw);

        assertThat(queries).hasSize(2);
        assertThat(queries.get(0).query()).isEqualTo("支付回调处理方法");
        assertThat(queries.get(0).intentType()).isEqualTo(IntentType.LISTENER);
    }

    @Test
    @DisplayName("裸数组（没有 queries 包装）也能解析")
    void bareArrayParses() {
        String raw = """
                好的，分解结果如下：
                [
                  {"query": "订单状态更新", "type": "SQL", "confidence": 0.85},
                  {"query": "通知发送服务", "type": "GENERAL", "confidence": 0.7}
                ]""";

        List<SubQuery> queries = decomposer.parseJsonSubQueries(raw);

        assertThat(queries).hasSize(2);
        assertThat(queries.get(0).query()).isEqualTo("订单状态更新");
        assertThat(queries.get(0).intentType()).isEqualTo(IntentType.SQL);
        assertThat(queries.get(1).query()).isEqualTo("通知发送服务");
    }

    @Test
    @DisplayName("丢掉最外层大括号的 JSON 体重新包装后解析")
    void bodyWithoutOuterBracesParses() {
        String raw = """
                根据需求，分解如下：
                "queries": [
                  {"query": "需求状态变更逻辑", "type": "GENERAL", "confidence": 0.9}
                ]""";

        List<SubQuery> queries = decomposer.parseJsonSubQueries(raw);

        assertThat(queries).hasSize(1);
        assertThat(queries.get(0).query()).isEqualTo("需求状态变更逻辑");
    }

    @Test
    @DisplayName("markdown 围栏 JSON 正常解析")
    void fencedJsonParses() {
        String raw = """
                ```json
                {"queries": [{"query": "定时刷新 cron", "type": "SCHEDULE", "confidence": 0.95}]}
                ```""";

        List<SubQuery> queries = decomposer.parseJsonSubQueries(raw);

        assertThat(queries).hasSize(1);
        assertThat(queries.get(0).query()).isEqualTo("定时刷新 cron");
        assertThat(queries.get(0).intentType()).isEqualTo(IntentType.SCHEDULE);
    }

    @Test
    @DisplayName("JSON 语法碎片永远不能成为子查询（线上故障的兜底输出）")
    void jsonSyntaxFragmentsAreNeverQueries() {
        // 2026-11-26 线上实况：旧兜底把美化的 JSON 按行切开，每行碎片都成了"子查询"。
        // 这些行拼起来不是合法 JSON，新解析器必须返回空列表，绝不能把碎片送进检索。
        String raw = """
                "queries": [
                "query": "图谱构建与生成逻辑",
                "type": "GENERAL",
                "confidence": 0.9,
                "query": "graph construction generation build",
                "type": "GENERAL",
                "confidence": 0.7,
                "query": "图谱检索召回算法",
                "type": "GENERAL",
                "confidence": 0.9
                ]""";

        List<SubQuery> queries = decomposer.parseJsonSubQueries(raw);

        assertThat(queries).as("JSON 碎片一行都不能漏进检索").isEmpty();
    }

    @Test
    @DisplayName("纯自然语言分行回复：只保留读起来像自然语言的整行")
    void proseLinesSurviveAsFallback() {
        String raw = """
                1. 图谱构建与生成逻辑
                2. 图谱检索召回算法
                3. 图谱存储索引实现""";

        List<SubQuery> queries = decomposer.parseJsonSubQueries(raw);

        assertThat(queries).hasSize(3);
        assertThat(queries.get(0).query()).isEqualTo("1. 图谱构建与生成逻辑");
        assertThat(queries.get(0).intentType()).isEqualTo(IntentType.GENERAL);
    }

    @Test
    @DisplayName("完全无法解析的内容返回空列表，交给调用方的下一级策略")
    void unparseableContentReturnsEmpty() {
        assertThat(decomposer.parseJsonSubQueries(null)).isEmpty();
        assertThat(decomposer.parseJsonSubQueries("   ")).isEmpty();
        assertThat(decomposer.parseJsonSubQueries("\"query\":")).isEmpty();
    }
}
