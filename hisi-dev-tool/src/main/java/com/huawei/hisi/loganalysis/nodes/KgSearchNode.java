package com.huawei.hisi.loganalysis.nodes;

import com.huawei.hisi.loganalysis.orchestrator.LogAnalysisDagNode;
import com.huawei.hisi.ram.kg.KgMcpClient;
import com.huawei.hisi.ram.kg.dto.CallTreeNode;
import com.huawei.hisi.ram.kg.dto.Entry;
import com.huawei.hisi.ram.kg.dto.Seed;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

/**
 * KgSearchNode - Second node in log analysis DAG.
 *
 * Uses KgMcpClient to search the knowledge graph for methods matching
 * the error stack frames. Finds related code context for analysis.
 *
 * Input: { parsedError, keyFrames, searchTerms, projectPath }
 * Output: { matchedMethods, callChains, entryPoints }
 */
@Slf4j
@Component
public class KgSearchNode implements LogAnalysisDagNode {

    private final KgMcpClient kgMcpClient;

    public KgSearchNode(KgMcpClient kgMcpClient) {
        this.kgMcpClient = kgMcpClient;
    }

    @Override
    public String name() {
        return "KgSearchNode";
    }

    @Override
    public Map<String, Object> execute(Map<String, Object> input) {
        log.info("[KgSearchNode] 开始 KG 检索");

        String projectPathRaw = (String) input.get("projectPath");
        List<String> searchTerms = (List<String>) input.get("searchTerms");
        List<Map<String, Object>> keyFrames = (List<Map<String, Object>>) input.get("keyFrames");

        // Get layered frames from ParseNode (new layered extraction)
        List<Map<String, Object>> businessFrames = (List<Map<String, Object>>) input.get("businessFrames");
        List<Map<String, Object>> rootCauseFrames = (List<Map<String, Object>>) input.get("rootCauseFrames");
        boolean deepMode = Boolean.TRUE.equals(input.get("deepMode"));

        // Diagnostic: show what we got
        log.info("[KgSearchNode] 输入诊断: projectPathRaw长度={}, searchTerms数量={}, keyFrames数量={}, businessFrames={}, rootCauseFrames={}, deepMode={}",
                projectPathRaw != null ? projectPathRaw.length() : 0,
                searchTerms != null ? searchTerms.size() : 0,
                keyFrames != null ? keyFrames.size() : 0,
                businessFrames != null ? businessFrames.size() : 0,
                rootCauseFrames != null ? rootCauseFrames.size() : 0,
                deepMode);

        // Split comma-separated projectPath if needed
        List<String> projectPaths = parseProjectPaths(projectPathRaw);
        log.info("[KgSearchNode] projectPath解析结果: {} 个路径", projectPaths.size());
        if (!projectPaths.isEmpty()) {
            log.info("[KgSearchNode] 第一个路径: {}", projectPaths.get(0));
            if (projectPaths.size() > 1) {
                log.info("[KgSearchNode] 最后一个路径: {}", projectPaths.get(projectPaths.size() - 1));
            }
        }

        if (projectPaths.isEmpty()) {
            log.warn("[KgSearchNode] 缺少 projectPath，跳过 KG 检索");
            return new LinkedHashMap<>(input);
        }

        Map<String, Object> output = new LinkedHashMap<>(input);

        // Use first path for single-path API calls, and the list for multi-path search
        String firstPath = projectPaths.isEmpty() ? "" : projectPaths.get(0);

        // 1. Hybrid search for each search term (use multi-path overload)
        List<Seed> matchedMethods = new ArrayList<>();
        Set<String> seenNodeIds = new HashSet<>();

        if (searchTerms != null) {
            for (String term : searchTerms) {
                if (term == null || term.isBlank()) continue;
                // Use multi-path overload for better coverage
                List<Seed> results = kgMcpClient.hybridSearch(term, projectPaths, 10);
                for (Seed seed : results) {
                    if (!seenNodeIds.contains(seed.nodeId())) {
                        matchedMethods.add(seed);
                        seenNodeIds.add(seed.nodeId());
                    }
                }
            }
        }

        log.info("[KgSearchNode] hybridSearch 检索到 {} 个方法 (搜索词: {})",
                matchedMethods.size(), searchTerms != null ? searchTerms.size() : 0);

        // 2. Find root entry points for business frames (per roundtable conclusion)
        // Default: call kg_root_entries for top 3 business frames
        List<Entry> businessEntryPoints = findEntryPoints(businessFrames, firstPath, 3);
        log.info("[KgSearchNode] 业务帧入口点: {} 个", businessEntryPoints.size());

        // Deep mode: also find entry points for root cause frames
        List<Entry> rootCauseEntryPoints = new ArrayList<>();
        if (deepMode && rootCauseFrames != null && !rootCauseFrames.isEmpty()) {
            rootCauseEntryPoints = findEntryPoints(rootCauseFrames, firstPath, 5);
            log.info("[KgSearchNode] 根因帧入口点: {} 个", rootCauseEntryPoints.size());
        }

        // 3. Build call chains for key stack frames (use firstPath for single-path calls)
        List<Map<String, Object>> callChains = new ArrayList<>();
        if (keyFrames != null) {
            for (Map<String, Object> frame : keyFrames) {
                String className = (String) frame.get("className");
                String methodName = (String) frame.get("methodName");
                if (className == null || methodName == null) continue;

                // Downstream callees tree
                CallTreeNode callees = kgMcpClient.calleesTree(className, methodName, firstPath, 3);
                if (callees != null && callees.nodeId() != null) {
                    Map<String, Object> chainInfo = new LinkedHashMap<>();
                    chainInfo.put("className", className);
                    chainInfo.put("methodName", methodName);
                    chainInfo.put("calleesTree", flattenCallTree(callees));
                    callChains.add(chainInfo);
                }
            }
        }

        log.info("[KgSearchNode] 构建了 {} 个调用链", callChains.size());

        // 4. Combine entry points with layer separation (for ReportNode)
        List<Map<String, Object>> entryPointsWithLayers = new ArrayList<>();
        for (Entry entry : businessEntryPoints) {
            entryPointsWithLayers.add(entryToMap(entry, "business"));
        }
        for (Entry entry : rootCauseEntryPoints) {
            entryPointsWithLayers.add(entryToMap(entry, "rootCause"));
        }

        // Fallback: if no KG entry points found, generate from stack frames (降级策略)
        if (businessEntryPoints.isEmpty() && rootCauseEntryPoints.isEmpty() && keyFrames != null && !keyFrames.isEmpty()) {
            log.warn("[KgSearchNode] KG 入口点为空，启用降级策略：从堆栈帧生成");
            for (Map<String, Object> frame : keyFrames.subList(0, Math.min(keyFrames.size(), 3))) {
                Map<String, Object> fallbackEntry = new LinkedHashMap<>();
                fallbackEntry.put("className", frame.get("className"));
                fallbackEntry.put("methodName", frame.get("methodName"));
                fallbackEntry.put("layer", "fallback");
                fallbackEntry.put("source", "stack_trace");
                fallbackEntry.put("nodeId", null); // No nodeId for fallback
                fallbackEntry.put("type", "UNKNOWN");
                entryPointsWithLayers.add(fallbackEntry);
            }
        }

        // Keep original Entry list for CodeContextNode (needs Entry type)
        List<Entry> allEntryPoints = new ArrayList<>();
        allEntryPoints.addAll(businessEntryPoints);
        allEntryPoints.addAll(rootCauseEntryPoints);

        output.put("matchedMethods", matchedMethods);
        output.put("callChains", callChains);
        output.put("entryPoints", allEntryPoints); // Original Entry list for CodeContextNode
        output.put("entryPointsWithLayers", entryPointsWithLayers); // Map format for ReportNode
        output.put("businessEntryPoints", businessEntryPoints);
        output.put("rootCauseEntryPoints", rootCauseEntryPoints);

        return output;
    }

