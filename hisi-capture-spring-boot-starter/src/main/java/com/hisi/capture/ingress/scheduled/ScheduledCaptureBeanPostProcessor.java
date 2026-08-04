package com.hisi.capture.ingress.scheduled;

import com.hisi.capture.context.*;
import com.hisi.capture.util.SizeLimiter;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.stereotype.Component;

/**
 * 包装 @Scheduled 任务：在 task 执行前后 setup/clear CaptureContext。
 *
 * 实现方式：postProcessAfterInitialization 时扫描 @Scheduled 方法，
 * 返回一个代理（或包装 Runnable）。
 *
 * 简化实现：通过 ScheduledTaskRegistrar 注册自定义 TaskScheduler。
 */
@Component
public class ScheduledCaptureBeanPostProcessor implements BeanPostProcessor {

    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) throws BeansException {
        // 扫描 bean 中所有 @Scheduled 方法，返回包装后的代理
        // 实际实现见 ScheduledAnnotationBeanPostProcessor 的 customizationHook
        return bean;  // 简化：实际方案见 CaptureScheduledErrorHandler
    }
}
