package com.huawei.hisi.ram.nodes.impact;

import com.huawei.hisi.ram.nodes.impl.RamClaudeJsonClient;
import com.huawei.hisi.ram.sdk.SendOptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Decomposes a complex requirement intent into 3–6 focused sub-queries
 * suitable for independent semantic search calls.
 *
 * <p>Uses Claude to understand semantic boundaries; falls back to
 * punctuation-based splitting when the LLM is unavailable.</p>
 */
@Component
public class QueryDecomposer {

    private static final Logger log = LoggerFactory.getLogger(QueryDecomposer.class);

    /** Minimum useful sub-query length (characters). */
    private static final int MIN_SUBQUERY_LENGTH = 4;

    /** Maximum sub-queries to keep (prevent runaway decomposition). */
    private static final int MAX_SUBQUERIES = 6;

    private static final String SYSTEM_PROMPT = """
            你是一个代码搜索查询分解器。
            将用户的需求描述拆分为 3-5 个独立的语义搜索子查询。
            每个子查询应该聚焦一个具体的功能点或代码概念。

            返回 JSON: {"queries": ["子查询1", "子查询2", ...]}

            示例：
            输入："添加支付回调处理，需要更新订单状态并发送通知"
            输出：{"queries": ["支付回调处理方法", "订单状态更新", "通知发送服务", "支付状态变更"]}
            """;

    private final RamClaudeJsonClient claude;

    public QueryDecomposer(RamClaudeJsonClient claude) {
        this.claude = claude;
    }

    /**
     * Decompose the given intent into focused sub-queries.
     *
     * @param intent the user's requirement description
     * @return list of 1–{@value MAX_SUBQUERIES} sub-queries (never empty)
     */
    public List<String> decompose(String intent) {
        if (intent == null || intent.isBlank()) {
            return List.of();
        }

        if (!claude.isAvailable()) {
            log.info("[QueryDecomposer] Claude unavailable — using fallback split");
            return fallbackSplit(intent);
        }

        try {
            Map<String, Object> result = claude.callJson(
                    SYSTEM_PROMPT,
                    intent,
                    new SendOptions(claude.defaultModel(), 512, 0.1, SYSTEM_PROMPT));

            List<String> queries = extractQueries(result);
            if (queries.isEmpty()) {
                log.warn("[QueryDecomposer] Claude returned empty queries — falling back");
                return fallbackSplit(intent);
            }
            log.info("[QueryDecomposer] decomposed intent into {} sub-queries: {}",
                    queries.size(), queries);
            return queries;
        } catch (Exception ex) {
            log.warn("[QueryDecomposer] Claude call failed — falling back: {}", ex.getMessage());
            return fallbackSplit(intent);
        }
    }

    /**
     * Fallback: split on Chinese/English punctuation, keep fragments ≥ 4 chars,
     * and append the original intent as a catch-all query.
     */
    List<String> fallbackSplit(String intent) {
        List<String> parts = Arrays.stream(intent.split("[，,。.；;、\n]"))
                .map(String::trim)
                .filter(s -> s.length() >= MIN_SUBQUERY_LENGTH)
                .collect(Collectors.toCollection(ArrayList::new));

        if (parts.size() <= 1) {
            return List.of(intent);
        }

        // Append original intent as a catch-all semantic query
        parts.add(intent);

        return parts.size() > MAX_SUBQUERIES
                ? List.copyOf(parts.subList(0, MAX_SUBQUERIES))
                : List.copyOf(parts);
    }

    /**
     * Extract the {@code "queries"} array from Claude's JSON response.
     */
    @SuppressWarnings("unchecked")
    private List<String> extractQueries(Map<String, Object> result) {
        Object raw = result.get("queries");
        if (!(raw instanceof List<?> list)) {
            return List.of();
        }
        List<String> queries = new ArrayList<>();
        for (Object item : list) {
            if (item instanceof String s && s.length() >= MIN_SUBQUERY_LENGTH) {
                queries.add(s);
            }
        }
        return queries.size() > MAX_SUBQUERIES
                ? List.copyOf(queries.subList(0, MAX_SUBQUERIES))
                : List.copyOf(queries);
    }
}
