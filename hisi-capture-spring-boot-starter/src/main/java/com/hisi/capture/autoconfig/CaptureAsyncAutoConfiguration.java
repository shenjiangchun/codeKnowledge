package com.hisi.capture.autoconfig;

import com.hisi.capture.ingress.async.AsyncAspect;
import com.hisi.capture.ingress.async.CaptureTaskDecorator;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.Bean;

@AutoConfiguration
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
