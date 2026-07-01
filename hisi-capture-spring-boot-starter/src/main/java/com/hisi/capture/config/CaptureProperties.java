package com.hisi.capture.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 顶层配置属性：hisi.capture.*
 */
@ConfigurationProperties(prefix = "hisi.capture")
public class CaptureProperties {

    /** 总开关，默认 true */
    private boolean enabled = true;

    /** 单个参数最大字节数 */
    private int maxArgSize = 1024;

    /** HTTP body 最大字节数 */
    private int maxBodySize = 4096;

    /** silent_catch 检测开关 */
    private boolean silentCatchEnabled = true;

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public int getMaxArgSize() { return maxArgSize; }
    public void setMaxArgSize(int maxArgSize) { this.maxArgSize = maxArgSize; }
    public int getMaxBodySize() { return maxBodySize; }
    public void setMaxBodySize(int maxBodySize) { this.maxBodySize = maxBodySize; }
    public boolean isSilentCatchEnabled() { return silentCatchEnabled; }
    public void setSilentCatchEnabled(boolean silentCatchEnabled) { this.silentCatchEnabled = silentCatchEnabled; }
}
