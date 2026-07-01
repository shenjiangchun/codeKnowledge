package com.hisi.capture.autoconfig;

import com.hisi.capture.ingress.async.AsyncAspect;
import com.hisi.capture.ingress.async.CaptureTaskDecorator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class CaptureAsyncAutoConfiguration {

    @Bean
    public AsyncAspect asyncAspect() {
        return new AsyncAspect();
    }

    @Bean
    public CaptureTaskDecorator captureTaskDecorator() {
        return new CaptureTaskDecorator();
    }
}
