package com.huawei.hisi.config;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Agent type registry — auto-binds from {@code hisi.agents.*} YAML config.
 */
@Slf4j
@Component
@ConfigurationProperties(prefix = "hisi.agents")
public class AgentTypeRegistry {

    private Map<String, AgentTypeConfig> agents = new LinkedHashMap<>();

    public AgentTypeConfig get(String key) { return agents.get(key); }
    public java.util.Set<String> keys() { return agents.keySet(); }
    public Map<String, AgentTypeConfig> getAgents() { return agents; }
    public void setAgents(Map<String, AgentTypeConfig> agents) {
        this.agents = agents != null ? agents : new LinkedHashMap<>();
    }

    @PostConstruct
    void logBinding() {
        log.info("[AgentTypeRegistry] Loaded {} agent types: {}", agents.size(), agents.keySet());
    }

    /** Per-agent configuration. Must be regular class for relaxed binding. */
    public static class AgentTypeConfig {
        private String systemPrompt;
        private String provider;
        private Integer toolCallLimit;

        public AgentTypeConfig() {}
        public AgentTypeConfig(String systemPrompt, String provider, Integer toolCallLimit) {
            this.systemPrompt = systemPrompt;
            this.provider = provider;
            this.toolCallLimit = toolCallLimit;
        }

        public String getSystemPrompt() { return systemPrompt; }
        public void setSystemPrompt(String systemPrompt) { this.systemPrompt = systemPrompt; }
        public String getProvider() { return provider; }
        public void setProvider(String provider) { this.provider = provider; }
        public Integer getToolCallLimit() { return toolCallLimit; }
        public void setToolCallLimit(Integer toolCallLimit) { this.toolCallLimit = toolCallLimit; }
    }
}
