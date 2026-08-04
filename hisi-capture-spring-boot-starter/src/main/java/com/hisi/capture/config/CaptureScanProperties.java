package com.hisi.capture.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * 扫描配置属性：hisi.capture.scan.*
 */
@ConfigurationProperties(prefix = "hisi.capture.scan")
public class CaptureScanProperties {

    /** 需要采集的 URI 白名单（空 = 全部采集） */
    private List<String> uriWhitelist = new ArrayList<>();

    /** 需要排除的 URI 黑名单 */
    private List<String> uriBlacklist = new ArrayList<>();

    /** 采样率 0.0~1.0 */
    private double sampleRate = 1.0;

    public List<String> getUriWhitelist() { return uriWhitelist; }
    public void setUriWhitelist(List<String> uriWhitelist) { this.uriWhitelist = uriWhitelist; }
    public List<String> getUriBlacklist() { return uriBlacklist; }
    public void setUriBlacklist(List<String> uriBlacklist) { this.uriBlacklist = uriBlacklist; }
    public double getSampleRate() { return sampleRate; }
    public void setSampleRate(double sampleRate) { this.sampleRate = sampleRate; }
}
