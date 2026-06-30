package com.huawei.hisi.ram.nodes.impact;

import com.huawei.hisi.ram.kg.KgMcpClient;
import com.huawei.hisi.ram.kg.dto.MethodBodyInfo;
import com.huawei.hisi.ram.kg.dto.Seed;
import com.huawei.hisi.ram.nodes.impl.RamClaudeJsonClient;
import com.huawei.hisi.ram.sdk.SendOptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * AI gatekeeper: loads candidate method source code and asks Claude to judge
 * which methods are truly relevant to the user's requirement.
 *
 * <p>Inserted between InvolvedRing (search results) and ModifiedRing (call
 * tree expansion) — the highest-leverage filtering point. By removing
 * irrelevant seeds <em>before</em> graph traversal, the downstream impact
 * ring produces dramatically fewer nodes.</p>
 *
 * <p>Graceful degradation: returns the original candidate list when Claude
 * is unavailable or the analysis fails.</p>
 */
@Component
public class ScopeNarrowingService {

    private static final Logger log = LoggerFactory.getLogger(ScopeNarrowingService.class);

    /** Truncate long method bodies to prevent prompt overflow. */
    private static final int MAX_METHOD_BODY_CHARS = 2000;

    /** Max user prompt length (chars) per Claude call — controls context budget. */
    private static final int MAX_PROMPT_CHARS = 80_000;

    private static final String SYSTEM_PROMPT = """
            你是一名资深 Java 架构师，负责判断代码方法与需求的关联性。

            给定一个需求描述和一组候选方法（含源代码），你需要判断每个方法是否真正需要修改。

            判断标准：
            - DIRECT: 方法直接实现需求描述的功能，需要修改
            - INDIRECT: 方法是直接相关方法的依赖，可能受影响
            - IRRELEVANT: 方法与需求无关，是搜索噪声

            返回 JSON:
            {
              "analysis": [
                {"nodeId": "...", "relevance": "DIRECT", "reason": "一句话理由"},
                {"nodeId": "...", "relevance": "INDIRECT", "reason": "一句话理由"}
              ]
            }

            只返回 DIRECT 和 INDIRECT 的条目。IRRELEVANT 的直接省略。
            """;

    private final RamClaudeJsonClient claude;
    private final KgMcpClient kg;

    public ScopeNarrowingService(RamClaudeJsonClient claude, KgMcpClient kg) {
        this.claude = claude;
        this.kg = kg;
    }

    /**
     * Filter candidates to only those Claude confirms are relevant.
     * Splits candidates into batches to avoid prompt overflow.
     *
     * @param intent      the user's requirement description
     * @param candidates  seed nodes from search
     * @param projectPath project path for loading method bodies
     * @return filtered list (DIRECT + INDIRECT only); original list on degradation
     */
    public List<Seed> narrow(String intent, List<Seed> candidates, String projectPath) {
        if (candidates == null || candidates.isEmpty()) {
            return candidates != null ? candidates : List.of();
        }
        if (!claude.isAvailable()) {
            log.info("[ScopeNarrowingService] Claude unavailable — skipping AI filtering");
            return candidates;
        }

        try {
            // 1. Batch-load method bodies
            List<String> nodeIds = candidates.stream()
                    .map(Seed::nodeId)
                    .toList();
            List<MethodBodyInfo> bodies = kg.loadMethodBodies(nodeIds, List.of(projectPath));
            Map<String, MethodBodyInfo> bodyMap = bodies.stream()
                    .collect(Collectors.toMap(
                            MethodBodyInfo::nodeId, b -> b, (a, b) -> a));

            // 2. Split into batches by cumulative prompt length, not fixed count
            Set<String> allRelevantIds = new HashSet<>();
            List<List<Seed>> batches = splitByPromptSize(intent, candidates, bodyMap);
            int totalBatches = batches.size();

            for (int batchIdx = 0; batchIdx < totalBatches; batchIdx++) {
                List<Seed> batch = batches.get(batchIdx);
                String userPrompt = buildUserPrompt(intent, batch, bodyMap);

                try {
                    Map<String, Object> result = claude.callJson(
                            SYSTEM_PROMPT,
                            userPrompt,
                            new SendOptions(claude.defaultModel(), 8192, 0.1, SYSTEM_PROMPT));

                    Set<String> batchRelevant = extractRelevantNodeIds(result);
                    allRelevantIds.addAll(batchRelevant);
                    log.info("[ScopeNarrowingService] batch {}/{}: {} candidates (prompt {} chars) → {} relevant",
                            batchIdx + 1, totalBatches, batch.size(), userPrompt.length(), batchRelevant.size());
                } catch (Exception ex) {
                    log.warn("[ScopeNarrowingService] batch {}/{} failed — keeping all in batch: {}",
                            batchIdx + 1, totalBatches, ex.getMessage());
                    batch.forEach(s -> allRelevantIds.add(s.nodeId()));
                }
            }

            if (allRelevantIds.isEmpty()) {
                log.warn("[ScopeNarrowingService] Claude returned no relevant nodes — keeping all candidates");
                return candidates;
            }

            List<Seed> narrowed = candidates.stream()
                    .filter(s -> allRelevantIds.contains(s.nodeId()))
                    .toList();

            log.info("[ScopeNarrowingService] narrowed {} candidates → {} relevant (removed {})",
                    candidates.size(), narrowed.size(), candidates.size() - narrowed.size());
            return narrowed;

        } catch (Exception ex) {
            log.warn("[ScopeNarrowingService] AI filtering failed — keeping all candidates: {}",
                    ex.getMessage());
            return candidates;
        }
    }

