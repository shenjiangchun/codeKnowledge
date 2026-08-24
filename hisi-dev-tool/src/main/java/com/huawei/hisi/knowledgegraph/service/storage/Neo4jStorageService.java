package com.huawei.hisi.knowledgegraph.service.storage;

import com.huawei.hisi.knowledgegraph.model.ClassExtends;
import com.huawei.hisi.knowledgegraph.model.InterfaceImplementation;
import com.huawei.hisi.knowledgegraph.model.MethodOverride;
import com.huawei.hisi.knowledgegraph.model.ProxyRelation;
import com.huawei.hisi.neo4j.model.DataModelNode;
import com.huawei.hisi.neo4j.model.EntryPointNode;
import com.huawei.hisi.neo4j.model.ComponentNode;
import com.huawei.hisi.neo4j.model.ApiClientNode;
import com.huawei.hisi.neo4j.model.FrontendRouteNode;
import com.huawei.hisi.neo4j.model.MethodNode;
import com.huawei.hisi.neo4j.repository.Neo4jDataModelNodeRepository;
import com.huawei.hisi.neo4j.repository.Neo4jEntryPointNodeRepository;
import com.huawei.hisi.neo4j.repository.Neo4jComponentNodeRepository;
import com.huawei.hisi.neo4j.repository.Neo4jApiClientNodeRepository;
import com.huawei.hisi.neo4j.repository.Neo4jFrontendRouteNodeRepository;
import com.huawei.hisi.neo4j.repository.Neo4jMethodNodeRepository;
import com.huawei.hisi.neo4j.repository.Neo4jClassNodeRepository;
import com.huawei.hisi.neo4j.repository.Neo4jServiceNodeRepository;
import com.huawei.hisi.neo4j.repository.ChurnNodeRepository;
import com.huawei.hisi.neo4j.repository.ModuleNodeRepository;
import com.huawei.hisi.neo4j.repository.DomainNodeRepository;
import com.huawei.hisi.neo4j.repository.AggregationCheckpointRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Neo4j 存储服务实现
 * 将知识图谱数据存储到 Neo4j 图数据库
 *
 * 使用独立的事务管理器避免与 JPA 事务冲突
 */
@Service("neo4jStorageService")
@RequiredArgsConstructor
@Slf4j
public class Neo4jStorageService implements KnowledgeGraphStorageService {

    private final Neo4jMethodNodeRepository methodNodeRepository;
    private final Neo4jEntryPointNodeRepository entryPointRepository;
    private final Neo4jDataModelNodeRepository dataModelNodeRepository;
    private final Neo4jComponentNodeRepository componentNodeRepository;
    private final Neo4jApiClientNodeRepository apiClientNodeRepository;
    private final Neo4jFrontendRouteNodeRepository frontendRouteNodeRepository;
    private final Neo4jClassNodeRepository classNodeRepository;
    private final Neo4jServiceNodeRepository serviceNodeRepository;
    private final ChurnNodeRepository churnNodeRepository;
    private final ModuleNodeRepository moduleNodeRepository;
    private final DomainNodeRepository domainNodeRepository;
    private final AggregationCheckpointRepository aggregationCheckpointRepository;

    // ==================== 方法节点操作 ====================

    @Override
    @Transactional(transactionManager = "neo4jTransactionManager")
    public void saveMethodNode(MethodNode node) {
        methodNodeRepository.save(node);
    }

