package com.huawei.hisi.ram.nodes;

import com.huawei.hisi.ram.kg.KgMcpClient;
import com.huawei.hisi.ram.kg.dto.Bridge;
import com.huawei.hisi.ram.kg.dto.CallTreeNode;
import com.huawei.hisi.ram.kg.dto.Entry;
import com.huawei.hisi.ram.kg.dto.MethodBodyInfo;
import com.huawei.hisi.ram.kg.dto.Seed;
import com.huawei.hisi.ram.model.DomainHint;
import com.huawei.hisi.ram.model.Phase2Context;
import com.huawei.hisi.workflow.DagNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * Phase 2 Analysis Node (第二阶段 KG 深度数据收集).
 *
 * <p>Collects deep KG data for a user's follow-up question after the initial
 * project overview analysis. Implements 8-step KG tool call chain:</p>
 *
 * <ol>
 *     <li>extractKeywords → LLM-based keyword extraction from question</li>
 *     <li>inferDomain → Domain type inference based on question keywords</li>
 *     <li>hybridSearch × N → Multi-keyword search with deduplication (max 30)</li>
 *     <li>affecting → Upstream caller chain traversal (maxDepth=5)</li>
 *     <li>calleesTree → Downstream callee chain traversal (maxDepth=5)</li>
 *     <li>rootEntries → Entry point溯源</li>
 *     <li>loadMethodBodies → Code body loading for AI analysis (max 20)</li>
 *     <li>bridges → Bridge point identification (Feign/MQ/Mapper)</li>
 * </ol>
 *
 * <p>Output is a {@link Phase2Context} containing all collected KG data.</p>
 */
@Slf4j
@Component
public class Phase2AnalysisNode implements DagNode {

    private static final int MAX_SEARCH_LIMIT = 10;
    private static final int MAX_CORE_METHODS = 30;
    private static final int MAX_CHAIN_DEPTH = 5;
    private static final int MAX_METHOD_BODIES = 20;
    private static final int MAX_CHAINS_PER_METHOD = 3;

    private final KgMcpClient kgClient;
    private final ProjectOverviewLlmClient llmClient;

    public Phase2AnalysisNode(KgMcpClient kgClient, ProjectOverviewLlmClient llmClient) {
        this.kgClient = kgClient;
        this.llmClient = llmClient;
    }

    @Override
    public String name() {
        return "phase2_analysis";
    }

    @Override
    public String agentId() {
        return "phase2-analysis-v1";
    }

    @Override
    public Map<String, Object> execute(Map<String, Object> input) {
        try {
            validateInput(input);

            String projectPath = (String) input.get("projectPath");
            List<String> projectPaths = List.of(projectPath);
            String question = (String) input.get("question");

            log.info("[RAM][Phase2AnalysisNode] execute projectPath={} question={}", projectPath, question);

            // Step 1: Keyword extraction
            List<String> keywords = extractKeywords(question);
            log.info("[RAM][Phase2AnalysisNode] Step 1: extracted {} keywords: {}", keywords.size(), keywords);

            // Step 2: Domain inference
            DomainHint domainHint = DomainHint.inferDomain(question);
            log.info("[RAM][Phase2AnalysisNode] Step 2: domainHint = {} (tools={}, direction={})",
                    domainHint.analysisType(), domainHint.primaryTools(), domainHint.treeDirection());

            // Step 3: Multi-keyword hybrid search
            List<Seed> coreMethods = multiKeywordSearch(keywords, projectPaths);
            log.info("[RAM][Phase2AnalysisNode] Step 3: found {} unique core methods", coreMethods.size());

            // Build Phase2Context
            Phase2Context.Builder builder = Phase2Context.builder(projectPath, question)
                    .keywords(keywords)
                    .domainHint(domainHint)
                    .coreMethods(coreMethods);

            // Step 4-8: Deep KG data collection based on domainHint
            collectDeepKgData(builder, coreMethods, projectPaths, domainHint);

            Phase2Context context = builder.build();

            // Return output map
            Map<String, Object> output = new LinkedHashMap<>();
            output.put("project_path", projectPath);
            output.put("question", question);
            output.put("keywords", keywords);
            output.put("domain_hint", domainHint);
            output.put("core_methods", coreMethods);
            output.put("upstream_chains", context.upstreamChains());
            output.put("downstream_chains", context.downstreamChains());
            output.put("root_entries", context.rootEntries());
            output.put("method_bodies", context.methodBodies());
            output.put("bridge_points", context.bridgePoints());
            output.put("phase2_context", context);
            output.put("success", true);

            log.info("[RAM][Phase2AnalysisNode] completed: {} upstream chains, {} downstream chains, {} method bodies, {} bridges",
                    context.upstreamChains().size(), context.downstreamChains().size(),
                    context.methodBodies().size(), context.bridgePoints().size());

            return output;
        } catch (Exception e) {
            log.error("[RAM][Phase2AnalysisNode] execute failed: {}", e.getMessage(), e);
            return Map.of("success", false, "error", e.getMessage());
        }
    }

