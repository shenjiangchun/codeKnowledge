package com.hisi.capture.autoconfig;

import com.hisi.capture.config.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

@Configuration
@ConditionalOnProperty(prefix = "hisi.capture", name = "enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties({
    CaptureProperties.class,
    CaptureTtlProperties.class,
    CaptureCryptoProperties.class,
    CaptureScanProperties.class,
})
@Import({
    CaptureWebAutoConfiguration.class,
    CaptureAsyncAutoConfiguration.class,
    CaptureScheduledAutoConfiguration.class,
    CaptureFeignAutoConfiguration.class,
    CaptureAopAutoConfiguration.class,
    CaptureExceptionAutoConfiguration.class,
    CaptureCryptoAutoConfiguration.class,
    CaptureTtlAutoConfiguration.class,
})
public class CaptureAutoConfiguration {
}
