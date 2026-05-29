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
import java.util.List;
import java.util.Map;

@Service
@Slf4j
public class TestScopeService {

    private final RamClaudeJsonClient claudeClient;
    private final ObjectMapper objectMapper;

    public TestScopeService(RamClaudeJsonClient claudeClient, ObjectMapper objectMapper) {
        this.claudeClient = claudeClient;
        this.objectMapper = objectMapper;
    }

    private static final String SYSTEM_PROMPT = """
            你是测试范围分析专家。根据代码变更的影响分析结果，生成测试范围建议。
            输出纯 JSON（不要 markdown 包装），格式:
            {
              "groups": [
                {
                  "entryPointName": "ClassName.methodName",
                  "urlPattern": "/api/xxx",
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
        log.info("[TestScope] Generating test scope for {} entry points",
                impactResult.getAffectedEntryPoints() != null ? impactResult.getAffectedEntryPoints().size() : 0);

        if (!claudeClient.isAvailable()) {
            log.warn("[TestScope] Claude API not available, returning empty result");
            return TestScopeResult.builder()
                    .groups(new ArrayList<>())
                    .regressionSuggestions(List.of("Claude API unavailable — manual test scope recommended"))
                    .build();
        }

        try {
            String userPrompt = buildPrompt(impactResult, diffResult);
            Map<String, Object> response = claudeClient.callJson(SYSTEM_PROMPT, userPrompt,
                    new SendOptions(claudeClient.defaultModel(), 4096, 0.3, null));

            return parseResponse(response);
        } catch (Exception e) {
            log.error("[TestScope] LLM generation failed: {}", e.getMessage());
            return TestScopeResult.builder()
                    .groups(new ArrayList<>())
                    .regressionSuggestions(List.of("Test scope generation failed: " + e.getMessage()))
                    .build();
        }
    }

    private String buildPrompt(ImpactResult impactResult, DiffResult diffResult) {
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

        sb.append("\n变更文件:\n");
        for (DiffResult.FileDiff file : diffResult.getFiles()) {
            sb.append(String.format("- %s [%s] (+%d/-%d)\n",
                    file.getFilePath(), file.getChangeType(), file.getAdditions(), file.getDeletions()));
        }

        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    private TestScopeResult parseResponse(Map<String, Object> response) {
        List<TestScopeResult.TestCaseGroup> groups = new ArrayList<>();
        List<String> regressionSuggestions = new ArrayList<>();

        Object groupsObj = response.get("groups");
        if (groupsObj instanceof List<?> groupsList) {
            for (Object item : groupsList) {
                if (item instanceof Map<?, ?> rawGroupMap) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> groupMap = (Map<String, Object>) rawGroupMap;
                    List<TestScopeResult.TestCase> testCases = new ArrayList<>();
                    Object casesObj = groupMap.get("testCases");
                    if (casesObj instanceof List<?> casesList) {
                        for (Object c : casesList) {
                            if (c instanceof Map<?, ?> rawCaseMap) {
                                @SuppressWarnings("unchecked")
                                Map<String, Object> caseMap = (Map<String, Object>) rawCaseMap;
                                testCases.add(TestScopeResult.TestCase.builder()
                                        .description(String.valueOf(caseMap.getOrDefault("description", "")))
                                        .riskLevel(String.valueOf(caseMap.getOrDefault("riskLevel", "MEDIUM")))
                                        .reason(String.valueOf(caseMap.getOrDefault("reason", "")))
                                        .build());
                            }
                        }
                    }
                    groups.add(TestScopeResult.TestCaseGroup.builder()
                            .entryPointName(String.valueOf(groupMap.getOrDefault("entryPointName", "")))
                            .urlPattern(String.valueOf(groupMap.getOrDefault("urlPattern", "")))
                            .riskLevel(String.valueOf(groupMap.getOrDefault("riskLevel", "MEDIUM")))
                            .testCases(testCases)
                            .build());
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
}