    private void validateInput(Map<String, Object> input) {
        if (input == null) {
            throw new IllegalArgumentException("Phase2AnalysisNode input must not be null");
        }

        String projectPath = (String) input.get("projectPath");
        if (projectPath == null || projectPath.isBlank()) {
            throw new IllegalArgumentException("projectPath is required for phase2 analysis");
        }

        String question = (String) input.get("question");
        if (question == null || question.isBlank()) {
            throw new IllegalArgumentException("question is required for phase2 analysis");
        }
    }

    /**
     * Step 1: Extract keywords from user question via LLM.
     */
    private List<String> extractKeywords(String question) {
        try {
            return llmClient.extractKeywords(question);
        } catch (Exception e) {
            log.warn("[RAM][Phase2AnalysisNode] extractKeywords failed: {}", e.getMessage());
            // Fallback: split by spaces and common delimiters
            return List.of(question.split("[\\s,，。.!！?？]+"));
        }
    }

    /**
     * Step 3: Multi-keyword hybrid search with deduplication.
     */
    private List<Seed> multiKeywordSearch(List<String> keywords, List<String> projectPaths) {
        Set<String> seenNodeIds = new HashSet<>();
        List<Seed> allMethods = new ArrayList<>();

        for (String keyword : keywords) {
            try {
                List<Seed> found = kgClient.hybridSearch(keyword, projectPaths, MAX_SEARCH_LIMIT);
                for (Seed seed : found) {
                    if (seed.nodeId() != null && !seenNodeIds.contains(seed.nodeId())) {
                        seenNodeIds.add(seed.nodeId());
                        allMethods.add(seed);
                    }
                }
                log.debug("[RAM][Phase2AnalysisNode] keyword '{}' found {} methods ({} unique total)",
                        keyword, found.size(), allMethods.size());
            } catch (Exception e) {
                log.debug("[RAM][Phase2AnalysisNode] hybridSearch for '{}' failed: {}", keyword, e.getMessage());
            }

            // Stop if we've reached the limit
            if (allMethods.size() >= MAX_CORE_METHODS) {
                break;
            }
        }

        // Limit to MAX_CORE_METHODS
        return allMethods.stream().limit(MAX_CORE_METHODS).toList();
    }

