package com.huawei.hisi.ram.phase2v2.impl;

import com.huawei.hisi.ram.kg.KgMcpClient;
import com.huawei.hisi.ram.kg.dto.Bridge;
import com.huawei.hisi.ram.kg.dto.CallTreeNode;
import com.huawei.hisi.ram.kg.dto.Entry;
import com.huawei.hisi.ram.kg.dto.MethodBodyInfo;
import com.huawei.hisi.ram.phase2v2.ChainAnalysisAgent;
import com.huawei.hisi.ram.phase2v2.model.ChainContext;
import com.huawei.hisi.ram.phase2v2.model.ChainReport;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * @deprecated 由 {@link com.huawei.hisi.ram.chat.RamChatOrchestrator} 的工具循环替代。
 */
@Slf4j
@Component
@RequiredArgsConstructor
@Deprecated
public class ClaudeChainAnalysisAgent implements ChainAnalysisAgent {

    private final KgMcpClient kgClient;

    @Override
    public String agentType() {
        return "claude-chain-analysis-v1";
    }

    @Override
    public ChainReport analyze(ChainContext context) {
        log.info("[ChainAgent] Starting analysis for chainId={} chainName={}",
                context.chainId(), context.chainName());

        try {
            ChainReport.KgRawData kgData = collectKgData(context);

            ChainReport.AnalysisResult analysis = new ChainReport.AnalysisResult(
                    "待分析",
                    "",
                    "",
                    "",
                    List.of(),
                    List.of(),
                    new ChainReport.Confidence("insufficient",
                            new ChainReport.KgCoverage(false, false, 0, List.of("骨架实现")),
                            List.of())
            );

            return new ChainReport(
                    context.chainId(),
                    context.chainName(),
                    new ChainReport.EntryPointInfo(
                            context.entryPoint().type(),
                            context.entryPoint().className(),
                            context.entryPoint().methodName(),
                            context.entryPoint().nodeId()
                    ),
                    analysis,
                    kgData,
                    "DONE",
                    null
            );
        } catch (Exception e) {
            log.error("[ChainAgent] Analysis failed for chainId={}: {}",
                    context.chainId(), e.getMessage(), e);

            return new ChainReport(
                    context.chainId(),
                    context.chainName(),
                    null,
                    null,
                    null,
                    "FAILED",
                    e.getMessage()
            );
        }
    }

    private ChainReport.KgRawData collectKgData(ChainContext context) {
        Entry entry = context.entryPoint();
        String projectPath = context.projectPath();

        String className = entry.className();
        String methodName = entry.methodName();

        if (className == null || methodName == null) {
            log.warn("[ChainAgent] Missing className/methodName for chain {}", context.chainId());
            return new ChainReport.KgRawData(List.of(), List.of(), List.of(), List.of());
        }

        List<Map<String, Object>> upstreamChains = new ArrayList<>();
        try {
            List<Entry> affecting = kgClient.affecting(className, methodName, projectPath, 5);
            for (Entry e : affecting) {
                upstreamChains.add(entryToMap(e));
            }
            log.debug("[ChainAgent] Found {} upstream entries", upstreamChains.size());
        } catch (Exception e) {
            log.debug("[ChainAgent] affecting failed: {}", e.getMessage());
        }

        List<Map<String, Object>> downstreamChains = new ArrayList<>();
        try {
            CallTreeNode tree = kgClient.calleesTree(className, methodName, projectPath, 5);
            if (tree != null) {
                downstreamChains.add(callTreeNodeToMap(tree));
            }
            log.debug("[ChainAgent] Found downstream tree with depth {}",
                    tree != null ? tree.depth() : 0);
        } catch (Exception e) {
            log.debug("[ChainAgent] calleesTree failed: {}", e.getMessage());
        }

        try {
            List<Entry> roots = kgClient.rootEntries(className, methodName, projectPath);
            for (Entry e : roots) {
                upstreamChains.add(entryToMap(e));
            }
        } catch (Exception e) {
            log.debug("[ChainAgent] rootEntries failed: {}", e.getMessage());
        }

        List<Map<String, Object>> methodBodies = new ArrayList<>();
        List<String> nodeIds = collectNodeIds(upstreamChains, downstreamChains);
        if (!nodeIds.isEmpty()) {
            try {
                List<MethodBodyInfo> bodies = kgClient.loadMethodBodies(
                        nodeIds.stream().limit(20).toList(), projectPath);
                for (MethodBodyInfo body : bodies) {
                    methodBodies.add(methodBodyToMap(body));
                }
                log.debug("[ChainAgent] Loaded {} method bodies", methodBodies.size());
            } catch (Exception e) {
                log.debug("[ChainAgent] loadMethodBodies failed: {}", e.getMessage());
            }
        }

        List<Map<String, Object>> bridgePoints = new ArrayList<>();
        try {
            List<Bridge> bridges = kgClient.bridges(entry.nodeId(), projectPath);
            for (Bridge b : bridges) {
                bridgePoints.add(bridgeToMap(b));
            }
            log.debug("[ChainAgent] Found {} bridge points", bridgePoints.size());
        } catch (Exception e) {
            log.debug("[ChainAgent] bridges failed: {}", e.getMessage());
        }

        return new ChainReport.KgRawData(upstreamChains, downstreamChains, methodBodies, bridgePoints);
    }

    private Map<String, Object> entryToMap(Entry e) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("nodeId", e.nodeId());
        m.put("className", e.className());
        m.put("methodName", e.methodName());
        m.put("type", e.type());
        return m;
    }

    private Map<String, Object> callTreeNodeToMap(CallTreeNode node) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("nodeId", node.nodeId());
        m.put("className", node.className());
        m.put("methodName", node.methodName());
        m.put("depth", node.depth());
        if (node.children() != null && !node.children().isEmpty()) {
            m.put("children", node.children().stream()
                    .map(this::callTreeNodeToMap)
                    .toList());
        }
        return m;
    }

    private Map<String, Object> methodBodyToMap(MethodBodyInfo body) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("nodeId", body.nodeId());
        m.put("className", body.className());
        m.put("methodName", body.methodName());
        m.put("filePath", body.filePath());
        m.put("methodBody", body.methodBody());
        m.put("description", body.description());
        return m;
    }

    private Map<String, Object> bridgeToMap(Bridge b) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("nodeId", b.nodeId());
        m.put("bridgeType", b.bridgeType());
        m.put("target", b.target());
        return m;
    }

    private List<String> collectNodeIds(
            List<Map<String, Object>> upstream,
            List<Map<String, Object>> downstream) {
        List<String> nodeIds = new ArrayList<>();

        for (Map<String, Object> m : upstream) {
            String nodeId = (String) m.get("nodeId");
            if (nodeId != null && !nodeId.isBlank()) {
                nodeIds.add(nodeId);
            }
        }

        collectNodeIdsFromTree(downstream, nodeIds);

        return nodeIds;
    }

    @SuppressWarnings("unchecked")
    private void collectNodeIdsFromTree(List<Map<String, Object>> tree, List<String> nodeIds) {
        for (Map<String, Object> node : tree) {
            String nodeId = (String) node.get("nodeId");
            if (nodeId != null && !nodeId.isBlank()) {
                nodeIds.add(nodeId);
            }
            Object children = node.get("children");
            if (children instanceof List<?> list) {
                List<Map<String, Object>> childrenList = (List<Map<String, Object>>) list;
                collectNodeIdsFromTree(childrenList, nodeIds);
            }
        }
    }
}
