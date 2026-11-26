package com.huawei.hisi.workbench.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.huawei.hisi.ram.model.AgentEvent;
import com.huawei.hisi.ram.model.EventType;
import com.huawei.hisi.ram.repository.AgentEventRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BooleanSupplier;

/**
 * 修复运行的事件发射器：把阶段进度写成 {@code agent_event}（复用工作流 SSE 推流与回放）。
 *
 * <p>约定：{@code NODE_START} / {@code NODE_COMPLETE} 只带节点名；{@code CHECKPOINT} 带阶段产物；
 * 运行日志走 {@code NODE_DELTA} 并节流合并（编译输出这类密集日志不至于打爆事件表）。
 * 刻意不发 {@code HITL_REQ}——工作流 SSE 处理器收到它会立刻关流；
 * 「等你确认」用会话状态 {@code WAITING_HITL} 表达，一条流从头用到尾。
 */
@Slf4j
@Component
public class WorkbenchEventSink {

    /** 日志行的合并窗口：窗口内的 NODE_DELTA 合并成一条。 */
    private static final long DELTA_MERGE_MS = 2000;

    private final AgentEventRepository eventRepository;
    private final ObjectMapper objectMapper;

    private final Map<Long, PendingDelta> pendingDeltas = new ConcurrentHashMap<>();
    /** sessionId:nodeName → 已进入的轮次。 */
    private final Map<String, java.util.concurrent.atomic.AtomicLong> rounds = new ConcurrentHashMap<>();

    public WorkbenchEventSink(AgentEventRepository eventRepository, ObjectMapper objectMapper) {
        this.eventRepository = eventRepository;
        this.objectMapper = objectMapper;
    }

    /**
     * 每个阶段进入一次计一轮。幂等键带上轮次：方案被驳回后重出、回归失败回到开发、重启后继续，
     * 同一个阶段会再次产出，新产物必须能覆盖旧版本（否则事件表按幂等键去重，留下的永远是最早那一版）。
     */
    private long round(long sessionId, String nodeName) {
        return rounds.computeIfAbsent(sessionId + ":" + nodeName, k -> new java.util.concurrent.atomic.AtomicLong()).get();
    }

    public void nodeStart(long sessionId, String nodeName) {
        long round = rounds.computeIfAbsent(sessionId + ":" + nodeName,
                k -> new java.util.concurrent.atomic.AtomicLong()).incrementAndGet();
        append(sessionId, EventType.NODE_START, Map.of("nodeName", nodeName, "round", round),
                "wb-" + sessionId + "-start-" + nodeName + "-r" + round);
    }

    public void nodeComplete(long sessionId, String nodeName, Map<String, Object> summary) {
        Map<String, Object> payload = new java.util.LinkedHashMap<>();
        payload.put("nodeName", nodeName);
        if (summary != null && !summary.isEmpty()) {
            payload.putAll(summary);
        }
        append(sessionId, EventType.NODE_COMPLETE, payload,
                "wb-" + sessionId + "-complete-" + nodeName + "-r" + round(sessionId, nodeName));
    }

    /** 阶段产物：报告 / 候选方案 / Spec / 任务 / 测试结果等。 */
    public void checkpoint(long sessionId, String nodeName, Map<String, Object> output) {
        append(sessionId, EventType.CHECKPOINT, Map.of("nodeName", nodeName, "output", output),
                "wb-" + sessionId + "-ckpt-" + nodeName + "-r" + round(sessionId, nodeName));
    }

    /** 运行日志：合并窗口内追加到同一个 pending，flush 时落一条 NODE_DELTA。 */
    public void log(long sessionId, String nodeName, String message) {
        PendingDelta pending = pendingDeltas.computeIfAbsent(sessionId,
                k -> new PendingDelta(nodeName));
        synchronized (pending) {
            pending.lines.add(message);
            if (pending.lines.size() >= 20 || System.currentTimeMillis() - pending.firstAt >= DELTA_MERGE_MS) {
                flush(sessionId, pending);
            }
        }
    }

    /** 阶段结束时把积压的日志行落库。 */
    public void flush(long sessionId) {
        PendingDelta pending = pendingDeltas.get(sessionId);
        if (pending != null) {
            synchronized (pending) {
                flush(sessionId, pending);
            }
        }
    }

    public void error(long sessionId, String nodeName, String message) {
        flush(sessionId);
        append(sessionId, EventType.ERROR, Map.of("nodeName", nodeName, "error", message),
                "wb-" + sessionId + "-error-" + nodeName);
    }

    private void flush(long sessionId, PendingDelta pending) {
        if (pending.lines.isEmpty()) {
            return;
        }
        List<String> lines = new ArrayList<>(pending.lines);
        pending.lines.clear();
        append(sessionId, EventType.NODE_DELTA,
                Map.of("nodeName", pending.nodeName, "message", String.join("\n", lines)),
                "wb-" + sessionId + "-delta-" + System.currentTimeMillis());
    }

    private void append(long sessionId, EventType type, Map<String, Object> payload, String idempotencyKey) {
        try {
            eventRepository.append(AgentEvent.builder()
                    .sessionId(sessionId)
                    .type(type)
                    .payload(objectMapper.writeValueAsString(payload))
                    .idempotencyKey(idempotencyKey)
                    .createdAt(System.currentTimeMillis() / 1000L)
                    .build());
        } catch (Exception e) {
            log.warn("[WorkbenchEvents] 事件写入失败 type={} key={}: {}", type, idempotencyKey, e.getMessage());
        }
    }

    /** 供阶段里用的取消检查。 */
    public boolean cancelled(BooleanSupplier supplier) {
        return supplier.getAsBoolean();
    }

    private static class PendingDelta {
        final String nodeName;
        final long firstAt = System.currentTimeMillis();
        final List<String> lines = new ArrayList<>();
        PendingDelta(String nodeName) {
            this.nodeName = nodeName;
        }
    }
}
