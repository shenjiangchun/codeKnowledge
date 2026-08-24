package com.huawei.hisi.neo4j.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 外部 rerank 精排服务配置。
 *
 * <p>Bound to {@code search.rerank} in application.yml.
 * 开关 {@code enabled} 默认 {@code false}：关闭时检索行为与无 rerank 时完全一致。</p>
 */
@Component
@ConfigurationProperties(prefix = "search.rerank")
public class RerankProperties {

    /** 是否启用 rerank 精排（默认关闭）。 */
    private boolean enabled = false;

    /** rerank 服务 base-url（OpenAI 兼容 /rerank 端点）。 */
    private String baseUrl;

    /** rerank 模型名。 */
    private String model;

    /** rerank 服务 API Key（敏感，走环境变量占位）。 */
    private String apiKey;

    // --- Getters / Setters ---

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }
}
