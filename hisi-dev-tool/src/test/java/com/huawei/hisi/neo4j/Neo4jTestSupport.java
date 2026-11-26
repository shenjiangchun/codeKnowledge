package com.huawei.hisi.neo4j;

import org.junit.jupiter.api.Assumptions;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

import java.util.List;

/**
 * 连接本机 Neo4j 的集成测试用的口令。
 *
 * <p>口令不写在测试代码里（仓库是公开的）：优先取环境变量 {@code NEO4J_PASSWORD}，其次读本机未入库的
 * {@code application-local.yml}；两处都没有时跳过测试而不是失败。
 */
final class Neo4jTestSupport {

    private Neo4jTestSupport() {
    }

    static String requirePassword() {
        String password = System.getenv("NEO4J_PASSWORD");
        if (password == null || password.isBlank()) {
            password = fromLocalConfig();
        }
        Assumptions.assumeTrue(password != null && !password.isBlank(),
                "未配置 Neo4j 口令（环境变量 NEO4J_PASSWORD 或本机 application-local.yml 的 neo4j.password），跳过");
        return password;
    }

    private static String fromLocalConfig() {
        try {
            ClassPathResource resource = new ClassPathResource("application-local.yml", Neo4jTestSupport.class.getClassLoader());
            if (!resource.exists()) {
                return null;
            }
            List<PropertySource<?>> sources = new YamlPropertySourceLoader().load("application-local", resource);
            for (PropertySource<?> source : sources) {
                Object value = source.getProperty("neo4j.password");
                if (value != null) {
                    return String.valueOf(value);
                }
            }
            return null;
        } catch (Exception e) {
            return null;
        }
    }
}
