package com.huawei.hisi.service;

import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Chiron-inspired 自适应 batch size 控制器。
 *
 * <p>算法：
 * <ul>
 *   <li>下调：任一信号触发 → halving（错误率 > SLO 或 延迟 > SLO）</li>
 *   <li>上调：hysteresis 冷却完成 + 连续 2 次满足条件 → batchSize × 1.5</li>
 *   <li>429 冷却：发生错误后暂停 3 个周期 N 批，然后 slow-start 恢复</li>
 * </ul>
 *
 * <p>配置开关：{@code adaptive.enabled: true}（默认）
 */
@Slf4j
public class AdaptiveBatchController {

    // === 可调参数 ===
    static final int WINDOW_SIZE = 50;
    static final int ADJUST_INTERVAL = 20;
    static final int HYSTERESIS_CYCLES = 5;
    static final int UP_CONSECUTIVE = 2;
    static final double UPSCALE_FACTOR = 1.5;
    static final double SLO_ERROR_RATE = 0.02;
    static final double SLO_LATENCY_MS = 200;
    static final int COOLDOWN_BATCHES = 3;
    static final int SLOW_START_BATCHES = 5;

    private final int minBatchSize;
    private final int maxBatchSize;
    private final AtomicInteger batchSize;

    // 滑动窗口
    private final List<Double> latencies = new ArrayList<>();
    private final List<Boolean> errors = new ArrayList<>();
    private final List<Integer> batchSizes = new ArrayList<>();

    private int batchesSinceAdjust = 0;
    private int cyclesSinceHalving = HYSTERESIS_CYCLES + 1;
    private int consecutiveUpChecks = 0;
    private int cooldownRemaining = 0;
    private int slowStartStep = 0;
    private int totalBatches = 0;

    public AdaptiveBatchController(int initialBatchSize, int minBatchSize, int maxBatchSize) {
        this.batchSize = new AtomicInteger(initialBatchSize);
        this.minBatchSize = minBatchSize;
        this.maxBatchSize = maxBatchSize;
    }

    public AdaptiveBatchController(int initialBatchSize) {
        this(initialBatchSize, 5, 50);
    }

    public AdaptiveBatchController() {
        this(20, 5, 50);
    }

    public int getBatchSize() {
        return batchSize.get();
    }

    /**
     * 返回考虑冷却/慢启动后的实际生效 batch size。
     */
    public int getEffectiveBatchSize() {
        if (cooldownRemaining > 0) {
            return minBatchSize;
        }
        if (slowStartStep > 0 && slowStartStep <= SLOW_START_BATCHES) {
            return Math.min(batchSize.get(), minBatchSize + slowStartStep);
        }
        return batchSize.get();
    }

    /**
     * 记录一次批次完成后的指标。
     */
    public void recordBatch(int batchSize, double latencyMs, boolean error) {
        latencies.add(latencyMs);
        errors.add(error);
        batchSizes.add(batchSize);
        if (latencies.size() > WINDOW_SIZE) {
            latencies.remove(0);
            errors.remove(0);
            batchSizes.remove(0);
        }

        // 429 全局冷却
        if (error && cooldownRemaining <= 0) {
            cooldownRemaining = COOLDOWN_BATCHES;
            slowStartStep = 1;
        }
        if (cooldownRemaining > 0) {
            cooldownRemaining--;
            if (cooldownRemaining == 0) {
                int old = this.batchSize.get();
                this.batchSize.set(Math.max(minBatchSize, old / 2));
                log.info("[ADAPTIVE] 429 冷却 → halving: {} → {}", old, this.batchSize.get());
            }
        }
        if (slowStartStep > 0 && slowStartStep <= SLOW_START_BATCHES) {
            slowStartStep++;
        }

        batchesSinceAdjust++;
        totalBatches++;
    }

    /**
     * 是否到了评估周期。
     */
    public boolean shouldAdjust() {
        // slowStartStep=0 means normal operation (no 429 cooldown active)
        boolean slowStartDone = (slowStartStep == 0 || slowStartStep > SLOW_START_BATCHES);
        return batchesSinceAdjust >= ADJUST_INTERVAL
                && latencies.size() >= ADJUST_INTERVAL
                && cooldownRemaining <= 0
                && slowStartDone;
    }

