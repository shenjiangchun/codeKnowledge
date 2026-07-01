package com.hisi.capture.ttl;

import com.alibaba.ttl.threadpool.TtlExecutors;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * 决策 1 默认实现：BeanPostProcessor 自动包装业务方 ExecutorPoolConfig Bean。
 *
 * 业务方零代码改动：只要线程池通过 Spring Bean 暴露（业务方扫描结果显示
 * 100% 通过 com.hisilicon.<module>.basic.config.ExecutorPoolConfig 暴露），
 * 即可自动包装为 TTL-aware。
 *
 * 开关：hisi.capture.ttl.mode=auto（默认）/ agent / explicit
 *   - auto: 本类生效
 *   - agent: 本类不生效，依赖 TTL javaagent 字节码改写
 *   - explicit: 本类不生效，业务方手动 TtlExecutors.getTtlExecutorService(pool) 包装
 */
@Component
public class CaptureTtlBeanPostProcessor implements BeanPostProcessor {

    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) throws BeansException {
        if (!"auto".equalsIgnoreCase(System.getProperty("hisi.capture.ttl.mode", "auto"))) {
            return bean;
        }

        // 包装 ThreadPoolTaskExecutor
        if (bean instanceof ThreadPoolTaskExecutor) {
            ThreadPoolTaskExecutor executor = (ThreadPoolTaskExecutor) bean;
            // 通过 TtlExecutors 包装底层 ThreadPoolExecutor
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
