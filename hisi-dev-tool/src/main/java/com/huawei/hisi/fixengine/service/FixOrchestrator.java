package com.huawei.hisi.fixengine.service;

import com.huawei.hisi.fixengine.model.FixSession;
import com.huawei.hisi.fixengine.repository.FixSessionRepository;
import com.huawei.hisi.ram.model.AgentSession;
import com.huawei.hisi.ram.model.SessionType;
import com.huawei.hisi.ram.repository.AgentSessionRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Orchestrates the fix-engine lifecycle: session creation, async flow launch,
 * and status tracking.
 */
@Slf4j
@Service
public class FixOrchestrator {

    private final FixSessionRepository fixSessionRepository;
    private final AgentSessionRepository agentSessionRepository;
    private final FixFlowRunner fixFlowRunner;

    private final ExecutorService executor = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "fix-flow");
        t.setDaemon(true);
        return t;
    });

    public FixOrchestrator(FixSessionRepository fixSessionRepository,
                           AgentSessionRepository agentSessionRepository,
                           FixFlowRunner fixFlowRunner) {
        this.fixSessionRepository = fixSessionRepository;
        this.agentSessionRepository = agentSessionRepository;
        this.fixFlowRunner = fixFlowRunner;
    }

    /**
     * Create a fix session and kick off the async fix flow.
     *
     * @param reportId the log-analysis report to fix
     * @return the persisted {@link FixSession}
     */
    public FixSession startSession(Long reportId) {
        log.info("[FixOrchestrator] starting fix session for reportId={}", reportId);

        // 1. Create AgentSession (type FIX) following the existing RAM pattern
        AgentSession agentSession = AgentSession.newRunning("fix-engine", SessionType.FIX);
        agentSession.setIntent("Auto-fix for report " + reportId);
        AgentSession savedAgent = agentSessionRepository.save(agentSession);

        // 2. Create FixSession
        String branchName = "fix/" + reportId + "-" + UUID.randomUUID().toString().substring(0, 8);
        FixSession fixSession = FixSession.newRunning(reportId, savedAgent.getId(), branchName);
        FixSession saved = fixSessionRepository.save(fixSession);

        log.info("[FixOrchestrator] created fixSession={} agentSession={} branch={}",
                saved.getId(), savedAgent.getId(), branchName);

        // 3. Launch async flow
        CompletableFuture.runAsync(() -> {
            try {
                fixFlowRunner.run(saved);
            } catch (Exception e) {
                log.error("[FixOrchestrator] flow failed for fixSession={}: {}",
                        saved.getId(), e.getMessage(), e);
                saved.setStatus("FAILED");
                fixSessionRepository.update(saved);
            }
        }, executor);

        return saved;
    }
}
