package com.huawei.hisi.ram.phase2v2;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.huawei.hisi.ram.kg.KgMcpClient;
import com.huawei.hisi.ram.kg.dto.Entry;
import com.huawei.hisi.ram.phase2v2.model.ChainContext;
import com.huawei.hisi.ram.phase2v2.model.DetailLayer;
import com.huawei.hisi.ram.phase2v2.model.Phase2V2Report;
import com.huawei.hisi.ram.phase2v2.model.SummaryLayer;
import com.huawei.hisi.ram.repository.AgentEventRepository;
import com.huawei.hisi.ram.repository.AgentSessionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Phase2 V2 多 Agent 协作编排器。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class Phase2V2Orchestrator {

    private final KgMcpClient kgClient;
    private final ChainSplitter chainSplitter;
    private final AgentSessionRepository sessionRepository;
    private final AgentEventRepository eventRepository;
    private final ObjectMapper objectMapper;

    /**
     * 执行 Phase2 V2 分析。
     */
    public Phase2V2Report orchestrate(
            String parentSessionId,
            String question,
            String projectPath) {

        log.info("[Phase2V2] Starting orchestration for parentSession={} question={}",
                parentSessionId, question);

        // Step 1: 继承 Phase1 数据 (骨架: 返回 null)
        ChainContext.Phase1InheritedData inheritedData = loadPhase1Checkpoint(parentSessionId);

        // Step 2: KG entryPoints 匹配
        List<Entry> entryPoints = inheritedData != null && inheritedData.entryPoints() != null
                ? inheritedData.entryPoints()
                : fetchEntryPoints(projectPath);

        // Step 3: 拆分链路
        List<ChainContext> chainContexts = chainSplitter.split(
                entryPoints, question, projectPath, parentSessionId);

        log.info("[Phase2V2] Split into {} chains", chainContexts.size());

        // Step 4-5: 骨架返回空报告
        return new Phase2V2Report(
                new SummaryLayer("", "", List.of(), List.of(), List.of()),
                new DetailLayer(List.of(), 0, 0, 0),
                "RUNNING",
                question
        );
    }

    /**
     * 从 Phase1 session 加载 checkpoint 数据 (骨架)。
     */
    private ChainContext.Phase1InheritedData loadPhase1Checkpoint(String sessionId) {
        // TODO: Task 2.2 实现
        return null;
    }

    /**
     * 直接获取 KG entryPoints (兜底)。
     */
    private List<Entry> fetchEntryPoints(String projectPath) {
        try {
            return kgClient.entryPoints(projectPath, "ALL");
        } catch (Exception e) {
            log.warn("[Phase2V2] Failed to fetch entryPoints: {}", e.getMessage());
            return List.of();
        }
    }
}