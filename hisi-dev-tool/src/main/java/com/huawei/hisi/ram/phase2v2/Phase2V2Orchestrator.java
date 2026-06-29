package com.huawei.hisi.ram.phase2v2;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.huawei.hisi.ram.kg.KgMcpClient;
import com.huawei.hisi.ram.kg.dto.Entry;
import com.huawei.hisi.ram.model.AgentEvent;
import com.huawei.hisi.ram.model.EventType;
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
import java.util.Map;

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

        log.info("[Phase2V2] EntryPoints source={}, count={}",
                inheritedData != null ? "Phase1 checkpoint" : "KG fallback",
                entryPoints.size());

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
     * 从 Phase1 session 加载 checkpoint 数据。
     */
    @SuppressWarnings("unchecked")
    private ChainContext.Phase1InheritedData loadPhase1Checkpoint(String sessionId) {
        try {
            // 解析 backend ID
            Long backendId = sessionRepository.findByUuid(sessionId)
                    .map(s -> s.getId())
                    .orElse(null);

            if (backendId == null) {
                log.warn("[Phase2V2] Parent session not found: {}", sessionId);
                return null;
            }

            // 查找 project_overview CHECKPOINT
            List<AgentEvent> events = eventRepository.findBySessionId(backendId);
            for (int i = events.size() - 1; i >= 0; i--) {
                AgentEvent ev = events.get(i);
                if (ev.getType() != EventType.CHECKPOINT) continue;

                Map<String, Object> payload = parsePayload(ev.getPayload());
                if (!"project_overview".equals(payload.get("nodeName"))) continue;

                Map<String, Object> output = (Map<String, Object>) payload.get("output");
                if (output == null) continue;

                // 提取 entryPoints (raw Entry list stored by Phase1)
                List<Entry> entryPoints = extractEntryPoints(output.get("entry_points"));
                if (entryPoints.isEmpty()) {
                    // Fallback: try entry_points_summary (LLM text, may not parse)
                    entryPoints = extractEntryPoints(output.get("entry_points_summary"));
                }

                // 提取 coreMethods nodeIds
                List<String> coreMethodNodeIds = extractCoreMethodNodeIds(output.get("core_call_chains"));

                return new ChainContext.Phase1InheritedData(
                        entryPoints,
                        0,  // totalBridges (待后续实现)
                        0,  // feignCount
                        0,  // mqCount
                        coreMethodNodeIds
                );
            }

            return null;
        } catch (Exception e) {
            log.warn("[Phase2V2] Failed to load Phase1 checkpoint: {}", e.getMessage());
            return null;
        }
    }

    private Map<String, Object> parsePayload(String json) {
        if (json == null || json.isBlank()) return Map.of();
        try {
            return objectMapper.readValue(json, Map.class);
        } catch (Exception e) {
            return Map.of();
        }
    }

    @SuppressWarnings("unchecked")
    private List<Entry> extractEntryPoints(Object obj) {
        if (obj instanceof List<?> list) {
            return list.stream()
                    .filter(item -> item instanceof Map<?, ?>)
                    .map(item -> {
                        Map<String, Object> m = (Map<String, Object>) item;
                        return new Entry(
                                (String) m.getOrDefault("nodeId", ""),
                                (String) m.getOrDefault("className", ""),
                                (String) m.getOrDefault("methodName", ""),
                                (String) m.getOrDefault("type", "")
                        );
                    })
                    .toList();
        }
        return List.of();
    }

    @SuppressWarnings("unchecked")
    private List<String> extractCoreMethodNodeIds(Object obj) {
        if (obj instanceof List<?> list) {
            return list.stream()
                    .filter(item -> item instanceof Map<?, ?>)
                    .map(item -> {
                        Map<String, Object> m = (Map<String, Object>) item;
                        Object nodeId = m.get("nodeId");
                        return nodeId != null ? nodeId.toString() : "";
                    })
                    .filter(id -> !id.isBlank())
                    .toList();
        }
        return List.of();
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