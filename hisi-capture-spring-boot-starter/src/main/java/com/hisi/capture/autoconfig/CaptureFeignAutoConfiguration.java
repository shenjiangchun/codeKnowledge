package com.hisi.capture.autoconfig;

import com.hisi.capture.ingress.feign.CaptureFeignRequestInterceptor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConditionalOnClass(name = "feign.RequestInterceptor")
public class CaptureFeignAutoConfiguration {

    @Bean
    public CaptureFeignRequestInterceptor captureFeignRequestInterceptor() {
        return new CaptureFeignRequestInterceptor();
    }
}
