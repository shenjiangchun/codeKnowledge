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

    public ExecutionResult resume(long sessionId,
                                  Map<String, Object> clarifyAnswers,
                                  List<DagNode> nodes) {
        appendClarifyRes(sessionId, clarifyAnswers);
        sessionRepo.updateStatus(sessionId, SessionStatus.RUNNING);
        Map<String, Object> merged = new LinkedHashMap<>();
        if (clarifyAnswers != null) {
            merged.putAll(clarifyAnswers);
        }
        return executor.run(nodes, sessionId, merged);
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

    private String toJson(Map<String, Object> payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to serialize event payload", e);
        }
    }
}
