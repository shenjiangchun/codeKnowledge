package com.huawei.hisi.scheduler;

import com.huawei.hisi.model.AppLogConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * AppLogConfig 单元测试
 */
class AppLogConfigTest {

    @Test
    @DisplayName("配置实体 - Builder 测试")
    void testConfigBuilder() {
        AppLogConfig config = AppLogConfig.builder()
            .id(1L)
            .appId("hiapm")
            .projectPath("/path/to/project")
            .dslQuery("{\"query\": {\"match\": {\"level\": \"ERROR\"}}}")
            .pullIntervalMinutes(10)
            .enabled(true)
            .build();

        assertEquals(1L, config.getId());
        assertEquals("hiapm", config.getAppId());
        assertEquals("/path/to/project", config.getProjectPath());
        assertEquals(10, config.getPullIntervalMinutes());
        assertTrue(config.getEnabled());
    }

    @Test
    @DisplayName("配置实体 - 默认值")
    void testConfigDefaults() {
        AppLogConfig config = new AppLogConfig();
        assertNull(config.getId());
        assertNull(config.getAppId());
    }
}