    private String buildUserPrompt(String intent, List<Seed> candidates,
                                   Map<String, MethodBodyInfo> bodyMap) {
        StringBuilder sb = new StringBuilder();
        sb.append("## 需求\n").append(intent).append("\n\n## 候选方法\n\n");

        for (int i = 0; i < candidates.size(); i++) {
            Seed seed = candidates.get(i);
            MethodBodyInfo body = bodyMap.get(seed.nodeId());

            sb.append("### 方法 ").append(i + 1).append("\n");
            sb.append("- nodeId: ").append(seed.nodeId()).append("\n");

            if (body != null) {
                sb.append("- class: ").append(body.className()).append("\n");
                sb.append("- method: ").append(body.methodName()).append("\n");
                if (body.description() != null) {
                    sb.append("- description: ").append(body.description()).append("\n");
                }
                String code = body.methodBody() != null ? body.methodBody() : "(无源码)";
                if (code.length() > MAX_METHOD_BODY_CHARS) {
                    code = code.substring(0, MAX_METHOD_BODY_CHARS) + "\n// ... truncated";
                }
                sb.append("```java\n").append(code).append("\n```\n\n");
            } else {
                sb.append("- summary: ").append(seed.summary()).append("\n");
                sb.append("- (无法加载源码)\n\n");
            }
        }
        return sb.toString();
    }

    /**
     * Parse Claude's JSON response to extract nodeIds with DIRECT or INDIRECT relevance.
     */
    private Set<String> extractRelevantNodeIds(Map<String, Object> result) {
        Set<String> ids = new HashSet<>();
        Object analysis = result.get("analysis");
        if (!(analysis instanceof List<?> list)) {
            return ids;
        }
        for (Object item : list) {
            if (item instanceof Map<?, ?> map) {
                String nodeId = map.get("nodeId") instanceof String s ? s : null;
                String relevance = map.get("relevance") instanceof String s ? s : null;
                if (nodeId != null && !"IRRELEVANT".equalsIgnoreCase(relevance)) {
                    ids.add(nodeId);
                }
            }
        }
        return ids;
    }

    /**
     * Split candidates into batches where each batch's user prompt stays
     * under {@link #MAX_PROMPT_CHARS}. Estimates per-candidate prompt size
     * from method body length to avoid building the full prompt speculatively.
     */
    private List<List<Seed>> splitByPromptSize(String intent, List<Seed> candidates,
                                                Map<String, MethodBodyInfo> bodyMap) {
        // Base overhead: intent section + formatting per candidate
        int baseLen = intent.length() + 50; // "## 需求\n" + intent + "\n\n## 候选方法\n\n"

        List<List<Seed>> batches = new ArrayList<>();
        List<Seed> current = new ArrayList<>();
        int currentLen = baseLen;

        for (Seed seed : candidates) {
            int entryLen = estimateEntryLength(seed, bodyMap);
            if (!current.isEmpty() && currentLen + entryLen > MAX_PROMPT_CHARS) {
                batches.add(List.copyOf(current));
                current = new ArrayList<>();
                currentLen = baseLen;
            }
            current.add(seed);
            currentLen += entryLen;
        }
        if (!current.isEmpty()) {
            batches.add(List.copyOf(current));
        }
        return batches;
    }

    /** Estimate the prompt characters a single candidate entry will occupy. */
    private int estimateEntryLength(Seed seed, Map<String, MethodBodyInfo> bodyMap) {
        MethodBodyInfo body = bodyMap.get(seed.nodeId());
        if (body != null) {
            int codeLen = body.methodBody() != null
                    ? Math.min(body.methodBody().length(), MAX_METHOD_BODY_CHARS)
                    : 10; // "(无源码)"
            // "### 方法 N\n- nodeId: ...\n- class: ...\n- method: ...\n- description: ...\n```java\n...\n```\n\n"
            return 80 + seed.nodeId().length() + body.className().length()
                    + body.methodName().length()
                    + (body.description() != null ? body.description().length() : 0)
                    + codeLen;
        }
        // No body: "### 方法 N\n- nodeId: ...\n- summary: ...\n- (无法加载源码)\n\n"
        return 60 + seed.nodeId().length() + (seed.summary() != null ? seed.summary().length() : 0);
    }
}
