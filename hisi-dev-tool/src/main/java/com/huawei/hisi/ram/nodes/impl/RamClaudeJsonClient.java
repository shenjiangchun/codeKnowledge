package com.huawei.hisi.ram.nodes.impl;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.huawei.hisi.ram.sdk.SendOptions;
import com.huawei.hisi.ram.sdk.ToolDefinition;
import com.huawei.hisi.ram.sdk.impl.AnthropicHttpClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Thin wrapper around {@link AnthropicHttpClient} for non-streaming,
 * JSON-in / JSON-out calls used by the RAM LLM clients
 * ({@link ClaudeClarifyLlmClient}, {@link ClaudeImplementLlmClient}).
 *
 * <p>Collects the full SSE stream into a single text response, then
 * parses it as a {@code Map<String, Object>} (the LLM is instructed
 * to return raw JSON — no markdown fences).
 */
@Slf4j
@Component
public class RamClaudeJsonClient {

    private static final String FALLBACK_MODEL = "claude-sonnet-4-20250514";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Maximum number of tool_use round-trips before forcing termination. */
    private static final int MAX_TOOL_ROUNDS = 10;

    private final AnthropicHttpClient http;
    private final String apiKey;
    private final String defaultModel;

    public RamClaudeJsonClient(AnthropicHttpClient http,
                               @Value("${anthropic.api-key:}") String apiKey,
                               @Value("${anthropic.model:}") String configModel) {
        this.http = http;
        this.apiKey = apiKey;
        this.defaultModel = (configModel != null && !configModel.isBlank())
                ? configModel : FALLBACK_MODEL;
    }

    /** Returns {@code true} when an Anthropic API key is configured. */
    public boolean isAvailable() {
        return apiKey != null && !apiKey.isBlank();
    }

    /** Default model used when the caller does not specify one. */
    public String defaultModel() {
        return defaultModel;
    }

    /**
     * Send a single-turn system + user message and parse the assistant
     * response as a JSON {@code Map<String, Object>}.
     *
     * <p>The {@link SendOptions#systemPrompt()} field is ignored;
     * {@code systemPrompt} and {@code userPrompt} are wired into the
     * request body explicitly.
     *
     * @throws IllegalStateException if the API call fails or the response
     *                               cannot be parsed as JSON
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> callJson(String systemPrompt,
                                        String userPrompt,
                                        SendOptions opts) {
        SendOptions effective = new SendOptions(
                opts.model(), opts.maxTokens(), opts.temperature(), systemPrompt);

        List<Map<String, Object>> messages = List.of(
                Map.of("role", "user", "content", userPrompt));

        // Collect the full streamed response into a single string.
        StringBuilder sb = new StringBuilder();
        http.stream(messages, List.of(), effective)
                .doOnNext(line -> {
                    try {
                        Map<String, Object> event = MAPPER.readValue(line, new TypeReference<>() {});
                        String type = String.valueOf(event.get("type"));
                        if ("content_block_delta".equals(type)) {
                            Object delta = event.get("delta");
                            if (delta instanceof Map<?, ?> d) {
                                Object text = d.get("text");
                                if (text != null) sb.append(text);
                            }
                        }
                    } catch (Exception ignored) {
                        // Non-JSON lines (e.g. "[DONE]") — skip.
                    }
                })
                .blockLast();

        String raw = sb.toString().trim();

        log.debug("[RamClaudeJsonClient] raw response length={}", raw.length());

        if (raw.isEmpty()) {
            log.error("[RamClaudeJsonClient] Empty response from Claude — returning empty map");
            return Map.of();
        }

        return parseJsonResponse(raw);
    }

    // ──────────────── Tool-Use API ────────────────

    /**
     * Send a system + user message with tool definitions, then loop
     * up to {@link #MAX_TOOL_ROUNDS} times handling any {@code tool_use}
     * blocks the model emits. When the model finally stops with
     * {@code end_turn}, parse the concatenated text blocks as JSON.
     *
     * <p>If no tools are provided or the tools list is empty, this
     * method behaves identically to {@link #callJson}.
     *
     * @param tools    tool definitions to offer the model (may be empty)
     * @param handlers map of toolName → handler function; each handler
     *                 receives the tool input and returns a serializable result
     */
    /**
     * Result of a tool-use call including both the parsed JSON and
     * reasoning steps collected during the tool rounds.
     */
    public record JsonCallResult(Map<String, Object> json, List<String> reasoning) {}

