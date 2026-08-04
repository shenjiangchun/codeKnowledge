package com.huawei.hisi.ram.nodes.impact;

import com.huawei.hisi.ram.kg.KgMcpClient;
import com.huawei.hisi.ram.kg.dto.Entry;
import com.huawei.hisi.ram.nodes.impl.RamClaudeJsonClient;
import com.huawei.hisi.ram.sdk.SendOptions;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Uses AI (via {@link RamClaudeJsonClient}) to classify upstream entry points
 * as {@code DIRECT} or {@code INDIRECT} relative to a requirement.
 *
 * <p>When the AI is unavailable or the call fails, all entries are
 * conservatively classified as {@code INDIRECT} with a reason explaining
 * the fallback.</p>
 */
@Slf4j
@Component
public class AffectedEntriesAnnotator {

    private final RamClaudeJsonClient claude;
    private final KgMcpClient kg;

    /**
     * Constructs an annotator with the required AI and knowledge-graph clients.
     *
     * @param claude the JSON-based Claude client for AI classification
     * @param kg     the knowledge-graph MCP client (reserved for future enrichment)
     */
    public AffectedEntriesAnnotator(RamClaudeJsonClient claude, KgMcpClient kg) {
        this.claude = claude;
        this.kg = kg;
    }

    /** An annotated entry with DIRECT/INDIRECT relevance and deep analysis. */
    public record AnnotatedEntry(
            String nodeId, String className, String methodName,
            String type, String relevance, String reason,
            String businessFunction, String impactMechanism,
            String changeBehavior, String callPath) {

        /** Create an entry with deep analysis fields defaulting to empty. */
        public static AnnotatedEntry shallow(String nodeId, String className, String methodName,
                                             String type, String relevance, String reason) {
            return new AnnotatedEntry(nodeId, className, methodName, type, relevance, reason,
                    "", "", "", "");
        }
    }

    /** Result of annotation: split into direct and indirect entries. */
    public record AnnotatedEntries(
            List<AnnotatedEntry> direct, List<AnnotatedEntry> indirect) {}

    private static final String SYSTEM_PROMPT = """
            你是一名资深 Java 架构师，负责判断系统入口与需求的关联性，并给出深度影响分析。

            给定一个需求描述和一组受影响的上游入口（Controller、定时任务、MQ消费者等），
            判断每个入口是"直接相关"还是"间接相关"，并对每个入口提供深度分析。

            判断标准：
            - DIRECT: 此入口的功能与需求直接相关，用户这次改动的逻辑会直接体现在此入口的行为中
            - INDIRECT: 此入口只是调用链上被关联影响的，并非需求直接目标

            对每个入口，必须提供以下深度分析字段：
            - business_function: 此入口的业务功能是什么（如"协作交付接口：下游项目接收上游需求交付的HTTP端点"）
            - impact_mechanism: 修改如何影响到此入口（如"deliver内部调用syncReqStatus，状态回卷逻辑修改会改变deliver的返回状态值"）
            - change_behavior: 修改后此入口的行为变化（如"原逻辑：交付后状态不变；新逻辑：交付后若下游状态>上游则回卷"）

            返回 JSON:
            {
              "analysis": [
                {
                  "nodeId": "...",
                  "relevance": "DIRECT",
                  "reason": "一句话理由",
                  "business_function": "业务功能说明",
                  "impact_mechanism": "影响机制",
                  "change_behavior": "行为变化"
                },
                {
                  "nodeId": "...",
                  "relevance": "INDIRECT",
                  "reason": "一句话理由",
                  "business_function": "...",
                  "impact_mechanism": "...",
                  "change_behavior": "..."
                }
              ]
            }

            核心原则：
            1. business_function 必须说明此入口在系统中的业务角色，不要只重复方法名
            2. impact_mechanism 必须说明调用链上的传导机制，不要只说"会受影响"
            3. change_behavior 必须包含"原逻辑→新逻辑"的对比
            4. INDIRECT条目的深度分析也必须填写，但可以更简略

            只返回 DIRECT 和 INDIRECT 的条目。完全不相关的省略。
            所有自然语言值使用简体中文。
            """;

