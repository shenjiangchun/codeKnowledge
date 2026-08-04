package com.hisi.capture.autoconfig;

import com.hisi.capture.exception.CaptureScheduledErrorHandler;
import com.hisi.capture.ingress.scheduled.ScheduledCaptureBeanPostProcessor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;

import java.lang.reflect.Method;

@AutoConfiguration
@ConditionalOnClass(name = "org.springframework.scheduling.annotation.Scheduled")
public class CaptureScheduledAutoConfiguration implements SchedulingConfigurer {

    @Autowired
    private CaptureScheduledErrorHandler errorHandler;

    @Bean
    public ScheduledCaptureBeanPostProcessor scheduledCaptureBeanPostProcessor() {
        return new ScheduledCaptureBeanPostProcessor();
    }

    @Override
    public void configureTasks(ScheduledTaskRegistrar taskRegistrar) {
        // setErrorHandler 仅在 Spring 6.0+ 可用（SB 3.x），Spring 5.3 无此方法
        try {
            Method setter = ScheduledTaskRegistrar.class.getMethod("setErrorHandler",
                    org.springframework.util.ErrorHandler.class);
            setter.invoke(taskRegistrar, errorHandler);
        } catch (NoSuchMethodException e) {
            // Spring 5.x — 不支持 setErrorHandler，定时任务异常走默认 uncaughtExceptionHandler
        } catch (Exception e) {
            // 反射调用失败，降级
        }
    }
}
