package com.huawei.hisi.neo4j.service;

import lombok.extern.slf4j.Slf4j;
import org.neo4j.driver.Driver;
import org.neo4j.driver.SessionConfig;
import org.neo4j.driver.Session;
import org.neo4j.driver.Record;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Neo4j 向量索引服务
 *
 * 负责检测 Neo4j 版本并管理向量索引：
 * - 启动时检测 Neo4j 版本
 * - 版本 >= 5.11 时自动创建向量索引
 * - 版本 < 5.11 或不支持时，标记 vectorIndexAvailable = false，后续查询自动降级为全表扫描
 */
@Slf4j
@Service
@ConditionalOnProperty(name = "neo4j.uri")
public class Neo4jVectorIndexService {

    private static final String MIN_VECTOR_INDEX_VERSION = "5.11";
    private static final int INDEX_ONLINE_POLL_INTERVAL_MS = 2000;
    private static final int INDEX_ONLINE_TIMEOUT_S = 120;

    /**
     * 向量索引配置（启动时根据 EmbeddingService 维度动态构建）
     */
    private List<VectorIndexConfig> vectorIndexes;

    private final Driver neo4jDriver;
    private final SessionConfig neo4jSessionConfig;
    private final EmbeddingService embeddingService;

    /**
     * 当前使用的 embedding 维度（运行时从 EmbeddingService 获取）
     */
    private int embeddingDimension;

    /**
     * 向量索引是否可用
     * 默认为 false，在版本检测通过后设置为 true
     */
    private volatile boolean vectorIndexAvailable = false;

    /**
     * Neo4j 版本信息
     */
    private String neo4jVersion;

    public Neo4jVectorIndexService(Driver neo4jDriver, SessionConfig neo4jSessionConfig, EmbeddingService embeddingService) {
        this.neo4jDriver = neo4jDriver;
        this.neo4jSessionConfig = neo4jSessionConfig;
        this.embeddingService = embeddingService;
    }

    /**
     * 应用启动完成后检测 Neo4j 版本并创建向量索引
     */
    @EventListener(ApplicationReadyEvent.class)
    public void initialize() {
        log.info("开始检测 Neo4j 版本和向量索引支持...");

        // 从 EmbeddingService 获取当前维度
        embeddingDimension = embeddingService.getEmbeddingDimension();
        log.info("当前 Embedding 维度: {}", embeddingDimension);

        vectorIndexes = List.of(
            new VectorIndexConfig("method_description_vector_index", "Method", "descriptionEmbedding", embeddingDimension),
            new VectorIndexConfig("method_code_vector_index", "Method", "codeEmbedding", embeddingDimension),
            new VectorIndexConfig("sql_vector_index", "Sql", "sqlEmbedding", embeddingDimension)
        );

        try {
            // 1. 检测 Neo4j 版本
            neo4jVersion = detectNeo4jVersion();
            log.info("检测到 Neo4j 版本: {}", neo4jVersion);

            // 2. 检查版本是否支持向量索引
            if (!isVersionSupported(neo4jVersion)) {
                log.warn("Neo4j 版本 {} 不支持向量索引 (需要 >= {})，将使用全表扫描模式",
                        neo4jVersion, MIN_VECTOR_INDEX_VERSION);
                vectorIndexAvailable = false;
                return;
            }

            // 3. 创建向量索引
            try (Session session = neo4jDriver.session(neo4jSessionConfig)) {
                int successCount = 0;
                int failCount = 0;

                for (VectorIndexConfig config : vectorIndexes) {
                    try {
                        if (createVectorIndex(session, config)) {
                            successCount++;
                        } else {
                            failCount++;
                        }
                    } catch (Exception e) {
                        log.error("创建向量索引失败: {} - {}", config.indexName(), e.getMessage());
                        failCount++;
                    }
                }

                // 等待所有新建/重建的索引上线
                int onlineCount = 0;
                for (VectorIndexConfig config : vectorIndexes) {
                    if (waitForIndexOnline(config.indexName())) {
                        onlineCount++;
                    } else {
                        log.warn("向量索引 '{}' 未在 {}s 内上线", config.indexName(), INDEX_ONLINE_TIMEOUT_S);
                        failCount++;
                    }
                }

                vectorIndexAvailable = (failCount == 0);

                log.info("向量索引初始化完成: 成功={}, 上线={}, 失败={}, 可用={}",
                        successCount, onlineCount, failCount, vectorIndexAvailable);
            }

        } catch (Exception e) {
            log.error("Neo4j 向量索引初始化失败: {}", e.getMessage(), e);
            vectorIndexAvailable = false;
        }
    }

