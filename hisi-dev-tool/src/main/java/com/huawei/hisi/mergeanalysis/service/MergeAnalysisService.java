package com.huawei.hisi.mergeanalysis.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.huawei.hisi.ram.model.AgentEvent;
import com.huawei.hisi.ram.model.AgentSession;
import com.huawei.hisi.ram.model.EventType;
import com.huawei.hisi.ram.model.SessionStatus;
import com.huawei.hisi.ram.orchestrator.DagExecutor;
import com.huawei.hisi.ram.repository.AgentEventRepository;
import com.huawei.hisi.ram.repository.AgentSessionRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Service
@Slf4j
public class MergeAnalysisService {

    private final AgentSessionRepository sessionRepository;
    private final AgentEventRepository eventRepository;
    private final DagExecutor dagExecutor;
    private final MergeAnalysisDagNodes dagNodes;
    private final ObjectMapper objectMapper;

    private final ExecutorService asyncExecutor = Executors.newFixedThreadPool(2);

    public MergeAnalysisService(AgentSessionRepository sessionRepository,
                                AgentEventRepository eventRepository,
                                DagExecutor dagExecutor,
                                MergeAnalysisDagNodes dagNodes,
                                ObjectMapper objectMapper) {
        this.sessionRepository = sessionRepository;
        this.eventRepository = eventRepository;
        this.dagExecutor = dagExecutor;
        this.dagNodes = dagNodes;
        this.objectMapper = objectMapper;
    }

    public long createSession(String projectPath, String sourceBranch, String targetBranch) {
        long now = System.currentTimeMillis() / 1000L;
        AgentSession session = AgentSession.builder()
                .userId("merge-analysis")
                .planId(projectPath)
                .status(SessionStatus.RUNNING)
                .currentNode("diff_extract")
                .stepCount(0)
                .version(0)
                .projectPaths(projectPath)
                .sourceBranch(sourceBranch)
                .targetBranch(targetBranch)
                .createdAt(now)
                .updatedAt(now)
                .build();
        AgentSession saved = sessionRepository.save(session);
        log.info("[MergeAnalysis] Created session id={} for {}: {} -> {}", saved.getId(), projectPath, sourceBranch, targetBranch);
        return saved.getId();
    }

    public void runAnalysis(long sessionId, String projectPath, String sourceBranch, String targetBranch) {
        CompletableFuture.runAsync(() -> {
            try {
                // Store initial input as USER_MSG event (same pattern as RAM)
                storeInitialInput(sessionId, projectPath, sourceBranch, targetBranch);

                // Build input map for DagExecutor
                Map<String, Object> input = new LinkedHashMap<>();
                input.put("projectPath", projectPath);
                input.put("sourceBranch", sourceBranch);
                input.put("targetBranch", targetBranch);

                log.info("[MergeAnalysis] Session {} — starting DAG execution", sessionId);
                dagExecutor.run(dagNodes.nodes(), sessionId, input);
                log.info("[MergeAnalysis] Session {} — completed", sessionId);

            } catch (Exception e) {
                log.error("[MergeAnalysis] Session {} — failed: {}", sessionId, e.getMessage(), e);
                sessionRepository.updateStatus(sessionId, SessionStatus.FAILED);
            }
        }, asyncExecutor);
    }

    public void rerunFromNode(long sessionId, String nodeName) {
        CompletableFuture.runAsync(() -> {
            try {
                // Recover context from AgentSession
                AgentSession session = sessionRepository.findById(sessionId).orElse(null);
                if (session == null) {
                    log.error("[MergeAnalysis] rerunFromNode — session {} not found", sessionId);
                    return;
                }

                String projectPath = session.getProjectPaths() != null ? session.getProjectPaths() : session.getPlanId();
                String sourceBranch = session.getSourceBranch();
                String targetBranch = session.getTargetBranch();

                Map<String, Object> input = new LinkedHashMap<>();
                input.put("projectPath", projectPath);
                input.put("sourceBranch", sourceBranch);
                input.put("targetBranch", targetBranch);

                // Inject clarify_history from prior events (same pattern as RAM resume)
                // Merge-analysis doesn't have clarify rounds, but we keep the pattern for consistency

                log.info("[MergeAnalysis] Session {} — rerunning from node {} with projectPath={}", sessionId, nodeName, projectPath);
                dagExecutor.run(dagNodes.nodes(), sessionId, input);
                log.info("[MergeAnalysis] Session {} — rerun completed", sessionId);

            } catch (Exception e) {
                log.error("[MergeAnalysis] Session {} — rerun failed: {}", sessionId, e.getMessage(), e);
                sessionRepository.updateStatus(sessionId, SessionStatus.FAILED);
            }
        }, asyncExecutor);
    }

    private void storeInitialInput(long sessionId, String projectPath, String sourceBranch, String targetBranch) {
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            Map<String, Object> initialInput = new LinkedHashMap<>();
            initialInput.put("projectPath", projectPath);
            initialInput.put("sourceBranch", sourceBranch);
            initialInput.put("targetBranch", targetBranch);
            payload.put("initialInput", initialInput);

            AgentEvent ev = AgentEvent.builder()
                    .sessionId(sessionId)
                    .type(EventType.USER_MSG)
                    .payload(objectMapper.writeValueAsString(payload))
                    .idempotencyKey("initial-input-" + sessionId)
                    .circuitState("OK")
                    .validatorStatus("OK")
                    .createdAt(System.currentTimeMillis() / 1000L)
                    .build();
            eventRepository.append(ev);
        } catch (Exception e) {
            log.error("[MergeAnalysis] Failed to store initial input for session {}: {}", sessionId, e.getMessage());
        }
    }
}
