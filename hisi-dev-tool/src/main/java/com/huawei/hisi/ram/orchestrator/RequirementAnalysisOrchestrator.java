package com.huawei.hisi.ram.orchestrator;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.huawei.hisi.ram.contract.SchemaValidator;
import com.huawei.hisi.ram.model.AgentEvent;
import com.huawei.hisi.ram.model.AgentSession;
import com.huawei.hisi.ram.model.EventType;
import com.huawei.hisi.ram.model.SessionStatus;
import com.huawei.hisi.ram.model.SessionType;
import com.huawei.hisi.ram.registry.AgentRegistry;
import com.huawei.hisi.ram.repository.AgentEventRepository;
import com.huawei.hisi.ram.repository.AgentSessionRepository;
import com.huawei.hisi.workflow.DagExecutor;
import com.huawei.hisi.workflow.DagNode;
import com.huawei.hisi.workflow.ExecutionResult;
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
        AgentSession session = sessionRepo.save(AgentSession.newRunning(userId, SessionType.DEMAND));
        return start(session, userInput, nodes);
    }

    /**
     * Variant that runs the DAG against a session that was pre-created by the
     * caller (e.g. the REST controller, which needs the long id available
     * synchronously to set up its UUID->id mapping before the async dispatch).
     */
    public ExecutionResult start(long existingSessionId,
                                 Map<String, Object> userInput,
                                 List<DagNode> nodes) {
        return start(sessionRepo.findById(existingSessionId).orElse(null), userInput, nodes);
    }

    private ExecutionResult start(AgentSession session,
                                  Map<String, Object> userInput,
                                  List<DagNode> nodes) {
        long sessionId = session == null ? -1L : session.getId();
        // 首次运行和之后的每次续跑必须用同一套办法拼输入：检查点是按「节点输入」的哈希找回来的，
        // 第一次少一个键、续跑时又多出来，人刚批准的结果就会因哈希对不上被丢弃并重算
        Map<String, Object> input = rebuildInput(session, userInput, collectAllClarifyRounds(sessionId));
        storeInitialInput(sessionId, input);
        return executor.run(nodes, sessionId, input);
    }

    public ExecutionResult resume(long sessionId,
                                  Map<String, Object> clarifyAnswers,
                                  List<DagNode> nodes) {
        appendClarifyRes(sessionId, clarifyAnswers);
        sessionRepo.updateStatus(sessionId, SessionStatus.RUNNING);
        // Reconstruct the original input (userRequirement, projectHints, etc.)
        // from the event log, then accumulate ALL clarify answers from ALL rounds
        // so the LLM sees the full Q&A history on re-run.
        Map<String, Object> merged = rebuildInput(sessionRepo.findById(sessionId).orElse(null),
                findSessionInitialInput(sessionId), collectAllClarifyRounds(sessionId));
        return executor.run(nodes, sessionId, merged);
    }

    /**
     * 用首次输入 + 全部澄清问答拼出这次运行的输入；项目路径按会话记录补齐，
     * 保证同一会话每次拼出来的键一致。
     */
    private Map<String, Object> rebuildInput(AgentSession session,
                                             Map<String, Object> initialInput,
                                             List<Map<String, Object>> clarifyRounds) {
        Map<String, Object> merged = new LinkedHashMap<>(initialInput);
        if (!clarifyRounds.isEmpty()) {
            merged.put("clarify_history", clarifyRounds);
        }
        // Override project paths from AgentSession so rerun uses the original project
        if (session != null) {
            injectProjectPaths(session, merged);
        }
        return merged;
    }

    /**
     * Persists the initial user input as a {@code USER_MSG} event so that
     * any later resume (clarify or HITL confirm) can reconstruct it.
     */
    private void storeInitialInput(long sessionId, Map<String, Object> userInput) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("initialInput", userInput == null ? Map.of() : userInput);
        String key = "initial-input-" + sessionId;
        AgentEvent ev = AgentEvent.builder()
                .sessionId(sessionId)
                .type(EventType.USER_MSG)
                .payload(toJson(payload))
                .idempotencyKey(key)
                .circuitState("OK")
                .validatorStatus("OK")
                .createdAt(System.currentTimeMillis() / 1000L)
                .build();
        eventRepo.append(ev);
    }

    /**
     * Reconstructs the initial input for a session. Checks three sources
     * in priority order:
     * <ol>
     *   <li>{@code USER_MSG} with {@code initialInput} — new sessions</li>
     *   <li>{@code CLARIFY_REQ} with {@code originalInput} — legacy sessions</li>
     * </ol>
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> findSessionInitialInput(long sessionId) {
        List<AgentEvent> events = eventRepo.findBySessionId(sessionId);

        // Forward scan: USER_MSG with initialInput is typically the first event
        for (AgentEvent ev : events) {
            if (ev.getType() == EventType.USER_MSG) {
                Map<String, Object> payload = parsePayload(ev.getPayload());
                if (payload != null && payload.get("initialInput") instanceof Map<?, ?> init) {
                    return new LinkedHashMap<>((Map<String, Object>) init);
                }
            }
        }

        // Fallback: legacy sessions stored input in CLARIFY_REQ.originalInput
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
     *         <li>{@code questions} — the {@code List<String>} from the CLARIFY_REQ</li>
     *         <li>{@code answers} — the {@code Map<String, Object>} from the CLARIFY_RES</li>
     *         <li>{@code analysis} — the {@code Map<String, Object>} the LLM had already
     *         extracted when it asked (target methods etc.), so later rounds — which run
     *         without tools — can carry the code findings forward instead of losing them</li>
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
        Map<String, Object> pendingAnalysis = null;

        for (AgentEvent ev : events) {
            if (ev.getType() == EventType.CLARIFY_REQ) {
                Map<String, Object> payload = parsePayload(ev.getPayload());
                if (payload != null && payload.get("questions") instanceof List<?> qs) {
                    pendingQuestions = qs.stream()
                            .filter(q -> q instanceof String)
                            .map(q -> (String) q)
                            .toList();
                }
                pendingAnalysis = payload != null && payload.get("partialOutput") instanceof Map<?, ?> partial
                        ? (Map<String, Object>) partial
                        : null;
            } else if (ev.getType() == EventType.CLARIFY_RES && pendingQuestions != null) {
                Map<String, Object> payload = parsePayload(ev.getPayload());
                Map<String, Object> answers = Map.of();
                if (payload != null && payload.get("answers") instanceof Map<?, ?> ans) {
                    answers = (Map<String, Object>) ans;
                }
                Map<String, Object> round = new LinkedHashMap<>();
                round.put("questions", List.copyOf(pendingQuestions));
                round.put("answers", answers);
                if (pendingAnalysis != null && !pendingAnalysis.isEmpty()) {
                    round.put("analysis", pendingAnalysis);
                }
                rounds.add(round);
                pendingQuestions = null;
                pendingAnalysis = null;
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

        // Reconstruct the SAME input that was used in the last successful DAG
        // run so that already-completed nodes cache-hit on their inputsHash.
        Map<String, Object> fullInput = rebuildInput(sessionRepo.findById(sessionId).orElse(null),
                findSessionInitialInput(sessionId), collectAllClarifyRounds(sessionId));
        return executor.run(nodes, sessionId, fullInput);
    }

    public AgentRegistry getAgentRegistry() {
        return agentRegistry;
    }

    public SchemaValidator getSchemaValidator() {
        return schemaValidator;
    }

    private void appendClarifyRes(long sessionId, Map<String, Object> answers) {
        // Find the latest CLARIFY_REQ roundNo for this session
        Integer roundNo = null;
        List<AgentEvent> events = eventRepo.findBySessionId(sessionId);
        for (int i = events.size() - 1; i >= 0; i--) {
            if (events.get(i).getType() == EventType.CLARIFY_REQ && events.get(i).getClarifyRoundNo() != null) {
                roundNo = events.get(i).getClarifyRoundNo();
                break;
            }
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("answers", answers == null ? Map.of() : answers);
        String key = "clarify-res-" + sessionId + "-" + System.nanoTime();
        AgentEvent ev = AgentEvent.builder()
                .sessionId(sessionId)
                .type(EventType.CLARIFY_RES)
                .payload(toJson(payload))
                .idempotencyKey(key)
                .clarifyRoundNo(roundNo)
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
     * Appends a new CHECKPOINT with the user-edited output. It keeps the inputsHash
     * of the checkpoint it replaces — the executor looks checkpoints up by the hash
     * of the node's <em>input</em>, so a hash of the edited output would never match
     * and the user's edit would be silently replaced by the original output. Because
     * this node's output changes, every downstream node misses its cache and re-runs.
     */
    private void overwriteCheckpoint(long sessionId, String nodeName,
                                      Map<String, Object> editedOutput) {
        String inputsHash = latestCheckpointHash(sessionId, nodeName);
        if (inputsHash == null) {
            return; // nothing to edit — no checkpoint for this node
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("nodeName", nodeName);
        payload.put("inputsHash", inputsHash);
        payload.put("output", editedOutput);
        payload.put("edited", true);
        String key = "ckpt-edit-" + sessionId + "-" + nodeName + "-" + System.nanoTime();
        AgentEvent ev = AgentEvent.builder()
                .sessionId(sessionId)
                .type(EventType.CHECKPOINT)
                .payload(toJson(payload))
                .idempotencyKey(key)
                .inputsHash(inputsHash)
                .circuitState("OK")
                .validatorStatus("OK")
                .createdAt(System.currentTimeMillis() / 1000L)
                .build();
        eventRepo.append(ev);
    }

    /** inputsHash carried by the newest CHECKPOINT of {@code nodeName}, or null when the node has none. */
    private String latestCheckpointHash(long sessionId, String nodeName) {
        List<AgentEvent> events = eventRepo.findBySessionId(sessionId);
        for (int i = events.size() - 1; i >= 0; i--) {
            AgentEvent ev = events.get(i);
            if (ev.getType() != EventType.CHECKPOINT) continue;
            Map<String, Object> payload = parsePayload(ev.getPayload());
            if (payload == null || !nodeName.equals(payload.get("nodeName"))) continue;
            return payload.get("inputsHash") instanceof String hash ? hash : null;
        }
        return null;
    }

    private String toJson(Map<String, Object> payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to serialize event payload", e);
        }
    }

    /**
     * Inject project paths from AgentSession into the input map.
     * Handles JSON array format (e.g. '["/path/a","/path/b"]') or plain string.
     */
    @SuppressWarnings("unchecked")
    private void injectProjectPaths(AgentSession session, Map<String, Object> input) {
        String pp = session.getProjectPaths();
        if (pp == null || pp.isBlank()) return;
        try {
            List<String> paths = objectMapper.readValue(pp, List.class);
            if (!paths.isEmpty()) {
                input.put("project_paths", paths);
                input.put("projectPath", paths.get(0));
            }
        } catch (Exception e) {
            // Not JSON — treat as single path
            input.put("project_paths", List.of(pp));
            input.put("projectPath", pp);
        }
    }
}
