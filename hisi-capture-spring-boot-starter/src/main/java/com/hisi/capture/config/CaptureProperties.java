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

    /** 最大 Span 栈深度保护（超过则跳过采集，防止栈溢出） */
    private int maxSpanDepth = 50;

    /** 返回值最大序列化字节数（超过则截断） */
    private int maxRetSize = 4096;

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public int getMaxArgSize() { return maxArgSize; }
    public void setMaxArgSize(int maxArgSize) { this.maxArgSize = maxArgSize; }
    public int getMaxBodySize() { return maxBodySize; }
    public void setMaxBodySize(int maxBodySize) { this.maxBodySize = maxBodySize; }
    public boolean isSilentCatchEnabled() { return silentCatchEnabled; }
    public void setSilentCatchEnabled(boolean silentCatchEnabled) { this.silentCatchEnabled = silentCatchEnabled; }
    public int getMaxSpanDepth() { return maxSpanDepth; }
    public void setMaxSpanDepth(int maxSpanDepth) { this.maxSpanDepth = maxSpanDepth; }
    public int getMaxRetSize() { return maxRetSize; }
    public void setMaxRetSize(int maxRetSize) { this.maxRetSize = maxRetSize; }
}
