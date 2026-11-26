package com.huawei.hisi.ram.nodes.impl;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;

import java.util.Map;
import java.util.Optional;

/**
 * 把 Spring AI {@link ChatClient} 的一次调用结果取成 JSON 对象。
 *
 * <p>带工具的多轮调用里，模型查完代码常用一段大白话收尾（「I have enough context now. …」），
 * 直接按 JSON 解析会抛异常，调用方只能回退到桩实现——需求分析的澄清 / 实现 / 技术方案以前就是这样一路走桩的。
 * 这里分三步：原文能解析就用；JSON 夹在文字里就截出来；整段都不是 JSON 就做一次「按 schema 转成 JSON」的格式化调用。
 */
public final class LlmJsonAnswer {

    private static final Logger log = LoggerFactory.getLogger(LlmJsonAnswer.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String FORMAT_SYSTEM = """
            你负责把一段分析结论整理成 JSON。只输出一个 JSON 对象：不要解释、不要代码围栏、不要重复结论里的过程记录。
            结论里没有依据的字段填空值（空数组、空字符串、false），不要编造。
            """;

    private LlmJsonAnswer() {
    }

    /**
     * @param call         已经发出的调用（可带工具）
     * @param formatter    不带记忆、不思考的 ChatClient，用来把散文转成 JSON；为 null 时不做转换
     * @param schemaPrompt 这次调用的系统提示词（含输出 schema），格式化时作为字段说明
     * @throws IllegalStateException 回答不是 JSON、且（做了或没做）格式化之后仍不是
     */
    public static Map<String, Object> fromCall(ChatClient.CallResponseSpec call, ChatClient formatter, String schemaPrompt) {
        String content = call.content();
        Optional<Map<String, Object>> parsed = extractObject(content);
        if (parsed.isPresent()) {
            return parsed.get();
        }
        if (formatter == null) {
            throw notJson(content, false);
        }
        log.warn("[RAM][LlmJsonAnswer] 回答不是 JSON（{} 字符），做一次格式化转换", content == null ? 0 : content.length());
        String reformatted = formatter.prompt()
                .system(FORMAT_SYSTEM + "\n\n字段与要求（来自原任务的说明）：\n" + schemaPrompt)
                .user("下面是分析师查完代码后得出的结论，请整理成约定的 JSON 对象：\n" + content)
                .call()
                .content();
        parsed = extractObject(reformatted);
        if (parsed.isEmpty()) {
            throw notJson(reformatted, true);
        }
        return parsed.get();
    }

    /** 从一段文字里取出第一个能解析成 JSON 对象的片段：整体、代码围栏里的、或夹在文字中间的。 */
    public static Optional<Map<String, Object>> extractObject(String text) {
        if (text == null || text.isBlank()) {
            return Optional.empty();
        }
        String trimmed = text.trim();
        if (trimmed.startsWith("```")) {
            int firstBreak = trimmed.indexOf('\n');
            int fenceEnd = trimmed.lastIndexOf("```");
            if (firstBreak > 0 && fenceEnd > firstBreak) {
                trimmed = trimmed.substring(firstBreak + 1, fenceEnd).trim();
            }
        }
        Map<String, Object> whole = parseObject(trimmed);
        if (whole != null) {
            return Optional.of(whole);
        }
        Map<String, Object> embedded = parseObject(firstBalancedObject(trimmed));
        return embedded == null ? Optional.<Map<String, Object>>empty() : Optional.of(embedded);
    }

    /** 自带配对扫描（跳过字符串里的花括号）：从每个 {@code {} 起找与之配对的 {@code }}。 */
    static String firstBalancedObject(String text) {
        for (int start = text.indexOf('{'); start >= 0; start = text.indexOf('{', start + 1)) {
            int depth = 0;
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
                } else if (c == '{') {
                    depth++;
                } else if (c == '}' && --depth == 0) {
                    String candidate = text.substring(start, i + 1);
                    if (parseObject(candidate) != null) {
                        return candidate;
                    }
                    break;
                }
            }
        }
        return null;
    }

    private static Map<String, Object> parseObject(String candidate) {
        if (candidate == null || candidate.isBlank()) {
            return null;
        }
        try {
            return MAPPER.readValue(candidate, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            return null;
        }
    }

    private static IllegalStateException notJson(String content, boolean afterFormatting) {
        return new IllegalStateException("LLM 回答不是 JSON" + (afterFormatting ? "（格式化转换后仍不是）" : "") + "："
                + (content == null ? "" : content.substring(0, Math.min(200, content.length())).replace('\n', ' ')));
    }
}
