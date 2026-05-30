package com.huawei.hisi.mergeanalysis.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.huawei.hisi.mergeanalysis.model.DiffResult;
import com.huawei.hisi.mergeanalysis.model.ImpactResult;
import com.huawei.hisi.mergeanalysis.model.TestScopeResult;
import com.huawei.hisi.ram.model.AgentEvent;
import com.huawei.hisi.ram.model.AgentSession;
import com.huawei.hisi.ram.model.EventType;
import com.huawei.hisi.ram.model.SessionStatus;
import com.huawei.hisi.ram.repository.AgentEventRepository;
import com.huawei.hisi.ram.repository.AgentSessionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Service
@RequiredArgsConstructor
@Slf4j
public class MergeAnalysisService {

    private final AgentSessionRepository sessionRepository;
    private final AgentEventRepository eventRepository;
    private final DiffExtractService diffExtractService;
    private final ImpactAnalysisService impactAnalysisService;
    private final TestScopeService testScopeService;
    private final ObjectMapper objectMapper;

    private final ExecutorService asyncExecutor = Executors.newFixedThreadPool(2);

    public long createSession(String projectPath, String sourceBranch, String targetBranch) {
        long now = System.currentTimeMillis() / 1000L;
        AgentSession session = AgentSession.builder()
                .userId("merge-analysis")
                .planId(projectPath)
                .status(SessionStatus.RUNNING)
                .currentNode("diff_extract")
                .stepCount(0)
                .version(0)
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
                // Node 1: DiffExtract
                log.info("[MergeAnalysis] Session {} — starting DiffExtract", sessionId);
                updateCurrentNode(sessionId, "diff_extract");
                DiffResult diffResult = diffExtractService.extractDiff(projectPath, sourceBranch, targetBranch);
                emitCheckpoint(sessionId, "diff_extract", diffResult);

                // Node 2: ImpactAnalysis
                log.info("[MergeAnalysis] Session {} — starting ImpactAnalysis", sessionId);
                updateCurrentNode(sessionId, "impact_analysis");
                ImpactResult impactResult = impactAnalysisService.analyze(projectPath, diffResult);
                emitCheckpoint(sessionId, "impact_analysis", impactResult);

                // Node 3: TestScope
                log.info("[MergeAnalysis] Session {} — starting TestScope", sessionId);
                updateCurrentNode(sessionId, "test_scope");
                TestScopeResult testScopeResult = testScopeService.generateTestScope(impactResult, diffResult);
                emitCheckpoint(sessionId, "test_scope", testScopeResult);

                // Mark done
                sessionRepository.updateStatus(sessionId, SessionStatus.DONE);
                log.info("[MergeAnalysis] Session {} — completed", sessionId);

            } catch (Exception e) {
                log.error("[MergeAnalysis] Session {} — failed: {}", sessionId, e.getMessage(), e);
                emitError(sessionId, e.getMessage());
                sessionRepository.updateStatus(sessionId, SessionStatus.FAILED);
            }
        }, asyncExecutor);
    }

    private void updateCurrentNode(long sessionId, String nodeName) {
        sessionRepository.findById(sessionId).ifPresent(session -> {
            session.setCurrentNode(nodeName);
            sessionRepository.update(session);
        });
    }

    private void emitCheckpoint(long sessionId, String nodeName, Object payload) {
        try {
            String json = objectMapper.writeValueAsString(payload);
            String payloadStr = objectMapper.writeValueAsString(
                    java.util.Map.of("node", nodeName, "data", json));
            AgentEvent event = AgentEvent.builder()
                    .sessionId(sessionId)
                    .seq(0)
                    .type(EventType.CHECKPOINT)
                    .payload(payloadStr)
                    .idempotencyKey(UUID.randomUUID().toString())
                    .cumulativeTokens(0L)
                    .retryCount(0)
                    .circuitState("OK")
                    .costUsdCents(0)
                    .validatorStatus("OK")
                    .createdAt(System.currentTimeMillis() / 1000L)
                    .build();
            eventRepository.append(event);
        } catch (Exception e) {
            log.error("[MergeAnalysis] Failed to emit checkpoint for node {}: {}", nodeName, e.getMessage());
        }
    }

    private void emitError(long sessionId, String errorMessage) {
        try {
            String payloadStr = objectMapper.writeValueAsString(
                    java.util.Map.of("error", errorMessage != null ? errorMessage : "Unknown error"));
            AgentEvent event = AgentEvent.builder()
                    .sessionId(sessionId)
                    .seq(0)
                    .type(EventType.ERROR)
                    .payload(payloadStr)
                    .idempotencyKey(UUID.randomUUID().toString())
                    .cumulativeTokens(0L)
                    .retryCount(0)
                    .circuitState("OK")
                    .costUsdCents(0)
                    .validatorStatus("OK")
                    .createdAt(System.currentTimeMillis() / 1000L)
                    .build();
            eventRepository.append(event);
        } catch (Exception e) {
            log.error("[MergeAnalysis] Failed to emit error event: {}", e.getMessage());
        }
    }
}
