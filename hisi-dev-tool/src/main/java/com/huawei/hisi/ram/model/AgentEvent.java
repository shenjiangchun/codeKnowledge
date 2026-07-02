package com.huawei.hisi.ram.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Append-only event in a RAM session log.
 *
 * <p>One row per Claude SDK delta / tool call / clarification / HITL handoff.
 * Replaying events in {@code seq} order reconstructs session state. The
 * {@link #idempotencyKey} is UNIQUE — retries with the same key are silently
 * deduplicated by {@code AgentEventRepository.append}.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AgentEvent {

    private Long id;
    private long sessionId;
    private long seq;
    private EventType type;
    private String payload;
    private String toolUseId;
    private Long parentEventId;
    private String idempotencyKey;
    private long cumulativeTokens;
    private int retryCount;
    private Integer clarifyRoundNo;
    private String inputsHash;
    private String circuitState;
    private int costUsdCents;
    private String validatorStatus;
    private long createdAt;

    private static long nowEpoch() {
        return System.currentTimeMillis() / 1000L;
    }

    private static AgentEventBuilder base(long sessionId, long seq, EventType type, String idemKey) {
        return AgentEvent.builder()
                .sessionId(sessionId)
                .seq(seq)
                .type(type)
                .idempotencyKey(idemKey)
                .cumulativeTokens(0L)
                .retryCount(0)
                .circuitState("OK")
                .costUsdCents(0)
                .validatorStatus("OK")
                .createdAt(nowEpoch());
    }

    public static AgentEvent userMsg(long sessionId, long seq, String content, String idemKey) {
        return base(sessionId, seq, EventType.USER_MSG, idemKey)
                .payload(content)
                .build();
    }

    public static AgentEvent assistantDelta(long sessionId, long seq, String content, String idemKey) {
        return base(sessionId, seq, EventType.ASSISTANT_DELTA, idemKey)
                .payload(content)
                .build();
    }

    public static AgentEvent toolUse(long sessionId, long seq, String toolUseId, String payload, String idemKey) {
        return base(sessionId, seq, EventType.TOOL_USE, idemKey)
                .toolUseId(toolUseId)
                .payload(payload)
                .build();
    }

    public static AgentEvent toolResult(long sessionId, long seq, String toolUseId, String payload, String idemKey) {
        return base(sessionId, seq, EventType.TOOL_RESULT, idemKey)
                .toolUseId(toolUseId)
                .payload(payload)
                .build();
    }

    public static AgentEvent clarifyReq(long sessionId, long seq, String payload, Integer roundNo, String idemKey) {
        return base(sessionId, seq, EventType.CLARIFY_REQ, idemKey)
                .payload(payload)
                .clarifyRoundNo(roundNo)
                .build();
    }

    public static AgentEvent turnInterrupted(long sessionId, long seq, String payload, String idemKey) {
        return base(sessionId, seq, EventType.TURN_INTERRUPTED, idemKey)
                .payload(payload)
                .build();
    }
}
