package com.hisi.capture.autoconfig;

import com.hisi.capture.config.*;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

/**
 * Capture Starter 根自动配置。
 *
 * 使用 @AutoConfiguration 供 SB 2.7+/3.x 自动注册；其余分领域自动配置类各自
 * 独立列在 AutoConfiguration.imports 与 spring.factories 中，不再通过 @Import 显式拉起，
 * 避免绕过 Spring Boot 自动装配的 conditional 评估顺序。
 */
@AutoConfiguration
@ConditionalOnProperty(prefix = "hisi.capture", name = "enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties({
    CaptureProperties.class,
    CaptureTtlProperties.class,
    CaptureCryptoProperties.class,
    CaptureScanProperties.class,
})
public class CaptureAutoConfiguration {
}
