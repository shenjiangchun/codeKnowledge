package com.huawei.hisi.piagent.context;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Optional;

/**
 * 提示词里的结构化载荷：整条提示词就是 JSON，或者其中最大的一段 JSON。
 * 记下它在原文中的起止位置，压缩后原样换回去，前后的说明文字不动。
 */
record JsonPayload(String original, int start, int end, JsonNode node) {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    /** 找内嵌 JSON 时最多尝试多少个起始括号：正常情况第一个就能解析成功。 */
    private static final int MAX_ATTEMPTS = 200;

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

    boolean wholeText() {
        return original.substring(0, start).isBlank() && original.substring(end).isBlank();
    }

    /** 把压缩后的载荷换回原文。 */
    String replace(JsonNode compacted) {
        return original.substring(0, start) + toJson(compacted) + original.substring(end);
    }

    private static int indexOfOpening(String text, int from) {
        int brace = text.indexOf('{', from);
        int bracket = text.indexOf('[', from);
        if (brace < 0) return bracket;
        if (bracket < 0) return brace;
        return Math.min(brace, bracket);
    }

    /** 与 {@code start} 处的括号配对的右括号位置，跳过字符串里的括号；配不上返回 -1。 */
    static int matchingClose(String text, int start) {
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
