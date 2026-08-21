package com.huawei.hisi.knowledgegraph.aggregation.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

/**
 * 走 deepseek 网关的 OpenAI 兼容 /chat/completions 链路，用 {@code response_format: json_object}
 * 强制返回 JSON 对象，替代 anthropic 中转 deepseek 链路。
 *
 * <p>为什么不用 {@code extractionChatClient}（AnthropicChatModel）：
 * anthropic /messages 链路把推理模型的 thinking 块拼进 content，且 tool_choice 强制在中转层失效，
 * 导致 .entity() 与 .content() 均无法拿到干净 JSON。而 OpenAI 兼容链路把思考内容放在独立的
 * {@code reasoning_content} 字段，{@code content} 天然是干净答案，配合 json_object 强制即可稳定产出 JSON。
 *
 * <p>配置复用顶层 {@code anthropic.*}（application-local.yml 已指向 deepseek 网关
 * {@code http://1.95.145.190:8888} + {@code deepseek-v4-pro-cc}）。
 */
@Slf4j
@Service
public class DeepseekJsonClient {

    private static final int MAX_TOKENS = 16384;

    private final String apiUrl;
    private final String apiKey;
    private final String model;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final RestTemplate restTemplate = new RestTemplate();

    public DeepseekJsonClient(
            @Value("${anthropic.base-url:}") String baseUrl,
            @Value("${anthropic.api-key:}") String apiKey,
            @Value("${anthropic.model:deepseek-v4-pro-cc}") String model) {
        String effective = (baseUrl == null || baseUrl.isBlank())
                ? "http://1.95.145.190:8888" : baseUrl.trim();
        if (effective.endsWith("/")) effective = effective.substring(0, effective.length() - 1);
        // 对齐 AnthropicHttpClient 的拼接规则：base-url 已含 /v1 时不再重复拼
        if (effective.endsWith("/v1")) {
            this.apiUrl = effective + "/chat/completions";
        } else {
            this.apiUrl = effective + "/v1/chat/completions";
        }
        this.apiKey = apiKey;
        this.model = model;
        log.info("[DeepseekJson] apiUrl={} model={}", this.apiUrl, this.model);
    }

    /**
     * 结构化 JSON 抽取：调用 deepseek 网关，json_object 强制返回 JSON，反序列化为目标类型。
     *
     * @param systemPrompt 系统提示（可为 null）
     * @param userPrompt   用户提示（若不含 "json" 字样，自动补，满足 json_object 前置条件）
     * @param type         反序列化目标类型
     * @return 目标对象，或 null（调用失败 / 反序列化失败）
     */
    public <T> T chatJson(String systemPrompt, String userPrompt, Class<T> type) {
        try {
            String json = rawChatJson(systemPrompt, userPrompt);
            if (json == null || json.isBlank()) {
                return null;
            }
            try {
                return objectMapper.readValue(json, type);
            } catch (Exception e) {
                log.warn("[DeepseekJson] 反序列化失败 type={}: {}, json前200={}",
                        type.getSimpleName(), e.getMessage(),
                        json.substring(0, Math.min(200, json.length())));
                return null;
            }
        } catch (Exception e) {
            log.warn("[DeepseekJson] 调用失败: {}", e.getMessage());
            return null;
        }
    }

    /** 调用 /chat/completions 并返回 content 文本（json_object 强制后的干净 JSON）。 */
    private String rawChatJson(String systemPrompt, String userPrompt) throws Exception {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("model", model);
        body.put("temperature", 0.0);
        body.put("max_tokens", MAX_TOKENS);
        // 关键：json_object 强制（deepseek 支持 json_object，不支持 json_schema）
        ObjectNode rf = body.putObject("response_format");
        rf.put("type", "json_object");

        // json_object 前置条件：prompt 必须含 "json" 字样
        String effectiveUser = userPrompt;
        if (!userPrompt.toLowerCase().contains("json")) {
            effectiveUser = userPrompt + "\n请以 JSON 格式输出。";
        }

        var messages = body.putArray("messages");
        if (systemPrompt != null && !systemPrompt.isBlank()) {
            ObjectNode sys = messages.addObject();
            sys.put("role", "system");
            sys.put("content", systemPrompt);
        }
        ObjectNode user = messages.addObject();
        user.put("role", "user");
        user.put("content", effectiveUser);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Authorization", "Bearer " + (apiKey == null ? "" : apiKey));

        HttpEntity<String> entity = new HttpEntity<>(body.toString(), headers);
        ResponseEntity<String> response = restTemplate.exchange(apiUrl, HttpMethod.POST, entity, String.class);

        JsonNode root = objectMapper.readTree(response.getBody());
        JsonNode choices = root.get("choices");
        if (choices == null || !choices.isArray() || choices.isEmpty()) {
            log.warn("[DeepseekJson] 响应无 choices");
            return null;
        }
        JsonNode message = choices.get(0).get("message");
        if (message == null || !message.has("content") || message.get("content").isNull()) {
            log.warn("[DeepseekJson] 响应无 content");
            return null;
        }
        return message.get("content").asText().trim();
    }
}