    /**
     * Classify upstream entries into DIRECT and INDIRECT.
     *
     * @param intent       the user's requirement
     * @param upstream     root entry points from rootEntryAncestors
     * @param targetNodeId the primary method being modified (for context)
     * @param projectPath  Neo4j projectPath
     * @return annotated entries split into direct and indirect
     */
    public AnnotatedEntries annotate(String intent, List<Entry> upstream,
                                     String targetNodeId, String projectPath) {
        if (upstream == null || upstream.isEmpty()) {
            return new AnnotatedEntries(List.of(), List.of());
        }
        if (!claude.isAvailable()) {
            log.info("[AffectedEntriesAnnotator] Claude unavailable -- heuristic fallback");
            return heuristicFallback(upstream, targetNodeId);
        }

        try {
            // Build user prompt
            StringBuilder sb = new StringBuilder();
            sb.append("## 需求\n").append(intent).append("\n\n");
            sb.append("## 修改目标方法\n").append(targetNodeId).append("\n\n");
            sb.append("## 受影响的上游入口\n\n");
            for (int i = 0; i < upstream.size(); i++) {
                Entry e = upstream.get(i);
                sb.append(i + 1).append(". nodeId=").append(e.nodeId());
                if (e.className() != null) {
                    sb.append(" class=").append(e.className());
                }
                if (e.methodName() != null) {
                    sb.append(" method=").append(e.methodName());
                }
                if (e.type() != null) {
                    sb.append(" type=").append(e.type());
                }
                sb.append("\n");
            }

            Map<String, Object> result = claude.callJson(SYSTEM_PROMPT, sb.toString(),
                    new SendOptions(claude.defaultModel(), 4096, 0.1, SYSTEM_PROMPT));

            return parseAnnotationResult(result, upstream);
        } catch (Exception ex) {
            log.warn("[AffectedEntriesAnnotator] AI annotation failed -- heuristic fallback: {}",
                    ex.getMessage());
            return heuristicFallback(upstream, targetNodeId);
        }
    }

    /**
     * Heuristic fallback when AI annotation is unavailable or fails.
     * Classifies entries as DIRECT if their short class#method appears in
     * the target summary string, otherwise INDIRECT.
     */
    private AnnotatedEntries heuristicFallback(List<Entry> upstream, String targetSummary) {
        List<AnnotatedEntry> direct = new ArrayList<>();
        List<AnnotatedEntry> indirect = new ArrayList<>();
        for (Entry e : upstream) {
            String shortRef = shortClassMethod(e.className(), e.methodName());
            boolean matches = shortRef != null && targetSummary != null
                    && targetSummary.contains(shortRef);
            if (matches) {
                direct.add(AnnotatedEntry.shallow(e.nodeId(), e.className(), e.methodName(),
                        e.type(), "DIRECT", "与修改目标直接相关（启发式标注）"));
            } else {
                indirect.add(AnnotatedEntry.shallow(e.nodeId(), e.className(), e.methodName(),
                        e.type(), "INDIRECT", "调用链间接受影响（启发式标注）"));
            }
        }
        return new AnnotatedEntries(direct, indirect);
    }

    private String shortClassMethod(String className, String methodName) {
        if (className == null && methodName == null) return null;
        String shortClass = className;
        if (className != null && className.contains(".")) {
            shortClass = className.substring(className.lastIndexOf('.') + 1);
        }
        if (shortClass != null && methodName != null) return shortClass + "#" + methodName;
        return methodName != null ? methodName : shortClass;
    }

    /**
     * Parse the AI response map into an {@link AnnotatedEntries} result,
     * matching each entry's nodeId against the analysis items.
     *
     * <p>Entries not found in the AI response are classified as INDIRECT
     * with the reason "未标注，默认间接".</p>
     */
    private AnnotatedEntries parseAnnotationResult(Map<String, Object> result,
                                                   List<Entry> upstream) {
        // Map from nodeId → {relevance, reason, business_function, impact_mechanism, change_behavior}
        Map<String, String[]> annotationMap = new HashMap<>();
        Object analysis = result.get("analysis");
        if (analysis instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof Map<?, ?> map) {
                    String nodeId = map.get("nodeId") instanceof String s ? s : null;
                    String relevance = map.get("relevance") instanceof String s ? s : null;
                    String reason = map.get("reason") instanceof String s ? s : "";
                    String businessFunction = map.get("business_function") instanceof String s ? s : "";
                    String impactMechanism = map.get("impact_mechanism") instanceof String s ? s : "";
                    String changeBehavior = map.get("change_behavior") instanceof String s ? s : "";
                    if (nodeId != null && relevance != null) {
                        annotationMap.put(nodeId, new String[]{relevance, reason,
                                businessFunction, impactMechanism, changeBehavior});
                    }
                }
            }
        }

        List<AnnotatedEntry> direct = new ArrayList<>();
        List<AnnotatedEntry> indirect = new ArrayList<>();
        for (Entry e : upstream) {
            String[] anno = annotationMap.get(e.nodeId());
            if (anno != null) {
                String relevance = anno[0];
                String reason = anno[1];
                String businessFunction = anno[2];
                String impactMechanism = anno[3];
                String changeBehavior = anno[4];
                AnnotatedEntry ae = new AnnotatedEntry(e.nodeId(), e.className(), e.methodName(),
                        e.type(), relevance, reason,
                        businessFunction, impactMechanism, changeBehavior, "");
                if ("DIRECT".equalsIgnoreCase(relevance)) {
                    direct.add(ae);
                } else {
                    indirect.add(ae);
                }
            } else {
                AnnotatedEntry ae = AnnotatedEntry.shallow(e.nodeId(), e.className(), e.methodName(),
                        e.type(), "INDIRECT", "未标注，默认间接");
                indirect.add(ae);
            }
        }
        return new AnnotatedEntries(direct, indirect);
    }
}
