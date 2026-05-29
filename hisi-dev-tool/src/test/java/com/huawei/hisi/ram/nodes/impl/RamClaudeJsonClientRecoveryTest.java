package com.huawei.hisi.ram.nodes.impl;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link RamClaudeJsonClient#recoverTruncatedJson(String)}.
 */
class RamClaudeJsonClientRecoveryTest {

    @Test
    void recoversTruncatedArrayInObject() {
        // Simulates: {"queries": [{"query": "a", "type": "GENERAL", "confidence": 0.9}, {"query": "b"
        String truncated = "{\"queries\": [{\"query\": \"a\", \"type\": \"GENERAL\", \"confidence\": 0.9}, {\"query\": \"b\"";

        Map<String, Object> result = RamClaudeJsonClient.recoverTruncatedJson(truncated);

        assertThat(result).isNotNull();
        assertThat(result).containsKey("queries");
    }

    @Test
    void recoversTruncatedAtCompleteElement() {
        // Simulates: {"queries": [{"query": "a", "type": "GENERAL", "confidence": 0.9}, {"query": "b", "type":
        // Should cut at last complete element "},"
        String truncated = "{\"queries\": [{\"query\": \"a\", \"type\": \"GENERAL\", \"confidence\": 0.9}, {\"query\": \"b\", \"type\":";

        Map<String, Object> result = RamClaudeJsonClient.recoverTruncatedJson(truncated);

        assertThat(result).isNotNull();
        assertThat(result).containsKey("queries");
    }

    @Test
    void returnsNullForNonJsonObject() {
        Map<String, Object> result = RamClaudeJsonClient.recoverTruncatedJson("not json at all");
        assertThat(result).isNull();
    }

    @Test
    void returnsNullForEmptyInput() {
        assertThat(RamClaudeJsonClient.recoverTruncatedJson(null)).isNull();
        assertThat(RamClaudeJsonClient.recoverTruncatedJson("")).isNull();
        assertThat(RamClaudeJsonClient.recoverTruncatedJson("   ")).isNull();
    }

    @Test
    void handlesValidJsonPassthrough() {
        // Valid JSON should not be "recovered" but the method is only called after parse failure
        // Still, if called with valid JSON, it should return a parsed result
        String valid = "{\"key\": \"value\"}";
        Map<String, Object> result = RamClaudeJsonClient.recoverTruncatedJson(valid);
        // Depths are balanced so method returns null (nothing to recover)
        assertThat(result).isNull();
    }

    @Test
    void recoversTruncatedWithUnclosedString() {
        // String value cut off mid-quote
        String truncated = "{\"queries\": [{\"query\": \"需求状态变更逻";

        Map<String, Object> result = RamClaudeJsonClient.recoverTruncatedJson(truncated);

        assertThat(result).isNotNull();
        assertThat(result).containsKey("queries");
    }

    @Test
    void recoversRealWorldTruncation() {
        // Based on actual error: array of objects with confidence, cut mid-string
        String truncated = "{\"queries\": [\n" +
                "  {\"query\": \"HiAPM下发需求后进展情况无法编辑\", \"type\": \"GENERAL\", \"confidence\": 0.95},\n" +
                "  {\"query\": \"requirement progress edit disabled after HiAPM downlink\", \"type\": \"GENERAL\", \"confidence\": 0.8},\n" +
                "  {\"query\": \"需求状态变更逻辑有子项看子项进度卷积\", \"type\": \"GENERAL\", \"confidence\": 0.95},\n" +
                "  {\"query\": \"child requirement progress aggregate parent status change\", \"type\": \"GENERAL\", \"confidence\": 0.75},\n" +
                "  {\"query\": \"子项关联TP看EDA验证进度不关联看进展\", \"type\": \"GENERAL\", \"confidence\": 0.9},\n" +
                "  {\"query\": \"child linked TP check EDA p";

        Map<String, Object> result = RamClaudeJsonClient.recoverTruncatedJson(truncated);

        assertThat(result).isNotNull();
        assertThat(result).containsKey("queries");
    }
}
