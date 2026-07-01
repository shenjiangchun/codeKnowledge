package com.hisi.capture.autoconfig;

import com.hisi.capture.aop.CaptureAspect;
import com.hisi.capture.aop.CaptureLogAspect;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.EnableAspectJAutoProxy;

@Configuration
@EnableAspectJAutoProxy
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
