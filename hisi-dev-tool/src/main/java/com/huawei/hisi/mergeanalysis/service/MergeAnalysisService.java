package com.huawei.hisi.mergeanalysis.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.huawei.hisi.ram.model.AgentEvent;
import com.huawei.hisi.ram.model.AgentSession;
import com.huawei.hisi.ram.model.EventType;
import com.huawei.hisi.ram.model.SessionStatus;
import com.huawei.hisi.ram.model.SessionType;
import com.huawei.hisi.agentbase.AgentRegistry;
import com.huawei.hisi.workflow.DagExecutor;
import com.huawei.hisi.workflow.ExecutionPolicy;
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

    /**
     * 执行策略取自 {@code AgentRegistry} 里 {@code merge-analysis} 的声明（{@code skipHitl=true}）。
     * 合入分析是一次性分析流，页面上没有节点间确认入口：此前走 {@link DagExecutor#run(List, long, Map)}
     * 的无策略重载，拿到默认策略开了确认门，非首次运行时每个非末位节点后都会停在 WAITING_HITL，且无从确认。
     */
    private static final ExecutionPolicy EXECUTION_POLICY = ExecutionPolicy.fromMetadata(
            AgentRegistry.workflow("merge-analysis").map(w -> w.policy()).orElse(Map.of()));

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
                .sessionType(SessionType.MERGE_ANALYSIS)
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

    public void runAnalysis(long sessionId, String projectPath, String sourceBranch, String targetBranch,
                            java.util.List<java.util.Map<String, Object>> images) {
        CompletableFuture.runAsync(() -> {
            try {
                // Store initial input as USER_MSG event (same pattern as RAM)
                storeInitialInput(sessionId, projectPath, sourceBranch, targetBranch, images);

                // Build input map for DagExecutor
                Map<String, Object> input = new LinkedHashMap<>();
                input.put("projectPath", projectPath);
                input.put("sourceBranch", sourceBranch);
                input.put("targetBranch", targetBranch);
                if (images != null && !images.isEmpty()) {
                    input.put("images", images);
                    log.info("[MergeAnalysis] Session {} — included {} images in input", sessionId, images.size());
                }

                log.info("[MergeAnalysis] Session {} — starting DAG execution", sessionId);
                dagExecutor.run(dagNodes.nodes(), sessionId, input, EXECUTION_POLICY);
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
                dagExecutor.run(dagNodes.nodes(), sessionId, input, EXECUTION_POLICY);
                log.info("[MergeAnalysis] Session {} — rerun completed", sessionId);

            } catch (Exception e) {
                log.error("[MergeAnalysis] Session {} — rerun failed: {}", sessionId, e.getMessage(), e);
                sessionRepository.updateStatus(sessionId, SessionStatus.FAILED);
            }
        }, asyncExecutor);
    }

    private void storeInitialInput(long sessionId, String projectPath, String sourceBranch, String targetBranch,
                                   java.util.List<java.util.Map<String, Object>> images) {
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            Map<String, Object> initialInput = new LinkedHashMap<>();
            initialInput.put("projectPath", projectPath);
            initialInput.put("sourceBranch", sourceBranch);
            initialInput.put("targetBranch", targetBranch);
            if (images != null && !images.isEmpty()) {
                initialInput.put("images", images);
            }
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
