package com.hisi.capture.ttl;

import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.ExecutorService;

/**
 * 适配器：把 TTL 包装后的 ExecutorService 适配回 ThreadPoolTaskExecutor。
 *
 * 继承 ThreadPoolTaskExecutor 而非仅实现 TaskExecutor，
 * 保证 Spring 容器中类型检查（bean instanceof ThreadPoolTaskExecutor）不会失败。
 *
 * 业务方调用 @Async 时不感知，仍用原 ThreadPoolTaskExecutor 接口。
 */
public class TtlWrappedTaskExecutor extends ThreadPoolTaskExecutor {

    private final ThreadPoolTaskExecutor original;
    private final ExecutorService ttlWrapped;

    public TtlWrappedTaskExecutor(ThreadPoolTaskExecutor original, ExecutorService ttlWrapped) {
        this.original = original;
        this.ttlWrapped = ttlWrapped;
    }

    @Override
    public void execute(Runnable task) {
        ttlWrapped.execute(task);
    }

    public ThreadPoolTaskExecutor getOriginal() { return original; }
    public ExecutorService getTtlWrapped() { return ttlWrapped; }
}