    @Override
    @Transactional(transactionManager = "neo4jTransactionManager")
    public void saveMethodNodes(List<MethodNode> nodes) {
        // 兜底默认值 - 为新增字段填充默认值（向后兼容 Java 调用方）
        nodes.forEach(n -> {
            if (n.getLanguage() == null) n.setLanguage("java");
        });

        // 去重 - 同一个 nodeId 只保留一个
        Map<String, MethodNode> uniqueNodes = new LinkedHashMap<>();
        for (MethodNode node : nodes) {
            uniqueNodes.putIfAbsent(node.getNodeId(), node);
        }
        List<MethodNode> deduplicatedNodes = new ArrayList<>(uniqueNodes.values());

        // Convert to Map format for mergeAll
        List<Map<String, Object>> nodeMaps = deduplicatedNodes.stream()
            .map(n -> {
                Map<String, Object> map = new LinkedHashMap<>();
                map.put("nodeId", n.getNodeId());
                map.put("className", n.getClassName());
                map.put("methodName", n.getMethodName());
                map.put("signature", n.getSignature());
                map.put("description", n.getDescription());
                map.put("filePath", n.getFilePath());
                map.put("startLine", n.getStartLine());
                map.put("endLine", n.getEndLine());
                map.put("complexity", n.getComplexity());
                map.put("methodBody", n.getMethodBody());
                map.put("projectPath", n.getProjectPath());
                map.put("serviceName", n.getServiceName());
                map.put("comment", n.getComment());
                map.put("thrownExceptions", n.getThrownExceptions());
                map.put("caughtExceptions", n.getCaughtExceptions());
                map.put("language", n.getLanguage());
                map.put("packageName", n.getPackageName());
                map.put("codeHash", n.getCodeHash());
                return map;
            })
            .toList();
        methodNodeRepository.mergeAll(nodeMaps);
        log.info("[Neo4j] MERGE 方法节点: {} 个 (去重前: {} 个)", deduplicatedNodes.size(), nodes.size());
    }

    @Override
    @Transactional(transactionManager = "neo4jTransactionManager")
    public void saveComponentNodes(List<ComponentNode> nodes) {
        if (nodes == null || nodes.isEmpty()) {
            return;
        }
        // 去重 - 同一个 componentId 只保留一个
        Map<String, ComponentNode> uniqueNodes = new LinkedHashMap<>();
        for (ComponentNode node : nodes) {
            uniqueNodes.putIfAbsent(node.getComponentId(), node);
        }
        List<ComponentNode> deduplicatedNodes = new ArrayList<>(uniqueNodes.values());

        // Convert to Map format for mergeAll
        List<Map<String, Object>> nodeMaps = deduplicatedNodes.stream()
            .map(n -> {
                Map<String, Object> map = new LinkedHashMap<>();
                map.put("componentId", n.getComponentId());
                map.put("componentName", n.getComponentName());
                map.put("filePath", n.getFilePath());
                map.put("projectPath", n.getProjectPath());
                map.put("language", n.getLanguage());
                map.put("framework", n.getFramework());
                map.put("description", n.getDescription());
                return map;
            })
            .toList();
        componentNodeRepository.mergeAll(nodeMaps);
        log.info("[Neo4j] MERGE 组件节点: {} 个 (去重前: {} 个)", deduplicatedNodes.size(), nodes.size());
    }

    @Override
    @Transactional(transactionManager = "neo4jTransactionManager")
    public void saveApiClientNodes(List<ApiClientNode> nodes) {
        if (nodes == null || nodes.isEmpty()) {
            return;
        }
        Map<String, ApiClientNode> uniqueNodes = new LinkedHashMap<>();
        for (ApiClientNode node : nodes) {
            uniqueNodes.putIfAbsent(node.getApiClientId(), node);
        }
        List<Map<String, Object>> nodeMaps = uniqueNodes.values().stream()
            .map(n -> {
                Map<String, Object> map = new LinkedHashMap<>();
                map.put("apiClientId", n.getApiClientId());
                map.put("method", n.getMethod());
                map.put("url", n.getUrl());
                map.put("sourceFile", n.getSourceFile());
                map.put("componentName", n.getComponentName());
                map.put("projectPath", n.getProjectPath());
                map.put("language", n.getLanguage());
                return map;
            })
            .toList();
        apiClientNodeRepository.mergeAll(nodeMaps);
        log.info("[Neo4j] MERGE API 调用点节点: {} 个", nodeMaps.size());
    }

