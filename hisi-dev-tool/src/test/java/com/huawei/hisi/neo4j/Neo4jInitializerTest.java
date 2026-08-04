package com.huawei.hisi.neo4j;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.neo4j.driver.AuthTokens;
import org.neo4j.driver.Driver;
import org.neo4j.driver.GraphDatabase;
import org.neo4j.driver.Session;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Neo4j 初始化测试
 * 执行约束和索引创建脚本
 */
class Neo4jInitializerTest {

    private static final String URI = "neo4j://127.0.0.1:7687";
    private static final String USERNAME = "neo4j";
    private static final String PASSWORD = "12345678";

    /**
     * 唯一性约束列表
     */
    private static final List<String> UNIQUE_CONSTRAINTS = List.of(
        "CREATE CONSTRAINT method_nodeId_unique IF NOT EXISTS FOR (m:Method) REQUIRE m.nodeId IS UNIQUE",
        "CREATE CONSTRAINT entryPoint_id_unique IF NOT EXISTS FOR (e:EntryPoint) REQUIRE e.id IS UNIQUE",
        "CREATE CONSTRAINT interface_interfaceName_unique IF NOT EXISTS FOR (i:Interface) REQUIRE i.interfaceName IS UNIQUE",
        "CREATE CONSTRAINT implementation_className_unique IF NOT EXISTS FOR (impl:Implementation) REQUIRE impl.className IS UNIQUE",
        "CREATE CONSTRAINT mapper_mapperInterface_unique IF NOT EXISTS FOR (m:Mapper) REQUIRE m.mapperInterface IS UNIQUE",
        "CREATE CONSTRAINT sqlStatement_sqlId_unique IF NOT EXISTS FOR (s:SqlStatement) REQUIRE s.sqlId IS UNIQUE",
        "CREATE CONSTRAINT service_name_unique IF NOT EXISTS FOR (s:Service) REQUIRE s.name IS UNIQUE",
        "CREATE CONSTRAINT project_projectPath_unique IF NOT EXISTS FOR (p:Project) REQUIRE p.projectPath IS UNIQUE"
    );

    /**
     * 存在性约束列表
     */
    private static final List<String> EXISTS_CONSTRAINTS = List.of(
        "CREATE CONSTRAINT method_className_exists IF NOT EXISTS FOR (m:Method) REQUIRE m.className IS NOT NULL",
        "CREATE CONSTRAINT method_methodName_exists IF NOT EXISTS FOR (m:Method) REQUIRE m.methodName IS NOT NULL"
    );

    /**
     * 向量索引列表
     */
    private static final List<String> VECTOR_INDEXES = List.of(
        "CREATE VECTOR INDEX method_description_vector_index IF NOT EXISTS FOR (m:Method) ON m.descriptionEmbedding OPTIONS { indexConfig: { `vector.dimensions`: 384, `vector.similarity_function`: 'cosine' } }",
        "CREATE VECTOR INDEX method_code_vector_index IF NOT EXISTS FOR (m:Method) ON m.codeEmbedding OPTIONS { indexConfig: { `vector.dimensions`: 384, `vector.similarity_function`: 'cosine' } }",
        "CREATE VECTOR INDEX interface_vector_index IF NOT EXISTS FOR (i:Interface) ON i.embedding OPTIONS { indexConfig: { `vector.dimensions`: 384, `vector.similarity_function`: 'cosine' } }",
        "CREATE VECTOR INDEX sql_vector_index IF NOT EXISTS FOR (s:SqlStatement) ON s.embedding OPTIONS { indexConfig: { `vector.dimensions`: 384, `vector.similarity_function`: 'cosine' } }"
    );

    /**
     * 全文索引列表
     */
    private static final List<String> FULLTEXT_INDEXES = List.of(
        "CREATE FULLTEXT INDEX method_fulltext_index IF NOT EXISTS FOR (m:Method) ON EACH [m.className, m.methodName, m.signature, m.commentSummary]",
        "CREATE FULLTEXT INDEX entry_point_fulltext_index IF NOT EXISTS FOR (e:EntryPoint) ON EACH [e.entryKey, e.entryType]",
        "CREATE FULLTEXT INDEX interface_fulltext_index IF NOT EXISTS FOR (i:Interface) ON EACH [i.interfaceName, i.description]",
        "CREATE FULLTEXT INDEX sql_fulltext_index IF NOT EXISTS FOR (s:SqlStatement) ON EACH [s.sqlId, s.sqlContent, s.description]",
        "CREATE FULLTEXT INDEX service_fulltext_index IF NOT EXISTS FOR (s:Service) ON EACH [s.name, s.description]"
    );

