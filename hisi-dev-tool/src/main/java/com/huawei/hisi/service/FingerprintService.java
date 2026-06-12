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

    // Framework class prefixes to exclude
    private static final String[] FRAMEWORK_PREFIXES = {
        "java.", "javax.", "sun.", "org.springframework.",
        "org.apache.", "com.google.", "io.netty.", "reactor.", "org.slf4j."
    };

    private static final String DEFAULT_FINGERPRINT = "00000000000000000000000000000000";

    /**
     * Generate deterministic fingerprint from log content
     * Components: errorType + top3 stack frames (without line numbers)
     */
    public String generateFingerprint(String logContent) {
        if (logContent == null || logContent.isEmpty()) {
            return DEFAULT_FINGERPRINT;
        }

        StringBuilder normalized = new StringBuilder();

        // Extract error type
        Matcher errorMatcher = ERROR_TYPE_PATTERN.matcher(logContent);
        if (errorMatcher.find()) {
            normalized.append(errorMatcher.group(1)).append("|");
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
            }
        }

        // Generate MD5 hash
        return md5Hash(normalized.toString());
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
            return DEFAULT_FINGERPRINT;
        }
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] digest = md.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            log.error("MD5 hash failed", e);
            return DEFAULT_FINGERPRINT;
        }
    }
}