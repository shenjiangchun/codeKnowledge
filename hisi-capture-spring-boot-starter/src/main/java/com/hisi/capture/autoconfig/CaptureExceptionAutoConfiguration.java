package com.hisi.capture.autoconfig;

import com.hisi.capture.exception.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class CaptureExceptionAutoConfiguration {

    @Bean
    public CaptureExceptionEnricher captureExceptionEnricher() {
        return new CaptureExceptionEnricher();
    }

    @Bean
    public CaptureControllerAdvice captureControllerAdvice() {
        return new CaptureControllerAdvice();
    }

    @Bean
    public CaptureAsyncUncaughtExceptionHandler captureAsyncUncaughtExceptionHandler() {
        return new CaptureAsyncUncaughtExceptionHandler();
    }

    @Bean
    public CaptureUncaughtExceptionHandler captureUncaughtExceptionHandler() {
        return new CaptureUncaughtExceptionHandler();
    }

    @Bean
    @ConditionalOnProperty(prefix = "hisi.capture", name = "silent-catch-enabled", havingValue = "true", matchIfMissing = true)
    public SilentCatchDetector silentCatchDetector() {
        return new SilentCatchDetector();
    }
}
