package com.huawei.hisi.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 日志指纹服务
 * 生成确定性指纹用于快速去重
 *
 * 指纹组成：错误类型 + 前3个非框架栈帧（类名+方法名，不含行号）
 */
@Slf4j
@Service
public class FingerprintService {

    // Stack frame pattern: at package.Class.method(File.java:line)
    private static final Pattern STACK_FRAME_PATTERN =
        Pattern.compile("at\\s+([\\w.]+)\\.([\\w]+|<[\\w]+>)\\([\\w.]+:\\d+\\)");

    // Error type pattern: java.lang.ExceptionType: message
    private static final Pattern ERROR_TYPE_PATTERN =
        Pattern.compile("^([\\w.]+Exception|[\\w.]+Error|[\\w.]+Throwable):");

    // Framework class prefixes to exclude（扩展列表，参考实际日志分析经验）
    private static final String[] FRAMEWORK_PREFIXES = {
        // Java 标准库
        "java.", "javax.", "sun.", "com.sun.",
        // Jakarta（Servlet 新标准）
        "jakarta.",
        // Spring 框架
        "org.springframework.", "org.mybatis.spring",
        // Apache 框架
        "org.apache.",
        // AWS SDK
        "com.amazonaws.",
        // Feign HTTP 客户端
        "feign.", "reactor.feign.",
        // 其他常见框架
        "com.google.", "io.netty.", "reactor.", "org.slf4j.",
        "ch.qos.logback.", "com.fasterxml.", "com.grapecity.",
        // 华为内部框架（非项目代码）
        "com.huawei.opengauss.", "com.huawei.it.jalor5."
    };

    private static final String DEFAULT_FINGERPRINT = "00000000000000000000000000000000";

    /**
     * Generate deterministic fingerprint from log content
     * Components: errorType + top3 stack frames (without line numbers)
     */
    public String generateFingerprint(String logContent) {
        if (logContent == null || logContent.isEmpty()) {
            log.warn("[FingerprintService] logContent is null or empty, returning default fingerprint");
            return DEFAULT_FINGERPRINT;
        }

        log.info("[FingerprintService] 输入长度: {}, 前200字符: {}",
                logContent.length(),
                logContent.length() > 200 ? logContent.substring(0, 200) + "..." : logContent);

        StringBuilder normalized = new StringBuilder();

        // Extract error type
        Matcher errorMatcher = ERROR_TYPE_PATTERN.matcher(logContent);
        if (errorMatcher.find()) {
            normalized.append(errorMatcher.group(1)).append("|");
            log.info("[FingerprintService] 提取到错误类型: {}", errorMatcher.group(1));
        } else {
            log.info("[FingerprintService] 未找到错误类型模式");
        }

        // Extract top 3 stack frames (class + method only, no line numbers)
        Matcher frameMatcher = STACK_FRAME_PATTERN.matcher(logContent);
        int frameCount = 0;
        while (frameMatcher.find() && frameCount < 3) {
            String className = frameMatcher.group(1);
            String methodName = frameMatcher.group(2);
            // Exclude framework classes
            if (!isFrameworkClass(className)) {
                normalized.append(className).append(".").append(methodName).append("|");
                frameCount++;
                log.info("[FingerprintService] 提取到栈帧 #{}: {}.{}", frameCount, className, methodName);
            }
        }

        if (frameCount == 0) {
            log.warn("[FingerprintService] 未找到有效的非框架栈帧");
        }

        // Generate MD5 hash
        String normalizedStr = normalized.toString();
        log.info("[FingerprintService] normalized长度: {}, 内容: {}",
                normalizedStr.length(),
                normalizedStr.length() > 100 ? normalizedStr.substring(0, 100) + "..." : normalizedStr);

        return md5Hash(normalizedStr);
    }

    private boolean isFrameworkClass(String className) {
        for (String prefix : FRAMEWORK_PREFIXES) {
            if (className.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    private String md5Hash(String input) {
        if (input.isEmpty()) {
            log.warn("[FingerprintService] normalized input is empty, returning default fingerprint");
            return DEFAULT_FINGERPRINT;
        }
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] digest = md.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            String fingerprint = sb.toString();
            log.info("[FingerprintService] 生成的指纹: {}", fingerprint);
            return fingerprint;
        } catch (Exception e) {
            log.error("[FingerprintService] MD5 hash failed", e);
            return DEFAULT_FINGERPRINT;
        }
    }
}