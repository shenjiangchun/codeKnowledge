package com.huawei.hisi.ram.orchestrator;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.huawei.hisi.ram.model.AgentEvent;
import com.huawei.hisi.ram.model.EventType;
import com.huawei.hisi.ram.model.SessionStatus;
import com.huawei.hisi.ram.repository.AgentEventRepository;
import com.huawei.hisi.ram.repository.AgentSessionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Runs an ordered list of {@link DagNode}s for a session with
 * checkpoint-aware "minimum recompute" semantics:
 *
 * <ul>
 *   <li>Each node's input is hashed via {@link InputsHasher}.</li>
 *   <li>If a prior {@code CHECKPOINT} event for the same {@code (sessionId, nodeName)}
 *       carries the same {@code inputsHash}, its cached output is reused and the
 *       node is reported as <em>skipped</em>.</li>
 *   <li>Otherwise the node is executed and a fresh {@code CHECKPOINT} event is
 *       appended.</li>
 *   <li>{@link ClarifyRequiredException} parks the session in
 *       {@code WAITING_CLARIFY}; any other exception fails the session.</li>
 * </ul>
 */
@Component
public class DagExecutor {

    private static final Logger log = LoggerFactory.getLogger(DagExecutor.class);

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    private final AgentEventRepository eventRepo;
    private final AgentSessionRepository sessionRepo;
    private final ObjectMapper objectMapper;

    public DagExecutor(AgentEventRepository eventRepo,
                       AgentSessionRepository sessionRepo,
                       ObjectMapper objectMapper) {
        this.eventRepo = eventRepo;
        this.sessionRepo = sessionRepo;
        this.objectMapper = objectMapper;
    }

    public ExecutionResult run(List<DagNode> orderedNodes,
                               long sessionId,
                               Map<String, Object> initialInput) {
        List<String> executed = new ArrayList<>();
        List<String> skipped = new ArrayList<>();
        Map<String, Object> previousOutput = initialInput == null ? Map.of() : initialInput;

        List<AgentEvent> sessionEvents = eventRepo.findBySessionId(sessionId);
        log.info("[RAM][DagExecutor] run start sid={} nodes={} initialInput.keys={} priorEvents={}",
                sessionId,
                orderedNodes.stream().map(DagNode::name).toList(),
                previousOutput.keySet(),
                sessionEvents.size());

        for (DagNode node : orderedNodes) {
            Map<String, Object> input = previousOutput;
            String inputsHash = InputsHasher.hash(input);

            Map<String, Object> cached = findCachedOutput(sessionEvents, node.name(), inputsHash);
            if (cached != null) {
                log.info("[RAM][DagExecutor] sid={} node={} CACHE HIT inputsHash={} cachedOutput.keys={}",
                        sessionId, node.name(), inputsHash, cached.keySet());
                skipped.add(node.name());
                previousOutput = cached;
                continue;
            }

            log.info("[RAM][DagExecutor] sid={} node={} EXECUTE inputsHash={} input.keys={}",
                    sessionId, node.name(), inputsHash, input.keySet());
            Map<String, Object> output;
            try {
                output = node.execute(input);
            } catch (ClarifyRequiredException ce) {
                log.info("[RAM][DagExecutor] sid={} node={} CLARIFY_REQ questions={}",
                        sessionId, node.name(), ce.getClarifyQuestions());
                appendClarifyReq(sessionId, node.name(), ce.getClarifyQuestions());
                sessionRepo.updateStatus(sessionId, SessionStatus.WAITING_CLARIFY);
                return new ExecutionResult(
                        sessionId, SessionStatus.WAITING_CLARIFY, executed, skipped, previousOutput);
            } catch (RuntimeException ex) {
                log.error("[RAM][DagExecutor] sid={} node={} FAILED message={} type={}",
                        sessionId, node.name(), ex.getMessage(), ex.getClass().getName(), ex);
                appendError(sessionId, node.name(), ex);
                sessionRepo.updateStatus(sessionId, SessionStatus.FAILED);
                return new ExecutionResult(
                        sessionId, SessionStatus.FAILED, executed, skipped, previousOutput);
            }

            Map<String, Object> safeOutput = output == null ? Map.of() : output;
            log.info("[RAM][DagExecutor] sid={} node={} OK output.keys={}",
                    sessionId, node.name(), safeOutput.keySet());
            appendCheckpoint(sessionId, node.name(), inputsHash, safeOutput);
            executed.add(node.name());
            previousOutput = safeOutput;
        }

        log.info("[RAM][DagExecutor] sid={} DONE executed={} skipped={}",
                sessionId, executed, skipped);
        sessionRepo.updateStatus(sessionId, SessionStatus.DONE);
        return new ExecutionResult(sessionId, SessionStatus.DONE, executed, skipped, previousOutput);
    }

