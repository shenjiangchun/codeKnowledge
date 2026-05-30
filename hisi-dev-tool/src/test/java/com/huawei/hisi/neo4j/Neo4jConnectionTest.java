package com.huawei.hisi.neo4j;

import org.junit.jupiter.api.Test;
import org.neo4j.driver.AuthTokens;
import org.neo4j.driver.Driver;
import org.neo4j.driver.GraphDatabase;
import org.neo4j.driver.Session;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Neo4j 连接和插件验证测试
 */
class Neo4jConnectionTest {

    @Test
    void testConnection() {
        String uri = "neo4j://127.0.0.1:7687";
        String username = "neo4j";
        String password = "12345678";

        try (Driver driver = GraphDatabase.driver(uri, AuthTokens.basic(username, password));
             Session session = driver.session()) {

            // 测试基本连接
            var result = session.run("RETURN 1 AS test").single();
            assertEquals(1, result.get("test").asInt());
            System.out.println("✅ Neo4j 连接成功");

            // 验证 APOC 插件
            var apocResult = session.run("RETURN apoc.version() AS version").single();
            String apocVersion = apocResult.get("version").asString();
            System.out.println("✅ APOC 版本: " + apocVersion);
            assertNotNull(apocVersion);

            // 验证 GDS 插件
            var gdsResult = session.run("RETURN gds.version() AS version").single();
            String gdsVersion = gdsResult.get("version").asString();
            System.out.println("✅ GDS 版本: " + gdsVersion);
            assertNotNull(gdsVersion);

        } catch (Exception e) {
            fail("Neo4j 连接失败: " + e.getMessage());
        }
    }

    @Test
    void testConstraintsAndIndexes() {
        String uri = "neo4j://127.0.0.1:7687";
        String username = "neo4j";
        String password = "12345678";

        try (Driver driver = GraphDatabase.driver(uri, AuthTokens.basic(username, password));
             Session session = driver.session()) {

            // 统计约束数量
            var constraintResult = session.run("SHOW CONSTRAINTS YIELD name RETURN count(*) AS count").single();
            long constraintCount = constraintResult.get("count").asLong();
            System.out.println("✅ 约束数量: " + constraintCount);

            // 统计索引数量
            var indexResult = session.run("SHOW INDEXES YIELD name RETURN count(*) AS count").single();
            long indexCount = indexResult.get("count").asLong();
            System.out.println("✅ 索引数量: " + indexCount);

            assertTrue(constraintCount >= 10, "约束数量应 >= 10，实际: " + constraintCount);
            assertTrue(indexCount >= 10, "索引数量应 >= 10，实际: " + indexCount);

        } catch (Exception e) {
            fail("查询约束/索引失败: " + e.getMessage());
        }
    }
}
