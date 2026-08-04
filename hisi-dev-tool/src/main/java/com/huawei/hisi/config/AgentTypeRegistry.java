package com.huawei.hisi.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Agent type registry — auto-binds from {@code hisi.agents.*} YAML config.
 * Falls back to hardcoded defaults when YAML relaxed binding is inactive.
 */
@Slf4j
@Component
@ConfigurationProperties(prefix = "hisi.agents")
public class AgentTypeRegistry {

    private Map<String, AgentTypeConfig> agents = new LinkedHashMap<>();

    public AgentTypeConfig get(String key) {
        ensureDefaults();
        return agents.get(key);
    }

    public Set<String> keys() {
        ensureDefaults();
        return agents.keySet();
    }

    public Map<String, AgentTypeConfig> getAgents() {
        ensureDefaults();
        return agents;
    }

    public void setAgents(Map<String, AgentTypeConfig> agents) {
        this.agents = agents != null ? agents : new LinkedHashMap<>();
        if (this.agents.isEmpty()) {
            log.info("[AgentTypeRegistry] YAML binding returned empty map");
        } else {
            log.info("[AgentTypeRegistry] Loaded {} agent types from YAML: {}",
                    this.agents.size(), this.agents.keySet());
        }
    }

    /** Lazy fallback — Spring Boot 3.5 @ConfigurationProperties may set
     *  empty map after constructor. Populate defaults on first access. */
    private void ensureDefaults() {
        if (!agents.isEmpty()) return;
        synchronized (this) {
            if (!agents.isEmpty()) return;
            log.warn("[AgentTypeRegistry] Empty — using hardcoded defaults");
            agents.put("apm-diagnose", new AgentTypeConfig(
                    "你是APM诊断专家。根据异常日志和调用链信息定位根因并给出修复建议。", "anthropic", null));
            agents.put("call-chain-analysis", new AgentTypeConfig(
                    "你是调用链分析专家。追踪方法间调用关系，识别性能瓶颈和循环依赖。", "anthropic", null));
            agents.put("log-analysis", new AgentTypeConfig(
                    "你是日志分析专家。从日志中提取错误模式并关联到代码。", "anthropic", null));
            agents.put("code-analysis", new AgentTypeConfig(
                    "你是代码变更分析专家。分析Git提交记录并评估影响范围。", "anthropic", null));
            agents.put("dialog", new AgentTypeConfig(
                    "你是自然语言交互助手。理解用户意图并调用对应功能。", "anthropic", null));
            agents.put("fix", new AgentTypeConfig(
                    "你是代码修复专家。根据异常信息和测试用例定位并修复代码缺陷。", "anthropic", 5));
        }
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
