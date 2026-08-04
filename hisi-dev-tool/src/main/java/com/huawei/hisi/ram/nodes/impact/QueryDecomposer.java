package com.huawei.hisi.ram.nodes.impact;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.huawei.hisi.neo4j.model.IntentType;
import com.huawei.hisi.neo4j.model.SubQuery;
import com.huawei.hisi.ram.nodes.impl.RamClaudeJsonClient;
import com.huawei.hisi.ram.sdk.SendOptions;
import com.huawei.hisi.service.UnifiedTextService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Decomposes a complex requirement intent into 3–15 focused sub-queries
 * tagged with intent types and confidence scores.
 *
 * <p>Uses Claude (via {@link RamClaudeJsonClient}) as the primary decomposer;
 * falls back to {@link UnifiedTextService} (智谱 GLM) when Claude is unavailable;
 * only uses punctuation-based splitting when <em>both</em> LLMs are unavailable.</p>
 *
 * <p>Each sub-query carries an {@link IntentType} and confidence score:
 * <ul>
 *   <li>IntentType determines RRF weight and optional post-filter enhancements</li>
 *   <li>Confidence controls dual-channel redundancy (low-confidence also runs through GENERAL)</li>
 * </ul>
 */
@Component
public class QueryDecomposer {

    private static final Logger log = LoggerFactory.getLogger(QueryDecomposer.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Minimum useful sub-query length (characters). */
    private static final int MIN_SUBQUERY_LENGTH = 4;

    /** Maximum sub-queries to keep (hard ceiling, prevent runaway decomposition). */
    private static final int MAX_SUBQUERIES = 15;

    /** Max tokens for the GLM fallback call. */
    private static final int GLM_MAX_TOKENS = 768;

    private static final String SYSTEM_PROMPT = """
            你是一个代码搜索查询分解器。将用户的需求描述拆分为独立的语义搜索子查询，建议 6 个左右，最多不超过 15 个。

            每个子查询必须标注意图类型和置信度：
            - SCHEDULE: 定时任务、周期性执行、cron、每隔N分钟、定时刷新、定时同步
            - HTTP: HTTP接口、API端点、REST请求、Controller
            - SQL: SQL操作、数据库查询、Mapper、MyBatis
            - EXCEPTION: 异常处理、错误捕获、try-catch
            - LISTENER: 事件回调、消息监听、订阅触发、MQ消费
            - CONFIG: 配置、properties、yml、Configuration、枚举值、状态可选值
            - AUTH: 登录、鉴权、权限、认证、Security
            - TRANSACTION: 事务、提交、回滚、Transactional
            - GENERAL: 通用功能点（无特殊类型）

            核心原则（必须严格遵守）：
            1. 每个子查询聚焦一个具体的功能点或代码概念
            2. 覆盖需求中所有可检索的功能点
            3. **术语桥接（最重要）**：对每个功能点，必须同时生成需求侧术语和代码侧术语变体。
               - 需求文档使用业务语言（如"回卷"、"反标"、"下发"、"卷积"、"定时刷新"）
               - 代码使用技术语言（如"syncReqStatus"、"@Scheduled"、"cron"、"aggregate"）
               - 对同一功能点，必须生成两个子查询：一个用需求侧术语，一个用代码侧术语
               - 代码侧子查询必须包含可能的代码关键词（方法名、注解名、类名片段）

            4. **意图类型强制触发规则**（出现信号时必须生成对应类型子查询，置信度 >= 0.9）：
               - SCHEDULE: "每N分钟"/"每隔"/"定时"/"周期"/"定期"/"自动刷新"/"cron"
                 → 子查询必须包含 schedule 或 cron 或 @Scheduled 关键词
               - HTTP: "接口"/"API"/"请求"/"Controller"/"端点"/"URL"/"HTTP"/"GET"/"POST"/"REST"
                 → 子查询必须包含 @RestController 或 @RequestMapping 或 Controller 关键词
               - SQL: "数据库"/"查询"/"插入"/"更新"/"删除"/"SQL"/"表"/"Mapper"/"MyBatis"/"持久化"
                 → 子查询必须包含 mapper 或 @Mapper 或 MyBatis 关键词
               - LISTENER: "回调"/"监听"/"订阅"/"MQ"/"消息"/"事件触发"/"Kafka"/"消费"
                 → 子查询必须包含 @EventListener 或 @RabbitListener 或 @KafkaListener 关键词
               - CONFIG: "配置"/"properties"/"yml"/"枚举"/"可选值"/"状态值"/"常量定义"
                 → 子查询必须包含 @Configuration 或 @Value 或 enum 关键词
               - EXCEPTION: "异常"/"错误"/"捕获"/"try-catch"/"抛出"/"校验失败"
                 → 子查询必须包含 @ExceptionHandler 或 try-catch 关键词
               - AUTH: "权限"/"登录"/"鉴权"/"认证"/"角色"/"Security"/"不可编辑"
                 → 子查询必须包含 @PreAuthorize 或 Security 或 authentication 关键词

            5. 置信度范围 0.0-1.0：明确识别到模式（如"每10分钟"→SCHEDULE），置信度 0.9+；推测性 0.5-0.7

            返回 JSON: {"queries": [{"query": "子查询文本", "type": "SCHEDULE", "confidence": 0.9}, ...]}

            示例：
            输入："添加支付回调处理，需要更新订单状态并发送通知"
            输出：{"queries": [
              {"query": "支付回调处理方法", "type": "LISTENER", "confidence": 0.9},
              {"query": "payment callback @EventListener", "type": "LISTENER", "confidence": 0.85},
              {"query": "订单状态更新", "type": "GENERAL", "confidence": 0.9},
              {"query": "order status update @Mapper", "type": "SQL", "confidence": 0.85},
              {"query": "通知发送服务", "type": "GENERAL", "confidence": 0.9},
              {"query": "notification send @Service", "type": "GENERAL", "confidence": 0.7}
            ]}

            输入："上游需求下发后进展情况无法编辑，当前需求状态的变更逻辑，总体原则有子项看子项的进度卷积，每10分钟刷新一次，下游状态回卷到上游"
            输出：{"queries": [
              {"query": "需求下发后进展情况编辑", "type": "GENERAL", "confidence": 0.9},
              {"query": "需求状态变更逻辑", "type": "GENERAL", "confidence": 0.9},
              {"query": "子项进度卷积规则", "type": "GENERAL", "confidence": 0.8},
              {"query": "child progress aggregate", "type": "GENERAL", "confidence": 0.7},
              {"query": "下游状态回卷机制", "type": "GENERAL", "confidence": 0.85},
              {"query": "需求反标 syncReqStatus", "type": "GENERAL", "confidence": 0.85},
              {"query": "需求状态定时同步 @Scheduled cron", "type": "SCHEDULE", "confidence": 0.95},
              {"query": "需求状态定时刷新 schedule syncReqStatus", "type": "SCHEDULE", "confidence": 0.9},
              {"query": "需求基线与状态流转", "type": "GENERAL", "confidence": 0.8},
              {"query": "关联TP的EDA验证进度", "type": "GENERAL", "confidence": 0.7}
            ]}
            """;

    private final RamClaudeJsonClient claude;
    private final UnifiedTextService textService;

    public QueryDecomposer(RamClaudeJsonClient claude,
                           @Autowired(required = false) UnifiedTextService textService) {
        this.claude = claude;
        this.textService = textService;
    }

    /**
     * Decompose the given intent into focused, intent-typed sub-queries.
     *
     * <p>Strategy priority: GLM (primary — more precise for keyword extraction) →
     * Claude (supplementary — deeper reasoning) → punctuation split.</p>
     *
     * @param intent the user's requirement description
     * @return list of 1–{@value MAX_SUBQUERIES} sub-queries (never empty)
     */
    public List<SubQuery> decompose(String intent) {
        if (intent == null || intent.isBlank()) {
            return List.of();
        }

        // Strategy 1: GLM (primary — produces more focused, keyword-like sub-queries)
        if (textService != null && textService.isAvailable()) {
            try {
                String raw = textService.chat(SYSTEM_PROMPT, intent, GLM_MAX_TOKENS);
                List<SubQuery> queries = parseJsonSubQueries(raw);
                if (!queries.isEmpty()) {
                    log.info("[QueryDecomposer] GLM decomposed intent into {} sub-queries: {}",
                            queries.size(), queries);
                    return queries;
                }
                log.warn("[QueryDecomposer] GLM returned empty/unparseable queries — trying Claude fallback");
            } catch (Exception ex) {
                log.warn("[QueryDecomposer] GLM call failed — trying Claude fallback: {}", ex.getMessage());
            }
        }

        // Strategy 2: Claude (supplementary — when GLM unavailable)
        if (claude.isAvailable()) {
            try {
                Map<String, Object> result = claude.callJson(
                        SYSTEM_PROMPT,
                        intent,
                        new SendOptions(claude.defaultModel(), 1024, 0.1, SYSTEM_PROMPT));

                List<SubQuery> queries = extractSubQueries(result);
                if (!queries.isEmpty()) {
                    log.info("[QueryDecomposer] Claude decomposed intent into {} sub-queries: {}",
                            queries.size(), queries);
                    return queries;
                }
                log.warn("[QueryDecomposer] Claude returned empty queries — trying fallback split");
            } catch (Exception ex) {
                log.warn("[QueryDecomposer] Claude call failed — trying fallback split: {}", ex.getMessage());
            }
        } else {
            log.info("[QueryDecomposer] GLM unavailable, Claude also unavailable — using fallback split");
        }

        // Strategy 3: punctuation-based split (last resort)
        return fallbackSplit(intent);
    }

    /**
     * Backward-compatible overload: returns query strings only (loses intent type info).
     * Prefer {@link #decompose(String)} for full intent-aware results.
     */
    public List<String> decomposeToStrings(String intent) {
        return decompose(intent).stream()
                .map(SubQuery::query)
                .collect(Collectors.toList());
    }

    /**
     * Fallback: split on Chinese/English punctuation, keep fragments ≥ 4 chars,
     * and append the original intent as a catch-all query.
     * All fragments get GENERAL type with full confidence.
     */
    List<SubQuery> fallbackSplit(String intent) {
        List<SubQuery> parts = Arrays.stream(intent.split("[，,。.；;、\n]"))
                .map(String::trim)
                .filter(s -> s.length() >= MIN_SUBQUERY_LENGTH)
                .map(s -> SubQuery.general(s))
                .collect(Collectors.toCollection(ArrayList::new));

        if (parts.size() <= 1) {
            return List.of(SubQuery.general(intent));
        }

        // Append original intent as a catch-all semantic query
        parts.add(SubQuery.general(intent));

        return parts.size() > MAX_SUBQUERIES
                ? List.copyOf(parts.subList(0, MAX_SUBQUERIES))
                : List.copyOf(parts);
    }

    /**
     * Extract the {@code "queries"} array from Claude's JSON response,
     * parsing each item as a SubQuery with type and confidence.
     */
    @SuppressWarnings("unchecked")
    private List<SubQuery> extractSubQueries(Map<String, Object> result) {
        Object raw = result.get("queries");
        if (!(raw instanceof List<?> list)) {
            // Legacy format: queries is a List<String> — convert to GENERAL SubQueries
            return extractStringQueries(result);
        }

        List<SubQuery> queries = new ArrayList<>();
        for (Object item : list) {
            if (item instanceof String s && s.length() >= MIN_SUBQUERY_LENGTH) {
                // Legacy string item — treat as GENERAL
                queries.add(SubQuery.general(s));
            } else if (item instanceof Map<?, ?> map) {
                String query = (String) map.get("query");
                if (query == null || query.length() < MIN_SUBQUERY_LENGTH) continue;

                IntentType type = parseIntentType((String) map.get("type"));
                double confidence = parseConfidence(map.get("confidence"));
                queries.add(new SubQuery(query, type, confidence));
            }
        }
        return queries.size() > MAX_SUBQUERIES
                ? List.copyOf(queries.subList(0, MAX_SUBQUERIES))
                : List.copyOf(queries);
    }

    /**
     * Legacy format extraction: queries is a plain List<String>.
     */
    @SuppressWarnings("unchecked")
    private List<SubQuery> extractStringQueries(Map<String, Object> result) {
        Object raw = result.get("queries");
        if (!(raw instanceof List<?> list)) {
            return List.of();
        }
        List<SubQuery> queries = new ArrayList<>();
        for (Object item : list) {
            if (item instanceof String s && s.length() >= MIN_SUBQUERY_LENGTH) {
                queries.add(SubQuery.general(s));
            }
        }
        return queries.size() > MAX_SUBQUERIES
                ? List.copyOf(queries.subList(0, MAX_SUBQUERIES))
                : List.copyOf(queries);
    }

    /**
     * Parse the raw text response from GLM into a list of SubQueries.
     * Handles: pure JSON, markdown-fenced JSON, and JSON embedded in prose.
     */
    private List<SubQuery> parseJsonSubQueries(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }

        String json = raw.trim();

        // Strip markdown fences
        if (json.startsWith("```")) {
            int start = json.indexOf('\n');
            int end = json.lastIndexOf("```");
            if (start > 0 && end > start) {
                json = json.substring(start + 1, end).trim();
            }
        }

        // Find JSON object if embedded in prose
        if (!json.startsWith("{")) {
            int brace = json.indexOf('{');
            if (brace >= 0) {
                json = json.substring(brace);
            }
        }

        try {
            Map<String, Object> map = MAPPER.readValue(json, new TypeReference<>() {});
            return extractSubQueries(map);
        } catch (Exception ex) {
            log.debug("[QueryDecomposer] Failed to parse GLM JSON response: {}", ex.getMessage());
            // Last resort: split by newlines and treat each non-empty line as a GENERAL query
            return Arrays.stream(json.split("[\n]"))
                    .map(String::trim)
                    .filter(s -> s.length() >= MIN_SUBQUERY_LENGTH && !s.startsWith("{") && !s.startsWith("```"))
                    .limit(MAX_SUBQUERIES)
                    .map(SubQuery::general)
                    .collect(Collectors.toList());
        }
    }

    /**
     * Parse intent type string from LLM response, defaulting to GENERAL.
     */
    private IntentType parseIntentType(String typeStr) {
        if (typeStr == null || typeStr.isBlank()) {
            return IntentType.GENERAL;
        }
        try {
            return IntentType.valueOf(typeStr.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            log.debug("[QueryDecomposer] Unknown intent type '{}', defaulting to GENERAL", typeStr);
            return IntentType.GENERAL;
        }
    }

    /**
     * Parse confidence value from LLM response, defaulting to 0.8.
     */
    private double parseConfidence(Object confidenceObj) {
        if (confidenceObj == null) {
            return 0.8; // default confidence when not specified
        }
        if (confidenceObj instanceof Number n) {
            double val = n.doubleValue();
            return Math.max(0.0, Math.min(1.0, val));
        }
        try {
            double val = Double.parseDouble(confidenceObj.toString());
            return Math.max(0.0, Math.min(1.0, val));
        } catch (NumberFormatException e) {
            return 0.8;
        }
    }
}
