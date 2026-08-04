package com.huawei.hisi.ram.hitl;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.BlockingDeque;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingDeque;

/**
 * In-memory Human-In-The-Loop queue used by the RAM orchestrator.
 *
 * <p>For each session id we keep:
 * <ul>
 *   <li>the most recent batch of clarify questions posted by a node, and</li>
 *   <li>a FIFO queue of answer payloads submitted by the user.</li>
 * </ul>
 *
 * <p>Phase 1 keeps this entirely in-process. A durable backend (Neo4j /
 * SQLite) is planned for later phases.</p>
 */
@Component
public class HitlQueue {

    private final ConcurrentHashMap<Long, List<String>> pendingQuestions = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, BlockingDeque<Map<String, Object>>> answers = new ConcurrentHashMap<>();

    /**
     * Record clarify questions raised by a node for a given session.
     * Overwrites any previous pending questions for the same session.
     */
    public void submitQuestions(long sessionId, List<String> questions) {
        if (questions == null) {
            pendingQuestions.remove(sessionId);
            return;
        }
        pendingQuestions.put(sessionId, List.copyOf(questions));
    }

    /**
     * Inspect the most recently posted clarify questions for a session.
     *
     * @return a defensive copy; empty list if none are pending
     */
    public List<String> getPendingQuestions(long sessionId) {
        List<String> qs = pendingQuestions.get(sessionId);
        return qs == null ? List.of() : List.copyOf(qs);
    }

    /**
     * Push a user-supplied answer payload to the session's answer queue.
     */
    public void submitAnswers(long sessionId, Map<String, Object> answersPayload) {
        if (answersPayload == null) {
            return;
        }
        answers.computeIfAbsent(sessionId, k -> new LinkedBlockingDeque<>())
                .add(Map.copyOf(answersPayload));
    }

    /**
     * Non-blocking poll for the next answer payload for the given session.
     */
    public Optional<Map<String, Object>> pollAnswers(long sessionId) {
        BlockingDeque<Map<String, Object>> q = answers.get(sessionId);
        if (q == null) {
            return Optional.empty();
        }
        Map<String, Object> head = q.pollFirst();
        return Optional.ofNullable(head);
    }
}
