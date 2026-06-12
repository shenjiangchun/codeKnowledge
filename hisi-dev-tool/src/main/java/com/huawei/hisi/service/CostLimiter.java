package com.huawei.hisi.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 成本限制器
 * 控制 LLM 调用次数和错误率，防止成本失控
 *
 * Task 11: Cost limiter for LLM call budget control
 *
 * 规则：
 * 1. 日调用限额 (default 1000)
 * 2. 错误率阈值 15% (熔断)
 * 3. 每日零点重置
 */
@Slf4j
@Service
public class CostLimiter {

    @Value("${cost.limiter.daily-limit:1000}")
    private int dailyLimit;

    private final AtomicInteger callCount = new AtomicInteger(0);
    private final AtomicInteger failCount = new AtomicInteger(0);
    private volatile LocalDate lastResetDate = LocalDate.now();

    private static final double ERROR_RATE_THRESHOLD = 0.15; // 15%

    /**
     * Setter for test injection
     */
    void setDailyLimit(int limit) {
        this.dailyLimit = limit;
    }

    /**
     * 检查是否允许调用
     */
    public boolean allowCall() {
        checkAndResetDaily();

        if (callCount.get() >= dailyLimit) {
            log.warn("[CostLimiter] Daily limit reached: {} calls", callCount.get());
            return false;
        }

        if (getErrorRate() > ERROR_RATE_THRESHOLD) {
            log.warn("[CostLimiter] Error rate too high: {} (threshold: {})", getErrorRate(), ERROR_RATE_THRESHOLD);
            return false;
        }

        return true;
    }

    /**
     * 记录一次调用
     */
    public void recordCall() {
        checkAndResetDaily();
        callCount.incrementAndGet();
    }

    /**
     * 记录一次失败
     */
    public void recordFailure() {
        failCount.incrementAndGet();
    }

    /**
     * 检查熔断器状态
     */
    public boolean isCircuitBroken() {
        return callCount.get() >= dailyLimit || getErrorRate() > ERROR_RATE_THRESHOLD;
    }

    /**
     * 每日重置
     */
    public void resetDaily() {
        callCount.set(0);
        failCount.set(0);
        lastResetDate = LocalDate.now();
        log.info("[CostLimiter] Daily reset completed");
    }

    /**
     * 获取当前调用次数
     */
    public int getCallCount() {
        return callCount.get();
    }

    /**
     * 获取当前失败次数
     */
    public int getFailCount() {
        return failCount.get();
    }

    private void checkAndResetDaily() {
        LocalDate today = LocalDate.now();
        if (!today.equals(lastResetDate)) {
            resetDaily();
        }
    }

    private double getErrorRate() {
        int total = callCount.get();
        if (total == 0) return 0;
        return (double) failCount.get() / total;
    }
}