    /**
     * Send a system + user message with tool definitions, then loop
     * up to {@link #MAX_TOOL_ROUNDS} times handling any {@code tool_use}
     * blocks the model emits. When the model finally stops with
     * {@code end_turn}, parse the concatenated text blocks as JSON.
     *
     * <p>If no tools are provided or the tools list is empty, this
     * method behaves identically to {@link #callJson}.
     *
     * @param tools    tool definitions to offer the model (may be empty)
     * @param handlers map of toolName → handler function; each handler
     *                 receives the tool input and returns a serializable result
     */
    public Map<String, Object> callJsonWithTools(String systemPrompt,
                                                  String userPrompt,
                                                  List<ToolDefinition> tools,
                                                  Map<String, Function<Map<String, Object>, Object>> handlers,
                                                  SendOptions opts) {
        return callJsonWithToolsAndReasoning(systemPrompt, userPrompt, tools, handlers, opts).json();
    }

    /**
     * Same as {@link #callJsonWithTools} but also returns reasoning steps.
     */
    public JsonCallResult callJsonWithToolsAndReasoning(String systemPrompt,
                                                        String userPrompt,
                                                        List<ToolDefinition> tools,
                                                        Map<String, Function<Map<String, Object>, Object>> handlers,
                                                        SendOptions opts) {
        if (tools == null || tools.isEmpty()) {
            Map<String, Object> json = callJson(systemPrompt, userPrompt, opts);
            return new JsonCallResult(json, List.of("单轮调用，无工具使用"));
        }

        SendOptions effective = new SendOptions(
                opts.model(), opts.maxTokens(), opts.temperature(), systemPrompt);

        List<Map<String, Object>> messages = new ArrayList<>();
        messages.add(Map.of("role", "user", "content", userPrompt));

        List<String> reasoningSteps = new ArrayList<>();
        reasoningSteps.add("初始查询: " + truncateForReasoning(userPrompt));

        for (int round = 0; round < MAX_TOOL_ROUNDS; round++) {
            // ── Inject "wrap-up" nudge when approaching the limit ──
            if (round == MAX_TOOL_ROUNDS - 2) {
                messages.add(Map.of("role", "user", "content",
                        "[SYSTEM] You have used most of your tool budget. " +
                        "You MUST output your final JSON response in the next turn. " +
                        "Stop calling tools and produce the complete JSON output now."));
            }

            // ── Stream one API call and collect content blocks ──
            StreamResult result = streamAndCollect(messages, tools, effective);

            log.info("[RamClaudeJsonClient] round={} stop_reason={} text.len={} tool_use_blocks={}",
                    round, result.stopReason, result.textContent.length(), result.toolUseBlocks.size());

            // ── If stop_reason is "end_turn" or no tool_use blocks → done ──
            if (!"tool_use".equals(result.stopReason) || result.toolUseBlocks.isEmpty()) {
                reasoningSteps.add("LLM返回最终结果");
                return new JsonCallResult(parseJsonResponse(result.textContent.toString()), List.copyOf(reasoningSteps));
            }

            // ── Process tool_use blocks ──
            // Build the assistant message with all content blocks
            List<Map<String, Object>> assistantContent = new ArrayList<>();

            // Add text blocks before tool_use
            if (!result.textContent.isEmpty()) {
                assistantContent.add(Map.of("type", "text", "text", result.textContent.toString()));
            }

            // Add tool_use blocks
            List<Map<String, Object>> toolResults = new ArrayList<>();
            for (ToolUseBlock block : result.toolUseBlocks) {
                assistantContent.add(Map.of(
                        "type", "tool_use",
                        "id", block.id,
                        "name", block.name,
                        "input", block.input
                ));

                // Execute the tool handler
                String toolResultContent = executeToolHandler(handlers, block);
                reasoningSteps.add(String.format("Round %d: %s(%s) → %s",
                        round, block.name, summarizeInput(block.name, block.input),
                        truncateForReasoning(toolResultContent)));
                toolResults.add(Map.of(
                        "type", "tool_result",
                        "tool_use_id", block.id,
                        "content", toolResultContent
                ));
            }

            // Append assistant message and tool results
            messages.add(Map.of("role", "assistant", "content", assistantContent));
            messages.add(Map.of("role", "user", "content", toolResults));
        }

        // Exceeded MAX_TOOL_ROUNDS — give a warning and try to force final JSON output
        log.warn("[RamClaudeJsonClient] Exceeded {} tool rounds — forcing termination", MAX_TOOL_ROUNDS);
        reasoningSteps.add("超过最大工具轮次，强制终止");
        // Inject a forceful instruction and call WITHOUT tools to guarantee end_turn
        messages.add(Map.of("role", "user", "content",
                "[SYSTEM] Tool budget exhausted. You MUST now output your final answer " +
                "as a single valid JSON object. Do NOT call any more tools. " +
                "Do NOT include any prose before or after the JSON. " +
                "Output ONLY the JSON object starting with { and ending with }."));
        StreamResult finalResult = streamAndCollect(messages, List.of(), effective);
        String finalText = finalResult.textContent.toString().trim();
        if (finalText.isEmpty()) {
            log.error("[RamClaudeJsonClient] Final forced response is empty — returning error");
            throw new IllegalStateException("Claude response is not valid JSON");
        }
        return new JsonCallResult(parseJsonResponse(finalText), List.copyOf(reasoningSteps));
    }

