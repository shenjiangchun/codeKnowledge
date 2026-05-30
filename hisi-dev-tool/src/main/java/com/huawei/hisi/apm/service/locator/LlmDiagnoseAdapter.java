package com.huawei.hisi.apm.service.locator;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.huawei.hisi.apm.config.ApmDiagnoseProperties;
import com.huawei.hisi.apm.model.ApmSpanEntity;
import com.huawei.hisi.apm.model.DiagnoseReport.EvidenceAnchor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Production {@link LlmDiagnoser} implementation. Builds a prompt via
 * {@link FailureLocatorPromptBuilder}, calls the configured {@link LlmClient}
 * with a per-call timeout, and parses the response into an {@link LlmResult}.
 *
 * <p>This bean is {@link Primary} so it overrides the {@code stubLlmDiagnoser}
 * registered in {@code ApmDiagnoseConfig} when both an {@link LlmClient} bean
 * exists and {@code hisi.apm.diagnose.llmEnabled} is not explicitly false.
 *
 * <p>All failure modes are mapped to subclasses of {@link DiagnoseLlmException}
 * so {@link FailureLocatorService#mapException} can translate them to canonical
 * {@code ApmErrorCode} values.
 *
 * @author HiSi DevTool Team
 */
@Component
@Primary
@ConditionalOnBean(LlmClient.class)
@ConditionalOnProperty(prefix = "hisi.apm.diagnose", name = "llmEnabled",
        havingValue = "true", matchIfMissing = true)
public class LlmDiagnoseAdapter implements LlmDiagnoser {

    private static final Logger LOG = LoggerFactory.getLogger(LlmDiagnoseAdapter.class);

    private static final String DEFAULT_ROOT_CAUSE = "Could not extract root cause";
    private static final double DEFAULT_CONFIDENCE = 0.3;
    private static final int RAW_SNIPPET_FOR_ERROR = 200;

    private final FailureLocatorPromptBuilder builder;
    private final LlmClient client;
    private final ApmDiagnoseProperties props;
    private final ObjectMapper objectMapper;

    /**
     * @param builder       prompt builder
     * @param client        LLM transport (typically wraps UnifiedTextService)
     * @param props         diagnose configuration (timeout in seconds)
     * @param objectMapper  Jackson mapper used to parse the JSON response
     */
    public LlmDiagnoseAdapter(FailureLocatorPromptBuilder builder,
                              LlmClient client,
                              ApmDiagnoseProperties props,
                              ObjectMapper objectMapper) {
        this.builder = Objects.requireNonNull(builder, "builder");
        this.client = Objects.requireNonNull(client, "client");
        this.props = Objects.requireNonNull(props, "props");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
    }

    @Override
    public LlmResult diagnose(String projectPath,
                              List<ApmSpanEntity> exceptionSpans,
                              List<EvidenceAnchor> kgEvidence,
                              String userNote) {
        Objects.requireNonNull(projectPath, "projectPath");
        Objects.requireNonNull(exceptionSpans, "exceptionSpans");
        Objects.requireNonNull(kgEvidence, "kgEvidence");

        FailureLocatorPromptBuilder.PromptPayload p =
                builder.build(projectPath, exceptionSpans, kgEvidence, userNote);
        try {
            String raw = callWithTimeout(p, props.getLlmTimeoutSeconds());
            return parse(raw);
        } catch (DiagnoseLlmException e) {
            throw e;
        } catch (TimeoutException te) {
            LOG.warn("LLM diagnose timed out after {}s", props.getLlmTimeoutSeconds());
            throw new DiagnoseLlmTimeoutException(
                    "LLM timed out after " + props.getLlmTimeoutSeconds() + "s", te);
        } catch (Exception e) {
            LOG.warn("LLM diagnose failed: {}", e.getMessage());
            throw new DiagnoseLlmException("LLM call failed: " + e.getMessage(), e);
        }
    }

    /**
     * Run the LLM call on a background thread, blocking up to {@code timeoutSeconds}.
     * Visible for testing.
     *
     * @param payload         the assembled prompt payload
     * @param timeoutSeconds  per-call deadline in seconds
     * @return raw LLM response text
     * @throws TimeoutException if the deadline elapses
     * @throws InterruptedException if interrupted while waiting
     * @throws ExecutionException if the underlying call throws
     */
    String callWithTimeout(FailureLocatorPromptBuilder.PromptPayload payload, int timeoutSeconds)
            throws InterruptedException, ExecutionException, TimeoutException {
        CompletableFuture<String> future = CompletableFuture.supplyAsync(
                () -> client.chat(payload.systemPrompt(), payload.userPrompt()));
        try {
            return future.get(timeoutSeconds, TimeUnit.SECONDS);
        } catch (TimeoutException te) {
            future.cancel(true);
            throw te;
        } catch (ExecutionException ee) {
            Throwable cause = ee.getCause();
            if (cause instanceof DiagnoseLlmException dle) {
                throw dle;
            }
            throw ee;
        }
    }

    /**
     * Parse the raw LLM response into an {@link LlmResult}.
     * Visible for testing.
     *
     * @param raw raw assistant text (may include code-fence markers)
     * @return parsed result with clamped confidence
     */
    LlmResult parse(String raw) {
        String body = stripFences(raw == null ? "" : raw.trim());
        try {
            JsonNode root = objectMapper.readTree(body);
            String md = root.hasNonNull("rootCauseMarkdown")
                    ? root.get("rootCauseMarkdown").asText()
                    : DEFAULT_ROOT_CAUSE;
            double conf = root.hasNonNull("confidence")
                    ? root.get("confidence").asDouble(DEFAULT_CONFIDENCE)
                    : DEFAULT_CONFIDENCE;
            double clamped = Math.max(0.0, Math.min(1.0, conf));
            return new LlmResult(md, clamped);
        } catch (Exception ex) {
            throw new DiagnoseLlmInvalidResponseException(
                    "LLM response unparseable: " + truncate(raw, RAW_SNIPPET_FOR_ERROR), ex);
        }
    }

    private static String stripFences(String s) {
        if (s == null || s.isEmpty()) {
            return s;
        }
        String t = s.trim();
        if (t.startsWith("```")) {
            int firstNl = t.indexOf('\n');
            if (firstNl >= 0) {
                t = t.substring(firstNl + 1);
            } else {
                t = t.substring(3);
            }
            if (t.endsWith("```")) {
                t = t.substring(0, t.length() - 3);
            }
        }
        return t.trim();
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }
}
