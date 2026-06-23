package com.huawei.hisi.ram.nodes;

import com.huawei.hisi.knowledgegraph.model.BridgeStats;
import com.huawei.hisi.ram.kg.KgMcpClient;
import com.huawei.hisi.ram.kg.dto.CallTreeNode;
import com.huawei.hisi.ram.kg.dto.Entry;
import com.huawei.hisi.ram.kg.dto.Seed;
import com.huawei.hisi.workflow.DagNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Project Status Analysis Node (项目现状分析).
 *
 * <p>Collects KG data for a project and generates a comprehensive overview report
 * for new employees to quickly understand the project structure, core call chains,
 * and technology stack.</p>
 *
 * <p>KG tool call chain:
 * <ol>
 *     <li>entryPoints → Get all entry points (Controller, MQ, Feign)</li>
 *     <li>bridgeStats → Get cross-service call statistics</li>
 *     <li>hybridSearch → Find core business methods</li>
 *     <li>calleesTree → Get downstream call chains for top methods</li>
 *     <li>rootEntries → Get upstream entry sources for key methods</li>
 * </ol>
 */
@Slf4j
@Component
public class ProjectOverviewNode implements DagNode {

    private static final String SCHEMA_NAME = "project_overview.output";

    private final KgMcpClient kgClient;
    private final ProjectOverviewLlmClient llmClient;

    public ProjectOverviewNode(KgMcpClient kgClient, ProjectOverviewLlmClient llmClient) {
        this.kgClient = kgClient;
        this.llmClient = llmClient;
    }

    @Override
    public String name() {
        return "project_overview";
    }

    @Override
    public String agentId() {
        return "project-overview-v1";
    }

    @Override
    public Map<String, Object> execute(Map<String, Object> input) {
        if (input == null) {
            throw new IllegalArgumentException("ProjectOverviewNode input must not be null");
        }

        String projectPath = (String) input.get("projectPath");
        if (projectPath == null || projectPath.isBlank()) {
            throw new IllegalArgumentException("projectPath is required");
        }

        String mode = (String) input.getOrDefault("mode", "quick");
        String question = (String) input.getOrDefault("question", "");

        log.info("[RAM][ProjectOverviewNode] execute projectPath={} mode={} question={}", projectPath, mode, question);

        // Step 1: Collect KG data (customized by question if provided)
        ProjectOverviewContext context = collectKgData(projectPath, question);

        // Step 2: Generate report via LLM (with question for customized prompt)
        Map<String, Object> report = llmClient.generate(context, projectPath, question);

        // Normalize output
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("project_path", projectPath);
        output.put("mode", mode);
        output.put("question", question);
        output.put("entry_points_summary", report.getOrDefault("entry_points_summary", ""));
        output.put("core_call_chains", report.getOrDefault("core_call_chains", List.of()));
        output.put("modules_analysis", report.getOrDefault("modules_analysis", ""));
        output.put("tech_stack", report.getOrDefault("tech_stack", Map.of()));
        output.put("recommendations", report.getOrDefault("recommendations", List.of()));
        output.put("markdown_report", report.getOrDefault("markdown_report", ""));
        output.put("success", true);

        return output;
    }

