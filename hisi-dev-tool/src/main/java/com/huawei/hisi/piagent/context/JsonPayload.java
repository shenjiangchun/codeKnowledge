package com.huawei.hisi.piagent.context;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 提示词里的结构化载荷：整条提示词就是 JSON，或者其中最大的一段 JSON。
 * 记下它在原文中的起止位置，压缩后原样换回去，前后的说明文字不动。
 *
 * <p>同时提供从一段模型输出里取 JSON 对象的通用办法（{@link #objectCandidates}），供需要结构化结果的调用方复用。
 */
public final class JsonPayload {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    /** 找内嵌 JSON 时最多尝试多少个起始括号：正常情况第一个就能解析成功。 */
    private static final int MAX_ATTEMPTS = 200;

    private final String original;
    private final int start;
    private final int end;
    private final JsonNode node;

    private JsonPayload(String original, int start, int end, JsonNode node) {
        this.original = original;
        this.start = start;
        this.end = end;
        this.node = node;
    }

    static Optional<JsonPayload> locate(String text) {
        if (text == null || text.isBlank()) {
            return Optional.empty();
        }
        JsonNode whole = parse(text.trim());
        if (isContainer(whole)) {
            return Optional.of(new JsonPayload(text, 0, text.length(), whole));
        }
        for (int attempt = 0, from = 0; attempt < MAX_ATTEMPTS; attempt++) {
            int start = indexOfOpening(text, from);
            if (start < 0) {
                return Optional.empty();
            }
            int end = matchingClose(text, start);
            // 配不上右括号（比如这段文字本身带了一个孤立的 {）：从下一个括号再找
            if (end >= 0) {
                JsonNode parsed = parse(text.substring(start, end + 1));
                if (isContainer(parsed)) {
                    return Optional.of(new JsonPayload(text, start, end + 1, parsed));
                }
            }
            from = start + 1;
        }
        return Optional.empty();
    }

    private static boolean isContainer(JsonNode node) {
        return node != null && (node.isObject() || node.isArray());
    }

    /** 载荷解析后的树。 */
    JsonNode node() {
        return node;
    }

    boolean wholeText() {
        return original.substring(0, start).isBlank() && original.substring(end).isBlank();
    }

    /** 把压缩后的载荷换回原文。 */
    String replace(JsonNode compacted) {
        return original.substring(0, start) + toJson(compacted) + original.substring(end);
    }

    /**
     * 正文里每个「配对完整的 JSON 对象」片段，从最外层起逐个给出。
     * 不能用「第一个 { 到最后一个 }」：模型会在结论后面拖上工具调用的标记文本，那样整段都解析不了。
     */
    public static List<String> objectCandidates(String text) {
        List<String> candidates = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return candidates;
        }
        String trimmed = text.trim();
        for (int start = trimmed.indexOf('{'); start >= 0 && candidates.size() < MAX_ATTEMPTS;
             start = trimmed.indexOf('{', start + 1)) {
            int end = matchingClose(trimmed, start);
            if (end > start) {
                candidates.add(trimmed.substring(start, end + 1));
            }
        }
        return candidates;
    }

    /** 把文本解析成 JSON 对象；不是合法的 JSON 对象时返回 null。 */
    public static JsonNode parseObject(String text) {
        JsonNode node = parse(text);
        return node != null && node.isObject() ? node : null;
    }

    private static int indexOfOpening(String text, int from) {
        int brace = text.indexOf('{', from);
        int bracket = text.indexOf('[', from);
        if (brace < 0) return bracket;
        if (bracket < 0) return brace;
        return Math.min(brace, bracket);
    }

    /** 与 {@code start} 处的括号配对的右括号位置，跳过字符串里的括号；配不上返回 -1。 */
    public static int matchingClose(String text, int start) {
        StringBuilder stack = new StringBuilder();
        boolean inString = false;
        boolean escaped = false;
        for (int i = start; i < text.length(); i++) {
            char c = text.charAt(i);
            if (inString) {
                if (escaped) {
                    escaped = false;
                } else if (c == '\\') {
                    escaped = true;
                } else if (c == '"') {
                    inString = false;
                }
                continue;
            }
            if (c == '"') {
                inString = true;
            } else if (c == '{' || c == '[') {
                stack.append(c);
            } else if (c == '}' || c == ']') {
                char expected = c == '}' ? '{' : '[';
                if (stack.length() == 0 || stack.charAt(stack.length() - 1) != expected) {
                    return -1;
                }
                stack.deleteCharAt(stack.length() - 1);
                if (stack.length() == 0) {
                    return i;
                }
            }
        }
        return -1;
    }

    private static JsonNode parse(String candidate) {
        if (candidate == null) {
            return null;
        }
        try {
            return MAPPER.readTree(candidate);
        } catch (Exception e) {
            return null;
        }
    }

    static String toJson(JsonNode node) {
        try {
            return MAPPER.writeValueAsString(node);
        } catch (Exception e) {
            throw new IllegalStateException("压缩后的载荷无法序列化", e);
        }
    }
}
