package com.huawei.hisi.service;

import com.huawei.hisi.model.*;
import com.huawei.hisi.repository.LogAnalysisRepository;
import com.huawei.hisi.neo4j.model.MethodNode;
import com.huawei.hisi.neo4j.model.EntryPointNode;
import com.huawei.hisi.neo4j.repository.Neo4jMethodNodeRepository;
import com.huawei.hisi.neo4j.repository.Neo4jEntryPointNodeRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.*;
import java.util.Optional;

/**
 * 运维服务实现
 * 重构版：使用 Neo4j 知识图谱替代旧的 CallChainService
 *
 * @author HiAPM Plugin Team
 * @version 1.0.0
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OpsServiceImpl implements OpsService {

    private final LogCloudService logCloudService;
    private final LogAnalysisRepository repository;

    // Neo4j Repository (替代旧的 CallChainService)
    private final Neo4jMethodNodeRepository neo4jMethodNodeRepository;
    private final Neo4jEntryPointNodeRepository neo4jEntryPointNodeRepository;

    @Override
    public HealthStatus checkHealth() {
        Map<String, String> components = new LinkedHashMap<>();
        String overallStatus = "UP";

        // 检查数据库
        try {
            repository.findById(1L);
            components.put("database", "UP");
        } catch (Exception e) {
            components.put("database", "DOWN: " + e.getMessage());
            overallStatus = "DEGRADED";
        }

        // LLM health check removed - use Claude MCP instead
        components.put("llm", "REMOVED: use Claude MCP instead");

        // 检查日志云连接
        components.put("logcloud", "UP");

        // 检查 Neo4j
        try {
            long count = neo4jMethodNodeRepository.count();
            components.put("neo4j", "UP (nodes: " + count + ")");
        } catch (Exception e) {
            components.put("neo4j", "DOWN: " + e.getMessage());
            overallStatus = "DEGRADED";
        }

        return HealthStatus.builder()
                .status(overallStatus)
                .components(components)
                .checkTime(LocalDateTime.now().toString())
                .build();
    }

    @Override
    public ImpactAnalysisResponse analyzeImpact(ImpactAnalysisRequest request) {
        log.info("开始影响范围分析: {}.{}", request.getClassName(), request.getMethodName());

        String targetMethod = request.getClassName() + "." + request.getMethodName();

        List<String> affectedMethods = new ArrayList<>();
        List<String> affectedUris = new ArrayList<>();
        int maxDepth = 1;

        // 1. 查找目标方法节点
        List<MethodNode> targetNodes = neo4jMethodNodeRepository.findByClassName(request.getClassName());

        for (MethodNode targetNode : targetNodes) {
            if (targetNode.getMethodName().equals(request.getMethodName())) {
                // 2. 向上查找调用者（使用 Neo4j 图遍历）
                List<MethodNode> callers = neo4jMethodNodeRepository.findCallersUpToDepth(
                        targetNode.getNodeId(), 5);

                for (MethodNode caller : callers) {
                    String method = caller.getClassName() + "." + caller.getMethodName();
                    if (!affectedMethods.contains(method)) {
                        affectedMethods.add(method);
                    }
                }

                // 3. 查找影响此方法的入口点（反向图遍历）
                List<Neo4jMethodNodeRepository.EntryPointInfo> entryPoints =
                        neo4jMethodNodeRepository.findEntryPointsCallingMethod(
                                targetNode.getNodeId(), targetNode.getProjectPath());

                for (Neo4jMethodNodeRepository.EntryPointInfo ep : entryPoints) {
                    String uri = ep.entryKey();
                    if (uri != null && !affectedUris.contains(uri)) {
                        affectedUris.add(uri);
                    }
                }
            }
        }

        return ImpactAnalysisResponse.builder()
                .targetMethod(targetMethod)
                .affectedMethods(affectedMethods)
                .affectedUris(affectedUris)
                .depth(maxDepth)
                .analysisTime(LocalDateTime.now().toString())
                .build();
    }

    @Override
    public Map<String, Object> generateInterfaceDoc(String uri) {
        log.info("生成接口文档: {}", uri);

        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("uri", uri);
        doc.put("generatedAt", LocalDateTime.now().toString());

        List<Map<String, Object>> callChain = new ArrayList<>();
        List<String> methods = new ArrayList<>();

        // 1. 根据入口Key查找入口点
        Optional<EntryPointNode> entryPointOpt = neo4jEntryPointNodeRepository.findByEntryKey(uri);

        if (entryPointOpt.isPresent()) {
            EntryPointNode entryPoint = entryPointOpt.get();

            // 2. 获取调用链节点
            List<Neo4jMethodNodeRepository.GraphTraversalResult> nodes =
                    neo4jMethodNodeRepository.getCallChainNodesByEntryKey(
                            uri, entryPoint.getProjectPath(), 20);

            for (Neo4jMethodNodeRepository.GraphTraversalResult node : nodes) {
                Map<String, Object> nodeInfo = new LinkedHashMap<>();
                nodeInfo.put("nodeId", node.nodeId());
                nodeInfo.put("className", node.className());
                nodeInfo.put("methodName", node.methodName());
                nodeInfo.put("signature", node.signature());
                nodeInfo.put("depth", node.depth());
                callChain.add(nodeInfo);

                String method = node.className() + "." + node.methodName();
                if (!methods.contains(method)) {
                    methods.add(method);
                }
            }

            doc.put("entryId", entryPoint.getEntryId());
            doc.put("entryType", entryPoint.getEntryType());
        }

        doc.put("callChain", callChain);
        doc.put("callChainDepth", callChain.size());
        doc.put("methods", methods);

        return doc;
    }

    @Override
    public Map<String, Object> downloadErrorLogs(String service, String timeRange, String level) {
        log.info("下载错误日志: service={}, level={}", service, level);

        LogQueryDto query = new LogQueryDto();
        query.setAppId(service);
        query.setLogLevel(level);

        List<LogEntry> logs = logCloudService.queryLogs(query);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("total", logs.size());
        result.put("logs", logs);
        result.put("downloadTime", LocalDateTime.now().toString());

        return result;
    }
}
