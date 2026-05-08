package com.huawei.hisi.knowledgegraph.service.storage;

import com.huawei.hisi.knowledgegraph.model.InterfaceImplementation;
import com.huawei.hisi.neo4j.model.EntryPointNode;
import com.huawei.hisi.neo4j.model.MethodNode;
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

        methodNodeRepository.saveAll(deduplicatedNodes);
        log.info("[Neo4j] 保存方法节点: {} 个 (去重前: {} 个)", deduplicatedNodes.size(), nodes.size());
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

        entryPointRepository.saveAll(deduplicatedEntries);
        log.info("[Neo4j] 保存入口点: {} 个 (去重前: {} 个)", deduplicatedEntries.size(), entries.size());
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

    // ==================== 数据清理操作 ====================

    @Override
    @Transactional(transactionManager = "neo4jTransactionManager")
    public void cleanProjectData(String projectPath) {
        log.info("[Neo4j] 清理项目数据: {}", projectPath);
        methodNodeRepository.deleteByProjectPath(projectPath);
        entryPointRepository.deleteByProjectPath(projectPath);
    }

}