    /**
     * 范围索引列表
     */
    private static final List<String> RANGE_INDEXES = List.of(
        "CREATE INDEX method_lineNumber_index IF NOT EXISTS FOR (m:Method) ON (m.lineNumber)",
        "CREATE INDEX method_complexity_index IF NOT EXISTS FOR (m:Method) ON (m.cyclomaticComplexity)"
    );

    @Test
    void initializeConstraintsAndIndexes() {
        try (Driver driver = GraphDatabase.driver(URI, AuthTokens.basic(USERNAME, PASSWORD));
             Session session = driver.session()) {

            int successCount = 0;
            int failCount = 0;

            System.out.println("开始初始化 Neo4j 约束和索引...\n");

            // 创建唯一性约束
            System.out.println("创建唯一性约束...");
            for (String cypher : UNIQUE_CONSTRAINTS) {
                try {
                    session.run(cypher).consume();
                    successCount++;
                    System.out.println("  ✅ " + extractName(cypher));
                } catch (Exception e) {
                    System.out.println("  ❌ " + extractName(cypher) + " - " + e.getMessage());
                    failCount++;
                }
            }

            // 创建存在性约束
            System.out.println("\n创建存在性约束...");
            for (String cypher : EXISTS_CONSTRAINTS) {
                try {
                    session.run(cypher).consume();
                    successCount++;
                    System.out.println("  ✅ " + extractName(cypher));
                } catch (Exception e) {
                    System.out.println("  ❌ " + extractName(cypher) + " - " + e.getMessage());
                    failCount++;
                }
            }

            // 创建向量索引
            System.out.println("\n创建向量索引...");
            for (String cypher : VECTOR_INDEXES) {
                try {
                    session.run(cypher).consume();
                    successCount++;
                    System.out.println("  ✅ " + extractName(cypher));
                } catch (Exception e) {
                    System.out.println("  ❌ " + extractName(cypher) + " - " + e.getMessage());
                    failCount++;
                }
            }

            // 创建全文索引
            System.out.println("\n创建全文索引...");
            for (String cypher : FULLTEXT_INDEXES) {
                try {
                    session.run(cypher).consume();
                    successCount++;
                    System.out.println("  ✅ " + extractName(cypher));
                } catch (Exception e) {
                    System.out.println("  ❌ " + extractName(cypher) + " - " + e.getMessage());
                    failCount++;
                }
            }

            // 创建范围索引
            System.out.println("\n创建范围索引...");
            for (String cypher : RANGE_INDEXES) {
                try {
                    session.run(cypher).consume();
                    successCount++;
                    System.out.println("  ✅ " + extractName(cypher));
                } catch (Exception e) {
                    System.out.println("  ❌ " + extractName(cypher) + " - " + e.getMessage());
                    failCount++;
                }
            }

            System.out.println("\n初始化完成: 成功=" + successCount + ", 失败=" + failCount);

            // 验证结果
            System.out.println("\n验证初始化结果...");
            var constraintResult = session.run("SHOW CONSTRAINTS YIELD name RETURN count(*) AS count").single();
            long constraintCount = constraintResult.get("count").asLong();
            System.out.println("  约束数量: " + constraintCount);

            var indexResult = session.run("SHOW INDEXES YIELD name RETURN count(*) AS count").single();
            long indexCount = indexResult.get("count").asLong();
            System.out.println("  索引数量: " + indexCount);

            assertTrue(constraintCount >= 8, "约束数量应 >= 8，实际: " + constraintCount);
            assertTrue(indexCount >= 10, "索引数量应 >= 10，实际: " + indexCount);

        } catch (Exception e) {
            fail("初始化失败: " + e.getMessage());
        }
    }

    private String extractName(String cypher) {
        try {
            if (cypher.contains("CONSTRAINT")) {
                int start = cypher.indexOf("CONSTRAINT ") + 11;
                int end = cypher.indexOf(" IF NOT EXISTS");
                return cypher.substring(start, end).trim();
            } else if (cypher.contains("INDEX")) {
                int start = cypher.indexOf("INDEX ") + 6;
                int end = cypher.indexOf(" IF NOT EXISTS");
                return cypher.substring(start, end).trim();
            }
        } catch (Exception e) {
            return "unknown";
        }
        return "unknown";
    }
}