    /**
     * Find root entry points for a list of frames (with limit).
     * Implements降级策略: returns empty list gracefully on KG failure.
     */
    private List<Entry> findEntryPoints(List<Map<String, Object>> frames, String projectPath, int limit) {
        List<Entry> entries = new ArrayList<>();
        if (frames == null || frames.isEmpty()) {
            return entries;
        }

        int count = 0;
        for (Map<String, Object> frame : frames) {
            if (count >= limit) break;

            String className = (String) frame.get("className");
            String methodName = (String) frame.get("methodName");
            if (className == null || methodName == null) continue;

            try {
                List<Entry> frameEntries = kgMcpClient.rootEntries(className, methodName, projectPath);
                if (frameEntries != null && !frameEntries.isEmpty()) {
                    entries.addAll(frameEntries);
                    count++;
                }
            } catch (Exception e) {
                log.warn("[KgSearchNode] kg_root_entries 调用失败 ({}.{}): {}", className, methodName, e.getMessage());
                // 降级：继续处理下一个帧，不中断流程
            }
        }
        return entries;
    }

    /**
     * Convert Entry to Map with layer info.
     */
    private Map<String, Object> entryToMap(Entry entry, String layer) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("className", entry.className());
        map.put("methodName", entry.methodName());
        map.put("entryType", entry.type());
        map.put("layer", layer);
        map.put("source", "kg_root_entries");
        return map;
    }

    /**
     * Parse comma-separated projectPath into a list of paths.
     * Handles: "path1,path2,path3" → ["path1", "path2", "path3"]
     */
    private List<String> parseProjectPaths(String projectPathRaw) {
        if (projectPathRaw == null || projectPathRaw.isBlank()) {
            return Collections.emptyList();
        }
        // Check if it contains commas (comma-separated multi-path)
        if (projectPathRaw.contains(",")) {
            return Arrays.stream(projectPathRaw.split(","))
                    .map(String::trim)
                    .filter(p -> !p.isEmpty())
                    .collect(Collectors.toList());
        }
        // Single path
        return Collections.singletonList(projectPathRaw.trim());
    }

    /**
     * Flatten a call tree into a list of nodes for easier processing.
     */
    private List<Map<String, Object>> flattenCallTree(CallTreeNode node) {
        List<Map<String, Object>> flat = new ArrayList<>();
        flattenRecursive(node, flat);
        return flat;
    }

    private void flattenRecursive(CallTreeNode node, List<Map<String, Object>> flat) {
        if (node == null) return;

        Map<String, Object> item = new LinkedHashMap<>();
        item.put("nodeId", node.nodeId());
        item.put("className", node.className());
        item.put("methodName", node.methodName());
        item.put("depth", node.depth());
        flat.add(item);

        if (node.children() != null) {
            for (CallTreeNode child : node.children()) {
                flattenRecursive(child, flat);
            }
        }
    }
}