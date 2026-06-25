package com.huawei.hisi.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.*;

/**
 * FingerprintService 单元测试
 * 测试日志指纹生成和去重功能
 */
class FingerprintServiceTest {

    private FingerprintService fingerprintService;

    @BeforeEach
    void setUp() {
        fingerprintService = new FingerprintService();
    }

    // ==================== Fingerprint Generation Tests ====================

    @Test
    @DisplayName("生成指纹 - 从堆栈跟踪生成确定性指纹")
    void testGenerateFingerprintFromStackTrace() {
        String stackTrace = """
            java.lang.NullPointerException: Cannot invoke method on null object
                at com.example.service.UserService.getUserProfile(UserService.java:45)
                at com.example.controller.UserController.handleRequest(UserController.java:120)
                at org.springframework.web.servlet.FrameworkServlet.service(FrameworkServlet.java:897)
            """;

        String fingerprint = fingerprintService.generateFingerprint(stackTrace);

        // Same stack trace should produce same fingerprint
        String fingerprint2 = fingerprintService.generateFingerprint(stackTrace);
        assertThat(fingerprint).isEqualTo(fingerprint2);

        // Fingerprint should be 32-char MD5
        assertThat(fingerprint).hasSize(32);
    }

    @Test
    @DisplayName("生成指纹 - 不同行号应产生相同指纹")
    void testFingerprintExcludesLineNumbers() {
        String stackTrace1 = """
            java.lang.NullPointerException: null object
                at com.example.service.UserService.getUserProfile(UserService.java:45)
            """;
        String stackTrace2 = """
            java.lang.NullPointerException: null object
                at com.example.service.UserService.getUserProfile(UserService.java:50)
            """;

        // Same method signature, different line numbers → same fingerprint
        assertThat(fingerprintService.generateFingerprint(stackTrace1))
            .isEqualTo(fingerprintService.generateFingerprint(stackTrace2));
    }

    @Test
    @DisplayName("生成指纹 - 不同方法名应产生不同指纹")
    void testFingerprintDifferentMethods() {
        String stackTrace1 = """
            java.lang.NullPointerException: null
                at com.example.service.UserService.getUserProfile(UserService.java:45)
            """;
        String stackTrace2 = """
            java.lang.NullPointerException: null
                at com.example.service.UserService.createUser(UserService.java:30)
            """;

        assertThat(fingerprintService.generateFingerprint(stackTrace1))
            .isNotEqualTo(fingerprintService.generateFingerprint(stackTrace2));
    }

    @Test
    @DisplayName("生成指纹 - 空输入返回默认指纹")
    void testFingerprintEmptyInput() {
        String fingerprint = fingerprintService.generateFingerprint(null);
        assertThat(fingerprint).isEqualTo("00000000000000000000000000000000");

        fingerprint = fingerprintService.generateFingerprint("");
        assertThat(fingerprint).isEqualTo("00000000000000000000000000000000");
    }

    @Test
    @DisplayName("生成指纹 - 框架类被排除")
    void testFingerprintExcludesFrameworkClasses() {
        String stackTrace = """
            java.lang.NullPointerException: null
                at org.springframework.web.servlet.FrameworkServlet.service(FrameworkServlet.java:897)
                at java.lang.Thread.run(Thread.java:748)
            """;

        // Only framework classes → should use error type only
        String fingerprint = fingerprintService.generateFingerprint(stackTrace);
        assertThat(fingerprint).hasSize(32);
    }

    @Test
    @DisplayName("生成指纹 - 取前3个非框架栈帧")
    void testFingerprintTop3NonFrameworkFrames() {
        String stackTrace4Frames = """
            java.lang.NullPointerException: null
                at com.example.service.UserService.method1(UserService.java:10)
                at com.example.service.UserService.method2(UserService.java:20)
                at com.example.service.UserService.method3(UserService.java:30)
                at com.example.service.UserService.method4(UserService.java:40)
                at org.springframework.web.servlet.FrameworkServlet.service(FrameworkServlet.java:897)
            """;

        String stackTrace3Frames = """
            java.lang.NullPointerException: null
                at com.example.service.UserService.method1(UserService.java:10)
                at com.example.service.UserService.method2(UserService.java:20)
                at com.example.service.UserService.method3(UserService.java:30)
                at org.springframework.web.servlet.FrameworkServlet.service(FrameworkServlet.java:897)
            """;

        // 4th frame should be excluded, so fingerprints should match
        assertThat(fingerprintService.generateFingerprint(stackTrace4Frames))
            .isEqualTo(fingerprintService.generateFingerprint(stackTrace3Frames));
    }

    @Test
    @DisplayName("生成指纹 - 日志带时间戳前缀也能正确匹配")
    void testFingerprintWithTimestampPrefix() {
        // Real-world log format with timestamp and log level prefix
        String logWithPrefix = """
            2024-01-15 10:30:45.123 ERROR [thread-1] --- java.lang.NullPointerException: Cannot invoke method on null object
                at com.example.service.UserService.getUserProfile(UserService.java:45)
                at com.example.controller.UserController.handleRequest(UserController.java:120)
            """;

        String logWithoutPrefix = """
            java.lang.NullPointerException: Cannot invoke method on null object
                at com.example.service.UserService.getUserProfile(UserService.java:45)
                at com.example.controller.UserController.handleRequest(UserController.java:120)
            """;

        // Both should produce the same fingerprint (error type + stack frames)
        assertThat(fingerprintService.generateFingerprint(logWithPrefix))
            .isEqualTo(fingerprintService.generateFingerprint(logWithoutPrefix));
    }

    @Test
    @DisplayName("生成指纹 - Caused by 格式也能正确匹配")
    void testFingerprintWithCausedBy() {
        String logWithCausedBy = """
            java.lang.Exception: Wrapper exception
                at com.example.service.UserService.method1(UserService.java:10)
            Caused by: java.lang.NullPointerException: Root cause
                at com.example.dao.UserDao.findById(UserDao.java:25)
            """;

        // Should extract the first error type (java.lang.Exception) for fingerprint
        // Note: Caused by matching uses first match, not last
        String fingerprint = fingerprintService.generateFingerprint(logWithCausedBy);
        assertThat(fingerprint).hasSize(32);
        // Verify it's not the default fingerprint
        assertThat(fingerprint).isNotEqualTo("00000000000000000000000000000000");
    }
}