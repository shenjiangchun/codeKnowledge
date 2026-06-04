package com.huawei.hisi.mergeanalysis.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.huawei.hisi.mergeanalysis.model.DiffResult;
import com.huawei.hisi.mergeanalysis.model.ImpactResult;
import com.huawei.hisi.mergeanalysis.model.TestScopeResult;
import com.huawei.hisi.ram.nodes.impl.RamClaudeJsonClient;
import com.huawei.hisi.ram.sdk.SendOptions;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@Slf4j
public class TestScopeService {

    private static final int ENTRY_THRESHOLD = 30;

    private final RamClaudeJsonClient claudeClient;
    private final ObjectMapper objectMapper;

    public TestScopeService(RamClaudeJsonClient claudeClient, ObjectMapper objectMapper) {
        this.claudeClient = claudeClient;
        this.objectMapper = objectMapper;
    }

    // ── Detailed prompt (entry count ≤ threshold) ──
    private static final String SYSTEM_PROMPT_DETAILED = """
            你是测试范围分析专家。根据代码变更的影响分析结果，生成测试范围建议。
            要求：
            1. 每个受影响的入口点都必须出现在至少一个测试组中，不得遗漏任何一个
            2. 相同类/相近功能的入口点可合并为一个测试组，但需在 coveredMethods 中列出所有涵盖的方法
            3. 每组至少给出 2 条测试用例，高风险组至少 3 条
            输出纯 JSON（不要 markdown 包装），格式:
            {
              "groups": [
                {
                  "entryPointName": "ClassName.methodName",
                  "urlPattern": "/api/xxx",
                  "coveredEntryCount": 1,
                  "coveredMethods": "methodName",
                  "riskLevel": "HIGH|MEDIUM|LOW",
                  "testCases": [
                    {"description": "测试描述", "riskLevel": "HIGH|MEDIUM|LOW", "reason": "原因"}
                  ]
                }
              ],
              "regressionSuggestions": ["建议1", "建议2"]
            }
            """;

    // ── Aggregated prompt (entry count > threshold) ──
    private static final String SYSTEM_PROMPT_AGGREGATED = """
            你是测试范围分析专家。根据代码变更的影响分析结果，按 URL 文根聚合生成测试范围建议。
            要求：
            1. 输入中列出的每个文根组都必须在输出中出现，不得遗漏任何文根组
            2. 每个文根组对应一个测试组，用高层概括描述该文根下哪些功能需要回归验证
            3. 不要逐个列出具体方法，而是做业务层面的概括，例如"需求状态流转相关接口需回归，包括创建、状态变更、批量导入等场景"
            4. 每组至少给出 2 条测试用例，高风险组至少 3 条
            5. coveredEntryCount 必须与输入中文根组的入口数一致，coveredMethods 必须涵盖该文根组的所有方法
            输出纯 JSON（不要 markdown 包装），格式:
            {
              "groups": [
                {
                  "urlRoot": "/api/requirement",
                  "coveredEntryCount": 45,
                  "coveredMethods": "createReq, updateStatus, importReq, ...",
                  "riskLevel": "HIGH|MEDIUM|LOW",
                  "testCases": [
                    {"description": "测试描述", "riskLevel": "HIGH|MEDIUM|LOW", "reason": "原因"}
                  ]
                }
              ],
              "regressionSuggestions": ["建议1", "建议2"]
            }
            """;

    @SuppressWarnings("unchecked")
    public TestScopeResult generateTestScope(ImpactResult impactResult, DiffResult diffResult) {
        List<ImpactResult.AffectedEntryPoint> entries = impactResult.getAffectedEntryPoints();
        int entryCount = entries != null ? entries.size() : 0;
        log.info("[TestScope] Generating test scope for {} entry points", entryCount);

        if (!claudeClient.isAvailable()) {
            log.warn("[TestScope] Claude API not available, returning empty result");
            return TestScopeResult.builder()
                    .groups(new ArrayList<>())
                    .regressionSuggestions(List.of("Claude API unavailable — manual test scope recommended"))
                    .build();
        }

        boolean aggregated = entryCount > ENTRY_THRESHOLD;
        try {
            String userPrompt = aggregated
                    ? buildAggregatedPrompt(impactResult, diffResult)
                    : buildDetailedPrompt(impactResult, diffResult);

            String systemPrompt = aggregated ? SYSTEM_PROMPT_AGGREGATED : SYSTEM_PROMPT_DETAILED;
            Map<String, Object> response = claudeClient.callJson(systemPrompt, userPrompt,
                    new SendOptions(claudeClient.defaultModel(), 8192, 0.3, null));

            TestScopeResult result = parseResponse(response, aggregated);
            log.info("[TestScope] Generated {} groups (mode={}, entryCount={})",
                    result.getGroups().size(), aggregated ? "aggregated" : "detailed", entryCount);
            return result;
        } catch (Exception e) {
            log.error("[TestScope] LLM generation failed: {}", e.getMessage());
            return TestScopeResult.builder()
                    .groups(new ArrayList<>())
                    .regressionSuggestions(List.of("Test scope generation failed: " + e.getMessage()))
                    .build();
        }
    }

