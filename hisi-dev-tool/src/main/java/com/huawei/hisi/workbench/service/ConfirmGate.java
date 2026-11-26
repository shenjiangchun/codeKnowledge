package com.huawei.hisi.workbench.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * 确认等待通道：runner 停在「方案确认」时阻塞在 {@link #await}，
 * 确认/驳回接口通过 {@link #complete} 唤醒它。一次性传递，用完即清。
 */
@Slf4j
@Component
public class ConfirmGate {

    /** runId → 等待确认的 future。 */
    private final ConcurrentHashMap<Long, CompletableFuture<FixRunService.ConfirmResult>> waiting = new ConcurrentHashMap<>();

    /** runner 停在方案确认时调用：阻塞直到确认（返回选项）或驳回（返回反馈）。超时返回 null。 */
    public FixRunService.ConfirmResult await(long runId, long timeout, TimeUnit unit) {
        CompletableFuture<FixRunService.ConfirmResult> future = waiting.computeIfAbsent(runId,
                k -> new CompletableFuture<>());
        try {
            return future.get(timeout, unit);
        } catch (Exception e) {
            waiting.remove(runId);
            log.warn("[WorkbenchFix] 等待确认超时或中断 runId={}: {}", runId, e.getMessage());
            return null;
        }
    }

    /** 完成等待（确认/驳回/中止）。没有等待者时返回 false（调用方需自行重新提交 runner）。 */
    public boolean complete(long runId, FixRunService.ConfirmResult result) {
        CompletableFuture<FixRunService.ConfirmResult> future = waiting.remove(runId);
        if (future == null) {
            return false;
        }
        future.complete(result);
        return true;
    }

    /** 是否有等待者（runner 是否还停在这个 run 上）。 */
    public boolean hasWaiter(long runId) {
        return waiting.containsKey(runId);
    }
}
