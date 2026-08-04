package com.huawei.hisi.ram.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.Map;

@Configuration
@ConfigurationProperties(prefix = "chat")
@Data
public class ChatModelProperties {
    private Map<String, ModelSpec> models;
    /**
     * Explicit override for the default model id. Bound from
     * {@code chat.default-model}. When blank/null, {@link #defaultModelId()}
     * falls back to the first declared model in {@link #models}.
     */
    private String defaultModel;

    /**
     * Resolve the model id the orchestrator should use when no explicit
     * scenario-driven override applies.
     *
     * <p>Resolution order:
     * <ol>
     *     <li>{@code chat.default-model} if set and non-blank;</li>
     *     <li>the first key of {@link #models} (LinkedHashMap preserves YAML
     *     declaration order, so this is the first model declared in
     *     {@code chat-models.yml});</li>
     *     <li>literal {@code "glm-5.1"} legacy fallback (matches the
     *     pre-Phase-B hardcoded constant) when config is empty.</li>
     * </ol>
     */
    public String defaultModelId() {
        if (defaultModel != null && !defaultModel.isBlank()) {
            return defaultModel;
        }
        if (models != null && !models.isEmpty()) {
            return models.keySet().iterator().next();
        }
        return "glm-5.1";
    }

    @Data
    public static class ModelSpec {
        private String provider;
        private int maxContext;
        private Map<String, Integer> scenarioMaxTokens;
        private String endpoint;
        private String apiKey;
    }
}
