package com.huawei.hisi.workbench.service;

import com.huawei.hisi.ram.model.AgentEvent;
import com.huawei.hisi.ram.model.EventType;
import com.huawei.hisi.ram.repository.AgentEventRepository;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

/** 测试用的内存事件仓库：按会话保存事件，行为与 SQLite 版一致（seq 递增、同会话按 seq 排序）。 */
public class InMemoryAgentEvents implements AgentEventRepository {

    private final List<AgentEvent> events = new CopyOnWriteArrayList<>();

    @Override
    public AgentEvent append(AgentEvent event) {
        if (event.getSeq() == 0) {
            event.setSeq(events.stream().filter(e -> e.getSessionId() == event.getSessionId()).count() + 1);
        }
        events.add(event);
        return event;
    }

    @Override
    public Optional<AgentEvent> findByIdempotencyKey(String idempotencyKey) {
        return events.stream().filter(e -> idempotencyKey.equals(e.getIdempotencyKey())).findFirst();
    }

    @Override
    public List<AgentEvent> findBySessionId(long sessionId) {
        return events.stream().filter(e -> e.getSessionId() == sessionId)
                .sorted((a, b) -> Long.compare(a.getSeq(), b.getSeq())).toList();
    }

    @Override
    public long countBySessionId(long sessionId) {
        return events.stream().filter(e -> e.getSessionId() == sessionId).count();
    }

    @Override
    public long findMaxSeq(long sessionId) {
        return events.stream().filter(e -> e.getSessionId() == sessionId).mapToLong(AgentEvent::getSeq).max().orElse(0);
    }

    @Override
    public long countBySessionIdAndType(long sessionId, EventType type) {
        return events.stream().filter(e -> e.getSessionId() == sessionId && e.getType() == type).count();
    }

    @Override
    public List<AgentEvent> findCheckpointsContaining(String payloadFragment, long createdAfterEpoch) {
        return events.stream().filter(e -> e.getType() == EventType.CHECKPOINT && e.getPayload() != null
                && e.getPayload().contains(payloadFragment) && e.getCreatedAt() > createdAfterEpoch).toList();
    }
}