    // ── Aggregation: group entry points by URL root ──

    /**
     * Extract URL root (文根) from a URL pattern.
     * E.g. "/api/requirement/status" → "/api/requirement",
     *      "/api/rms/change/approve" → "/api/rms/change"
     * Keeps up to 3 path segments (excluding the version segment like /v1/).
     */
    String extractUrlRoot(String urlPattern) {
        if (urlPattern == null || urlPattern.isBlank()) return null;
        String normalized = urlPattern.split("\\?")[0].trim();
        String[] segments = normalized.split("/");
        // Skip leading empty segment from leading /
        int start = 0;
        if (segments.length > 0 && segments[0].isEmpty()) start = 1;
        // Skip version segments like v1, v2
        while (start < segments.length && segments[start].matches("v\\d+.*")) start++;
        // Take up to 2 meaningful segments after api/base prefix
        int end = Math.min(start + 3, segments.length);
        StringBuilder root = new StringBuilder();
        for (int i = 0; i < start + 2 && i < end; i++) {
            if (i < start) continue;
            root.append("/").append(segments[i]);
        }
        // If we only got 1 segment, try to take one more for better grouping
        if (root.length() == 0 && start < segments.length) {
            for (int i = start; i < Math.min(start + 2, segments.length); i++) {
                root.append("/").append(segments[i]);
            }
        }
        String result = root.toString();
        return result.isEmpty() ? normalized : result;
    }

    List<EntryGroup> aggregateByRoot(List<ImpactResult.AffectedEntryPoint> entries) {
        Map<String, EntryGroup> buckets = new LinkedHashMap<>();
        for (var ep : entries) {
            String root;
            String type = ep.getEntryType();
            if ("HTTP".equalsIgnoreCase(type) || "FASTAPI_ROUTE".equalsIgnoreCase(type)) {
                root = extractUrlRoot(ep.getUrlPattern());
                if (root == null || root.isBlank()) root = "HTTP_OTHER";
            } else {
                // Non-HTTP: group by class simple name
                String cls = ep.getClassName();
                root = cls != null && cls.contains(".")
                        ? cls.substring(cls.lastIndexOf('.') + 1)
                        : (cls != null ? cls : "OTHER");
                root = type + ":" + root;
            }
            buckets.computeIfAbsent(root, k -> new EntryGroup(k, type)).add(ep);
        }
        return new ArrayList<>(buckets.values());
    }

    static class EntryGroup {
        final String urlRoot;
        final String entryType;
        final List<ImpactResult.AffectedEntryPoint> entries = new ArrayList<>();

        EntryGroup(String urlRoot, String entryType) {
            this.urlRoot = urlRoot;
            this.entryType = entryType;
        }
        void add(ImpactResult.AffectedEntryPoint ep) { entries.add(ep); }
        int count() { return entries.size(); }
        String methodSummary() {
            return entries.stream()
                    .map(ImpactResult.AffectedEntryPoint::getMethodName)
                    .distinct()
                    .limit(8)
                    .collect(Collectors.joining(", "));
        }
        String classSummary() {
            return entries.stream()
                    .map(e -> {
                        String cls = e.getClassName();
                        return cls != null && cls.contains(".")
                                ? cls.substring(cls.lastIndexOf('.') + 1)
                                : (cls != null ? cls : "?");
                    })
                    .distinct()
                    .limit(5)
                    .collect(Collectors.joining(", "));
        }
    }

    // ── Prompt builders ──

    private String buildDetailedPrompt(ImpactResult impactResult, DiffResult diffResult) {
        StringBuilder sb = new StringBuilder();
        sb.append("变更概述:\n");
        sb.append(String.format("- 分支: %s → %s\n", diffResult.getSourceBranch(), diffResult.getTargetBranch()));
        sb.append(String.format("- 变更文件数: %d, 新增: %d行, 删除: %d行\n",
                diffResult.getTotalFiles(), diffResult.getTotalAdditions(), diffResult.getTotalDeletions()));
        sb.append(String.format("- 风险等级: %s\n", impactResult.getRiskLevel()));
        sb.append(String.format("- 业务影响: %s\n", impactResult.getBusinessImpactSummary()));

        if (impactResult.getAffectedEntryPoints() != null && !impactResult.getAffectedEntryPoints().isEmpty()) {
            sb.append("\n受影响的入口点:\n");
            for (var ep : impactResult.getAffectedEntryPoints()) {
                sb.append(String.format("- [%s] %s.%s", ep.getEntryType(), ep.getClassName(), ep.getMethodName()));
                if (ep.getUrlPattern() != null) sb.append(" (").append(ep.getUrlPattern()).append(")");
                sb.append("\n");
            }
        }

        appendChangedFiles(sb, diffResult);
        return sb.toString();
    }

