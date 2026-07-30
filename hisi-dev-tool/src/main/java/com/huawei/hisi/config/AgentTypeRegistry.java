package com.huawei.hisi.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Agent type registry — auto-binds from {@code hisi.agents.*} YAML config.
 */
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

    /** Per-agent configuration auto-bound from YAML. */
    public record AgentTypeConfig(String systemPrompt, String provider, Integer toolCallLimit) {}
}