    /**
     * 检测 Neo4j 版本
     *
     * @return Neo4j 版本字符串，例如 "5.11.0"
     */
    private String detectNeo4jVersion() {
        try (Session session = neo4jDriver.session(neo4jSessionConfig)) {
            Record record = session.run("CALL dbms.components() YIELD name, versions WHERE name = 'Neo4j Kernel' RETURN versions[0] AS version").single();
            return record.get("version").asString();
        } catch (Exception e) {
            log.error("检测 Neo4j 版本失败: {}", e.getMessage());
            return "unknown";
        }
    }

    /**
     * 检查版本是否支持向量索引
     * Neo4j 5.11+ 支持向量索引
     *
     * @param version Neo4j 版本字符串
     * @return true 如果支持向量索引
     */
    private boolean isVersionSupported(String version) {
        if (version == null || version.equals("unknown")) {
            return false;
        }

        try {
            // 提取版本号 (例如 "5.11.0" 或 "5.11.0-enterprise")
            Pattern pattern = Pattern.compile("^(\\d+)\\.(\\d+)");
            Matcher matcher = pattern.matcher(version);
            if (!matcher.find()) {
                return false;
            }

            int major = Integer.parseInt(matcher.group(1));
            int minor = Integer.parseInt(matcher.group(2));

            // 解析最低支持版本
            Pattern minPattern = Pattern.compile("^(\\d+)\\.(\\d+)");
            Matcher minMatcher = minPattern.matcher(MIN_VECTOR_INDEX_VERSION);
            if (!minMatcher.find()) {
                return false;
            }

            int minMajor = Integer.parseInt(minMatcher.group(1));
            int minMinor = Integer.parseInt(minMatcher.group(2));

            // 比较版本
            if (major > minMajor) {
                return true;
            } else if (major == minMajor) {
                return minor >= minMinor;
            }
            return false;

        } catch (Exception e) {
            log.warn("解析 Neo4j 版本失败: {} - {}", version, e.getMessage());
            return false;
        }
    }

    /**
     * 创建向量索引
     *
     * @param session Neo4j 会话
     * @param config 索引配置
     * @return true 如果成功创建或索引已存在
     */
    private boolean createVectorIndex(Session session, VectorIndexConfig config) {
        String indexName = config.indexName();

        try {
            // 检查索引是否已存在
            var checkResult = session.run(
                "SHOW INDEXES YIELD name, type, options " +
                "WHERE name = '" + indexName + "' AND type = 'VECTOR' " +
                "RETURN options"
            );

            if (checkResult.hasNext()) {
                // 索引存在，验证维度和状态
                var record = checkResult.next();
                var options = record.get("options").asMap();

                int currentDimension = extractDimension(options);
                log.info("向量索引 '{}' 已存在，当前维度: {}", indexName, currentDimension);

                if (currentDimension == config.dimension()) {
                    // 维度匹配，但还需确认索引已上线（可能上一轮刚重建还在 POPULATING）
                    String indexState = checkIndexState(indexName);
                    if ("ONLINE".equals(indexState)) {
                        log.info("向量索引 '{}' 维度正确且已上线，保留", indexName);
                        return true;
                    }
                    log.warn("向量索引 '{}' 维度正确但状态为 '{}'，等待上线后再判定", indexName, indexState);
                    // 不删不建，交给 waitForIndexOnline 处理
                    return true;
                }
                // 维度不对，删除重建
                log.warn("向量索引 '{}' 维度不正确 (当前: {}, 需要: {})，正在删除重建...",
                        indexName, currentDimension, config.dimension());
                session.run("DROP INDEX " + indexName).consume();
            }

            // 创建新索引
            String createCypher = String.format(
                "CREATE VECTOR INDEX %s IF NOT EXISTS FOR (n:%s) ON n.%s " +
                "OPTIONS { indexConfig: { `vector.dimensions`: %d, `vector.similarity_function`: 'cosine' } }",
                config.indexName(),
                config.label(),
                config.property(),
                config.dimension()
            );

            session.run(createCypher).consume();
            log.info("成功创建向量索引: {} (维度: {})", indexName, config.dimension());
            return true;

        } catch (Exception e) {
            log.error("创建向量索引 '{}' 失败: {}", indexName, e.getMessage(), e);
            return false;
        }
    }