    private String buildAggregatedPrompt(ImpactResult impactResult, DiffResult diffResult) {
        List<EntryGroup> groups = aggregateByRoot(impactResult.getAffectedEntryPoints());

        StringBuilder sb = new StringBuilder();
        sb.append("变更概述:\n");
        sb.append(String.format("- 分支: %s → %s\n", diffResult.getSourceBranch(), diffResult.getTargetBranch()));
        sb.append(String.format("- 变更文件数: %d, 新增: %d行, 删除: %d行\n",
                diffResult.getTotalFiles(), diffResult.getTotalAdditions(), diffResult.getTotalDeletions()));
        sb.append(String.format("- 风险等级: %s\n", impactResult.getRiskLevel()));
        sb.append(String.format("- 业务影响: %s\n", impactResult.getBusinessImpactSummary()));

        sb.append(String.format("\n受影响入口点共 %d 个，按 URL 文根聚合为 %d 组:\n",
                impactResult.getAffectedEntryPoints().size(), groups.size()));
        for (var g : groups) {
            sb.append(String.format("- 文根: %s | 类型: %s | 入口数: %d | 类: %s | 方法: %s\n",
                    g.urlRoot, g.entryType, g.count(), g.classSummary(), g.methodSummary()));
        }

        appendChangedFiles(sb, diffResult);
        return sb.toString();
    }

    private void appendChangedFiles(StringBuilder sb, DiffResult diffResult) {
        sb.append("\n变更文件:\n");
        for (DiffResult.FileDiff file : diffResult.getFiles()) {
            sb.append(String.format("- %s [%s] (+%d/-%d)\n",
                    file.getFilePath(), file.getChangeType(), file.getAdditions(), file.getDeletions()));
        }
    }

    // ── Response parsing ──

    @SuppressWarnings("unchecked")
    private TestScopeResult parseResponse(Map<String, Object> response, boolean aggregated) {
        List<TestScopeResult.TestCaseGroup> groups = new ArrayList<>();
        List<String> regressionSuggestions = new ArrayList<>();

        Object groupsObj = response.get("groups");
        if (groupsObj instanceof List<?> groupsList) {
            for (Object item : groupsList) {
                if (item instanceof Map<?, ?> rawGroupMap) {
                    Map<String, Object> groupMap = (Map<String, Object>) rawGroupMap;
                    List<TestScopeResult.TestCase> testCases = new ArrayList<>();
                    Object casesObj = groupMap.get("testCases");
                    if (casesObj instanceof List<?> casesList) {
                        for (Object c : casesList) {
                            if (c instanceof Map<?, ?> rawCaseMap) {
                                Map<String, Object> caseMap = (Map<String, Object>) rawCaseMap;
                                testCases.add(TestScopeResult.TestCase.builder()
                                        .description(String.valueOf(caseMap.getOrDefault("description", "")))
                                        .riskLevel(String.valueOf(caseMap.getOrDefault("riskLevel", "MEDIUM")))
                                        .reason(String.valueOf(caseMap.getOrDefault("reason", "")))
                                        .build());
                            }
                        }
                    }

                    TestScopeResult.TestCaseGroup.TestCaseGroupBuilder builder = TestScopeResult.TestCaseGroup.builder()
                            .riskLevel(String.valueOf(groupMap.getOrDefault("riskLevel", "MEDIUM")))
                            .testCases(testCases);

                    if (aggregated) {
                        builder.urlRoot(String.valueOf(groupMap.getOrDefault("urlRoot", "")));
                    } else {
                        builder.entryPointName(String.valueOf(groupMap.getOrDefault("entryPointName", "")))
                                .urlPattern(String.valueOf(groupMap.getOrDefault("urlPattern", "")));
                    }
                    // Both modes: always parse coverage fields
                    builder.coveredEntryCount(parseIntSafe(groupMap.get("coveredEntryCount"), 0))
                            .coveredMethods(String.valueOf(groupMap.getOrDefault("coveredMethods", "")));

                    groups.add(builder.build());
                }
            }
        }

        Object suggestionsObj = response.get("regressionSuggestions");
        if (suggestionsObj instanceof List<?> sugList) {
            for (Object s : sugList) {
                regressionSuggestions.add(String.valueOf(s));
            }
        }

        return TestScopeResult.builder()
                .groups(groups)
                .regressionSuggestions(regressionSuggestions)
                .build();
    }

    private int parseIntSafe(Object val, int defaultVal) {
        if (val instanceof Number n) return n.intValue();
        if (val instanceof String s) {
            try { return Integer.parseInt(s.trim()); } catch (Exception ignored) {}
        }
        return defaultVal;
    }
}