    /**
     * Collect KG data following the tool call chain.
     * If question is provided, uses hybridSearch to find relevant methods.
     */
    private ProjectOverviewContext collectKgData(String projectPath, String question) {
        ProjectOverviewContext ctx = new ProjectOverviewContext();
        ctx.projectPath = projectPath;
        ctx.question = question;

        // 1. Entry points
        try {
            ctx.entryPoints = kgClient.entryPoints(projectPath, "ALL");
            log.info("[RAM][ProjectOverviewNode] entryPoints: {} found", ctx.entryPoints.size());
        } catch (Exception e) {
            log.warn("[RAM][ProjectOverviewNode] entryPoints failed: {}", e.getMessage());
            ctx.entryPoints = List.of();
        }

        // 2. Bridge stats
        try {
            ctx.bridgeStats = kgClient.bridgeStats(projectPath);
            log.info("[RAM][ProjectOverviewNode] bridgeStats: totalBridges={}, feign={}, mq={}",
                    ctx.bridgeStats.getTotalBridges(),
                    ctx.bridgeStats.getFeignCallCount(),
                    ctx.bridgeStats.getMqCallCount());
        } catch (Exception e) {
            log.warn("[RAM][ProjectOverviewNode] bridgeStats failed: {}", e.getMessage());
            ctx.bridgeStats = BridgeStats.builder().build();
        }

        // 3. Hybrid search with keyword extraction
        // If question provided, extract keywords via LLM and search multiple times
        List<String> searchKeywords;
        if (!question.isBlank()) {
            searchKeywords = llmClient.extractKeywords(question);
            log.info("[RAM][ProjectOverviewNode] extracted {} keywords from question", searchKeywords.size());
        } else {
            searchKeywords = List.of("main handler process service");
        }

        // Multi-keyword search with deduplication
        java.util.Set<String> seenNodeIds = new java.util.HashSet<>();
        java.util.List<Seed> allMethods = new java.util.ArrayList<>();
        for (String keyword : searchKeywords) {
            try {
                List<Seed> found = kgClient.hybridSearch(keyword, projectPath, 10);
                for (Seed s : found) {
                    if (!seenNodeIds.contains(s.nodeId())) {
                        seenNodeIds.add(s.nodeId());
                        allMethods.add(s);
                    }
                }
                log.debug("[RAM][ProjectOverviewNode] keyword '{}' found {} methods", keyword, found.size());
            } catch (Exception e) {
                log.debug("[RAM][ProjectOverviewNode] hybridSearch for '{}' failed: {}", keyword, e.getMessage());
            }
        }
        ctx.coreMethods = allMethods.stream().limit(20).toList();
        log.info("[RAM][ProjectOverviewNode] coreMethods: {} unique methods from {} keywords",
                ctx.coreMethods.size(), searchKeywords.size());

        // 4. Callees tree for top methods (up to 5)
        ctx.callChains = new ArrayList<>();
        for (Seed seed : ctx.coreMethods.stream().limit(5).toList()) {
            try {
                CallTreeNode tree = extractCalleesTree(seed, projectPath);
                if (tree != null && tree.children() != null && !tree.children().isEmpty()) {
                    ctx.callChains.add(tree);
                }
            } catch (Exception e) {
                log.debug("[RAM][ProjectOverviewNode] calleesTree failed for {}: {}", seed.nodeId(), e.getMessage());
            }
        }
        log.info("[RAM][ProjectOverviewNode] callChains collected: {} trees", ctx.callChains.size());

        // 5. Root entries for key entry points (up to 8)
        ctx.rootEntries = new ArrayList<>();
        for (Entry entry : ctx.entryPoints.stream().limit(8).toList()) {
            try {
                List<Entry> roots = kgClient.rootEntries(entry.className(), entry.methodName(), projectPath);
                ctx.rootEntries.addAll(roots);
            } catch (Exception e) {
                log.debug("[RAM][ProjectOverviewNode] rootEntries failed for {}: {}", entry.nodeId(), e.getMessage());
            }
        }
        log.info("[RAM][ProjectOverviewNode] rootEntries collected: {} entries", ctx.rootEntries.size());

        return ctx;
    }

    /**
     * Extract callees tree from a Seed node.
     */
    private CallTreeNode extractCalleesTree(Seed seed, String projectPath) {
        if (seed.nodeId() == null || seed.nodeId().isBlank()) return null;

        // Parse nodeId format: projectPath:className.methodName.signatureHash
        String[] parts = seed.nodeId().split(":");
        if (parts.length < 2) return null;

        String classMethod = parts[parts.length - 1];
        int lastDot = classMethod.lastIndexOf('.');
        int secondLastDot = classMethod.lastIndexOf('.', lastDot - 1);
        if (secondLastDot < 0 || lastDot < 0) return null;

        String className = classMethod.substring(0, secondLastDot);
        String methodName = classMethod.substring(secondLastDot + 1, lastDot);

        return kgClient.calleesTree(className, methodName, projectPath, 3);
    }

    /**
     * Context object holding all KG data for LLM prompt.
     */
    static class ProjectOverviewContext {
        String projectPath;
        String question;  // User's question for customized analysis
        List<Entry> entryPoints;
        BridgeStats bridgeStats;
        List<Seed> coreMethods;
        List<CallTreeNode> callChains;
        List<Entry> rootEntries;
    }
}