package com.huawei.hisi.neo4j.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.neo4j.driver.Driver;
import org.neo4j.driver.Session;
import org.neo4j.driver.SessionConfig;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Neo4j 初始化器
 * 在应用启动时自动创建约束和索引
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "neo4j.uri")
public class Neo4jInitializer {

    private final Driver neo4jDriver;
    private final SessionConfig neo4jSessionConfig;

    /**
     * 唯一性约束列表
     * 注意：属性名必须与实体类 @Property 注解一致
     */
    private static final List<String> UNIQUE_CONSTRAINTS = List.of(
        "CREATE CONSTRAINT method_nodeId_unique IF NOT EXISTS FOR (m:Method) REQUIRE m.nodeId IS UNIQUE",
        "CREATE CONSTRAINT entryPoint_entryId_unique IF NOT EXISTS FOR (e:EntryPoint) REQUIRE e.entryId IS UNIQUE",
        "CREATE CONSTRAINT service_name_unique IF NOT EXISTS FOR (s:Service) REQUIRE s.name IS UNIQUE"
    );

    /**
     * 存在性约束列表
     */
    private static final List<String> EXISTS_CONSTRAINTS = List.of(
        "CREATE CONSTRAINT method_className_exists IF NOT EXISTS FOR (m:Method) REQUIRE m.className IS NOT NULL",
        "CREATE CONSTRAINT method_methodName_exists IF NOT EXISTS FOR (m:Method) REQUIRE m.methodName IS NOT NULL",
        "CREATE CONSTRAINT entryPoint_entryKey_exists IF NOT EXISTS FOR (e:EntryPoint) REQUIRE e.entryKey IS NOT NULL"
    );

    /**
     * 全文索引列表
     */
    private static final List<String> FULLTEXT_INDEXES = List.of(
        "CREATE FULLTEXT INDEX method_fulltext_index IF NOT EXISTS FOR (m:Method) ON EACH [m.className, m.methodName, m.signature, m.description]",
        "CREATE FULLTEXT INDEX entry_point_fulltext_index IF NOT EXISTS FOR (e:EntryPoint) ON EACH [e.entryKey, e.entryType]"
    );

    /**
     * 范围索引列表
     * 用于按项目路径查询、入口键查询等高频操作
     */
    private static final List<String> RANGE_INDEXES = List.of(
        // Method 节点索引
        "CREATE INDEX method_projectPath_index IF NOT EXISTS FOR (m:Method) ON (m.projectPath)",
        "CREATE INDEX method_className_index IF NOT EXISTS FOR (m:Method) ON (m.className)",
        "CREATE INDEX method_methodName_index IF NOT EXISTS FOR (m:Method) ON (m.methodName)",
        "CREATE INDEX method_serviceName_index IF NOT EXISTS FOR (m:Method) ON (m.serviceName)",
        "CREATE INDEX method_startLine_index IF NOT EXISTS FOR (m:Method) ON (m.startLine)",
        "CREATE INDEX method_complexity_index IF NOT EXISTS FOR (m:Method) ON (m.complexity)",
        // EntryPoint 节点索引
        "CREATE INDEX entryPoint_projectPath_index IF NOT EXISTS FOR (e:EntryPoint) ON (e.projectPath)",
        "CREATE INDEX entryPoint_entryKey_index IF NOT EXISTS FOR (e:EntryPoint) ON (e.entryKey)",
        "CREATE INDEX entryPoint_entryType_index IF NOT EXISTS FOR (e:EntryPoint) ON (e.entryType)",
        "CREATE INDEX entryPoint_methodNodeId_index IF NOT EXISTS FOR (e:EntryPoint) ON (e.methodNodeId)"
    );

    /**
     * 待删除索引列表
     * 用于清理已废弃的索引（如旧版的 method_vector_index，
     * 对应 MethodNode.embedding 字段已移除）。
     */
    private static final List<String> DROP_INDEXES = List.of(
        "DROP INDEX method_vector_index IF EXISTS",
        "DROP INDEX method_pub_path IF EXISTS",
        "DROP INDEX entrypoint_pub_path IF EXISTS",
        "DROP INDEX sql_pub_path IF EXISTS",
        "DROP INDEX service_pub_path IF EXISTS"
    );

    /**
     * 一次性数据回填语句列表
     * 用于将历史数据迁移到新字段；语句本身必须是幂等的（通过 WHERE 条件排除已处理数据）
     */
    private static final List<String> BACKFILL_STATEMENTS = List.of(
        // Remove publicProjectPath property from all nodes (cleanup migration)
        "MATCH (n) WHERE n.publicProjectPath IS NOT NULL REMOVE n.publicProjectPath"
    );

