package com.huawei.hisi.config;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

import java.util.Map;

/**
 * 当 neo4j.embedded.enabled=true（默认）时，自动注入 neo4j.uri 占位属性，
 * 使 @ConditionalOnProperty(name = "neo4j.uri") 守卫继续生效。
 * 实际 URI 由 Neo4jHarnessConfig 在运行时从 harness 获取。
 */
public class Neo4jEmbeddedEnvironmentPostProcessor implements EnvironmentPostProcessor {

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        String embedded = environment.getProperty("neo4j.embedded.enabled", "true");
        if ("true".equalsIgnoreCase(embedded)) {
            var propertySource = new MapPropertySource(
                    "neo4jEmbeddedDefaults",
                    Map.of("neo4j.uri", "bolt://embedded-harness")
            );
            environment.getPropertySources().addLast(propertySource);
        }
    }
}
