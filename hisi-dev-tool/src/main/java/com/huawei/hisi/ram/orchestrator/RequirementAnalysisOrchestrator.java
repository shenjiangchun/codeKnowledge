package com.huawei.hisi.ram.orchestrator;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.huawei.hisi.ram.contract.SchemaValidator;
import com.huawei.hisi.ram.model.AgentEvent;
import com.huawei.hisi.ram.model.AgentSession;
import com.huawei.hisi.ram.model.EventType;
import com.huawei.hisi.ram.model.SessionStatus;
import com.huawei.hisi.ram.registry.AgentRegistry;
import com.huawei.hisi.ram.repository.AgentEventRepository;
import com.huawei.hisi.ram.repository.AgentSessionRepository;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * High-level entry point for running the Requirement Analysis Master DAG.
 *
 * <p>{@link #start} creates a fresh session and runs the supplied node list
 * end-to-end. {@link #resume} records the user's clarification response and
 * re-runs the executor; downstream nodes recompute because their input
 * hashes have changed.
 */
@Service
public class RequirementAnalysisOrchestrator {

    private final DagExecutor executor;
    private final AgentSessionRepository sessionRepo;
    private final AgentEventRepository eventRepo;
    private final AgentRegistry agentRegistry;
    private final SchemaValidator schemaValidator;
    private final ObjectMapper objectMapper;

    public RequirementAnalysisOrchestrator(DagExecutor executor,
                                           AgentSessionRepository sessionRepo,
                                           AgentEventRepository eventRepo,
                                           AgentRegistry agentRegistry,
                                           SchemaValidator schemaValidator,
                                           ObjectMapper objectMapper) {
        this.executor = executor;
        this.sessionRepo = sessionRepo;
        this.eventRepo = eventRepo;
        this.agentRegistry = agentRegistry;
        this.schemaValidator = schemaValidator;
        this.objectMapper = objectMapper;
    }

    public ExecutionResult start(String userId,
                                 Map<String, Object> userInput,
                                 List<DagNode> nodes) {
        AgentSession session = sessionRepo.save(AgentSession.newRunning(userId));
        return executor.run(nodes, session.getId(), userInput);
    }

    /**
     * Variant that runs the DAG against a session that was pre-created by the
     * caller (e.g. the REST controller, which needs the long id available
     * synchronously to set up its UUID->id mapping before the async dispatch).
     */
    public ExecutionResult start(long existingSessionId,
                                 Map<String, Object> userInput,
                                 List<DagNode> nodes) {
        return executor.run(nodes, existingSessionId, userInput);
    }

    public ExecutionResult resume(long sessionId,
                                  Map<String, Object> clarifyAnswers,
                                  List<DagNode> nodes) {
        appendClarifyRes(sessionId, clarifyAnswers);
        sessionRepo.updateStatus(sessionId, SessionStatus.RUNNING);
        // Reconstruct the original input (userRequirement, projectHints, etc.)
        // from the event log, then accumulate ALL clarify answers from ALL rounds
        // so the LLM sees the full Q&A history on re-run.
        Map<String, Object> originalInput = findOriginalInput(sessionId);
        Map<String, Object> merged = new LinkedHashMap<>(originalInput);
        List<Map<String, Object>> allRounds = collectAllClarifyRounds(sessionId);
        if (!allRounds.isEmpty()) {
            merged.put("clarify_history", allRounds);
        }
        return executor.run(nodes, sessionId, merged);
    }

    /**
     * Scan the event log to reconstruct the original input that was used to
     * start the session. The CLARIFY_REQ event payload now includes the
     * {@code originalInput} map that was active when the clarify exception fired.
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> findOriginalInput(long sessionId) {
        List<AgentEvent> events = eventRepo.findBySessionId(sessionId);
        // Scan backwards — the most recent CLARIFY_REQ has the right input
        for (int i = events.size() - 1; i >= 0; i--) {
            AgentEvent ev = events.get(i);
            if (ev.getType() != EventType.CLARIFY_REQ) continue;
            Map<String, Object> payload = parsePayload(ev.getPayload());
            if (payload != null && payload.get("originalInput") instanceof Map<?, ?> orig) {
                return new LinkedHashMap<>((Map<String, Object>) orig);
            }
        }
        return Map.of();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parsePayload(String json) {
        if (json == null || json.isBlank()) return null;
        try {
            return objectMapper.readValue(json, LinkedHashMap.class);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Collects ALL clarify Q&A rounds for a session by pairing
     * consecutive CLARIFY_REQ → CLARIFY_RES events in chronological order.
     *
     * <p>Each round map contains:
     * <ul>
     *   <li>{@code questions} — the {@code List<String>} from the CLARIFY_REQ</li>
     *   <li>{@code answers} — the {@code Map<String, Object>} from the CLARIFY_RES</li>
     * </ul>
     *
     * <p>A CLARIFY_REQ without a subsequent CLARIFY_RES (i.e. the most recent
     * unanswered request) is excluded — it represents the pending round whose
     * response triggered this resume.
     */
    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> collectAllClarifyRounds(long sessionId) {
        List<AgentEvent> events = eventRepo.findBySessionId(sessionId);
        List<Map<String, Object>> rounds = new ArrayList<>();

        // Pending CLARIFY_REQ waiting for its CLARIFY_RES pair
        List<String> pendingQuestions = null;

        for (AgentEvent ev : events) {
            if (ev.getType() == EventType.CLARIFY_REQ) {
                Map<String, Object> payload = parsePayload(ev.getPayload());
                if (payload != null && payload.get("questions") instanceof List<?> qs) {
                    pendingQuestions = qs.stream()
                            .filter(q -> q instanceof String)
                            .map(q -> (String) q)
                            .toList();
                }
            } else if (ev.getType() == EventType.CLARIFY_RES && pendingQuestions != null) {
                Map<String, Object> payload = parsePayload(ev.getPayload());
                Map<String, Object> answers = Map.of();
                if (payload != null && payload.get("answers") instanceof Map<?, ?> ans) {
                    answers = (Map<String, Object>) ans;
                }
                Map<String, Object> round = new LinkedHashMap<>();
                round.put("questions", List.copyOf(pendingQuestions));
                round.put("answers", answers);
                rounds.add(round);
                pendingQuestions = null;
            }
        }
        return rounds;
    }

    /**
     * Confirms or rejects a node's output and re-runs the DAG.
     *
     * <ul>
     *   <li><b>approve</b> — the HITL_RES is recorded; on re-run, the confirmed
     *       node cache-hits and the next node starts executing.</li>
     *   <li><b>reject</b> — the HITL_RES carries feedback; {@link DagExecutor}
     *       injects it into the node's input, changing the hash and forcing
     *       a cache miss (re-execute).</li>
     *   <li><b>edit</b> — a new CHECKPOINT with edited output is appended,
     *       cascading cache misses to all downstream nodes.</li>
     * </ul>
     */
    public ExecutionResult confirmAndResume(long sessionId,
                                             String nodeName,
                                             String action,
                                             String feedback,
                                             Map<String, Object> editedOutput,
                                             List<DagNode> nodes) {
        appendHitlRes(sessionId, nodeName, action, feedback, editedOutput);
        sessionRepo.updateStatus(sessionId, SessionStatus.RUNNING);

        if ("edit".equals(action) && editedOutput != null) {
            overwriteCheckpoint(sessionId, nodeName, editedOutput);
        }

        return executor.run(nodes, sessionId, Map.of());
    }

    public AgentRegistry getAgentRegistry() {
        return agentRegistry;
    }

    public SchemaValidator getSchemaValidator() {
        return schemaValidator;
    }

    private void appendClarifyRes(long sessionId, Map<String, Object> answers) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("answers", answers == null ? Map.of() : answers);
        String key = "clarify-res-" + sessionId + "-" + System.nanoTime();
        AgentEvent ev = AgentEvent.builder()
                .sessionId(sessionId)
                .type(EventType.CLARIFY_RES)
                .payload(toJson(payload))
                .idempotencyKey(key)
                .circuitState("OK")
                .validatorStatus("OK")
                .createdAt(System.currentTimeMillis() / 1000L)
                .build();
        eventRepo.append(ev);
    }

    private void appendHitlRes(long sessionId, String nodeName,
                                String action, String feedback,
                                Map<String, Object> editedOutput) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("nodeName", nodeName);
        payload.put("action", action);
        if (feedback != null) {
            payload.put("feedback", feedback);
        }
        if (editedOutput != null) {
            payload.put("editedOutput", editedOutput);
        }
        String key = "hitl-res-" + sessionId + "-" + nodeName + "-" + System.nanoTime();
        AgentEvent ev = AgentEvent.builder()
                .sessionId(sessionId)
                .type(EventType.HITL_RES)
                .payload(toJson(payload))
                .idempotencyKey(key)
                .circuitState("OK")
                .validatorStatus("OK")
                .createdAt(System.currentTimeMillis() / 1000L)
                .build();
        eventRepo.append(ev);
    }

    /**
     * Appends a new CHECKPOINT with the user-edited output. The new inputsHash
     * differs from the original, cascading cache misses to all downstream nodes.
     */
    private void overwriteCheckpoint(long sessionId, String nodeName,
                                      Map<String, Object> editedOutput) {
        String newHash = InputsHasher.hash(editedOutput);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("nodeName", nodeName);
        payload.put("inputsHash", newHash);
        payload.put("output", editedOutput);
        payload.put("edited", true);
        String key = "ckpt-edit-" + sessionId + "-" + nodeName + "-" + System.nanoTime();
        AgentEvent ev = AgentEvent.builder()
                .sessionId(sessionId)
                .type(EventType.CHECKPOINT)
                .payload(toJson(payload))
                .idempotencyKey(key)
                .inputsHash(newHash)
                .circuitState("OK")
                .validatorStatus("OK")
                .createdAt(System.currentTimeMillis() / 1000L)
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
}