    /**
     * 执行一次调整，返回 (新 batchSize, 原因描述)。
     */
    public AdjustmentResult adjust() {
        batchesSinceAdjust = 0;
        cyclesSinceHalving++;

        double errorRate = errorRate();
        double avgLat = avgLatencyPerMethod();

        // --- 下调：任一触发 → halving ---
        if (errorRate > SLO_ERROR_RATE) {
            int old = batchSize.get();
            int next = Math.max(minBatchSize, old / 2);
            batchSize.set(next);
            cyclesSinceHalving = 0;
            consecutiveUpChecks = 0;
            String reason = String.format("[halving] err=%.1f%% > %.0f%%  %d → %d",
                    errorRate * 100, SLO_ERROR_RATE * 100, old, next);
            log.info("[ADAPTIVE] {}", reason);
            return new AdjustmentResult(next, reason);
        }

        if (avgLat > SLO_LATENCY_MS) {
            int old = batchSize.get();
            int next = Math.max(minBatchSize, old / 2);
            batchSize.set(next);
            cyclesSinceHalving = 0;
            consecutiveUpChecks = 0;
            String reason = String.format("[halving] lat=%.0fms > %.0fms  %d → %d",
                    avgLat, SLO_LATENCY_MS, old, next);
            log.info("[ADAPTIVE] {}", reason);
            return new AdjustmentResult(next, reason);
        }

        // --- hysteresis 冷却检查 ---
        if (cyclesSinceHalving < HYSTERESIS_CYCLES) {
            return new AdjustmentResult(batchSize.get(),
                    String.format("[hysteresis] 冷却中 (%d/%d)", cyclesSinceHalving, HYSTERESIS_CYCLES));
        }

        // --- 上调：连续 N 次满足条件 → ×1.5 ---
        boolean canUpscale = (errorRate < SLO_ERROR_RATE * 0.5)
                && (avgLat < SLO_LATENCY_MS * 0.7);

        if (canUpscale) {
            consecutiveUpChecks++;
            if (consecutiveUpChecks >= UP_CONSECUTIVE) {
                int old = batchSize.get();
                int next = Math.min(maxBatchSize, (int) (old * UPSCALE_FACTOR));
                if (next != old) {
                    batchSize.set(next);
                    consecutiveUpChecks = 0;
                    String reason = String.format("[upscale ×%.1f] err=%.1f%% lat=%.0fms  %d → %d",
                            UPSCALE_FACTOR, errorRate * 100, avgLat, old, next);
                    log.info("[ADAPTIVE] {}", reason);
                    return new AdjustmentResult(next, reason);
                }
            } else {
                return new AdjustmentResult(batchSize.get(),
                        String.format("[up-wait %d/%d] err=%.1f%% lat=%.0fms",
                                consecutiveUpChecks, UP_CONSECUTIVE, errorRate * 100, avgLat));
            }
        } else {
            consecutiveUpChecks = 0;
        }

        return new AdjustmentResult(batchSize.get(), "保持");
    }

    private double errorRate() {
        if (errors.isEmpty()) return 0;
        long errCount = errors.stream().filter(Boolean::booleanValue).count();
        return (double) errCount / errors.size();
    }

    private double avgLatencyPerMethod() {
        if (latencies.isEmpty() || batchSizes.isEmpty()) return 0;
        int n = Math.min(latencies.size(), batchSizes.size());
        double totalLat = 0;
        int totalMethods = 0;
        for (int i = latencies.size() - n; i < latencies.size(); i++) {
            totalLat += latencies.get(i);
            totalMethods += batchSizes.get(i);
        }
        return totalMethods > 0 ? totalLat / totalMethods : 0;
    }

    public int getTotalBatches() {
        return totalBatches;
    }

    public record AdjustmentResult(int newBatchSize, String reason) {}
}
