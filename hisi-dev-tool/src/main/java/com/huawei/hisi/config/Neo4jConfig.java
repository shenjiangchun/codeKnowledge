package com.huawei.hisi.config;

import org.neo4j.driver.AuthTokens;
import org.neo4j.driver.Config;
import org.neo4j.driver.Driver;
import org.neo4j.driver.GraphDatabase;
import org.neo4j.driver.SessionConfig;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.convert.converter.Converter;
import org.springframework.data.convert.ReadingConverter;
import org.springframework.data.convert.WritingConverter;
import org.springframework.data.neo4j.core.DatabaseSelection;
import org.springframework.data.neo4j.core.DatabaseSelectionProvider;
import org.springframework.data.neo4j.core.convert.Neo4jConversions;
import org.springframework.data.neo4j.core.transaction.Neo4jTransactionManager;
import org.springframework.data.neo4j.repository.config.EnableNeo4jRepositories;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Neo4j 配置类
 * 配置 Neo4j Driver 和事务管理器
 */
@Configuration
@EnableTransactionManagement
@EnableNeo4jRepositories(basePackages = "com.huawei.hisi.neo4j.repository")
public class Neo4jConfig {

    @Value("${neo4j.uri:bolt://localhost:7687}")
    private String uri;

    @Value("${neo4j.username:neo4j}")
    private String username;

    @Value("${neo4j.password:neo4j}")
    private String password;

    @Value("${neo4j.database:neo4j}")
    private String database;

    @Value("${neo4j.pool.max-connection-pool-size:50}")
    private int maxConnectionPoolSize;

    @Value("${neo4j.pool.connection-timeout:30s}")
    private Duration connectionTimeout;

    /**
     * 创建 Neo4j Driver Bean
     * 配置连接池参数以优化性能和资源管理
     */
    @Bean
    public Driver neo4jDriver() {
        Config config = Config.builder()
            .withMaxConnectionPoolSize(maxConnectionPoolSize)
            .withConnectionTimeout(connectionTimeout.toMillis(), TimeUnit.MILLISECONDS)
            .build();
        return GraphDatabase.driver(uri, AuthTokens.basic(username, password), config);
    }

    /**
     * 配置 DatabaseSelectionProvider，让 Spring Data Neo4j Repository 使用配置的数据库名
     * 默认 SDN 使用 "neo4j"，此处读取 neo4j.database 配置项覆盖
     */
    @Bean
    public DatabaseSelectionProvider databaseSelectionProvider() {
        return () -> DatabaseSelection.byName(database);
    }

    /**
     * SessionConfig bean — 所有直接使用 Driver.session() 的代码注入此 bean，
     * 确保统一走配置的数据库（而非 Driver 默认的 neo4j 库）
     */
    @Bean
    public SessionConfig neo4jSessionConfig() {
        return SessionConfig.forDatabase(database);
    }

    /**
     * 配置 Neo4j 事务管理器
     * 使用 transactionManager 作为主事务管理器
     */
    @Bean
    @org.springframework.context.annotation.Primary
    public PlatformTransactionManager transactionManager(Driver driver, DatabaseSelectionProvider databaseSelectionProvider) {
        return new Neo4jTransactionManager(driver, databaseSelectionProvider);
    }

    /**
     * Neo4j 专用事务管理器别名
     */
    @Bean("neo4jTransactionManager")
    public PlatformTransactionManager neo4jTransactionManager(Driver driver, DatabaseSelectionProvider databaseSelectionProvider) {
        return new Neo4jTransactionManager(driver, databaseSelectionProvider);
    }

    /**
     * 注册自定义 Neo4j 类型转换器
     * 修复 SDN 内置转换链 (AdditionalTypes.asFloatArray → asFloat → asString) 无法将
     * Neo4j 原生 FLOAT 列表映射为 Java float[] 的 bug。
     * 新增向量后，任何 RETURN m 查询的 MethodNode 都会携带 descriptionEmbedding/codeEmbedding
     * 字段，如果不注册此转换器，将抛出 MappingException。
     */
    @Bean
    public Neo4jConversions neo4jConversions() {
        return new Neo4jConversions(List.of(
                new FloatArrayReadingConverter(),
                new FloatArrayFromDoubleListConverter(),
                new FloatArrayFromFloatListConverter(),
                new FloatArrayFromNumberListConverter(),
                new FloatArrayWritingConverter()
        ));
    }

    /**
     * 读取转换器: List<Double> → float[]
     * SDN 在某些路径下会先把 Neo4j 列表展开为 List<Double> 再寻找转换器，
     * 此时 Value→float[] 转换器无法匹配，需要单独注册 List<Double> 路径。
     */
    @ReadingConverter
    static class FloatArrayFromDoubleListConverter implements Converter<List<Double>, float[]> {
        @Override
        public float[] convert(List<Double> source) {
            if (source == null) return null;
            float[] result = new float[source.size()];
            for (int i = 0; i < source.size(); i++) {
                Double d = source.get(i);
                result[i] = d == null ? 0f : d.floatValue();
            }
            return result;
        }
    }

    /**
     * 读取转换器: List<Float> → float[]
     */
    @ReadingConverter
    static class FloatArrayFromFloatListConverter implements Converter<List<Float>, float[]> {
        @Override
        public float[] convert(List<Float> source) {
            if (source == null) return null;
            float[] result = new float[source.size()];
            for (int i = 0; i < source.size(); i++) {
                Float f = source.get(i);
                result[i] = f == null ? 0f : f;
            }
            return result;
        }
    }

    /**
     * 读取转换器: List<Number> → float[] (兜底,覆盖 Long/Integer 等异常情况)
     */
    @ReadingConverter
    static class FloatArrayFromNumberListConverter implements Converter<List<Number>, float[]> {
        @Override
        public float[] convert(List<Number> source) {
            if (source == null) return null;
            float[] result = new float[source.size()];
            for (int i = 0; i < source.size(); i++) {
                Number n = source.get(i);
                result[i] = n == null ? 0f : n.floatValue();
            }
            return result;
        }
    }

    /**
     * 读取转换器: Neo4j Value (FLOAT LIST) → Java float[]
     * Neo4j 驱动将节点属性中的浮点数列表作为 org.neo4j.driver.Value 传入，
     * SDN 需要将其映射为实体的 float[] 字段。
     */
    @ReadingConverter
    static class FloatArrayReadingConverter implements Converter<org.neo4j.driver.Value, float[]> {
        @Override
        public float[] convert(org.neo4j.driver.Value source) {
            if (source == null || source.isNull()) {
                return null;
            }
            // 使用驱动内置转换，将每个 FloatValue 解包为 Java Double
            List<Double> list = source.asList(org.neo4j.driver.Value::asDouble);
            float[] result = new float[list.size()];
            for (int i = 0; i < list.size(); i++) {
                result[i] = list.get(i).floatValue();
            }
            return result;
        }
    }

    /**
     * 写入转换器: Java float[] → Neo4j Value (FLOAT LIST)
     */
    @WritingConverter
    static class FloatArrayWritingConverter implements Converter<float[], org.neo4j.driver.Value> {
        @Override
        public org.neo4j.driver.Value convert(float[] source) {
            if (source == null) {
                return org.neo4j.driver.Values.NULL;
            }
            List<Double> list = new ArrayList<>(source.length);
            for (float v : source) {
                list.add((double) v);
            }
            return org.neo4j.driver.Values.value(list);
        }
    }
}
