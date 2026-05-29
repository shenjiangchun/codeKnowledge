package com.huawei.hisi.mergeanalysis.service;

import com.huawei.hisi.mergeanalysis.model.DiffResult;
import com.huawei.hisi.mergeanalysis.model.ImpactResult;
import com.huawei.hisi.ram.kg.KgMcpClient;
import com.huawei.hisi.ram.kg.dto.Entry;
import com.huawei.hisi.ram.nodes.impl.RamClaudeJsonClient;
import com.huawei.hisi.ram.sdk.SendOptions;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

@Service
@Slf4j
public class ImpactAnalysisService {

    private final KgMcpClient kgClient;
    private final RamClaudeJsonClient claudeClient;

    public ImpactAnalysisService(KgMcpClient kgClient, RamClaudeJsonClient claudeClient) {
        this.kgClient = kgClient;
        this.claudeClient = claudeClient;
    }

    private static final String SYSTEM_PROMPT = """
            你是代码影响分析专家。根据变更文件的 diff 信息和知识图谱分析结果，
            评估代码变更对系统的业务影响。输出纯 JSON（不要 markdown 包装），格式:
            {"businessImpactSummary": "...", "riskLevel": "HIGH|MEDIUM|LOW"}
            """;

    @SuppressWarnings("unchecked")
    public ImpactResult analyze(String projectPath, DiffResult diffResult) {
        log.info("[ImpactAnalysis] Analyzing {} changed files for {}", diffResult.getTotalFiles(), projectPath);

        List<ImpactResult.AffectedEntryPoint> allEntryPoints = new ArrayList<>();
        List<ImpactResult.CallChainEdge> allEdges = new ArrayList<>();
        Set<String> seenEntries = new HashSet<>();

        for (DiffResult.FileDiff file : diffResult.getFiles()) {
            String filePath = file.getFilePath();
            if (!filePath.endsWith(".java") && !filePath.endsWith(".py")) {
                continue;
            }

            String className = filePathToClassName(filePath);
            if (className.isEmpty()) continue;

            try {
                List<Entry> rootEntries = kgClient.rootEntries(className, "*", projectPath);
                for (Entry entry : rootEntries) {
                    if (seenEntries.add(entry.nodeId())) {
                        allEntryPoints.add(ImpactResult.AffectedEntryPoint.builder()
                                .nodeId(entry.nodeId())
                                .entryType(entry.type())
                                .className(entry.className())
                                .methodName(entry.methodName())
                                .build());
                    }
                }

                List<Entry> upstreamCallers = kgClient.affecting(className, "*", projectPath, 3);
                for (Entry caller : upstreamCallers) {
                    allEdges.add(ImpactResult.CallChainEdge.builder()
                            .callerId(caller.nodeId())
                            .callerName(caller.className() + "." + caller.methodName())
                            .calleeId(className)
                            .calleeName(filePath)
                            .callType(caller.type())
                            .build());
                }
            } catch (Exception e) {
                log.warn("[ImpactAnalysis] KG query failed for {}: {}", className, e.getMessage());
            }
        }

        String businessImpactSummary = "No LLM analysis available";
        String riskLevel = deriveRiskLevel(diffResult, allEntryPoints.size());

        if (claudeClient.isAvailable()) {
            try {
                String userPrompt = buildPrompt(diffResult, allEntryPoints);
                Map<String, Object> response = claudeClient.callJson(SYSTEM_PROMPT, userPrompt,
                        new SendOptions(claudeClient.defaultModel(), 2048, 0.3, null));
                businessImpactSummary = String.valueOf(response.getOrDefault("businessImpactSummary", businessImpactSummary));
                riskLevel = String.valueOf(response.getOrDefault("riskLevel", riskLevel));
            } catch (Exception e) {
                log.warn("[ImpactAnalysis] LLM analysis failed: {}", e.getMessage());
            }
        }

        return ImpactResult.builder()
                .affectedEntryPoints(allEntryPoints)
                .callChainEdges(allEdges)
                .businessImpactSummary(businessImpactSummary)
                .riskLevel(riskLevel)
                .build();
    }

    private String filePathToClassName(String filePath) {
        String normalized = filePath.replace('\\', '/');
        int srcIdx = normalized.indexOf("src/main/java/");
        if (srcIdx >= 0) {
            String relative = normalized.substring(srcIdx + "src/main/java/".length());
            return relative.replace(".java", "").replace('/', '.');
        }
        if (normalized.endsWith(".py")) {
            return normalized.replace(".py", "").replace('/', '.');
        }
        return "";
    }

    private String deriveRiskLevel(DiffResult diffResult, int entryPointCount) {
        int totalChanges = diffResult.getTotalAdditions() + diffResult.getTotalDeletions();
        if (totalChanges > 500 || entryPointCount > 10) return "HIGH";
        if (totalChanges > 100 || entryPointCount > 3) return "MEDIUM";
        return "LOW";
    }

    private String buildPrompt(DiffResult diffResult, List<ImpactResult.AffectedEntryPoint> entryPoints) {
        StringBuilder sb = new StringBuilder();
        sb.append("变更概述:\n");
        sb.append(String.format("- 分支: %s → %s\n", diffResult.getSourceBranch(), diffResult.getTargetBranch()));
        sb.append(String.format("- 变更文件数: %d, 新增行: %d, 删除行: %d\n",
                diffResult.getTotalFiles(), diffResult.getTotalAdditions(), diffResult.getTotalDeletions()));
        sb.append("\n变更文件:\n");
        for (DiffResult.FileDiff file : diffResult.getFiles()) {
            sb.append(String.format("- %s [%s] (+%d/-%d)\n",
                    file.getFilePath(), file.getChangeType(), file.getAdditions(), file.getDeletions()));
        }
        if (!entryPoints.isEmpty()) {
            sb.append("\n受影响的入口点:\n");
            for (var ep : entryPoints) {
                sb.append(String.format("- [%s] %s.%s\n", ep.getEntryType(), ep.getClassName(), ep.getMethodName()));
            }
        }
        return sb.toString();
    }
}
