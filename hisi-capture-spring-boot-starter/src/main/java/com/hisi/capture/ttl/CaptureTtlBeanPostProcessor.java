package com.hisi.capture.ttl;

import com.alibaba.ttl.threadpool.TtlExecutors;
import com.hisi.capture.config.CaptureTtlProperties;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * 决策 1 默认实现：BeanPostProcessor 自动包装业务方 ExecutorPoolConfig Bean。
 *
 * 由 CaptureTtlAutoConfiguration 创建（非 @Component），避免与自动配置重复注册。
 *
 * 开关：hisi.capture.ttl.mode=auto（默认）/ agent / explicit
 */
public class CaptureTtlBeanPostProcessor implements BeanPostProcessor {

    private final CaptureTtlProperties ttlProperties;

    public CaptureTtlBeanPostProcessor(CaptureTtlProperties ttlProperties) {
        this.ttlProperties = ttlProperties;
    }

    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) throws BeansException {
        // 通过注入的 Properties 读取配置，不再使用 System.getProperty
        if (!"auto".equalsIgnoreCase(ttlProperties.getMode())) {
            return bean;
        }

        // 包装 ThreadPoolTaskExecutor
        if (bean instanceof ThreadPoolTaskExecutor) {
            ThreadPoolTaskExecutor executor = (ThreadPoolTaskExecutor) bean;
            ThreadPoolExecutor raw = executor.getThreadPoolExecutor();
            ExecutorService ttlWrapped = TtlExecutors.getTtlExecutorService(raw);
            return new TtlWrappedTaskExecutor(executor, ttlWrapped);
        }

        // 包装原生 ExecutorService
        if (bean instanceof ExecutorService) {
            return TtlExecutors.getTtlExecutorService((ExecutorService) bean);
        }

        return bean;
    }
}
