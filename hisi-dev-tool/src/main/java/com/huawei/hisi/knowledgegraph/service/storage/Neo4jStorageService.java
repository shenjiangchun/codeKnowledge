package com.huawei.hisi.knowledgegraph.service.storage;

import com.huawei.hisi.knowledgegraph.model.ClassExtends;
import com.huawei.hisi.knowledgegraph.model.InterfaceImplementation;
import com.huawei.hisi.knowledgegraph.model.MethodOverride;
import com.huawei.hisi.knowledgegraph.model.ProxyRelation;
import com.huawei.hisi.neo4j.model.EntryPointNode;
import com.huawei.hisi.neo4j.model.MethodNode;
import com.huawei.hisi.neo4j.repository.Neo4jDataModelNodeRepository;
import com.huawei.hisi.neo4j.repository.Neo4jEntryPointNodeRepository;
import com.huawei.hisi.neo4j.repository.Neo4jMethodNodeRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
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
                return map;
            })
            .toList();
        methodNodeRepository.mergeAll(nodeMaps);
        log.info("[Neo4j] MERGE 方法节点: {} 个 (去重前: {} 个)", deduplicatedNodes.size(), nodes.size());
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
    }

}
