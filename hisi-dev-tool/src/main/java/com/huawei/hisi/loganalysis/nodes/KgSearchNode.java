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

        // Diagnostic: show what we got
        log.info("[KgSearchNode] 输入诊断: projectPathRaw长度={}, searchTerms数量={}, keyFrames数量={}",
                projectPathRaw != null ? projectPathRaw.length() : 0,
                searchTerms != null ? searchTerms.size() : 0,
                keyFrames != null ? keyFrames.size() : 0);

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

        // 2. Build call chains for key stack frames (use firstPath for single-path calls)
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

        // 3. Find root entry points that reach the error methods (use firstPath)
        List<Entry> entryPoints = new ArrayList<>();
        if (keyFrames != null && !keyFrames.isEmpty()) {
            // Get first key frame's entry points
            Map<String, Object> firstFrame = keyFrames.get(0);
            String className = (String) firstFrame.get("className");
            String methodName = (String) firstFrame.get("methodName");
            if (className != null && methodName != null) {
                entryPoints = kgMcpClient.rootEntries(className, methodName, firstPath);
            }
        }

        log.info("[KgSearchNode] 找到 {} 个入口点", entryPoints.size());

        output.put("matchedMethods", matchedMethods);
        output.put("callChains", callChains);
        output.put("entryPoints", entryPoints);

        return output;
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