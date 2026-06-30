package com.huawei.hisi.ram.model;

/**
 * Lifecycle states for a Requirement Analysis Master (RAM) session.
 */
public enum SessionStatus {
    RUNNING,
    WAITING_CLARIFY,
    WAITING_HITL,
    PAUSED,
    DONE,
    FAILED,
    ABORTED,
    ARCHIVED
}