    private Map<String, Object> findCachedOutput(List<AgentEvent> events,
                                                 String nodeName,
                                                 String inputsHash) {
        for (int i = events.size() - 1; i >= 0; i--) {
            AgentEvent ev = events.get(i);
            if (ev.getType() != EventType.CHECKPOINT) {
                continue;
            }
            Map<String, Object> payload = parsePayload(ev.getPayload());
            if (payload == null) continue;
            if (!nodeName.equals(payload.get("nodeName"))) continue;
            if (!inputsHash.equals(payload.get("inputsHash"))) continue;
            Object out = payload.get("output");
            if (out instanceof Map<?, ?> m) {
                Map<String, Object> result = new LinkedHashMap<>();
                m.forEach((k, v) -> result.put(String.valueOf(k), v));
                return result;
            }
            return Map.of();
        }
        return null;
    }

    private Map<String, Object> parsePayload(String json) {
        if (json == null || json.isBlank()) return null;
        try {
            return objectMapper.readValue(json, MAP_TYPE);
        } catch (Exception e) {
            return null;
        }
    }

    private void appendCheckpoint(long sessionId, String nodeName,
                                  String inputsHash, Map<String, Object> output) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("nodeName", nodeName);
        payload.put("inputsHash", inputsHash);
        payload.put("output", output);
        String key = "ckpt-" + sessionId + "-" + nodeName + "-" + inputsHash;
        AgentEvent ev = AgentEvent.builder()
                .sessionId(sessionId)
                .type(EventType.CHECKPOINT)
                .payload(toJson(payload))
                .idempotencyKey(key)
                .inputsHash(inputsHash)
                .circuitState("OK")
                .validatorStatus("OK")
                .createdAt(nowEpoch())
                .build();
        eventRepo.append(ev);
    }

    private void appendClarifyReq(long sessionId, String nodeName, List<String> questions) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("nodeName", nodeName);
        payload.put("questions", questions);
        String key = "clarify-req-" + sessionId + "-" + nodeName + "-" + System.nanoTime();
        AgentEvent ev = AgentEvent.builder()
                .sessionId(sessionId)
                .type(EventType.CLARIFY_REQ)
                .payload(toJson(payload))
                .idempotencyKey(key)
                .circuitState("OK")
                .validatorStatus("OK")
                .createdAt(nowEpoch())
                .build();
        eventRepo.append(ev);
    }

    private void appendError(long sessionId, String nodeName, Throwable t) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("nodeName", nodeName);
        payload.put("error", String.valueOf(t.getMessage()));
        payload.put("type", t.getClass().getName());
        String key = "error-" + sessionId + "-" + nodeName + "-" + System.nanoTime();
        AgentEvent ev = AgentEvent.builder()
                .sessionId(sessionId)
                .type(EventType.ERROR)
                .payload(toJson(payload))
                .idempotencyKey(key)
                .circuitState("OK")
                .validatorStatus("FAIL")
                .createdAt(nowEpoch())
                .build();
        eventRepo.append(ev);
    }

    private String toJson(Map<String, Object> payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to serialize event payload", e);
        }
    }

    private static long nowEpoch() {
        return System.currentTimeMillis() / 1000L;
    }
}
