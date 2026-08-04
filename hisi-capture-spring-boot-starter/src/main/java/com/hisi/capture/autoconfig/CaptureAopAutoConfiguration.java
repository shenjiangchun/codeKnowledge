package com.hisi.capture.autoconfig;

import com.hisi.capture.aop.CaptureAspect;
import com.hisi.capture.aop.CaptureLogAspect;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.Bean;

@AutoConfiguration
public class CaptureAopAutoConfiguration {

    @Bean
    public CaptureAspect captureAspect() {
        return new CaptureAspect();
    }

    @Bean
    public CaptureLogAspect captureLogAspect() {
        return new CaptureLogAspect();
    }
}
