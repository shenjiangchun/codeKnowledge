package com.huawei.hisi.config;

import jakarta.annotation.PostConstruct;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 代码分析功能开关配置
 * 用于控制新旧逻辑的切换，确保重构安全
 *
 * @author HiAPM Plugin Team
 * @version 1.0.0
 */
@Data
@Slf4j
@Configuration
@ConfigurationProperties(prefix = "analysis.features")
public class AnalysisFeatureConfig {

    /**
     * 是否启用增强版接口实现解析
     * - true: 使用新的完整限定名解析逻辑
     * - false: 使用原有的简单名称解析逻辑（默认）
     */
    private boolean enhancedInterfaceResolution = false;

    /**
     * 是否启用增强版字段调用解析
     * - true: 增强对 @Autowired、@Lazy 等注入字段的调用链识别
     * - false: 使用原有的字段解析逻辑（默认）
     */
    private boolean enhancedFieldCallResolution = false;

    /**
     * 是否启用 Spring 注解感知调用链分析
     * - true: 识别 @Async、@Transactional 等 Spring 注解的代理调用
     * - false: 不处理 Spring 代理调用（默认）
     */
    private boolean springAnnotationAware = false;

    /**
     * 是否启用调试日志
     * - true: 输出详细的类型解析日志，便于排查问题
     * - false: 只输出常规日志（默认）
     */
    private boolean debugLogging = false;

    @PostConstruct
    public void logConfig() {
        log.info("AnalysisFeatureConfig loaded: enhancedInterfaceResolution={}, enhancedFieldCallResolution={}, springAnnotationAware={}, debugLogging={}",
            enhancedInterfaceResolution, enhancedFieldCallResolution, springAnnotationAware, debugLogging);
    }
}