    /**
     * Steps 4-8: Collect deep KG data based on domain hint.
     */
    private void collectDeepKgData(Phase2Context.Builder builder, List<Seed> coreMethods,
                                    List<String> projectPaths, DomainHint domainHint) {
        List<Entry> upstreamChains = new ArrayList<>();
        List<CallTreeNode> downstreamChains = new ArrayList<>();
        List<Entry> rootEntries = new ArrayList<>();
        List<MethodBodyInfo> methodBodies = new ArrayList<>();
        List<Bridge> bridgePoints = new ArrayList<>();

        // Collect nodeIds for method body loading
        List<String> nodeIdsForBodies = new ArrayList<>();

        // Step 4: Upstream chain traversal (if domain requires)
        if (needsUpstream(domainHint)) {
            for (Seed seed : coreMethods.stream().limit(MAX_CHAINS_PER_METHOD).toList()) {
                ClassMethodParts parts = parseNodeId(seed.nodeId());
                if (parts != null) {
                    try {
                        List<Entry> affecting = kgClient.affecting(parts.className, parts.methodName, projectPaths, MAX_CHAIN_DEPTH);
                        if (affecting != null && !affecting.isEmpty()) {
                            upstreamChains.addAll(affecting);  // affecting returns List<Entry> (upstream callers)
                            // Collect nodeIds for method bodies
                            affecting.stream()
                                    .filter(e -> e.nodeId() != null)
                                    .forEach(e -> nodeIdsForBodies.add(e.nodeId()));
                        }
                        log.debug("[RAM][Phase2AnalysisNode] affecting for {}#{} found {} entries",
                                parts.className, parts.methodName, affecting != null ? affecting.size() : 0);
                    } catch (Exception e) {
                        log.debug("[RAM][Phase2AnalysisNode] affecting failed for {}: {}", seed.nodeId(), e.getMessage());
                    }
                }
            }
        }

        // Step 5: Downstream chain traversal (if domain requires)
        if (needsDownstream(domainHint)) {
            for (Seed seed : coreMethods.stream().limit(MAX_CHAINS_PER_METHOD).toList()) {
                ClassMethodParts parts = parseNodeId(seed.nodeId());
                if (parts != null) {
                    try {
                        CallTreeNode tree = kgClient.calleesTree(parts.className, parts.methodName, projectPaths, MAX_CHAIN_DEPTH);
                        if (tree != null) {
                            downstreamChains.add(tree);
                            // Collect child nodeIds for method bodies
                            collectNodeIdsFromTree(tree, nodeIdsForBodies);
                        }
                        log.debug("[RAM][Phase2AnalysisNode] calleesTree for {}#{} depth={}",
                                parts.className, parts.methodName, tree != null ? tree.depth() : 0);
                    } catch (Exception e) {
                        log.debug("[RAM][Phase2AnalysisNode] calleesTree failed for {}: {}", seed.nodeId(), e.getMessage());
                    }
                }
            }
        }

        // Step 6: Entry point溯源
        for (Seed seed : coreMethods.stream().limit(MAX_CHAINS_PER_METHOD).toList()) {
            ClassMethodParts parts = parseNodeId(seed.nodeId());
            if (parts != null) {
                try {
                    List<Entry> roots = kgClient.rootEntries(parts.className, parts.methodName, projectPaths);
                    if (roots != null && !roots.isEmpty()) {
                        rootEntries.addAll(roots);
                        roots.stream()
                                .filter(e -> e.nodeId() != null)
                                .forEach(e -> nodeIdsForBodies.add(e.nodeId()));
                    }
                    log.debug("[RAM][Phase2AnalysisNode] rootEntries for {}#{} found {} entries",
                            parts.className, parts.methodName, roots != null ? roots.size() : 0);
                } catch (Exception e) {
                    log.debug("[RAM][Phase2AnalysisNode] rootEntries failed for {}: {}", seed.nodeId(), e.getMessage());
                }
            }
        }

        // Deduplicate upstreamChains by nodeId
        List<Entry> uniqueUpstreamChains = deduplicate(upstreamChains, Entry::nodeId);

        // Deduplicate rootEntries by nodeId
        List<Entry> uniqueRootEntries = deduplicate(rootEntries, Entry::nodeId);

        // Step 7: Load method bodies (limit to MAX_METHOD_BODIES)
        List<String> uniqueNodeIds = nodeIdsForBodies.stream().distinct().limit(MAX_METHOD_BODIES).toList();
        if (!uniqueNodeIds.isEmpty()) {
            try {
                methodBodies = kgClient.loadMethodBodies(uniqueNodeIds, projectPaths);
                log.info("[RAM][Phase2AnalysisNode] Step 7: loaded {} method bodies", methodBodies.size());
            } catch (Exception e) {
                log.warn("[RAM][Phase2AnalysisNode] loadMethodBodies failed: {}", e.getMessage());
            }
        }

        // Step 8: Bridge point identification (if domain requires)
        if (domainHint.focusOnBridges()) {
            for (Seed seed : coreMethods.stream().limit(MAX_CHAINS_PER_METHOD).toList()) {
                try {
                    List<Bridge> bridges = kgClient.bridges(seed.nodeId(), projectPaths);
                    if (bridges != null && !bridges.isEmpty()) {
                        bridgePoints.addAll(bridges);
                    }
                    log.debug("[RAM][Phase2AnalysisNode] bridges for {} found {} bridges",
                            seed.nodeId(), bridges != null ? bridges.size() : 0);
                } catch (Exception e) {
                    log.debug("[RAM][Phase2AnalysisNode] bridges failed for {}: {}", seed.nodeId(), e.getMessage());
                }
            }
        }

        // Deduplicate bridgePoints by nodeId
        List<Bridge> uniqueBridges = deduplicate(bridgePoints, Bridge::nodeId);

        // Set all collected data to builder
        builder.upstreamChains(uniqueUpstreamChains)
                .downstreamChains(downstreamChains)
                .rootEntries(uniqueRootEntries)
                .methodBodies(methodBodies)
                .bridgePoints(uniqueBridges);
    }

