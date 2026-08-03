package com.huawei.hisi.loganalysis;

import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.neo4j.core.Neo4jClient;

import java.util.Optional;

@Slf4j
@SpringBootTest
public class KgNodeCheckTest {
    
    @Autowired(required = false)
    private Neo4jClient neo4jClient;
    
    @Test
    void checkAllNodeTypes() {
        log.info("========== KG 所有节点类型检查 ==========");
        
        if (neo4jClient == null) {
            log.warn("neo4jClient 未注入");
            return;
        }
        
        // 查询所有节点标签及其数量
        try {
            var nodeLabels = neo4jClient.query(
                "CALL db.labels() YIELD label CALL apoc.cypher.run('MATCH (n:' + label + ') RETURN count(n) as count', {}) YIELD value RETURN label, value.count as count ORDER BY count DESC"
            ).fetch().all();
            log.info("节点标签分布:");
            nodeLabels.forEach(row -> log.info("  {} = {}", row.get("label"), row.get("count")));
        } catch (Exception e) {
            // 备用查询（无 apoc）
            log.info("尝试备用查询...");
            try {
                var result = neo4jClient.query(
                    "MATCH (n) WITH DISTINCT labels(n) as labels, count(*) as count UNWIND labels as label RETURN label, count ORDER BY count DESC"
                ).fetch().all();
                result.forEach(row -> log.info("  {} = {}", row.get("label"), row.get("count")));
            } catch (Exception e2) {
                log.error("查询失败: {}", e2.getMessage());
            }
        }
        
        // 查询所有关系类型
        try {
            var relTypes = neo4jClient.query(
                "MATCH ()-[r]->() WITH type(r) as type, count(*) as count RETURN type, count ORDER BY count DESC LIMIT 20"
            ).fetch().all();
            log.info("\n关系类型分布:");
            relTypes.forEach(row -> log.info("  {} = {}", row.get("type"), row.get("count")));
        } catch (Exception e) {
            log.error("查询关系失败: {}", e.getMessage());
        }
        
        // 查询有 projectPath 属性的节点
        try {
            var withProjectPath = neo4jClient.query(
                "MATCH (m:Method) WHERE m.projectPath IS NOT NULL RETURN DISTINCT m.projectPath as path, count(*) as count ORDER BY count DESC LIMIT 20"
            ).fetch().all();
            log.info("\nMethod节点 projectPath 分布:");
            withProjectPath.forEach(row -> log.info("  {} = {} 个方法", row.get("path"), row.get("count")));
            if (withProjectPath.isEmpty()) {
                log.warn("  无 projectPath 数据！");
                // 查看一个样本节点的属性
                var sample = neo4jClient.query(
                    "MATCH (m:Method) RETURN m LIMIT 1"
                ).fetch().one();
                if (sample.isPresent()) {
                    log.info("  Method节点样本属性 keys: {}", sample.get().keySet());
                }
            }
        } catch (Exception e) {
            log.error("查询 projectPath 失败: {}", e.getMessage());
        }

        log.info("========== KG 检查完成 ==========");
    }
}
