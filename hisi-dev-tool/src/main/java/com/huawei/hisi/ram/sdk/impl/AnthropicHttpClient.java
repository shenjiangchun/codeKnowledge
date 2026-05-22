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
    private static final String API_URL = "https://api.anthropic.com/v1/messages";
    private static final String API_VERSION = "2023-06-01";

    private final ObjectMapper mapper = new ObjectMapper();
    private final ProxyConfig proxyConfig;
    private final String apiKey;

    public AnthropicHttpClient(ProxyConfig proxyConfig,
                               @Value("${anthropic.api-key:}") String apiKey) {
        this.proxyConfig = proxyConfig;
        this.apiKey = apiKey;
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
                        .url(API_URL)
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
        body.put("model", opts.model());
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
                && proxyConfig.getPort() > 0) {
            Proxy.Type t = "SOCKS".equalsIgnoreCase(proxyConfig.getType())
                    ? Proxy.Type.SOCKS : Proxy.Type.HTTP;
            b.proxy(new Proxy(t, new InetSocketAddress(
                    proxyConfig.getHost(), proxyConfig.getPort())));
        }
        return b.build();
    }
}
