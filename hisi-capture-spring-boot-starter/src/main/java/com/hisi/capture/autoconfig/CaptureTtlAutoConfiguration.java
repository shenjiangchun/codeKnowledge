package com.hisi.capture.autoconfig;

import com.hisi.capture.ttl.CaptureTtlBeanPostProcessor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConditionalOnProperty(prefix = "hisi.capture.ttl", name = "mode", havingValue = "auto", matchIfMissing = true)
public class CaptureTtlAutoConfiguration {

    @Bean
    public CaptureTtlBeanPostProcessor captureTtlBeanPostProcessor() {
        return new CaptureTtlBeanPostProcessor();
    }
}
