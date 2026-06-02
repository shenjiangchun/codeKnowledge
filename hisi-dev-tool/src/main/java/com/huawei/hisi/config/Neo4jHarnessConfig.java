package com.huawei.hisi.config;

import lombok.extern.slf4j.Slf4j;
import org.neo4j.configuration.GraphDatabaseSettings;
import org.neo4j.driver.AuthTokens;
import org.neo4j.driver.Driver;
import org.neo4j.driver.GraphDatabase;
import org.neo4j.driver.SessionConfig;
import org.neo4j.harness.Neo4j;
import org.neo4j.harness.internal.InProcessNeo4jBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.data.neo4j.core.DatabaseSelection;
import org.springframework.data.neo4j.core.DatabaseSelectionProvider;

import jakarta.annotation.PreDestroy;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Neo4j Harness (进程内 Bolt 服务器) 配置。
 * 默认启用，提供"嵌入式"体验——无需安装 Neo4j Desktop/Docker，
 * 数据存本地可配置目录，支持持久化和向量索引。
 *
 * 设置 neo4j.embedded.enabled=false 可回退到外部 Bolt 服务器模式。
 */
@Configuration
@ConditionalOnProperty(name = "neo4j.embedded.enabled", havingValue = "true", matchIfMissing = true)
@Slf4j
public class Neo4jHarnessConfig {

    @Value("${neo4j.embedded.data-directory:${NEO4J_DATA_DIR:./neo4j-data}}")
    private String dataDirectory;

    @Value("${neo4j.database:neo4j}")
    private String database;

    private Neo4j embeddedServer;

    @Bean(destroyMethod = "close")
    @Primary
    public Driver neo4jEmbeddedDriver() {
        log.info("[Neo4j Harness] Starting in-process Neo4j server...");

        Path dataPath = Path.of(dataDirectory).toAbsolutePath();
        boolean persistentDir = false;

        // 尝试创建持久化目录
        if (!Files.exists(dataPath)) {
            try {
                Files.createDirectories(dataPath);
                log.info("[Neo4j Harness] Created data directory: {}", dataPath);
                persistentDir = true;
            } catch (Exception e) {
                log.warn("[Neo4j Harness] Failed to create data directory: {}, falling back to temp", dataPath, e);
                dataPath = null;
            }
        } else {
            persistentDir = true;
        }

        // 构建器：有持久化目录用 Path 构造函数，否则用默认临时目录
        var builder = persistentDir
                ? new InProcessNeo4jBuilder(dataPath).withDisabledServer()
                : new InProcessNeo4jBuilder().withDisabledServer();

        // 默认数据库名
        if (!"neo4j".equals(database)) {
            builder = builder.withConfig(GraphDatabaseSettings.initial_default_database, database);
            log.info("[Neo4j Harness] Default database set to: {}", database);
        }

        embeddedServer = builder.build();

        String boltUri = embeddedServer.boltURI().toString();
        log.info("[Neo4j Harness] Server started at {}, database={}, dataDir={}",
                boltUri, database, persistentDir ? dataPath : "<temp>");

        // harness 内部无认证
        return GraphDatabase.driver(boltUri, AuthTokens.none());
    }

    @Bean
    @ConditionalOnMissingBean(DatabaseSelectionProvider.class)
    public DatabaseSelectionProvider databaseSelectionProvider() {
        return () -> DatabaseSelection.byName(database);
    }

    @Bean
    @ConditionalOnMissingBean(SessionConfig.class)
    public SessionConfig neo4jSessionConfig() {
        return SessionConfig.forDatabase(database);
    }

    @PreDestroy
    public void shutdown() {
        if (embeddedServer != null) {
            log.info("[Neo4j Harness] Shutting down in-process Neo4j server...");
            try {
                embeddedServer.close();
                log.info("[Neo4j Harness] Server shut down successfully");
            } catch (Exception e) {
                log.warn("[Neo4j Harness] Error during shutdown: {}", e.getMessage());
            }
        }
    }
}
