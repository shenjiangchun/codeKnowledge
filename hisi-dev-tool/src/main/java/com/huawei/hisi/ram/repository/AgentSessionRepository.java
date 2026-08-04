package com.huawei.hisi.ram.repository;

import com.huawei.hisi.ram.model.AgentSession;
import com.huawei.hisi.ram.model.SessionStatus;

import java.util.List;
import java.util.Optional;

/**
 * Data access for {@link AgentSession}.
 */
public interface AgentSessionRepository {

    /** Insert a new session, returning a copy with the generated id populated. */
    AgentSession save(AgentSession session);

    Optional<AgentSession> findById(long id);

    Optional<AgentSession> findByUuid(String uuid);

    List<AgentSession> listRecent(int limit);

    List<AgentSession> listRecentByUserId(String userId, int limit);

    List<AgentSession> listRecentExcludingUserId(String excludeUserId, int limit);

    List<AgentSession> listRecentBySessionType(String sessionType, int limit);

    List<AgentSession> listRecentBySessionTypeExcludingUserId(String sessionType, String excludeUserId, int limit);

    /**
     * Optimistic-locking update. Returns the new version on success or empty when
     * the in-memory version no longer matches the stored row.
     */
    Optional<AgentSession> update(AgentSession session);

    int updateStatus(long id, SessionStatus status);

    int clearRerunFromNode(long id);
}
