package com.huawei.hisi.knowledgegraph.aggregation.llm;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;

/**
 * 从 LLM 响应中鲁棒提取结构化 JSON。
 *
 * <p>Spring AI 1.1.8 的 {@code AnthropicChatModel} 把中转返回的 thinking 块和 text 块各建一个
 * {@link Generation}，thinking 块排在 {@code generations[0]}，而 {@code ChatClient.content()} /
 * {@code .entity()} 只取 {@code getResult()}（= generations[0]），因此拿到的是思考散文而非 JSON。
 *
 * <p>本工具遍历所有 Generation，对每个文本块做「fence 剥离 + 平衡括号候选提取 + 严格/宽松两级解析」，
 * 跳过思考散文块，从 text 块中抠出目标 JSON 并反序列化为目标类型。
 */
public final class RobustJsonExtractor {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    /** 宽松 mapper：容忍尾随逗号、单引号、注释、无引号字段名。 */
    private static final ObjectMapper LENIENT_MAPPER = new ObjectMapper()
            .enable(JsonParser.Feature.ALLOW_TRAILING_COMMA)
            .enable(JsonParser.Feature.ALLOW_SINGLE_QUOTES)
            .enable(JsonParser.Feature.ALLOW_COMMENTS)
            .enable(JsonParser.Feature.ALLOW_UNQUOTED_FIELD_NAMES);

    private RobustJsonExtractor() {
    }

    /**
     * 遍历响应中的所有 Generation，跳过思考散文块，返回第一个能反序列化为 {@code type} 的结果。
     *
     * @return 目标对象，或 {@code null} 若所有块均无法提取
     */
    public static <T> T extract(ChatResponse response, Class<T> type) {
        if (response == null || response.getResults() == null) {
            return null;
        }
        for (Generation generation : response.getResults()) {
            if (generation == null || generation.getOutput() == null) {
                continue;
            }
            String text = generation.getOutput().getText();
            if (text == null || text.isBlank()) {
                continue;
            }
            T result = extractFromText(text, type);
            if (result != null) {
                return result;
            }
        }
        return null;
    }

    /**
     * 从单个文本块中鲁棒提取 JSON：容忍前导/尾部散文、markdown fence、截断、宽松语法。
     *
     * @return 目标对象，或 {@code null}
     */
    public static <T> T extractFromText(String raw, Class<T> type) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String text = raw.trim();

        // 策略 1：直接解析（纯 JSON）
        if (text.startsWith("{") || text.startsWith("[")) {
            T result = tryParse(text, type);
            if (result != null) {
                return result;
            }
        }

        // 策略 2：剥离 markdown fence 后解析
        String fenced = stripFence(text);
        if (!fenced.equals(text)) {
            T result = tryParse(fenced, type);
            if (result != null) {
                return result;
            }
        }

        // 策略 3：扫描每个 { 做平衡括号提取（容忍前导散文 / 多个候选）
        int brace = text.indexOf('{');
        while (brace >= 0) {
            String candidate = extractBalanced(text, brace);
            if (candidate != null) {
                T result = tryParse(candidate, type);
                if (result != null) {
                    return result;
                }
            }
            brace = text.indexOf('{', brace + 1);
        }

        return null;
    }

    /** 剥离 markdown 代码块 fence，返回剥离后的文本；无 fence 则原样返回。 */
    private static String stripFence(String text) {
        String t = text.trim();
        if (t.startsWith("```")) {
            int firstNewline = t.indexOf('\n');
            if (firstNewline < 0) {
                return t;
            }
            String afterOpen = t.substring(firstNewline + 1);
            int closeFence = afterOpen.lastIndexOf("```");
            if (closeFence >= 0) {
                return afterOpen.substring(0, closeFence).trim();
            }
            return afterOpen.trim();
        }
        return text;
    }

    /** 从 start 位置（必须是 '{'）提取平衡的 JSON 对象，跳过字符串内的花括号。 */
    private static String extractBalanced(String text, int start) {
        if (start < 0 || start >= text.length() || text.charAt(start) != '{') {
            return null;
        }
        int depth = 0;
        boolean inString = false;
        boolean escaped = false;
        for (int i = start; i < text.length(); i++) {
            char c = text.charAt(i);
            if (escaped) {
                escaped = false;
                continue;
            }
            if (c == '\\' && inString) {
                escaped = true;
                continue;
            }
            if (c == '"') {
                inString = !inString;
                continue;
            }
            if (inString) {
                continue;
            }
            if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return text.substring(start, i + 1);
                }
            }
        }
        return null; // 未闭合（截断）
    }

    /** 严格 → 宽松两级反序列化。 */
    private static <T> T tryParse(String json, Class<T> type) {
        try {
            return MAPPER.readValue(json, type);
        } catch (Exception strictEx) {
            try {
                return LENIENT_MAPPER.readValue(json, type);
            } catch (Exception lenientEx) {
                return null;
            }
        }
    }
}
