package com.huawei.hisi.ram.chat;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.Disposable;

import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Tracks the currently active turn per RAM chat session so that the running
 * generation can be cancelled via {@link #interrupt(long)}.
 *
 * <p>At most one active turn exists per session — {@link #register(long, ActiveTurn)}
 * disposes any previously registered turn before installing the new one.
 * {@link #complete(long, String)} uses a compare-and-remove so a stale
 * completion callback cannot clobber a newer turn.
 */
@Slf4j
@Component
public class TurnRegistry {

    private final ConcurrentMap<Long, ActiveTurn> activeBySession = new ConcurrentHashMap<>();

    /** Metadata + control surface for the currently streaming turn on a session. */
    public record ActiveTurn(
            String turnId,
            long sessionId,
            Disposable disposable,
            StringBuilder partialBuf,
            Instant startedAt,
            String modelId
    ) {}

    /** Snapshot returned to callers of {@link #interrupt(long)}. */
    public record InterruptResult(String turnId, String partialText) {}

    /**
     * Register {@code turn} as the active turn for {@code sessionId}. If another
     * turn was already active, it is disposed first (defensive — the normal flow
     * is that the previous turn's {@code complete} already removed it).
     */
    public void register(long sessionId, ActiveTurn turn) {
        ActiveTurn previous = activeBySession.put(sessionId, turn);
        if (previous != null) {
            try {
                previous.disposable().dispose();
            } catch (Exception e) {
                log.warn("[TurnRegistry] failed to dispose previous turn sessionId={} turnId={}: {}",
                        sessionId, previous.turnId(), e.getMessage());
            }
        }
    }

    public Optional<ActiveTurn> get(long sessionId) {
        return Optional.ofNullable(activeBySession.get(sessionId));
    }

    /**
     * Remove the active turn for {@code sessionId} only if its {@code turnId}
     * matches — prevents a late completion from clobbering a newer turn.
     */
    public void complete(long sessionId, String turnId) {
        activeBySession.computeIfPresent(sessionId, (sid, current) -> {
            if (current.turnId().equals(turnId)) {
                return null; // remove
            }
            return current;
        });
    }

    /**
     * Atomically remove the active turn for {@code sessionId}, dispose it, and
     * return a snapshot of its partial text. Returns {@link Optional#empty()}
     * if there is no active turn.
     */
    public Optional<InterruptResult> interrupt(long sessionId) {
        ActiveTurn removed = activeBySession.remove(sessionId);
        if (removed == null) {
            return Optional.empty();
        }
        try {
            removed.disposable().dispose();
        } catch (Exception e) {
            log.warn("[TurnRegistry] dispose failed on interrupt sessionId={} turnId={}: {}",
                    sessionId, removed.turnId(), e.getMessage());
        }
        String partial;
        synchronized (removed.partialBuf()) {
            partial = removed.partialBuf().toString();
        }
        return Optional.of(new InterruptResult(removed.turnId(), partial));
    }
}