    @Override
    @Transactional(transactionManager = "neo4jTransactionManager")
    public void saveFrontendRouteNodes(List<FrontendRouteNode> nodes) {
        if (nodes == null || nodes.isEmpty()) {
            return;
        }
        Map<String, FrontendRouteNode> uniqueNodes = new LinkedHashMap<>();
        for (FrontendRouteNode node : nodes) {
            uniqueNodes.putIfAbsent(node.getFrontendRouteId(), node);
        }
        List<Map<String, Object>> nodeMaps = uniqueNodes.values().stream()
            .map(n -> {
                Map<String, Object> map = new LinkedHashMap<>();
                map.put("frontendRouteId", n.getFrontendRouteId());
                map.put("path", n.getPath());
                map.put("name", n.getName());
                map.put("componentName", n.getComponentName());
                map.put("projectPath", n.getProjectPath());
                return map;
            })
            .toList();
        frontendRouteNodeRepository.mergeAll(nodeMaps);
        log.info("[Neo4j] MERGE 前端路由节点: {} 个", nodeMaps.size());
    }

    @Override
    @Transactional(transactionManager = "neo4jTransactionManager")
    public void saveMethodNodesForReuse(List<MethodNode> nodes, String projectPath) {
        if (nodes == null) {
            nodes = List.of();
        }
        // 兜底默认值（与 saveMethodNodes 对齐）
        nodes.forEach(n -> {
            if (n.getLanguage() == null) n.setLanguage("java");
        });

        // 去重
        Map<String, MethodNode> uniqueNodes = new LinkedHashMap<>();
        for (MethodNode node : nodes) {
            uniqueNodes.putIfAbsent(node.getNodeId(), node);
        }
        List<MethodNode> deduplicatedNodes = new ArrayList<>(uniqueNodes.values());

        // 查询现有节点 codeHash 映射（nodeId -> codeHash）
        Map<String, String> existingCodeHash = new HashMap<>();
        if (projectPath != null) {
            methodNodeRepository.findCodeHashByProjectPath(projectPath).forEach(p -> {
                if (p.nodeId() != null) {
                    existingCodeHash.put(p.nodeId(), p.codeHash());
                }
            });
        }

        // 分组：codeHash 命中（复用向量） vs 未命中（重算）
        List<MethodNode> hitNodes = new ArrayList<>();
        List<MethodNode> missNodes = new ArrayList<>();
        for (MethodNode node : deduplicatedNodes) {
            String oldHash = existingCodeHash.get(node.getNodeId());
            String newHash = node.getCodeHash();
            if (oldHash != null && oldHash.equals(newHash)) {
                hitNodes.add(node);
            } else {
                missNodes.add(node);
            }
        }

        // 命中 → mergeAllReuseHit（不碰 description/embedding）
        if (!hitNodes.isEmpty()) {
            methodNodeRepository.mergeAllReuseHit(hitNodes.stream().map(this::toNodeMap).toList());
            log.info("[Neo4j][Reuse] 复用命中 {} 个方法节点（向量保留）", hitNodes.size());
        }
        // 未命中 → mergeAll（覆盖结构字段），并显式清空向量触发重算
        if (!missNodes.isEmpty()) {
            methodNodeRepository.mergeAll(missNodes.stream().map(this::toNodeMap).toList());
            List<String> missNodeIds = missNodes.stream().map(MethodNode::getNodeId).toList();
            methodNodeRepository.clearEmbeddingsByNodeIds(missNodeIds);
            log.info("[Neo4j][Reuse] 未命中 {} 个方法节点（向量重算）", missNodes.size());
        }

        // 孤儿清理：删除「不在本轮 nodeId 集合内」的历史 Method 节点。
        // 空列表（源码清空/解析失败到 0 方法）→ 全删；非空 → 差集删孤儿。
        if (projectPath != null) {
            if (deduplicatedNodes.isEmpty()) {
                methodNodeRepository.deleteByProjectPath(projectPath);
                log.info("[Neo4j][Reuse] 本轮 0 方法，全量删除历史 Method 节点");
            } else {
                List<String> currentNodeIds = deduplicatedNodes.stream()
                        .map(MethodNode::getNodeId)
                        .toList();
                methodNodeRepository.deleteOrphansByProjectPathAndNotInNodeIds(projectPath, currentNodeIds);
            }
        }
        log.info("[Neo4j][Reuse] 保存方法节点完成: 命中 {} / 未命中 {} / 去重前 {}",
                hitNodes.size(), missNodes.size(), nodes.size());
    }

