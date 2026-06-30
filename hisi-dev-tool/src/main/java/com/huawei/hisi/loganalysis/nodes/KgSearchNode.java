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
 *
 * 改进：递进搜索机制 - 当第一批 keyFrames 搜索结果为空时，继续搜索后续帧
 */
@Slf4j
@Component
public class KgSearchNode implements LogAnalysisDagNode {

    private final KgMcpClient kgMcpClient;

    // 递进搜索配置：每批搜索帧数、最大批次
    private static final int BATCH_SIZE = 5;
    private static final int MAX_BATCHES = 3; // 最多搜索 15 帧

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
        List<Map<String, Object>> otherNonFrameworkFrames = (List<Map<String, Object>>) input.get("otherNonFrameworkFrames");
        boolean deepMode = Boolean.TRUE.equals(input.get("deepMode"));

        // Diagnostic: show what we got
        log.info("[KgSearchNode] 输入诊断: projectPathRaw长度={}, searchTerms数量={}, keyFrames数量={}, businessFrames={}, rootCauseFrames={}, otherNonFramework={}, deepMode={}",
                projectPathRaw != null ? projectPathRaw.length() : 0,
                searchTerms != null ? searchTerms.size() : 0,
                keyFrames != null ? keyFrames.size() : 0,
                businessFrames != null ? businessFrames.size() : 0,
                rootCauseFrames != null ? rootCauseFrames.size() : 0,
                otherNonFrameworkFrames != null ? otherNonFrameworkFrames.size() : 0,
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

        // 1. Hybrid search for each search term (use multi-path overload)
        // 改进：递进搜索机制 - 第一批空时继续搜索后续帧
        List<Seed> matchedMethods = new ArrayList<>();
        Set<String> seenNodeIds = new HashSet<>();
        List<Map<String, Object>> matchedFrames = new ArrayList<>(); // 记录找到 KG 数据的帧

        // 第一批：从 searchTerms（keyFrames）搜索
        if (searchTerms != null && !searchTerms.isEmpty()) {
            matchedMethods = hybridSearchBatch(searchTerms, projectPaths, seenNodeIds, matchedFrames);
        }

        log.info("[KgSearchNode] 第一批搜索结果: {} 个方法 (搜索词: {})",
                matchedMethods.size(), searchTerms != null ? searchTerms.size() : 0);

        // 递进搜索：第一批空时，继续从 otherNonFrameworkFrames 搜索后续帧
        if (matchedMethods.isEmpty() && otherNonFrameworkFrames != null && !otherNonFrameworkFrames.isEmpty()) {
            log.info("[KgSearchNode] 第一批搜索空，启用递进搜索...");

            int totalFrames = otherNonFrameworkFrames.size();
            int searchedFrames = 0;

            for (int batch = 1; batch <= MAX_BATCHES && searchedFrames < totalFrames; batch++) {
                int startIdx = searchedFrames;
                int endIdx = Math.min(startIdx + BATCH_SIZE, totalFrames);

                List<Map<String, Object>> batchFrames = otherNonFrameworkFrames.subList(startIdx, endIdx);
                List<String> batchTerms = buildSearchTermsFromFrames(batchFrames);

                log.info("[KgSearchNode] 递进搜索批次 {}: 帧 {}-{} (共 {} 帧)",
                        batch, startIdx, endIdx - 1, batchFrames.size());

                List<Seed> batchResults = hybridSearchBatch(batchTerms, projectPaths, seenNodeIds, matchedFrames);

                if (!batchResults.isEmpty()) {
                    matchedMethods.addAll(batchResults);
                    log.info("[KgSearchNode] 递进搜索批次 {} 找到 {} 个方法，停止搜索",
                            batch, batchResults.size());
                    break; // 找到数据就停止
                }

                searchedFrames = endIdx;
            }

            log.info("[KgSearchNode] 递进搜索完成: 共搜索 {} 帧，找到 {} 个方法",
                    Math.min(searchedFrames + BATCH_SIZE, totalFrames), matchedMethods.size());
        }

        // 更新 keyFrames：如果递进搜索找到了新帧，将其加入 keyFrames
        if (!matchedFrames.isEmpty() && keyFrames != null) {
            Set<String> existingFrames = keyFrames.stream()
                    .map(f -> f.get("className") + "#" + f.get("methodName"))
                    .collect(Collectors.toSet());

            for (Map<String, Object> frame : matchedFrames) {
                String frameKey = frame.get("className") + "#" + frame.get("methodName");
                if (!existingFrames.contains(frameKey)) {
                    keyFrames.add(frame);
                    existingFrames.add(frameKey);
                }
            }
            log.info("[KgSearchNode] keyFrames 更新: 从 {} 增加到 {} 个",
                    keyFrames.size() - matchedFrames.size(), keyFrames.size());
        }

        log.info("[KgSearchNode] hybridSearch 最终结果: {} 个方法", matchedMethods.size());

        // 2. Find root entry points for business frames (per roundtable conclusion)
        // Default: call kg_root_entries for top 3 business frames
        List<Entry> businessEntryPoints = findEntryPoints(businessFrames, projectPaths, 3);
        log.info("[KgSearchNode] 业务帧入口点: {} 个", businessEntryPoints.size());

        // Deep mode: also find entry points for root cause frames
        List<Entry> rootCauseEntryPoints = new ArrayList<>();
        if (deepMode && rootCauseFrames != null && !rootCauseFrames.isEmpty()) {
            rootCauseEntryPoints = findEntryPoints(rootCauseFrames, projectPaths, 5);
            log.info("[KgSearchNode] 根因帧入口点: {} 个", rootCauseEntryPoints.size());
        }

        // 3. Build call chains for key stack frames (use projectPaths for multi-project callees tree)
        List<Map<String, Object>> callChains = new ArrayList<>();
        if (keyFrames != null) {
            for (Map<String, Object> frame : keyFrames) {
                String className = (String) frame.get("className");
                String methodName = (String) frame.get("methodName");
                if (className == null || methodName == null) continue;

                // Downstream callees tree
                CallTreeNode callees = kgMcpClient.calleesTree(className, methodName, projectPaths, 3);
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
    private List<Entry> findEntryPoints(List<Map<String, Object>> frames, List<String> projectPaths, int limit) {
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
                List<Entry> frameEntries = kgMcpClient.rootEntries(className, methodName, projectPaths);
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
     * 执行一批 hybridSearch 搜索，返回匹配的方法和帧。
     */
    private List<Seed> hybridSearchBatch(List<String> terms, List<String> projectPaths,
                                          Set<String> seenNodeIds, List<Map<String, Object>> matchedFrames) {
        List<Seed> results = new ArrayList<>();

        for (String term : terms) {
            if (term == null || term.isBlank()) continue;

            try {
                List<Seed> seeds = kgMcpClient.hybridSearch(term, projectPaths, 10);
                if (seeds != null && !seeds.isEmpty()) {
                    for (Seed seed : seeds) {
                        if (!seenNodeIds.contains(seed.nodeId())) {
                            results.add(seed);
                            seenNodeIds.add(seed.nodeId());

                            // 记录找到 KG 数据的帧（用于后续分析）
                            // 从 seed.summary 提取 className#methodName
                            if (seed.summary() != null && seed.summary().contains("#")) {
                                String[] parts = seed.summary().split("#");
                                if (parts.length >= 2) {
                                    Map<String, Object> frame = new LinkedHashMap<>();
                                    frame.put("className", parts[0]);
                                    frame.put("methodName", parts[1]);
                                    frame.put("source", "kg_hybrid_search");
                                    matchedFrames.add(frame);
                                }
                            }
                        }
                    }
                }
            } catch (Exception e) {
                log.warn("[KgSearchNode] hybridSearch 失败 (term={}): {}", term, e.getMessage());
            }
        }

        return results;
    }

    /**
     * 从帧列表构建搜索词列表。
     */
    private List<String> buildSearchTermsFromFrames(List<Map<String, Object>> frames) {
        List<String> terms = new ArrayList<>();
        for (Map<String, Object> frame : frames) {
            String className = (String) frame.get("className");
            String methodName = (String) frame.get("methodName");
            if (className != null && methodName != null) {
                // 搜索词：className.methodName
                terms.add(className + "." + methodName);
            }
        }
        return terms;
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