package com.huawei.hisi.knowledgegraph.service;

import com.huawei.hisi.cache.GlobalAnalysisCache;
import com.huawei.hisi.knowledgegraph.model.CallTarget;
import com.huawei.hisi.neo4j.model.SqlNode;
import com.huawei.hisi.neo4j.repository.Neo4jSqlNodeRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Mapper 调用解析器
 * 负责解析 MyBatis Mapper 接口调用并关联 SQL 语句
 * 重构版：使用 Neo4j SqlNode 替代 PostgreSQL MyBatisSqlRepository
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class MapperCallResolver {

    private final GlobalAnalysisCache globalCache;
    private final Neo4jSqlNodeRepository neo4jSqlNodeRepository;

    /**
     * 检查方法调用是否是 Mapper 调用
     *
     * @param scopeType 调用目标类型/接口名
     * @param cache     全局分析缓存
     * @return 如果是 Mapper 调用返回 true
     */
    public boolean isMapperCall(String scopeType, GlobalAnalysisCache cache) {
        if (scopeType == null || cache == null) {
            return false;
        }

        Map<String, Set<String>> mapperMap = cache.getMyBatisMapperMap();
        if (mapperMap == null) {
            return false;
        }

        return mapperMap.containsKey(scopeType);
    }

    /**
     * 检查是否是 JPA Repository 调用
     *
     * @param scopeType 调用目标类型/接口名
     * @param cache     全局分析缓存
     * @return 如果是 JPA Repository 调用返回 true
     */
    public boolean isJpaRepositoryCall(String scopeType, GlobalAnalysisCache cache) {
        if (scopeType == null || cache == null) {
            return false;
        }

        Map<String, String> jpaMap = cache.getJpaRepositoryMap();
        if (jpaMap == null) {
            return false;
        }

        return jpaMap.containsKey(scopeType);
    }

    /**
     * 解析 Mapper 调用，返回关联的 SQL 信息
     * 重构版：从 Neo4j 查询 SqlNode，使用 sqlId + projectPath
     *
     * @param mapperInterface Mapper 接口全限定名
     * @param methodName      方法名
     * @param projectPath     项目路径
     * @return SQL 节点信息（如果存在）
     * @throws RuntimeException 如果查询过程中出现错误
     */
    public Optional<SqlNode> resolveMapperCall(
            String mapperInterface, String methodName, String projectPath) {

        if (mapperInterface == null || methodName == null || projectPath == null) {
            return Optional.empty();
        }

        // 构建 SQL ID: namespace.methodId
        String sqlId = mapperInterface + "." + methodName;

        try {
            // 使用 sqlId + projectPath 查询，确保唯一性
            Optional<SqlNode> sqlNode = neo4jSqlNodeRepository.findBySqlIdAndProjectPath(sqlId, projectPath);
            if (sqlNode.isPresent()) {
                log.debug("解析 Mapper 调用成功: {} -> SQL: {}", sqlId, sqlNode.get().getStatementType());
            } else {
                log.debug("未找到 Mapper SQL: {} (projectPath={})", sqlId, projectPath);
            }
            return sqlNode;
        } catch (Exception e) {
            log.error("解析 Mapper 调用失败: {}", sqlId, e);
            // 抛出异常以便定位问题（按照用户要求）
            throw new RuntimeException("解析 Mapper 调用失败: " + sqlId, e);
        }
    }

    /**
     * 构建带 SQL 关联的调用关系
     *
     * @param callerId       调用方节点 ID
     * @param mapperInterface Mapper 接口全限定名
     * @param methodName     方法名
     * @param sqlNode        SQL 节点信息（可为 null）
     * @param callLine       调用行号
     * @param projectPath    项目路径
     * @return 调用关系 Map
     */
    public Map<String, Object> buildMapperCallRelation(
            String callerId, String mapperInterface, String methodName,
            SqlNode sqlNode, int callLine, String projectPath) {

        // 构建 calleeId (使用 Mapper 接口和方法名)
        String calleeId = mapperInterface + "." + methodName;

        // 构建 SQL ID
        String sqlId = sqlNode != null ? sqlNode.getSqlId() : null;

        Map<String, Object> relation = new LinkedHashMap<>();
        relation.put("callerId", callerId);
        relation.put("calleeId", calleeId);
        relation.put("callType", "MAPPER");
        relation.put("bridgeType", "MAPPER");
        relation.put("sqlId", sqlId);
        relation.put("callLine", callLine);
        return relation;
    }

    /**
     * 解析调用目标
     * 根据调用目标类型自动判断是 Mapper、JPA 还是普通调用
     *
     * @param scopeType   调用目标类型/接口名
     * @param methodName  方法名
     * @param projectPath 项目路径
     * @param cache       全局分析缓存
     * @return 调用目标信息
     */
    public Optional<CallTarget> resolveCallTarget(
            String scopeType, String methodName, String projectPath, GlobalAnalysisCache cache) {

        if (scopeType == null || methodName == null) {
            return Optional.empty();
        }

        // 1. 检查是否是 Mapper 调用
        if (isMapperCall(scopeType, cache)) {
            String sqlId = scopeType + "." + methodName;

            // 尝试获取 SQL 信息
            Optional<SqlNode> sqlNode = Optional.empty();
            if (projectPath != null) {
                sqlNode = resolveMapperCall(scopeType, methodName, projectPath);
            }

            CallTarget.CallTargetBuilder builder = CallTarget.builder()
                    .type(CallTarget.TYPE_MAPPER)
                    .interfaceName(scopeType)
                    .methodName(methodName)
                    .sqlId(sqlId);

            return Optional.of(builder.build());
        }

        // 2. 检查是否是 JPA Repository 调用
        if (isJpaRepositoryCall(scopeType, cache)) {
            CallTarget target = CallTarget.jpaRepository(scopeType, methodName);
            return Optional.of(target);
        }

        // 3. 默认为直接调用
        CallTarget target = CallTarget.direct(scopeType, methodName);
        return Optional.of(target);
    }

    /**
     * 构建 JPA Repository 调用关系
     *
     * @param callerId       调用方节点 ID
     * @param repositoryName Repository 接口全限定名
     * @param methodName     方法名
     * @param callLine       调用行号
     * @param projectPath    项目路径
     * @return 调用关系 Map
     */
    public Map<String, Object> buildJpaCallRelation(
            String callerId, String repositoryName, String methodName,
            int callLine, String projectPath) {

        String calleeId = repositoryName + "." + methodName;

        Map<String, Object> relation = new LinkedHashMap<>();
        relation.put("callerId", callerId);
        relation.put("calleeId", calleeId);
        relation.put("callType", "JPA");
        relation.put("bridgeType", "JPA");
        relation.put("callLine", callLine);
        return relation;
    }

    /**
     * 检查调用目标是否是桥接调用（Mapper/JPA/Feign/MQ/HTTP）
     *
     * @param scopeType 调用目标类型/接口名
     * @param cache     全局分析缓存
     * @return 如果是桥接调用返回 true
     */
    public boolean isBridgeCall(String scopeType, GlobalAnalysisCache cache) {
        return isMapperCall(scopeType, cache) || isJpaRepositoryCall(scopeType, cache);
    }

    /**
     * 获取 Mapper 接口的所有方法签名
     *
     * @param mapperInterface Mapper 接口全限定名
     * @param cache           全局分析缓存
     * @return 方法签名集合
     */
    public Set<String> getMapperMethods(String mapperInterface, GlobalAnalysisCache cache) {
        if (mapperInterface == null || cache == null) {
            return Set.of();
        }

        Map<String, Set<String>> mapperMap = cache.getMyBatisMapperMap();
        if (mapperMap == null) {
            return Set.of();
        }

        return mapperMap.getOrDefault(mapperInterface, Set.of());
    }

    /**
     * 获取 JPA Repository 的实体类型
     *
     * @param repositoryName Repository 接口全限定名
     * @param cache          全局分析缓存
     * @return 实体类型（如果存在）
     */
    public Optional<String> getJpaEntityType(String repositoryName, GlobalAnalysisCache cache) {
        if (repositoryName == null || cache == null) {
            return Optional.empty();
        }

        Map<String, String> jpaMap = cache.getJpaRepositoryMap();
        if (jpaMap == null) {
            return Optional.empty();
        }

        return Optional.ofNullable(jpaMap.get(repositoryName));
    }
}
