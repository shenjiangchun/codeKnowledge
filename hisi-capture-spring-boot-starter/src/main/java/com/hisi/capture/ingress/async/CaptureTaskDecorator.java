package com.hisi.capture.ingress.async;

import com.hisi.capture.context.*;
import org.springframework.core.task.TaskDecorator;
import org.springframework.stereotype.Component;

/**
 * TaskDecorator：@Async 线程池提交任务时包装 Runnable，
 * 把父线程的 CaptureContext 复制到子线程。
 *
 * TTL 在普通 ThreadPoolTaskExecutor 也能传播，但 TaskDecorator 是双保险：
 * 1. 如果业务方 ExecutorPoolConfig 没被 BeanPostProcessor 包装（决策 1 切到 agent/explicit），
 *    TaskDecorator 仍能传播；
 * 2. 如果业务方用了 Spring 默认 ThreadPoolTaskExecutor（@Async 默认），TaskDecorator 生效。
 */
@Component
public class CaptureTaskDecorator implements TaskDecorator {

    @Override
    public Runnable decorate(Runnable runnable) {
        CaptureContext parent = CaptureContextHolder.get();
        return () -> {
            CaptureContextHolder.set(parent);
            try {
                runnable.run();
            } finally {
                CaptureContextHolder.clear();
            }
        };
    }
}