    /**
     * Check if upstream traversal is needed based on domain hint.
     */
    private boolean needsUpstream(DomainHint domainHint) {
        return domainHint.treeDirection() == DomainHint.TreeDirection.UPSTREAM
                || domainHint.treeDirection() == DomainHint.TreeDirection.BOTH;
    }

    /**
     * Check if downstream traversal is needed based on domain hint.
     */
    private boolean needsDownstream(DomainHint domainHint) {
        return domainHint.treeDirection() == DomainHint.TreeDirection.DOWNSTREAM
                || domainHint.treeDirection() == DomainHint.TreeDirection.BOTH;
    }

    /**
     * Parse nodeId to extract className and methodName.
     * Format: projectPath:className.methodName.signatureHash
     */
    private ClassMethodParts parseNodeId(String nodeId) {
        if (nodeId == null || nodeId.isBlank()) {
            return null;
        }

        // Format: projectPath:className.methodName.signatureHash
        String[] parts = nodeId.split(":");
        if (parts.length < 2) {
            return null;
        }

        String classMethod = parts[parts.length - 1];

        // Find the last two dots: className.methodName.signatureHash
        int lastDot = classMethod.lastIndexOf('.');
        if (lastDot < 0) {
            return null;
        }

        int secondLastDot = classMethod.lastIndexOf('.', lastDot - 1);
        if (secondLastDot < 0) {
            // Maybe no signatureHash, try className.methodName
            String className = classMethod.substring(0, lastDot);
            String methodName = classMethod.substring(lastDot + 1);
            return new ClassMethodParts(className, methodName);
        }

        String className = classMethod.substring(0, secondLastDot);
        String methodName = classMethod.substring(secondLastDot + 1, lastDot);
        return new ClassMethodParts(className, methodName);
    }

    /**
     * Collect nodeIds from a call tree for method body loading.
     */
    private void collectNodeIdsFromTree(CallTreeNode tree, List<String> nodeIds) {
        if (tree == null || tree.nodeId() == null) {
            return;
        }

        nodeIds.add(tree.nodeId());

        if (tree.children() != null) {
            for (CallTreeNode child : tree.children()) {
                collectNodeIdsFromTree(child, nodeIds);
            }
        }
    }

    /**
     * Helper record to hold parsed className and methodName.
     */
    private record ClassMethodParts(String className, String methodName) {}

    /**
     * Generic deduplication by nodeId.
     */
    private <T> List<T> deduplicate(List<T> items, Function<T, String> nodeIdExtractor) {
        Set<String> seen = new HashSet<>();
        List<T> result = new ArrayList<>();
        for (T item : items) {
            String nodeId = nodeIdExtractor.apply(item);
            if (nodeId != null && !seen.contains(nodeId)) {
                seen.add(nodeId);
                result.add(item);
            }
        }
        return result;
    }
}