package com.huawei.hisi.ram.nodes.impl;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.huawei.hisi.ram.sdk.SendOptions;
import com.huawei.hisi.ram.sdk.impl.AnthropicHttpClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

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

    private static final String DEFAULT_MODEL = "claude-sonnet-4-20250514";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final AnthropicHttpClient http;
    private final String apiKey;

    public RamClaudeJsonClient(AnthropicHttpClient http,
                               @Value("${anthropic.api-key:}") String apiKey) {
        this.http = http;
        this.apiKey = apiKey;
    }

    /** Returns {@code true} when an Anthropic API key is configured. */
    public boolean isAvailable() {
        return apiKey != null && !apiKey.isBlank();
    }

    /** Default model used when the caller does not specify one. */
    public String defaultModel() {
        return DEFAULT_MODEL;
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
        // Strip markdown fences if the model wrapped JSON in ```json ... ```
        if (raw.startsWith("```")) {
            int start = raw.indexOf('\n');
            int end = raw.lastIndexOf("```");
            if (start > 0 && end > start) {
                raw = raw.substring(start + 1, end).trim();
            }
        }

        log.debug("[RamClaudeJsonClient] raw response length={}", raw.length());

        try {
            return MAPPER.readValue(raw, new TypeReference<>() {});
        } catch (Exception ex) {
            log.error("[RamClaudeJsonClient] Failed to parse JSON response: {}",
                    raw.length() > 500 ? raw.substring(0, 500) + "..." : raw, ex);
            throw new IllegalStateException("Claude response is not valid JSON", ex);
        }
    }
}