    /**
     * 应用启动完成后执行初始化
     */
    @EventListener(ApplicationReadyEvent.class)
    public void initialize() {
        log.info("开始初始化 Neo4j 约束和索引...");

        try (Session session = neo4jDriver.session(neo4jSessionConfig)) {
            int successCount = 0;
            int failCount = 0;

            // 创建唯一性约束
            log.info("创建唯一性约束...");
            for (String cypher : UNIQUE_CONSTRAINTS) {
                try {
                    session.run(cypher).consume();
                    successCount++;
                    log.debug("成功创建约束: {}", extractConstraintName(cypher));
                } catch (Exception e) {
                    log.warn("创建约束失败: {} - {}", extractConstraintName(cypher), e.getMessage());
                    failCount++;
                }
            }

            // 创建存在性约束
            log.info("创建存在性约束...");
            for (String cypher : EXISTS_CONSTRAINTS) {
                try {
                    session.run(cypher).consume();
                    successCount++;
                    log.debug("成功创建约束: {}", extractConstraintName(cypher));
                } catch (Exception e) {
                    log.warn("创建约束失败: {} - {}", extractConstraintName(cypher), e.getMessage());
                    failCount++;
                }
            }

            // 创建全文索引
            log.info("创建全文索引...");
            for (String cypher : FULLTEXT_INDEXES) {
                try {
                    session.run(cypher).consume();
                    successCount++;
                    log.debug("成功创建全文索引: {}", extractIndexName(cypher));
                } catch (Exception e) {
                    log.warn("创建全文索引失败: {} - {}", extractIndexName(cypher), e.getMessage());
                    failCount++;
                }
            }

            // 创建范围索引
            log.info("创建范围索引...");
            for (String cypher : RANGE_INDEXES) {
                try {
                    session.run(cypher).consume();
                    successCount++;
                    log.debug("成功创建范围索引: {}", extractIndexName(cypher));
                } catch (Exception e) {
                    log.warn("创建范围索引失败: {} - {}", extractIndexName(cypher), e.getMessage());
                    failCount++;
                }
            }

            // 删除已废弃的索引
            log.info("删除已废弃的索引...");
            for (String cypher : DROP_INDEXES) {
                try {
                    session.run(cypher).consume();
                    successCount++;
                    log.debug("成功删除索引: {}", cypher);
                } catch (Exception e) {
                    log.warn("删除索引失败: {} - {}", cypher, e.getMessage());
                    failCount++;
                }
            }

            // 执行一次性数据回填（幂等）
            log.info("执行数据回填语句...");
            for (String cypher : BACKFILL_STATEMENTS) {
                try {
                    session.run(cypher).consume();
                    successCount++;
                    log.debug("成功执行回填语句: {}", cypher);
                } catch (Exception e) {
                    log.warn("执行回填语句失败: {} - {}", cypher, e.getMessage());
                    failCount++;
                }
            }

            log.info("Neo4j 初始化完成: 成功={}, 失败={}", successCount, failCount);

            // 验证初始化结果
            verifyInitialization(session);

        } catch (Exception e) {
            log.error("Neo4j 初始化失败: {}", e.getMessage(), e);
        }
    }

    /**
     * 验证初始化结果
     */
    private void verifyInitialization(Session session) {
        try {
            // 统计约束数量
            var constraintCount = session.run("SHOW CONSTRAINTS YIELD name RETURN count(*) AS count").single();
            log.info("约束数量: {}", constraintCount.get("count").asLong());

            // 统计索引数量
            var indexCount = session.run("SHOW INDEXES YIELD name RETURN count(*) AS count").single();
            log.info("索引数量: {}", indexCount.get("count").asLong());

        } catch (Exception e) {
            log.warn("验证初始化结果失败: {}", e.getMessage());
        }
    }

    /**
     * 从 Cypher 语句中提取约束名称
     */
    private String extractConstraintName(String cypher) {
        try {
            int start = cypher.indexOf("CONSTRAINT ") + 11;
            int end = cypher.indexOf(" IF NOT EXISTS");
            if (end == -1) {
                end = cypher.indexOf(" FOR");
            }
            return cypher.substring(start, end).trim();
        } catch (Exception e) {
            return "unknown";
        }
    }

    /**
     * 从 Cypher 语句中提取索引名称
     */
    private String extractIndexName(String cypher) {
        try {
            int start = cypher.indexOf("INDEX ") + 6;
            int end = cypher.indexOf(" IF NOT EXISTS");
            if (end == -1) {
                end = cypher.indexOf(" FOR");
            }
            return cypher.substring(start, end).trim();
        } catch (Exception e) {
            return "unknown";
        }
    }
}
