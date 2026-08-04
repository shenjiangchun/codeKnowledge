package com.hisi.capture.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * TTL 配置属性：hisi.capture.ttl.*
 */
@ConfigurationProperties(prefix = "hisi.capture.ttl")
public class CaptureTtlProperties {

    /** TTL 模式：auto（默认）/ agent / explicit */
    private String mode = "auto";

    public String getMode() { return mode; }
    public void setMode(String mode) { this.mode = mode; }
}