    // ──────────────── SSE stream parsing with tool_use support ────────────────

    /**
     * Holds the parsed result from a single streamed API response.
     */
    private static class StreamResult {
        final StringBuilder textContent = new StringBuilder();
        final List<ToolUseBlock> toolUseBlocks = new ArrayList<>();
        String stopReason = "end_turn";
    }

    /**
     * Represents a single tool_use content block from the model.
     */
    private static class ToolUseBlock {
        String id = "";
        String name = "";
        final StringBuilder inputJson = new StringBuilder();
        Map<String, Object> input = Map.of();
    }

    /**
     * Stream a single API call and parse the SSE events into text content
     * and tool_use blocks.
     */
    private StreamResult streamAndCollect(List<Map<String, Object>> messages,
                                          List<ToolDefinition> tools,
                                          SendOptions opts) {
        StreamResult result = new StreamResult();

        // Track the current content block being built
        // Index → block type, for content_block_start / content_block_delta routing
        Map<Integer, String> blockTypes = new LinkedHashMap<>();
        Map<Integer, ToolUseBlock> pendingToolBlocks = new LinkedHashMap<>();

        http.stream(messages, tools, opts)
                .doOnNext(line -> {
                    try {
                        Map<String, Object> event = MAPPER.readValue(line, new TypeReference<>() {});
                        String type = String.valueOf(event.get("type"));

                        switch (type) {
                            case "content_block_start" -> {
                                int index = getIntField(event, "index");
                                Object cb = event.get("content_block");
                                if (cb instanceof Map<?, ?> block) {
                                    String blockType = String.valueOf(block.get("type"));
                                    blockTypes.put(index, blockType);
                                    if ("tool_use".equals(blockType)) {
                                        ToolUseBlock tb = new ToolUseBlock();
                                        Object idVal = block.get("id");
                                        Object nameVal = block.get("name");
                                        tb.id = idVal != null ? String.valueOf(idVal) : "";
                                        tb.name = nameVal != null ? String.valueOf(nameVal) : "";
                                        pendingToolBlocks.put(index, tb);
                                    }
                                }
                            }
                            case "content_block_delta" -> {
                                int index = getIntField(event, "index");
                                Object delta = event.get("delta");
                                if (delta instanceof Map<?, ?> d) {
                                    String deltaType = String.valueOf(d.get("type"));
                                    if ("text_delta".equals(deltaType)) {
                                        Object text = d.get("text");
                                        if (text != null) result.textContent.append(text);
                                    } else if ("input_json_delta".equals(deltaType)) {
                                        Object partial = d.get("partial_json");
                                        ToolUseBlock tb = pendingToolBlocks.get(index);
                                        if (tb != null && partial != null) {
                                            tb.inputJson.append(partial);
                                        }
                                    }
                                }
                            }
                            case "content_block_stop" -> {
                                int index = getIntField(event, "index");
                                ToolUseBlock tb = pendingToolBlocks.remove(index);
                                if (tb != null) {
                                    // Parse the accumulated input JSON
                                    String inputStr = tb.inputJson.toString().trim();
                                    if (!inputStr.isEmpty()) {
                                        try {
                                            tb.input = MAPPER.readValue(inputStr, new TypeReference<>() {});
                                        } catch (Exception e) {
                                            log.warn("[RamClaudeJsonClient] Failed to parse tool input JSON: {}",
                                                    inputStr.length() > 200 ? inputStr.substring(0, 200) : inputStr);
                                            tb.input = Map.of();
                                        }
                                    }
                                    result.toolUseBlocks.add(tb);
                                }
                            }
                            case "message_delta" -> {
                                Object d = event.get("delta");
                                if (d instanceof Map<?, ?> delta) {
                                    Object sr = delta.get("stop_reason");
                                    if (sr instanceof String s) {
                                        result.stopReason = s;
                                    }
                                }
                            }
                            // Ignore: message_start, ping, message_stop
                        }
                    } catch (Exception ignored) {
                        // Non-JSON lines (e.g. "[DONE]") — skip.
                    }
                })
                .blockLast();

        return result;
    }

