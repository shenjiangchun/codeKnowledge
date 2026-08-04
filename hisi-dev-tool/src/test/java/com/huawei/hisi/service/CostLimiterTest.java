package com.huawei.hisi.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * CostLimiter 单元测试
 * 测试 LLM 调用预算控制功能
 */
class CostLimiterTest {

    private CostLimiter limiter;

    @BeforeEach
    void setUp() {
        limiter = new CostLimiter();
        limiter.setDailyLimit(1000); // 1000 calls per day
    }

    // ==================== allowCall Tests ====================

    @Test
    @DisplayName("允许调用 - 限额内允许")
    void testAllowCallsUnderLimit() {
        for (int i = 0; i < 500; i++) {
            assertTrue(limiter.allowCall());
        }
    }

    @Test
    @DisplayName("阻止调用 - 超过限额阻止")
    void testBlockCallsOverLimit() {
        // Exhaust limit
        for (int i = 0; i < 1000; i++) {
            limiter.recordCall();
        }

        // Should block
        assertFalse(limiter.allowCall());
        assertTrue(limiter.isCircuitBroken());
    }

    @Test
    @DisplayName("熔断器 - 错误率超阈值熔断")
    void testCircuitBreakOnErrorRate() {
        limiter = new CostLimiter();
        limiter.setDailyLimit(100);

        // 10 calls, 2 failures = 20% error rate > 15% threshold
        for (int i = 0; i < 10; i++) {
            limiter.recordCall();
            if (i < 2) {
                limiter.recordFailure();
            }
        }

        assertTrue(limiter.isCircuitBroken());
        assertFalse(limiter.allowCall());
    }

    // ==================== Reset Tests ====================

    @Test
    @DisplayName("每日重置 - 清零计数")
    void testResetAtMidnight() {
        limiter.recordCall();
        limiter.recordCall();
        limiter.recordFailure();

        limiter.resetDaily();

        assertTrue(limiter.allowCall());
        assertFalse(limiter.isCircuitBroken());
    }

    @Test
    @DisplayName("计数器 - 正确记录")
    void testRecordCallIncrementsCount() {
        // Record 1000 calls
        for (int i = 0; i < 1000; i++) {
            limiter.recordCall();
        }

        // Should block now
        assertFalse(limiter.allowCall());
        assertEquals(1000, limiter.getCallCount());
    }
}