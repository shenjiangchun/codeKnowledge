package com.huawei.hisi.loganalysis.nodes;

import com.huawei.hisi.loganalysis.orchestrator.LogAnalysisDagNode;
import com.huawei.hisi.ram.kg.KgMcpClient;
import com.huawei.hisi.ram.kg.dto.Entry;
import com.huawei.hisi.ram.kg.dto.MethodBodyInfo;
import com.huawei.hisi.ram.kg.dto.Seed;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

/**
 * CodeContextNode - Third node in log analysis DAG.
 *
 * Loads actual method bodies from the knowledge graph to provide
 * code context for Claude analysis.
 *
 * Input: { matchedMethods, callChains, entryPoints, projectPath }
 * Output: { codeBodies, methodDetails }
 */
@Slf4j
@Component
public class CodeContextNode implements LogAnalysisDagNode {

    private final KgMcpClient kgMcpClient;

    public CodeContextNode(KgMcpClient kgMcpClient) {
        this.kgMcpClient = kgMcpClient;
    }

    @Override
    public String name() {
        return "CodeContextNode";
    }

    @Override
    public Map<String, Object> execute(Map<String, Object> input) {
        log.info("[CodeContextNode] 开始加载代码上下文");

        String projectPathRaw = (String) input.get("projectPath");
        List<String> projectPaths = parseProjectPaths(projectPathRaw);
        List<Seed> matchedMethods = (List<Seed>) input.get("matchedMethods");
        List<Entry> entryPoints = (List<Entry>) input.get("entryPoints");
        List<Map<String, Object>> callChains = (List<Map<String, Object>>) input.get("callChains");

        Map<String, Object> output = new LinkedHashMap<>(input);

        // Collect all unique nodeIds to load
        Set<String> nodeIdsToLoad = new LinkedHashSet<>();

        // From matched methods
        if (matchedMethods != null) {
            for (Seed seed : matchedMethods) {
                if (seed.nodeId() != null) {
                    nodeIdsToLoad.add(seed.nodeId());
                }
            }
        }

        // From entry points
        if (entryPoints != null) {
            for (Entry entry : entryPoints) {
                if (entry.nodeId() != null) {
                    nodeIdsToLoad.add(entry.nodeId());
                }
            }
        }

        // From call chains
        if (callChains != null) {
            for (Map<String, Object> chain : callChains) {
                List<Map<String, Object>> flatTree = (List<Map<String, Object>>) chain.get("calleesTree");
                if (flatTree != null) {
                    for (Map<String, Object> node : flatTree) {
                        String nodeId = (String) node.get("nodeId");
                        if (nodeId != null) {
                            nodeIdsToLoad.add(nodeId);
                        }
                    }
                }
            }
        }

        // Limit to top 50 nodes to avoid overloading context
        List<String> limitedNodeIds = nodeIdsToLoad.stream()
                .limit(50)
                .collect(Collectors.toList());

        log.info("[CodeContextNode] 收集 {} 个 nodeIds，将加载 {} 个方法体",
                nodeIdsToLoad.size(), limitedNodeIds.size());

        // Load method bodies
        List<MethodBodyInfo> codeBodies = new ArrayList<>();
        if (!limitedNodeIds.isEmpty() && !projectPaths.isEmpty()) {
            codeBodies = kgMcpClient.loadMethodBodies(limitedNodeIds, projectPaths);
        }

        log.info("[CodeContextNode] 加载了 {} 个方法体", codeBodies.size());

        // Build method details summary for quick reference
        List<Map<String, Object>> methodDetails = new ArrayList<>();
        for (MethodBodyInfo info : codeBodies) {
            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("nodeId", info.nodeId());
            detail.put("className", info.className());
            detail.put("methodName", info.methodName());
            detail.put("filePath", info.filePath());
            detail.put("hasBody", info.methodBody() != null && !info.methodBody().isBlank());
            detail.put("hasDescription", info.description() != null && !info.description().isBlank());
            methodDetails.add(detail);
        }

        output.put("codeBodies", codeBodies);
        output.put("methodDetails", methodDetails);
        output.put("loadedNodeIds", limitedNodeIds);

        return output;
    }

    private List<String> parseProjectPaths(String projectPathRaw) {
        if (projectPathRaw == null || projectPathRaw.isBlank()) {
            return Collections.emptyList();
        }
        if (projectPathRaw.contains(",")) {
            return Arrays.stream(projectPathRaw.split(","))
                    .map(String::trim)
                    .filter(p -> !p.isEmpty())
                    .collect(Collectors.toList());
        }
        return Collections.singletonList(projectPathRaw.trim());
    }
}