package com.huawei.hisi.utils;

import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 零依赖令牌桶限流器（基于 {@link Semaphore} + {@link ScheduledExecutorService}）。
 *
 * <p>用于约束对外部 LLM API 的请求 QPS，避免被服务端 429 限流。
 * 与"线程池 + 重试"模式相比，令牌桶能在客户端主动平滑请求节奏，
 * 大幅减少 429 触发概率并显著提升整体吞吐稳定性。
 *
 * <p>语义：
 * <ul>
 *   <li>桶容量 = {@code burst}，启动时灌满（=burst）。</li>
 *   <li>每 {@code 1000/qps} 毫秒补充 1 个令牌，最多到 burst。</li>
 *   <li>{@link #acquire()} 阻塞直到拿到 1 个令牌；线程被中断会抛 {@link InterruptedException}。</li>
 *   <li>{@link #tryAcquire(long, TimeUnit)} 带超时获取。</li>
 * </ul>
 *
 * 非守护线程数 = 1（每个 limiter 独立调度），构造时启动，{@link #shutdown()} 关闭。
 */
@Slf4j
public class TokenBucketRateLimiter {

    private final String name;
    private final int burst;
    private final double qps;
    private final Semaphore permits;
    private final AtomicInteger available;
    private final ScheduledExecutorService scheduler;

    public TokenBucketRateLimiter(String name, double qps, int burst) {
        if (qps <= 0) {
            throw new IllegalArgumentException("qps 必须 > 0, got " + qps);
        }
        if (burst <= 0) {
            throw new IllegalArgumentException("burst 必须 > 0, got " + burst);
        }
        this.name = name;
        this.qps = qps;
        this.burst = burst;
        this.permits = new Semaphore(burst, true);
        this.available = new AtomicInteger(burst);

        long periodMicros = (long) (1_000_000.0 / qps);
        if (periodMicros < 1000) {
            periodMicros = 1000;  // 不允许 < 1ms 一次的补充
        }
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "TokenBucket-" + name);
            t.setDaemon(true);
            return t;
        });
        this.scheduler.scheduleAtFixedRate(this::refill, periodMicros, periodMicros, TimeUnit.MICROSECONDS);

        log.info("[TokenBucket] '{}' initialized: qps={}, burst={}, refillPeriod={}us",
                name, qps, burst, periodMicros);
    }

    private void refill() {
        int cur = available.get();
        if (cur < burst) {
            if (available.compareAndSet(cur, cur + 1)) {
                permits.release();
            }
        }
    }

    /**
     * 阻塞获取 1 个令牌。
     */
    public void acquire() throws InterruptedException {
        permits.acquire();
        available.decrementAndGet();
    }

    /**
     * 阻塞获取 1 个令牌（不可中断，遇 InterruptedException 仍重设中断标志后再次尝试）。
     * 调用方不愿处理 InterruptedException 时使用。
     */
    public void acquireUninterruptibly() {
        permits.acquireUninterruptibly();
        available.decrementAndGet();
    }

    /**
     * 带超时获取 1 个令牌。
     *
     * @return true 表示获取成功；false 表示超时未拿到
     */
    public boolean tryAcquire(long timeout, TimeUnit unit) throws InterruptedException {
        if (permits.tryAcquire(timeout, unit)) {
            available.decrementAndGet();
            return true;
        }
        return false;
    }

    /**
     * 当前可用令牌数（监控用）。
     */
    public int availablePermits() {
        return Math.max(0, available.get());
    }

    public double getQps() {
        return qps;
    }

    public int getBurst() {
        return burst;
    }

    public String getName() {
        return name;
    }

    public void shutdown() {
        scheduler.shutdownNow();
    }
}