    /**
     * Execute a tool handler and return the serialized result string.
     * If the handler throws, returns an error JSON.
     */
    private String executeToolHandler(Map<String, Function<Map<String, Object>, Object>> handlers,
                                       ToolUseBlock block) {
        Function<Map<String, Object>, Object> handler = handlers.get(block.name);
        if (handler == null) {
            log.warn("[RamClaudeJsonClient] No handler for tool '{}' — returning error", block.name);
            return "{\"error\": \"Unknown tool: " + block.name + "\"}";
        }

        try {
            log.info("[RamClaudeJsonClient] Executing tool '{}' with input: {}",
                    block.name, summarizeInput(block.name, block.input));
            Object toolResult = handler.apply(block.input);
            return KgToolRegistry.serializeResult(toolResult);
        } catch (Exception e) {
            log.error("[RamClaudeJsonClient] Tool '{}' execution failed: {}",
                    block.name, e.getMessage(), e);
            return "{\"error\": \"Tool execution failed: " + e.getMessage().replace("\"", "'") + "\"}";
        }
    }

    /**
     * Summarize tool input for logging — prints actual parameter values with truncation
     * so the user can see what the LLM is actually requesting.
     */
    private String summarizeInput(String toolName, Map<String, Object> input) {
        if (input == null || input.isEmpty()) {
            return "{}";
        }
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, Object> entry : input.entrySet()) {
            if (!first) sb.append(", ");
            first = false;
            sb.append(entry.getKey()).append("=");
            Object val = entry.getValue();
            if (val == null) {
                sb.append("null");
            } else {
                String str = String.valueOf(val);
                if (str.length() > 120) {
                    sb.append("\"").append(str, 0, 120).append("...\"(").append(str.length()).append(" chars)");
                } else {
                    sb.append("\"").append(str).append("\"");
                }
            }
        }
        sb.append("}");
        return sb.toString();
    }

    /**
     * Parse a raw text response as JSON, stripping markdown fences if present.
     * Handles three cases:
     * 1. Pure JSON: {"needs_clarification": true, ...}
     * 2. Starts with code fence: ```json\n{...}\n```
     * 3. JSON embedded in prose: "Some text...\n```json\n{...}\n```\nMore text..."
     */
    private Map<String, Object> parseJsonResponse(String raw) {
        raw = raw.trim();
        log.debug("[RamClaudeJsonClient] raw response length={}", raw.length());

        // Strategy 1: raw starts with ``` — strip fences
        if (raw.startsWith("```")) {
            String extracted = stripLeadingFence(raw);
            if (extracted != null) {
                Map<String, Object> result = tryParseJson(extracted);
                if (result != null) return result;
            }
        }

        // Strategy 2: direct JSON parse (starts with { or [)
        if (raw.startsWith("{") || raw.startsWith("[")) {
            Map<String, Object> result = tryParseJson(raw);
            if (result != null) return result;
        }

        // Strategy 3: find ```json ... ``` block embedded in text
        String embedded = extractEmbeddedJsonBlock(raw);
        if (embedded != null) {
            Map<String, Object> result = tryParseJson(embedded);
            if (result != null) {
                log.info("[RamClaudeJsonClient] Extracted JSON from embedded code block ({}→{} chars)",
                        raw.length(), embedded.length());
                return result;
            }
        }

        // Strategy 4: balanced-brace extraction — properly handles braces inside strings
        int firstBrace = raw.indexOf('{');
        if (firstBrace >= 0) {
            String candidate = extractBalancedJson(raw, firstBrace);
            if (candidate != null) {
                Map<String, Object> result = tryParseJson(candidate);
                if (result != null) {
                    log.info("[RamClaudeJsonClient] Extracted JSON via balanced-brace ({}→{} chars)",
                            raw.length(), candidate.length());
                    return result;
                }
                log.warn("[RamClaudeJsonClient] Balanced-brace extraction found candidate ({} chars) but parse failed",
                        candidate.length());
            }
        }

        // Strategy 5: simple first { / last } (fallback for imperfect JSON)
        int lastBrace = raw.lastIndexOf('}');
        if (firstBrace >= 0 && lastBrace > firstBrace) {
            String candidate = raw.substring(firstBrace, lastBrace + 1).trim();
            Map<String, Object> result = tryParseJson(candidate);
            if (result != null) {
                log.info("[RamClaudeJsonClient] Extracted JSON via simple brace matching ({}→{} chars)",
                        raw.length(), candidate.length());
                return result;
            }
            // Strategy 6: JSON might be truncated — try to repair by closing open structures
            String repaired = repairTruncatedJson(candidate);
            if (repaired != null) {
                result = tryParseJson(repaired);
                if (result != null) {
                    log.info("[RamClaudeJsonClient] Extracted JSON via truncation repair ({}→{} chars)",
                            raw.length(), repaired.length());
                    return result;
                }
            }
        }

        // All strategies failed — log full details for debugging
        log.error("[RamClaudeJsonClient] ALL {} strategies failed to extract JSON from response (len={}).",
                "6", raw.length());
        log.error("[RamClaudeJsonClient] First 800 chars: {}", raw.substring(0, Math.min(800, raw.length())));
        log.error("[RamClaudeJsonClient] Last 300 chars: {}",
                raw.length() > 300 ? raw.substring(raw.length() - 300) : raw);
        throw new IllegalStateException("Claude response is not valid JSON");
    }

    /** Strip leading ```json or ``` fence from text that starts with it */
    private String stripLeadingFence(String raw) {
        int start = raw.indexOf('\n');
        int end = raw.lastIndexOf("```");
        if (start > 0 && end > start) {
            return raw.substring(start + 1, end).trim();
        }
        return null;
    }

    /** Find ```json ... ``` or ``` ... ``` block embedded anywhere in text */
    private String extractEmbeddedJsonBlock(String raw) {
        // Try ```json first, then plain ```
        for (String marker : new String[]{"```json", "```JSON", "```"}) {
            int blockStart = raw.indexOf(marker);
            if (blockStart < 0) continue;

            int contentStart = raw.indexOf('\n', blockStart);
            if (contentStart < 0) continue;
            contentStart++; // skip the newline

            // Find closing ``` after content start
            int blockEnd = raw.indexOf("\n```", contentStart);
            if (blockEnd < 0) {
                blockEnd = raw.indexOf("```", contentStart);
            }
            if (blockEnd > contentStart) {
                String content = raw.substring(contentStart, blockEnd).trim();
                if (!content.isEmpty() && content.charAt(0) == '{') {
                    return content;
                }
            }
        }
        return null;
    }

    /** Try parsing a string as JSON; returns null on failure instead of throwing */
    private Map<String, Object> tryParseJson(String json) {
        try {
            return MAPPER.readValue(json, new TypeReference<>() {});
        } catch (Exception ex) {
            log.debug("[RamClaudeJsonClient] tryParseJson failed: {}", ex.getMessage());
            return null;
        }
    }

    /**
     * Extract a balanced JSON object from text starting at the given position.
     * Properly skips braces inside quoted strings.
     * Returns null if no balanced object found.
     */
    private String extractBalancedJson(String text, int start) {
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
            if (inString) continue;
            if (c == '{') depth++;
            else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return text.substring(start, i + 1);
                }
            }
        }
        // Unbalanced — JSON is truncated
        return null;
    }

    /**
     * Attempt to repair truncated JSON by closing open strings, arrays, and objects.
     * Handles common LLM truncation patterns where maxTokens cuts mid-output.
     */
    private String repairTruncatedJson(String json) {
        if (json == null || json.isEmpty()) return null;

        StringBuilder sb = new StringBuilder(json);
        // Remove trailing incomplete string (text after last complete value)
        // Find the last complete JSON value indicator: }, ], ", number, true, false, null
        int lastGoodPos = findLastCompleteValuePosition(json);
        if (lastGoodPos < 0) return null;

        // Trim to last good position
        sb.setLength(lastGoodPos + 1);

        // Remove trailing comma if present
        String trimmed = sb.toString().stripTrailing();
        if (trimmed.endsWith(",")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }

        sb = new StringBuilder(trimmed);

        // Count open structures and close them
        int openBraces = 0, openBrackets = 0;
        boolean inStr = false;
        boolean esc = false;
        for (int i = 0; i < sb.length(); i++) {
            char c = sb.charAt(i);
            if (esc) { esc = false; continue; }
            if (c == '\\' && inStr) { esc = true; continue; }
            if (c == '"') { inStr = !inStr; continue; }
            if (inStr) continue;
            if (c == '{') openBraces++;
            else if (c == '}') openBraces--;
            else if (c == '[') openBrackets++;
            else if (c == ']') openBrackets--;
        }

        // If we're inside a string, close it
        if (inStr) {
            sb.append("...\"");
        }

        // Close open arrays and objects
        for (int i = 0; i < openBrackets; i++) sb.append(']');
        for (int i = 0; i < openBraces; i++) sb.append('}');

        return sb.toString();
    }

    /** Find position of last character that ends a complete JSON value */
    private int findLastCompleteValuePosition(String json) {
        boolean inStr = false;
        boolean esc = false;
        int lastGood = -1;
        for (int i = 0; i < json.length(); i++) {
            char c = json.charAt(i);
            if (esc) { esc = false; continue; }
            if (c == '\\' && inStr) { esc = true; continue; }
            if (c == '"') {
                inStr = !inStr;
                if (!inStr) lastGood = i; // end of string
                continue;
            }
            if (inStr) continue;
            // Outside string: }, ], digits are good endpoints
            if (c == '}' || c == ']' || c == '"') lastGood = i;
            else if (c == 'e' && i >= 3 && json.substring(i - 3, i + 1).equals("true")) lastGood = i;
            else if (c == 'e' && i >= 4 && json.substring(i - 4, i + 1).equals("false")) lastGood = i;
            else if (c == 'l' && i >= 3 && json.substring(i - 3, i + 1).equals("null")) lastGood = i;
            else if (Character.isDigit(c) || c == '.') lastGood = i;
        }
        return lastGood;
    }

    private static int getIntField(Map<String, Object> event, String key) {
        Object v = event.get(key);
        if (v instanceof Number n) return n.intValue();
        return 0;
    }

    /** Truncate text for inclusion in reasoning steps. */
    private static String truncateForReasoning(String text) {
        if (text == null) return "";
        if (text.length() <= 150) return text;
        return text.substring(0, 150) + "...(" + text.length() + " chars)";
    }

    /**
     * Attempt to recover a truncated JSON response by closing unclosed structures.
     *
     * <p>When the LLM hits max_tokens mid-response, the JSON is cut off.
     * This method uses a stack-based approach to close unclosed strings, arrays,
     * and objects in the correct LIFO order, then re-parses.
     * Returns null if recovery fails.</p>
     */
    static Map<String, Object> recoverTruncatedJson(String raw) {
        if (raw == null || raw.isBlank()) return null;

        String json = raw.trim();
        // Strip markdown fences
        if (json.startsWith("```")) {
            int start = json.indexOf('\n');
            int end = json.lastIndexOf("```");
            if (start > 0 && end > start) {
                json = json.substring(start + 1, end).trim();
            }
        }

        // Check if it looks like it was supposed to be a JSON object
        if (!json.startsWith("{")) return null;

        // Use a stack to track open structures in order
        java.util.Deque<Character> stack = new java.util.ArrayDeque<>();
        boolean inString = false;
        boolean escape = false;

        for (int i = 0; i < json.length(); i++) {
            char c = json.charAt(i);
            if (escape) { escape = false; continue; }
            if (c == '\\') { escape = true; continue; }
            if (c == '"') { inString = !inString; continue; }
            if (inString) continue;
            if (c == '{' || c == '[') {
                stack.push(c);
            } else if (c == '}' || c == ']') {
                // Pop matching opener
                if (!stack.isEmpty()) {
                    char opener = stack.peek();
                    if ((c == '}' && opener == '{') || (c == ']' && opener == '[')) {
                        stack.pop();
                    }
                }
            }
        }

        // If nothing is unclosed, no recovery needed
        if (stack.isEmpty() && !inString) return null;

        // If in an unclosed string, close it
        if (inString) {
            json += "\"";
        }

        // Strategy 1: Try to cut at the last complete element boundary
        // Find the last "}," or "}]" that marks a complete item in the array
        if (!stack.isEmpty()) {
            int lastCompleteObj = json.lastIndexOf("},");
            int lastCompleteArr = json.lastIndexOf("}]");
            int cutPoint = Math.max(lastCompleteObj, lastCompleteArr);

            if (cutPoint > 0) {
                // Recalculate the stack for the truncated string
                String truncated = json.substring(0, cutPoint + 1);
                java.util.Deque<Character> truncatedStack = new java.util.ArrayDeque<>();
                boolean tInString = false;
                boolean tEscape = false;
                for (int i = 0; i < truncated.length(); i++) {
                    char c = truncated.charAt(i);
                    if (tEscape) { tEscape = false; continue; }
                    if (c == '\\') { tEscape = true; continue; }
                    if (c == '"') { tInString = !tInString; continue; }
                    if (tInString) continue;
                    if (c == '{' || c == '[') truncatedStack.push(c);
                    else if (c == '}' || c == ']') {
                        if (!truncatedStack.isEmpty()) {
                            char opener = truncatedStack.peek();
                            if ((c == '}' && opener == '{') || (c == ']' && opener == '[')) {
                                truncatedStack.pop();
                            }
                        }
                    }
                }
                // Close remaining openers in LIFO order
                StringBuilder closer = new StringBuilder();
                while (!truncatedStack.isEmpty()) {
                    char opener = truncatedStack.pop();
                    closer.append(opener == '{' ? '}' : ']');
                }
                String closed = truncated + closer;
                try {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> result = MAPPER.readValue(closed, Map.class);
                    return result;
                } catch (Exception e) {
                    // Cut-point recovery failed, try full closure below
                }
            }
        }

        // Strategy 2: Close all open structures in LIFO order on the full string
        StringBuilder closer = new StringBuilder();
        while (!stack.isEmpty()) {
            char opener = stack.pop();
            closer.append(opener == '{' ? '}' : ']');
        }
        String closed = json + closer;
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> result = MAPPER.readValue(closed, Map.class);
            return result;
        } catch (Exception e) {
            return null;
        }
    }
}
