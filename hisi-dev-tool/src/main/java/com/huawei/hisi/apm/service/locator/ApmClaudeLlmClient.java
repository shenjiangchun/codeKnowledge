package com.huawei.hisi.apm.service.locator;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.huawei.hisi.apm.config.ApmLlmProperties;

/**
 * Dedicated Claude-via-dmxapi LLM client for the APM Failure Locator pipeline.
 *
 * <p>Bypasses {@link com.huawei.hisi.service.UnifiedTextService} (which targets
 * cheap glm-4-flash for KG description generation) and instead talks directly
 * to an OpenAI-compatible {@code /v1/chat/completions} endpoint.
 *
 * <p>Enforces a hard global concurrency limit via a fair {@link Semaphore} so
 * runaway diagnose requests cannot exhaust the upstream rate budget.
 */
@Component
@Primary
@ConditionalOnExpression("'${hisi.apm.diagnose.llm.api-key:}' != ''")
public class ApmClaudeLlmClient implements LlmClient {

    private static final Logger LOG = LoggerFactory.getLogger(ApmClaudeLlmClient.class);
    private static final String CHAT_PATH = "/chat/completions";
    private static final String CHAT_PATH_FULL = "/v1/chat/completions";

    private final ApmLlmProperties props;
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;
    private final Semaphore concurrency;

    @Autowired
    public ApmClaudeLlmClient(ApmLlmProperties props) {
        this(props, buildRestTemplate(props), new ObjectMapper());
    }

    /** Test-friendly constructor that accepts a pre-configured RestTemplate. */
    public ApmClaudeLlmClient(ApmLlmProperties props, RestTemplate restTemplate, ObjectMapper objectMapper) {
        this.props = props;
        this.restTemplate = restTemplate;
        this.objectMapper = objectMapper;
        this.concurrency = new Semaphore(props.getMaxConcurrency(), true);
    }

    private static RestTemplate buildRestTemplate(ApmLlmProperties props) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        int millis = Math.toIntExact(Duration.ofSeconds(props.getTimeoutSeconds()).toMillis());
        factory.setConnectTimeout(millis);
        factory.setReadTimeout(millis);
        return new RestTemplate(factory);
    }

    @PostConstruct
    void logActivation() {
        LOG.info("ApmClaudeLlmClient activated - model={}, maxConcurrency={}",
                props.getModel(), props.getMaxConcurrency());
    }

    @Override
    public String chat(String systemPrompt, String userPrompt) {
        if (props.getApiKey() == null || props.getApiKey().isBlank()) {
            throw new DiagnoseLlmException(
                    "LLM api-key is blank - configure hisi.apm.diagnose.llm.api-key", null);
        }

        long acquireTimeout = (long) props.getTimeoutSeconds() + 5L;
        boolean acquired;
        try {
            acquired = concurrency.tryAcquire(acquireTimeout, TimeUnit.SECONDS);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new DiagnoseLlmException("LLM call interrupted while waiting for concurrency slot", ie);
        }
        if (!acquired) {
            throw new DiagnoseLlmException("LLM concurrency limit exceeded", null);
        }

        try {
            LOG.debug("LLM call begin - sysChars={}, userChars={}",
                    systemPrompt == null ? 0 : systemPrompt.length(),
                    userPrompt == null ? 0 : userPrompt.length());

            String url = resolveChatUrl(props.getBaseUrl());
            String body = buildRequestBody(systemPrompt, userPrompt);

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.set(HttpHeaders.AUTHORIZATION, "Bearer " + props.getApiKey());

            HttpEntity<String> entity = new HttpEntity<>(body, headers);
            ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.POST, entity, String.class);

            String content = parseContent(response.getBody());
            LOG.debug("LLM call done - respChars={}", content.length());
            return content;
        } catch (HttpStatusCodeException ex) {
            throw new DiagnoseLlmException(
                    "LLM upstream error: " + ex.getStatusCode().value() + " " + safeBody(ex.getResponseBodyAsString()),
                    ex);
        } catch (ResourceAccessException ex) {
            throw new DiagnoseLlmException("LLM network/timeout error: " + ex.getMessage(), ex);
        } catch (DiagnoseLlmException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new DiagnoseLlmException("LLM unexpected error: " + ex.getMessage(), ex);
        } finally {
            concurrency.release();
        }
    }

    private String buildRequestBody(String systemPrompt, String userPrompt) {
        Map<String, Object> req = new LinkedHashMap<>();
        req.put("model", props.getModel());
        req.put("messages", List.of(
                Map.of("role", "system", "content", systemPrompt == null ? "" : systemPrompt),
                Map.of("role", "user", "content", userPrompt == null ? "" : userPrompt)
        ));
        req.put("temperature", props.getTemperature());
        req.put("max_tokens", props.getMaxTokens());
        try {
            return objectMapper.writeValueAsString(req);
        } catch (Exception ex) {
            throw new DiagnoseLlmException("Failed to serialise LLM request body", ex);
        }
    }

    private String parseContent(String responseBody) {
        if (responseBody == null || responseBody.isBlank()) {
            throw new DiagnoseLlmInvalidResponseException("Empty LLM response body", null);
        }
        try {
            JsonNode root = objectMapper.readTree(responseBody);
            JsonNode choices = root.path("choices");
            if (!choices.isArray() || choices.isEmpty()) {
                throw new DiagnoseLlmInvalidResponseException("LLM response missing 'choices'", null);
            }
            JsonNode content = choices.get(0).path("message").path("content");
            if (content.isMissingNode() || content.isNull()) {
                throw new DiagnoseLlmInvalidResponseException(
                        "LLM response missing choices[0].message.content", null);
            }
            return content.asText();
        } catch (DiagnoseLlmInvalidResponseException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new DiagnoseLlmInvalidResponseException(
                    "Failed to parse LLM response: " + ex.getMessage(), ex);
        }
    }

    private static String trimTrailingSlash(String url) {
        if (url == null) {
            return "";
        }
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    /**
     * Resolve the full chat-completions URL, tolerant of whether {@code baseUrl}
     * already includes the {@code /v1} segment. Mirrors the convention used by
     * {@link com.huawei.hisi.ram.sdk.impl.AnthropicHttpClient}.
     *
     * <p>Examples:
     * <ul>
     *   <li>{@code http://h/v1} → {@code http://h/v1/chat/completions}</li>
     *   <li>{@code http://h} → {@code http://h/v1/chat/completions}</li>
     *   <li>{@code http://h/v1/} → {@code http://h/v1/chat/completions}</li>
     * </ul>
     */
    static String resolveChatUrl(String baseUrl) {
        String trimmed = trimTrailingSlash(baseUrl);
        if (trimmed.endsWith("/v1")) {
            return trimmed + CHAT_PATH;
        }
        if (trimmed.endsWith(CHAT_PATH_FULL)) {
            return trimmed;
        }
        return trimmed + CHAT_PATH_FULL;
    }

    private static String safeBody(String body) {
        if (body == null) {
            return "";
        }
        return body.length() > 512 ? body.substring(0, 512) + "...(truncated)" : body;
    }

    /** Visible for testing. */
    int availablePermits() {
        return concurrency.availablePermits();
    }
}
