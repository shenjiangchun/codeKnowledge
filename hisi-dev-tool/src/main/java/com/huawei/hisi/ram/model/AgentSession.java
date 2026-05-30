package com.huawei.hisi.ram.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Persistent state of a RAM (Requirement Analysis Master) session.
 *
 * <p>Stored in the {@code agent_session} table; one row per session. The full
 * step-by-step history is in {@code agent_event}; this entity tracks coarse
 * status / current DAG node / checkpoint pointer for fast resume.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AgentSession {

    private Long id;
    private String userId;
    private String planId;
    private SessionStatus status;
    private String currentNode;
    private int stepCount;
    private Long lastCheckpointEventId;
    private String cacheKey;
    private int version;
    private long createdAt;
    private long updatedAt;

    /**
     * Build a fresh session in RUNNING state for the given user. Timestamps and
     * id are populated by the repository on save.
     */
    public static AgentSession newRunning(String userId) {
        long now = System.currentTimeMillis() / 1000L;
        return AgentSession.builder()
                .userId(userId)
                .status(SessionStatus.RUNNING)
                .currentNode("clarify")
                .stepCount(0)
                .version(0)
                .createdAt(now)
                .updatedAt(now)
                .build();
    }
}
