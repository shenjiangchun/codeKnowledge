package com.huawei.hisi.ram.model;

/**
 * Event types persisted in the append-only RAM event log.
 */
public enum EventType {
    USER_MSG,
    ASSISTANT_DELTA,
    TOOL_USE,
    TOOL_RESULT,
    CHECKPOINT,
    CLARIFY_REQ,
    CLARIFY_RES,
    HITL_REQ,
    HITL_RES,
    NODES_CLEARED,
    ERROR,
    TURN_INTERRUPTED,
    MESSAGE
}
