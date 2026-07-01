package com.huawei.hisi.fixengine.repository;

import com.huawei.hisi.fixengine.model.FixSession;

import java.util.List;
import java.util.Optional;

/**
 * Data access for {@link FixSession}.
 */
public interface FixSessionRepository {

    /** Insert a new session, returning a copy with the generated id populated. */
    FixSession save(FixSession session);

    Optional<FixSession> findById(long id);

    List<FixSession> findByReportId(long reportId);

    Optional<FixSession> findByChatSessionId(long chatSessionId);

    /** Update all mutable fields (status, worktree, branch, commit, error, etc.). */
    int update(FixSession session);

    int updateStatus(long id, String status);
}
