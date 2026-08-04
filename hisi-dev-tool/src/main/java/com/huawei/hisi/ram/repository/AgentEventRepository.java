package com.huawei.hisi.ram.repository;

import com.huawei.hisi.ram.model.AgentEvent;
import com.huawei.hisi.ram.model.EventType;

import java.util.List;
import java.util.Optional;

/**
 * Append-only data access for {@link AgentEvent}.
 *
 * <p>{@link #append(AgentEvent)} is idempotent: calling twice with the same
 * {@code idempotencyKey} returns the same persisted row without producing
 * duplicates.
 */
public interface AgentEventRepository {

    /**
     * Idempotently append an event. If an event with the same idempotency key
     * already exists, the existing row is returned.
     */
    AgentEvent append(AgentEvent event);

    Optional<AgentEvent> findByIdempotencyKey(String idempotencyKey);

    List<AgentEvent> findBySessionId(long sessionId);

    long countBySessionId(long sessionId);

    /** Maximum seq for the session, or 0 if the session has no events. */
    long findMaxSeq(long sessionId);

    /** Count events for a session filtered by type. */
    long countBySessionIdAndType(long sessionId, EventType type);
}
