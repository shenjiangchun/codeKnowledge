package com.huawei.hisi.ram.sdk.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.huawei.hisi.config.ProxyConfig;
import com.huawei.hisi.ram.sdk.SendOptions;
import com.huawei.hisi.ram.sdk.ToolDefinition;
import lombok.extern.slf4j.Slf4j;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.io.BufferedReader;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Thin OkHttp client that POSTs to {@code /v1/messages} with {@code stream=true}
 * and returns the raw SSE {@code data:} lines as a {@link Flux}. Higher-level
 * parsing into typed events is the caller's responsibility.
 */
@Slf4j
@Component
public class AnthropicHttpClient {

    private static final MediaType JSON = MediaType.parse("application/json");
    private static final String DEFAULT_BASE_URL = "https://api.anthropic.com";
    private static final String API_VERSION = "2023-06-01";

    private final ObjectMapper mapper = new ObjectMapper();
    private final ProxyConfig proxyConfig;
    private final String apiKey;
    private final String apiUrl;
    private final String configuredModel;

    public AnthropicHttpClient(ProxyConfig proxyConfig,
                               @Value("${anthropic.api-key:}") String apiKey,
                               @Value("${anthropic.base-url:}") String baseUrl,
                               @Value("${anthropic.model:}") String configuredModel) {
        this.proxyConfig = proxyConfig;
        this.apiKey = apiKey;
        this.configuredModel = (configuredModel != null && !configuredModel.isBlank())
                ? configuredModel : "claude-sonnet-4-20250514";
        String effective = (baseUrl != null && !baseUrl.isBlank()) ? baseUrl : DEFAULT_BASE_URL;
        // Strip trailing slash
        if (effective.endsWith("/")) effective = effective.substring(0, effective.length() - 1);
        // Append /v1/messages only if base URL doesn't already include it
        if (effective.endsWith("/v1")) {
            this.apiUrl = effective + "/messages";
        } else if (effective.endsWith("/v1/messages")) {
            this.apiUrl = effective;
        } else {
            this.apiUrl = effective + "/v1/messages";
        }
        log.info("[AnthropicHttpClient] apiUrl={} model={}", this.apiUrl, this.configuredModel);
    }

    /**
     * Stream SSE {@code data:} payloads (raw JSON lines, without the
     * {@code data: } prefix) for a single Messages API request.
     */
    public Flux<String> stream(List<Map<String, Object>> messages,
                               List<ToolDefinition> tools,
                               SendOptions opts) {
        return Flux.create(sink -> {
            try {
                OkHttpClient client = buildClient();
                Map<String, Object> body = buildRequestBody(messages, tools, opts);
                String json = mapper.writeValueAsString(body);

                Request req = new Request.Builder()
                        .url(apiUrl)
                        .header("x-api-key", apiKey == null ? "" : apiKey)
                        .header("anthropic-version", API_VERSION)
                        .header("content-type", "application/json")
                        .post(RequestBody.create(json, JSON))
                        .build();

                try (Response resp = client.newCall(req).execute()) {
                    if (!resp.isSuccessful()) {
                        sink.error(new IllegalStateException(
                                "Anthropic API failed: " + resp.code()));
                        return;
                    }
                    ResponseBody rb = resp.body();
                    if (rb == null) {
                        sink.complete();
                        return;
                    }
                    try (BufferedReader br = new BufferedReader(rb.charStream())) {
                        String line;
                        while ((line = br.readLine()) != null) {
                            if (sink.isCancelled()) return;
                            if (line.startsWith("data:")) {
                                String data = line.substring(5).trim();
                                if (!data.isEmpty()) sink.next(data);
                            }
                        }
                    }
                    sink.complete();
                }
            } catch (Exception ex) {
                log.warn("[AnthropicHttpClient] stream failed: {}", ex.toString());
                sink.error(ex);
            }
        });
    }

    private Map<String, Object> buildRequestBody(List<Map<String, Object>> messages,
                                                 List<ToolDefinition> tools,
                                                 SendOptions opts) {
        Map<String, Object> body = new LinkedHashMap<>();
        // Always use the model configured via anthropic.model (application-local.yml).
        // opts.model() carries a chat-models.yml logical key (e.g. "glm-5.1"), not a
        // real API model name — sending it upstream causes 503 from the relay server.
        body.put("model", configuredModel);
        body.put("max_tokens", opts.maxTokens());
        body.put("temperature", opts.temperature());
        body.put("stream", true);
        if (opts.systemPrompt() != null && !opts.systemPrompt().isBlank()) {
            body.put("system", opts.systemPrompt());
        }
        body.put("messages", messages);
        if (tools != null && !tools.isEmpty()) {
            List<Map<String, Object>> toolList = new ArrayList<>();
            for (ToolDefinition t : tools) {
                Map<String, Object> tm = new LinkedHashMap<>();
                tm.put("name", t.name());
                if (t.description() != null) tm.put("description", t.description());
                try {
                    tm.put("input_schema", mapper.readValue(t.inputSchema(), Map.class));
                } catch (Exception ex) {
                    tm.put("input_schema", Map.of("type", "object"));
                }
                toolList.add(tm);
            }
            body.put("tools", toolList);
        }
        return body;
    }

    private OkHttpClient buildClient() {
        OkHttpClient.Builder b = new OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(10, TimeUnit.MINUTES)
                .writeTimeout(30, TimeUnit.SECONDS);
        if (proxyConfig != null && proxyConfig.isEnabled()
                && proxyConfig.getHost() != null && !proxyConfig.getHost().isBlank()
                && proxyConfig.getPort() > 0
                && !isNonProxyHost(apiUrl, proxyConfig.getNonProxyHosts())) {
            Proxy.Type t = "SOCKS".equalsIgnoreCase(proxyConfig.getType())
                    ? Proxy.Type.SOCKS : Proxy.Type.HTTP;
            b.proxy(new Proxy(t, new InetSocketAddress(
                    proxyConfig.getHost(), proxyConfig.getPort())));
            log.debug("[AnthropicHttpClient] Using proxy {}:{} for {}", proxyConfig.getHost(), proxyConfig.getPort(), apiUrl);
        } else if (proxyConfig != null && proxyConfig.isEnabled() && isNonProxyHost(apiUrl, proxyConfig.getNonProxyHosts())) {
            log.info("[AnthropicHttpClient] Bypassing proxy for {} (matched non-proxy-hosts: {})", apiUrl, proxyConfig.getNonProxyHosts());
        }
        return b.build();
    }

    /**
     * Check if the target URL's host matches any pattern in the non-proxy-hosts list.
     * Supports exact match and wildcard prefix (e.g. "*.huawei.com").
     */
    private static boolean isNonProxyHost(String url, String nonProxyHosts) {
        if (nonProxyHosts == null || nonProxyHosts.isBlank()) return false;
        String host;
        try {
            host = java.net.URI.create(url).getHost();
        } catch (Exception e) {
            return false;
        }
        if (host == null) return false;
        for (String pattern : nonProxyHosts.split(",")) {
            String p = pattern.trim();
            if (p.isEmpty()) continue;
            if (p.startsWith("*.")) {
                // Wildcard: *.huawei.com matches aiserver.hisi.huawei.com
                String suffix = p.substring(1); // ".huawei.com"
                if (host.endsWith(suffix) || host.equals(p.substring(2))) {
                    return true;
                }
            } else if (host.equalsIgnoreCase(p)) {
                return true;
            }
        }
        return false;
    }
}
