package com.hisi.capture.autoconfig;

import com.hisi.capture.exception.CaptureScheduledErrorHandler;
import com.hisi.capture.ingress.scheduled.ScheduledCaptureBeanPostProcessor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;

@Configuration
@EnableScheduling
public class CaptureScheduledAutoConfiguration implements SchedulingConfigurer {

    @Autowired
    private CaptureScheduledErrorHandler errorHandler;

    @Bean
    public ScheduledCaptureBeanPostProcessor scheduledCaptureBeanPostProcessor() {
        return new ScheduledCaptureBeanPostProcessor();
    }

    @Bean
    public CaptureScheduledErrorHandler captureScheduledErrorHandler() {
        return new CaptureScheduledErrorHandler();
    }

    @Override
    public void configureTasks(ScheduledTaskRegistrar taskRegistrar) {
        taskRegistrar.setErrorHandler(errorHandler);
    }
}