    /**
     * 查询索引当前状态。
     *
     * @param indexName 索引名称
     * @return 状态字符串（如 "ONLINE"、"POPULATING"、"FAILED"），查询失败返回 "UNKNOWN"
     */
    private String checkIndexState(String indexName) {
        try (Session session = neo4jDriver.session(neo4jSessionConfig)) {
            var result = session.run(
                "SHOW INDEXES YIELD name, state WHERE name = '" + indexName + "' RETURN state AS state"
            );
            if (result.hasNext()) {
                return result.next().get("state").asString();
            }
        } catch (Exception e) {
            log.warn("查询索引 '{}' 状态失败: {}", indexName, e.getMessage());
        }
        return "UNKNOWN";
    }

    /**
     * 轮询等待向量索引上线（状态变为 ONLINE）。
     * Neo4j 创建/重建索引后需要时间填充数据，此方法阻塞等待直到索引可用。
     *
     * @param indexName 索引名称
     * @return true 如果索引已上线，false 如果超时
     */
    private boolean waitForIndexOnline(String indexName) {
        long deadline = System.currentTimeMillis() + INDEX_ONLINE_TIMEOUT_S * 1000L;

        while (System.currentTimeMillis() < deadline) {
            try (Session session = neo4jDriver.session(neo4jSessionConfig)) {
                var result = session.run(
                    "SHOW INDEXES YIELD name, state WHERE name = '" + indexName + "' RETURN state AS state"
                );
                if (result.hasNext()) {
                    String state = result.next().get("state").asString();
                    if ("ONLINE".equals(state)) {
                        log.info("向量索引 '{}' 已上线", indexName);
                        return true;
                    }
                    log.debug("向量索引 '{}' 状态: {}, 继续等待...", indexName, state);
                } else {
                    log.debug("向量索引 '{}' 尚未出现在索引列表中，继续等待...", indexName);
                }
            } catch (Exception e) {
                log.warn("查询索引 '{}' 状态失败: {}", indexName, e.getMessage());
            }

            try {
                Thread.sleep(INDEX_ONLINE_POLL_INTERVAL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("等待索引 '{}' 上线被中断", indexName);
                return false;
            }
        }

        return false;
    }

    /**
     * 从索引选项中提取维度
     */
    private int extractDimension(Map<String, Object> options) {
        try {
            Object indexConfig = options.get("indexConfig");
            if (indexConfig instanceof Map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> configMap = (Map<String, Object>) indexConfig;
                Object dimObj = configMap.get("vector.dimensions");
                if (dimObj instanceof Number) {
                    return ((Number) dimObj).intValue();
                } else if (dimObj instanceof String) {
                    return Integer.parseInt((String) dimObj);
                }
            }
        } catch (Exception e) {
            log.warn("提取向量维度失败: {}", e.getMessage());
        }
        return -1;
    }

    /**
     * 检查向量索引是否可用
     *
     * @return true 如果向量索引可用，false 则应使用全表扫描
     */
    public boolean isVectorIndexAvailable() {
        return vectorIndexAvailable;
    }

    /**
     * 获取 Neo4j 版本
     *
     * @return Neo4j 版本字符串
     */
    public String getNeo4jVersion() {
        return neo4jVersion;
    }

    /**
     * 获取向量索引状态摘要
     *
     * @return 状态摘要字符串
     */
    public String getStatusSummary() {
        if (vectorIndexAvailable) {
            return String.format("Neo4j %s - 向量索引可用 (维度: %d)", neo4jVersion, embeddingDimension);
        } else {
            return String.format("Neo4j %s - 向量索引不可用，使用全表扫描模式", neo4jVersion);
        }
    }

    /**
     * 向量索引配置
     */
    private record VectorIndexConfig(
        String indexName,
        String label,
        String property,
        int dimension
    ) {}
}