    /**
     * 将 MethodNode 转为 mergeAll 用的 Map（含 description 字段，供 WIPE/未命中路径覆盖）。
     */
    private Map<String, Object> toNodeMap(MethodNode n) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("nodeId", n.getNodeId());
        map.put("className", n.getClassName());
        map.put("methodName", n.getMethodName());
        map.put("signature", n.getSignature());
        map.put("description", n.getDescription());
        map.put("filePath", n.getFilePath());
        map.put("startLine", n.getStartLine());
        map.put("endLine", n.getEndLine());
        map.put("complexity", n.getComplexity());
        map.put("methodBody", n.getMethodBody());
        map.put("projectPath", n.getProjectPath());
        map.put("serviceName", n.getServiceName());
        map.put("comment", n.getComment());
        map.put("thrownExceptions", n.getThrownExceptions());
        map.put("caughtExceptions", n.getCaughtExceptions());
        map.put("language", n.getLanguage());
        map.put("packageName", n.getPackageName());
        map.put("codeHash", n.getCodeHash());
        return map;
    }

    @Override
    @Transactional(transactionManager = "neo4jTransactionManager", readOnly = true)
    public int countMethodNodes(String projectPath) {
        return (int) methodNodeRepository.countByProjectPath(projectPath);
    }

    // ==================== 调用关系操作 ====================

    @Override
    @Transactional(transactionManager = "neo4jTransactionManager")
    public void saveCallRelations(List<Map<String, Object>> relations) {
        if (relations == null || relations.isEmpty()) {
            return;
        }

        // 批量创建关系
        methodNodeRepository.createCallRelations(relations);
        log.info("[Neo4j] 保存调用关系: {} 条", relations.size());
    }

    @Transactional(transactionManager = "neo4jTransactionManager")
    public void saveBridgeRelations(List<Map<String, Object>> relations) {
        if (relations == null || relations.isEmpty()) {
            return;
        }
        methodNodeRepository.createBridgeRelations(relations);
        log.info("[Neo4j] 保存 bridge 关系: {} 条", relations.size());
    }

    /**
     * 批量保存 CONTAINS 关系（codegraph contains 边）。
     * 一期 parent/child 均为 Method 节点。
     */
    @Transactional(transactionManager = "neo4jTransactionManager")
    public void saveContainsRelations(List<Map<String, Object>> relations) {
        if (relations == null || relations.isEmpty()) {
            return;
        }
        methodNodeRepository.createContainsRelations(relations);
        log.info("[Neo4j] 保存 CONTAINS 关系: {} 条", relations.size());
    }

    /**
     * 批量保存 IMPORTS 关系（codegraph imports 边）。
     */
    @Transactional(transactionManager = "neo4jTransactionManager")
    public void saveImportsRelations(List<Map<String, Object>> relations) {
        if (relations == null || relations.isEmpty()) {
            return;
        }
        methodNodeRepository.createImportsRelations(relations);
        log.info("[Neo4j] 保存 IMPORTS 关系: {} 条", relations.size());
    }

    /**
     * 批量保存 REFERENCES 关系（codegraph references 边，回调注册合成）。
     */
    @Transactional(transactionManager = "neo4jTransactionManager")
    public void saveReferencesRelations(List<Map<String, Object>> relations) {
        if (relations == null || relations.isEmpty()) {
            return;
        }
        methodNodeRepository.createReferencesRelations(relations);
        log.info("[Neo4j] 保存 REFERENCES 关系: {} 条", relations.size());
    }

    @Override
    @Transactional(transactionManager = "neo4jTransactionManager", readOnly = true)
    public int countCallRelations(String projectPath) {
        return (int) methodNodeRepository.countCallRelationsByProjectPath(projectPath);
    }

    // ==================== 入口点操作 ====================

    @Override
    @Transactional(transactionManager = "neo4jTransactionManager")
    public void saveEntryPoint(EntryPointNode entry) {
        entryPointRepository.save(entry);
    }

    @Override
    @Transactional(transactionManager = "neo4jTransactionManager")
    public void saveEntryPoints(List<EntryPointNode> entries) {
        // 兜底默认值 - 为新增字段填充默认值（向后兼容 Java 调用方）
        entries.forEach(n -> {
            if (n.getLanguage() == null) n.setLanguage("java");
        });

        // 去重 - 同一个 entryId 只保留一个
        Map<String, EntryPointNode> uniqueEntries = new LinkedHashMap<>();
        for (EntryPointNode entry : entries) {
            uniqueEntries.putIfAbsent(entry.getEntryId(), entry);
        }
        List<EntryPointNode> deduplicatedEntries = new ArrayList<>(uniqueEntries.values());

        // Convert to Map format for mergeAll
        List<Map<String, Object>> entryMaps = deduplicatedEntries.stream()
            .map(e -> {
                Map<String, Object> map = new LinkedHashMap<>();
                map.put("entryId", e.getEntryId());
                map.put("entryType", e.getEntryType());
                map.put("entryKey", e.getEntryKey());
                map.put("entryInfo", e.getEntryInfo());
                map.put("methodNodeId", e.getMethodNodeId());
                map.put("projectPath", e.getProjectPath());
                map.put("briefDescription", e.getBriefDescription());
                map.put("detailedDescription", e.getDetailedDescription());
                map.put("serviceName", e.getServiceName());
                map.put("language", e.getLanguage());
                return map;
            })
            .toList();
        entryPointRepository.mergeAll(entryMaps);
        log.info("[Neo4j] MERGE 入口点: {} 个 (去重前: {} 个)", deduplicatedEntries.size(), entries.size());
    }

    @Override
    @Transactional(transactionManager = "neo4jTransactionManager", readOnly = true)
    public int countEntryPoints(String projectPath) {
        return (int) entryPointRepository.countByProjectPath(projectPath);
    }

    // ==================== 接口实现操作 ====================

    @Override
    @Transactional(transactionManager = "neo4jTransactionManager")
    public void saveInterfaceImplementation(InterfaceImplementation impl) {
        if (impl == null) {
            return;
        }
        List<Map<String, Object>> relations = List.of(toImplementsRelationMap(impl));
        List<String> projectPaths = List.of(impl.getProjectPath());
        methodNodeRepository.createImplementsRelations(relations, projectPaths);
        log.debug("Interface implementation saved: {} -> {}", impl.getInterfaceName(), impl.getImplementationName());
    }

    @Override
    @Transactional(transactionManager = "neo4jTransactionManager")
    public void saveInterfaceImplementations(List<InterfaceImplementation> impls) {
        if (impls == null || impls.isEmpty()) {
            return;
        }
        List<Map<String, Object>> relations = impls.stream()
                .map(Neo4jStorageService::toImplementsRelationMap)
                .toList();
        List<String> projectPaths = impls.stream()
                .map(InterfaceImplementation::getProjectPath)
                .distinct()
                .toList();
        methodNodeRepository.createImplementsRelations(relations, projectPaths);
        log.info("[Neo4j] 保存接口实现: {} 个", impls.size());
    }

    @Override
    @Transactional(transactionManager = "neo4jTransactionManager", readOnly = true)
    public int countInterfaceImplementations(String projectPath) {
        return methodNodeRepository.countImplementsRelations(projectPath);
    }

    private static Map<String, Object> toImplementsRelationMap(InterfaceImplementation impl) {
        return Map.of(
                "interfaceName", impl.getInterfaceName(),
                "implementationName", impl.getImplementationName(),
                "projectPath", impl.getProjectPath(),
                "implType", impl.getImplType()
        );
    }

    // ==================== 类继承关系操作 ====================

    @Override
    @Transactional(transactionManager = "neo4jTransactionManager")
    public void saveClassExtends(ClassExtends extendsRelation) {
        if (extendsRelation == null) {
            return;
        }
        List<Map<String, Object>> relations = List.of(toExtendsRelationMap(extendsRelation));
        List<String> projectPaths = List.of(extendsRelation.getProjectPath());
        methodNodeRepository.createExtendsRelations(relations, projectPaths);
        log.debug("Class extends saved: {} -> {}", extendsRelation.getSubclass(), extendsRelation.getSuperclass());
    }

    @Override
    @Transactional(transactionManager = "neo4jTransactionManager")
    public void saveClassExtends(List<ClassExtends> extendsRelations) {
        if (extendsRelations == null || extendsRelations.isEmpty()) {
            return;
        }
        List<Map<String, Object>> relations = extendsRelations.stream()
                .map(Neo4jStorageService::toExtendsRelationMap)
                .toList();
        List<String> projectPaths = extendsRelations.stream()
                .map(ClassExtends::getProjectPath)
                .distinct()
                .toList();
        methodNodeRepository.createExtendsRelations(relations, projectPaths);
        log.info("[Neo4j] 保存类继承关系: {} 个", extendsRelations.size());
    }

    @Override
    @Transactional(transactionManager = "neo4jTransactionManager", readOnly = true)
    public int countClassExtends(String projectPath) {
        return methodNodeRepository.countExtendsRelations(projectPath);
    }

    private static Map<String, Object> toExtendsRelationMap(ClassExtends extendsRelation) {
        return Map.of(
                "subclass", extendsRelation.getSubclass(),
                "superclass", extendsRelation.getSuperclass(),
                "projectPath", extendsRelation.getProjectPath()
        );
    }

    // ==================== 方法重写关系操作 ====================

    @Override
    @Transactional(transactionManager = "neo4jTransactionManager")
    public void saveMethodOverride(MethodOverride overrideRelation) {
        if (overrideRelation == null) {
            return;
        }
        List<Map<String, Object>> relations = List.of(toOverrideRelationMap(overrideRelation));
        List<String> projectPaths = List.of(overrideRelation.getProjectPath());
        methodNodeRepository.createOverrideRelations(relations, projectPaths);
        log.debug("Method override saved: {}#{} -> {}#{}",
                overrideRelation.getSubclass(), overrideRelation.getMethodName(),
                overrideRelation.getSuperclass(), overrideRelation.getMethodName());
    }

    @Override
    @Transactional(transactionManager = "neo4jTransactionManager")
    public void saveMethodOverrides(List<MethodOverride> overrideRelations) {
        if (overrideRelations == null || overrideRelations.isEmpty()) {
            return;
        }
        List<Map<String, Object>> relations = overrideRelations.stream()
                .map(Neo4jStorageService::toOverrideRelationMap)
                .toList();
        List<String> projectPaths = overrideRelations.stream()
                .map(MethodOverride::getProjectPath)
                .distinct()
                .toList();
        methodNodeRepository.createOverrideRelations(relations, projectPaths);
        log.info("[Neo4j] 保存方法重写关系: {} 个", overrideRelations.size());
    }

    @Override
    @Transactional(transactionManager = "neo4jTransactionManager", readOnly = true)
    public int countMethodOverrides(String projectPath) {
        return methodNodeRepository.countOverrideRelations(projectPath);
    }

    private static Map<String, Object> toOverrideRelationMap(MethodOverride overrideRelation) {
        return Map.of(
                "subclass", overrideRelation.getSubclass(),
                "superclass", overrideRelation.getSuperclass(),
                "methodName", overrideRelation.getMethodName(),
                "projectPath", overrideRelation.getProjectPath()
        );
    }

    // ==================== 代理类关系操作 ====================

    @Override
    @Transactional(transactionManager = "neo4jTransactionManager")
    public void saveProxyRelation(ProxyRelation proxyRelation) {
        if (proxyRelation == null) {
            return;
        }
        List<Map<String, Object>> relations = List.of(toProxyRelationMap(proxyRelation));
        List<String> projectPaths = List.of(proxyRelation.getProjectPath());
        methodNodeRepository.createProxyRelations(relations, projectPaths);
        log.debug("Proxy relation saved: {} -> {} ({})",
                proxyRelation.getProxyClass(), proxyRelation.getTargetClass(), proxyRelation.getProxyType());
    }

    @Override
    @Transactional(transactionManager = "neo4jTransactionManager")
    public void saveProxyRelations(List<ProxyRelation> proxyRelations) {
        if (proxyRelations == null || proxyRelations.isEmpty()) {
            return;
        }
        List<Map<String, Object>> relations = proxyRelations.stream()
                .map(Neo4jStorageService::toProxyRelationMap)
                .toList();
        List<String> projectPaths = proxyRelations.stream()
                .map(ProxyRelation::getProjectPath)
                .distinct()
                .toList();
        methodNodeRepository.createProxyRelations(relations, projectPaths);
        log.info("[Neo4j] 保存代理类关系: {} 个", proxyRelations.size());
    }

    @Override
    @Transactional(transactionManager = "neo4jTransactionManager", readOnly = true)
    public int countProxyRelations(String projectPath) {
        return methodNodeRepository.countProxyRelations(projectPath);
    }

    private static Map<String, Object> toProxyRelationMap(ProxyRelation proxyRelation) {
        return Map.of(
                "proxyClass", proxyRelation.getProxyClass(),
                "targetClass", proxyRelation.getTargetClass(),
                "proxyType", proxyRelation.getProxyType(),
                "projectPath", proxyRelation.getProjectPath()
        );
    }

    // ==================== 数据清理操作 ====================

    @Override
    @Transactional(transactionManager = "neo4jTransactionManager")
    public void saveDataModels(List<DataModelNode> dataModelNodes, List<Map<String, Object>> usesModelRelations) {
        if (dataModelNodes != null && !dataModelNodes.isEmpty()) {
            dataModelNodeRepository.saveAll(dataModelNodes);
        }
        if (usesModelRelations != null && !usesModelRelations.isEmpty()) {
            dataModelNodeRepository.createUsesModelRelations(usesModelRelations);
        }
        log.info("[Neo4j] 保存数据模型: {} 节点, {} 关系", dataModelNodes.size(), usesModelRelations.size());
    }

    @Override
    public void cleanProjectData(String projectPath) {
        log.info("[Neo4j] 清理项目数据（分批分事务）: {}", projectPath);
        // 清理 DataModel 节点和 USES_MODEL 关系
        try {
            dataModelNodeRepository.deleteUsesModelRelationsByProjectPath(projectPath);
            dataModelNodeRepository.deleteByProjectPath(projectPath);
        } catch (Exception e) {
            log.warn("[Neo4j] 清理 DataModel 数据异常: {}", e.getMessage());
        }
        // 清理关系（按照从特殊到一般的顺序）
        methodNodeRepository.deleteExtendsRelationsByProjectPath(projectPath);
        methodNodeRepository.deleteOverrideRelationsByProjectPath(projectPath);
        methodNodeRepository.deleteProxyRelationsByProjectPath(projectPath);
        // 分批清理方法节点（避免大事务内存溢出）
        int totalDeleted = 0;
        long deleted;
        do {
            deleted = methodNodeRepository.deleteByProjectPathBatch(projectPath, 5000);
            totalDeleted += deleted;
        } while (deleted > 0);
        log.info("[Neo4j] 分批删除方法节点完成: projectPath={}, 共删除 {} 个", projectPath, totalDeleted);
        // 分批清理入口点
        totalDeleted = 0;
        do {
            deleted = entryPointRepository.deleteByProjectPathBatch(projectPath, 1000);
            totalDeleted += deleted;
        } while (deleted > 0);
        log.info("[Neo4j] 分批删除入口点完成: projectPath={}, 共删除 {} 个", projectPath, totalDeleted);
        // 清理聚合数据节点（每个独立 try-catch，避免部分失败导致剩余节点遗留）
        try {
            long moduleDeleted = moduleNodeRepository.deleteByProjectPath(projectPath);
            log.info("[Neo4j] 清理 ModuleNode: {}", moduleDeleted);
        } catch (Exception e) {
            log.warn("[Neo4j] 清理 ModuleNode 异常: {}", e.getMessage());
        }
        try {
            long churnDeleted = churnNodeRepository.deleteByProjectPath(projectPath);
            log.info("[Neo4j] 清理 ChurnNode: {}", churnDeleted);
        } catch (Exception e) {
            log.warn("[Neo4j] 清理 ChurnNode 异常: {}", e.getMessage());
        }
        try {
            long domainDeleted = domainNodeRepository.deleteByProjectPath(projectPath);
            log.info("[Neo4j] 清理 DomainNode: {}", domainDeleted);
        } catch (Exception e) {
            log.warn("[Neo4j] 清理 DomainNode 异常: {}", e.getMessage());
        }
        try {
            long checkpointDeleted = aggregationCheckpointRepository.deleteByProjectPath(projectPath);
            log.info("[Neo4j] 清理 AggregationCheckpoint: {}", checkpointDeleted);
        } catch (Exception e) {
            log.warn("[Neo4j] 清理 AggregationCheckpoint 异常: {}", e.getMessage());
        }
        try {
            classNodeRepository.detachDeleteByProjectPath(projectPath);
            log.info("[Neo4j] 清理 ClassNode 完成");
        } catch (Exception e) {
            log.warn("[Neo4j] 清理 ClassNode 异常: {}", e.getMessage());
        }
    }

    /**
     * 全量-复用（REUSE）清理：删边 + 删非 Method 节点，保留 Method 节点。
     * 与 {@link #cleanProjectData} 的差异只有两点：
     * ① 显式删除 CALLS 边（cleanProjectData 靠 DETACH DELETE Method 连带删，REUSE 保留 Method 故需显式删）；
     * ② 不删除 Method 节点。
     */
    @Override
    @Transactional(transactionManager = "neo4jTransactionManager")
    public void cleanProjectDataForReuse(String projectPath) {
        log.info("[Neo4j] 全量-复用清理（保留 Method 节点）: {}", projectPath);
        // 清理 DataModel 节点和 USES_MODEL 关系
        try {
            dataModelNodeRepository.deleteUsesModelRelationsByProjectPath(projectPath);
            dataModelNodeRepository.deleteByProjectPath(projectPath);
        } catch (Exception e) {
            log.warn("[Neo4j] 清理 DataModel 数据异常: {}", e.getMessage());
        }
        // 清理关系（IMPLEMENTS / EXTENDS / OVERRIDE / PROXY / CALLS）
        methodNodeRepository.deleteImplementsRelationsByProjectPath(projectPath);
        methodNodeRepository.deleteExtendsRelationsByProjectPath(projectPath);
        methodNodeRepository.deleteOverrideRelationsByProjectPath(projectPath);
        methodNodeRepository.deleteProxyRelationsByProjectPath(projectPath);
        methodNodeRepository.deleteCallRelationsByProjectPath(projectPath);
        // 分批清理入口点（EntryPoint 非 Method，需删除重建）
        long deleted;
        do {
            deleted = entryPointRepository.deleteByProjectPathBatch(projectPath, 1000);
        } while (deleted > 0);
        log.info("[Neo4j] 全量-复用清理入口点完成: projectPath={}", projectPath);
        // 清理聚合数据节点（非 Method 节点，删了重建）
        try {
            moduleNodeRepository.deleteByProjectPath(projectPath);
        } catch (Exception e) {
            log.warn("[Neo4j] 清理 ModuleNode 异常: {}", e.getMessage());
        }
        try {
            churnNodeRepository.deleteByProjectPath(projectPath);
        } catch (Exception e) {
            log.warn("[Neo4j] 清理 ChurnNode 异常: {}", e.getMessage());
        }
        try {
            domainNodeRepository.deleteByProjectPath(projectPath);
        } catch (Exception e) {
            log.warn("[Neo4j] 清理 DomainNode 异常: {}", e.getMessage());
        }
        try {
            aggregationCheckpointRepository.deleteByProjectPath(projectPath);
        } catch (Exception e) {
            log.warn("[Neo4j] 清理 AggregationCheckpoint 异常: {}", e.getMessage());
        }
        try {
            classNodeRepository.detachDeleteByProjectPath(projectPath);
        } catch (Exception e) {
            log.warn("[Neo4j] 清理 ClassNode 异常: {}", e.getMessage());
        }
        try {
            serviceNodeRepository.deleteByProjectPath(projectPath);
        } catch (Exception e) {
            log.warn("[Neo4j] 清理 ServiceNode 异常: {}", e.getMessage());
        }
        // 注意：不删除 Method 节点 —— 保留其 description/embedding 供 codeHash 复用判定
        log.info("[Neo4j] 全量-复用清理完成（Method 节点保留）: {}", projectPath);
    }

}
