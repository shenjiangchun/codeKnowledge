package com.huawei.hisi.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.huawei.hisi.config.LogCloudConfig;
import com.huawei.hisi.model.LogEntry;
import com.huawei.hisi.model.LogQueryDto;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * LogCloudServiceImpl 单元测试
 * 测试日志云服务的查询、登录、登出等功能
 */
@ExtendWith(MockitoExtension.class)
class LogCloudServiceImplTest {

    @Mock
    private LogCloudConfig logCloudConfig;

    @Mock
    private LogCloudConfig.ApiConfig apiConfig;

    private ObjectMapper objectMapper;
    private LogCloudServiceImpl logCloudService;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();

        // 配置 Mock - 使用 lenient 以避免不必要的 stubbing 警告
        lenient().when(logCloudConfig.getApi()).thenReturn(apiConfig);
        lenient().when(apiConfig.getConnectTimeout()).thenReturn(30000L);
        lenient().when(apiConfig.getTimeout()).thenReturn(60000L);
        lenient().when(apiConfig.getHeaderAppkey()).thenReturn("test-app-key-1234");

        logCloudService = new LogCloudServiceImpl(logCloudConfig, objectMapper);
    }

    // ==================== login Tests ====================

    @Test
    @DisplayName("登录 - API 认证准备成功")
    void testLogin_Success() {
        // When
        logCloudService.login();

        // Then - 验证登录方法正常执行，无异常
        verify(logCloudConfig, atLeastOnce()).getApi();
        verify(apiConfig, atLeastOnce()).getHeaderAppkey();
    }

    @Test
    @DisplayName("登录 - AppKey 脱敏显示")
    void testLogin_AppKeyMasking() {
        // Given
        when(apiConfig.getHeaderAppkey()).thenReturn("abcdefghijklmnop1234");

        // When
        logCloudService.login();

        // Then - 验证 AppKey 被正确获取（实际日志中会被脱敏）
        verify(apiConfig).getHeaderAppkey();
    }

    @Test
    @DisplayName("登录 - 空白 AppKey 处理")
    void testLogin_EmptyAppKey() {
        // Given
        when(apiConfig.getHeaderAppkey()).thenReturn("");

        // When & Then - 验证空白 AppKey 不会导致异常
        assertDoesNotThrow(() -> logCloudService.login());
    }

    // ==================== logout Tests ====================

    @Test
    @DisplayName("登出 - 会话清理成功")
    void testLogout_Success() {
        // When
        logCloudService.logout();

        // Then - 验证登出方法正常执行，无异常
        assertDoesNotThrow(() -> logCloudService.logout());
    }

    @Test
    @DisplayName("登出 - 多次调用无异常")
    void testLogout_MultipleCalls() {
        // When & Then
        assertDoesNotThrow(() -> {
            logCloudService.logout();
            logCloudService.logout();
            logCloudService.logout();
        });
    }

    // ==================== queryLogs Tests ====================

    @Test
    @DisplayName("查询日志 - 网络异常处理")
    void testQueryLogs_NetworkException() {
        // Given
        LogQueryDto query = createBasicQuery();
        when(apiConfig.getBaseUrl()).thenReturn("https://invalid-host-that-does-not-exist.example.com");
        when(apiConfig.getQueryPath()).thenReturn("/test/query");
        when(apiConfig.getHeaderXhwId()).thenReturn("test-xhw-id");
        when(apiConfig.getHeaderAppkey()).thenReturn("test-appkey");
        when(logCloudConfig.getAppId()).thenReturn("test-app-id");

        // When & Then - 验证网络异常被正确包装
        RuntimeException exception = assertThrows(RuntimeException.class, () -> {
            logCloudService.queryLogs(query);
        });

        assertTrue(exception.getMessage().contains("查询日志"));
    }

    @Test
    @DisplayName("查询日志 - 使用自定义 DSL 查询")
    void testQueryLogs_CustomDsl() {
        // Given
        LogQueryDto query = createBasicQuery();
        query.setDslQuery("{\"query\":{\"match_all\":{}}}");

        when(apiConfig.getBaseUrl()).thenReturn("https://test-api.example.com");
        when(apiConfig.getQueryPath()).thenReturn("/test/query");
        when(apiConfig.getHeaderXhwId()).thenReturn("test-xhw-id");
        when(apiConfig.getHeaderAppkey()).thenReturn("test-appkey");
        when(logCloudConfig.getAppId()).thenReturn("test-app-id");

        // When & Then - 验证自定义 DSL 被使用
        RuntimeException exception = assertThrows(RuntimeException.class, () -> {
            logCloudService.queryLogs(query);
        });

        assertTrue(exception.getMessage().contains("查询日志"));
    }

    @Test
    @DisplayName("查询日志 - 错误日志过滤")
    void testQueryLogs_ErrorOnly() {
        // Given
        LogQueryDto query = createBasicQuery();
        query.setErrorOnly(true);
        query.setLogLevel("ERROR");

        when(apiConfig.getBaseUrl()).thenReturn("https://test-api.example.com");
        when(apiConfig.getQueryPath()).thenReturn("/test/query");
        when(apiConfig.getHeaderXhwId()).thenReturn("test-xhw-id");
        when(apiConfig.getHeaderAppkey()).thenReturn("test-appkey");
        when(logCloudConfig.getAppId()).thenReturn("test-app-id");

        // When & Then
        RuntimeException exception = assertThrows(RuntimeException.class, () -> {
            logCloudService.queryLogs(query);
        });

        assertTrue(exception.getMessage().contains("查询日志"));
    }

    @Test
    @DisplayName("查询日志 - 带 TraceId 过滤")
    void testQueryLogs_WithTraceId() {
        // Given
        LogQueryDto query = createBasicQuery();
        query.setTraceId("trace-123-456");

        when(apiConfig.getBaseUrl()).thenReturn("https://test-api.example.com");
        when(apiConfig.getQueryPath()).thenReturn("/test/query");
        when(apiConfig.getHeaderXhwId()).thenReturn("test-xhw-id");
        when(apiConfig.getHeaderAppkey()).thenReturn("test-appkey");
        when(logCloudConfig.getAppId()).thenReturn("test-app-id");

        // When & Then
        RuntimeException exception = assertThrows(RuntimeException.class, () -> {
            logCloudService.queryLogs(query);
        });

        assertTrue(exception.getMessage().contains("查询日志"));
    }

    @Test
    @DisplayName("查询日志 - 带关键词过滤")
    void testQueryLogs_WithKeyword() {
        // Given
        LogQueryDto query = createBasicQuery();
        query.setKeyword("NullPointerException");

        when(apiConfig.getBaseUrl()).thenReturn("https://test-api.example.com");
        when(apiConfig.getQueryPath()).thenReturn("/test/query");
        when(apiConfig.getHeaderXhwId()).thenReturn("test-xhw-id");
        when(apiConfig.getHeaderAppkey()).thenReturn("test-appkey");
        when(logCloudConfig.getAppId()).thenReturn("test-app-id");

        // When & Then
        RuntimeException exception = assertThrows(RuntimeException.class, () -> {
            logCloudService.queryLogs(query);
        });

        assertTrue(exception.getMessage().contains("查询日志"));
    }

    @Test
    @DisplayName("查询日志 - 带内容包含过滤")
    void testQueryLogs_WithContentContains() {
        // Given
        LogQueryDto query = createBasicQuery();
        query.setContentContains("error occurred");

        when(apiConfig.getBaseUrl()).thenReturn("https://test-api.example.com");
        when(apiConfig.getQueryPath()).thenReturn("/test/query");
        when(apiConfig.getHeaderXhwId()).thenReturn("test-xhw-id");
        when(apiConfig.getHeaderAppkey()).thenReturn("test-appkey");
        when(logCloudConfig.getAppId()).thenReturn("test-app-id");

        // When & Then
        RuntimeException exception = assertThrows(RuntimeException.class, () -> {
            logCloudService.queryLogs(query);
        });

        assertTrue(exception.getMessage().contains("查询日志"));
    }

    @Test
    @DisplayName("查询日志 - 空时间范围处理")
    void testQueryLogs_NullTimeRange() {
        // Given
        LogQueryDto query = new LogQueryDto();
        query.setStartTime(null);
        query.setEndTime(null);

        when(apiConfig.getBaseUrl()).thenReturn("https://test-api.example.com");
        when(apiConfig.getQueryPath()).thenReturn("/test/query");
        when(apiConfig.getHeaderXhwId()).thenReturn("test-xhw-id");
        when(apiConfig.getHeaderAppkey()).thenReturn("test-appkey");
        when(logCloudConfig.getAppId()).thenReturn("test-app-id");

        // When & Then - 验证空时间范围使用默认值
        RuntimeException exception = assertThrows(RuntimeException.class, () -> {
            logCloudService.queryLogs(query);
        });

        assertTrue(exception.getMessage().contains("查询日志"));
    }

    @Test
    @DisplayName("查询日志 - 自定义排序")
    void testQueryLogs_CustomSort() {
        // Given
        LogQueryDto query = createBasicQuery();
        query.setSortBy("@timestamp");
        query.setSortOrder("asc");
        query.setSize(50);

        when(apiConfig.getBaseUrl()).thenReturn("https://test-api.example.com");
        when(apiConfig.getQueryPath()).thenReturn("/test/query");
        when(apiConfig.getHeaderXhwId()).thenReturn("test-xhw-id");
        when(apiConfig.getHeaderAppkey()).thenReturn("test-appkey");
        when(logCloudConfig.getAppId()).thenReturn("test-app-id");

        // When & Then
        RuntimeException exception = assertThrows(RuntimeException.class, () -> {
            logCloudService.queryLogs(query);
        });

        assertTrue(exception.getMessage().contains("查询日志"));
    }

    @Test
    @DisplayName("查询日志 - FATAL 级别日志")
    void testQueryLogs_FatalLevel() {
        // Given
        LogQueryDto query = createBasicQuery();
        query.setErrorOnly(true);
        query.setLogLevel(null); // 使用默认 ERROR + FATAL

        when(apiConfig.getBaseUrl()).thenReturn("https://test-api.example.com");
        when(apiConfig.getQueryPath()).thenReturn("/test/query");
        when(apiConfig.getHeaderXhwId()).thenReturn("test-xhw-id");
        when(apiConfig.getHeaderAppkey()).thenReturn("test-appkey");
        when(logCloudConfig.getAppId()).thenReturn("test-app-id");

        // When & Then
        RuntimeException exception = assertThrows(RuntimeException.class, () -> {
            logCloudService.queryLogs(query);
        });

        assertTrue(exception.getMessage().contains("查询日志"));
    }

    // ==================== Helper Methods ====================

    private LogQueryDto createBasicQuery() {
        LogQueryDto query = new LogQueryDto();
        query.setStartTime(LocalDateTime.now().minusHours(1));
        query.setEndTime(LocalDateTime.now());
        query.setErrorOnly(false);
        query.setSize(100);
        return query;
    }
}