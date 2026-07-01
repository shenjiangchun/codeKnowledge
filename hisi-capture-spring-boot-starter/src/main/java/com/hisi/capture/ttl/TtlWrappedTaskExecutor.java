package com.hisi.capture.ttl;

import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.ExecutorService;

/**
 * 适配器：把 TTL 包装后的 ExecutorService 适配回 ThreadPoolTaskExecutor 接口。
 *
 * 业务方调用 @Async 时不感知，仍用原 ThreadPoolTaskExecutor 接口。
 */
public class TtlWrappedTaskExecutor implements TaskExecutor {

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
