package com.huawei.hisi.neo4j.service;

import lombok.Builder;
import lombok.Data;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.neo4j.core.Neo4jClient;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * GraphSAGE图嵌入服务
 * 用于通过Neo4j GDS插件生成图的嵌入向量
 *
 * 功能:
 * - projectGraph() - 投影调用图
 * - trainGraphSageModel() - 训练GraphSAGE模型
 * - generateGraphEmbeddings() - 生成图嵌入
 * - dropGraph() - 清理图投影
 * - dropModel() - 清理模型
 */
@Service
public class GraphEmbeddingService {

    private static final Logger log = LoggerFactory.getLogger(GraphEmbeddingService.class);

    /**
     * 默认图名称
     */
    public static final String DEFAULT_GRAPH_NAME = "callGraph";

    /**
     * 默认模型名称
     */
    public static final String DEFAULT_MODEL_NAME = "callGraphSage";

    /**
     * 默认嵌入维度
     */
    public static final int DEFAULT_EMBEDDING_DIMENSION = 384;

    private final Neo4jClient neo4jClient;

    public GraphEmbeddingService(Neo4jClient neo4jClient) {
        this.neo4jClient = neo4jClient;
    }

    /**
     * 投影调用图到GDS内存
     *
     * @param graphName 图名称
     * @return 是否成功
     * @throws IllegalArgumentException 如果图名称为空
     */
    public boolean projectGraph(String graphName) {
        validateGraphName(graphName);
        log.info("开始投影图: {}", graphName);

        try {
            // 1. 检查并删除已存在的图
            dropGraphIfExists(graphName);

            // 2. 创建图投影
            String cypher = buildProjectGraphCypher(graphName);
            Optional<Map<String, Object>> result = neo4jClient.query(cypher)
                .fetch()
                .one();

            if (result.isPresent()) {
                log.info("图投影成功: {}", result.get());
                return true;
            }
            return false;
        } catch (Exception e) {
            log.error("图投影失败: {}", e.getMessage(), e);
            return false;
        }
    }

    /**
     * 使用默认名称投影图
     */
    public boolean projectGraph() {
        return projectGraph(DEFAULT_GRAPH_NAME);
    }

    /**
     * 训练GraphSAGE模型
     *
     * @param graphName 图名称
     * @param modelName 模型名称
     * @return 是否成功
     */
    public boolean trainGraphSageModel(String graphName, String modelName) {
        return trainGraphSageModel(graphName, modelName, TrainConfig.defaultConfig());
    }

    /**
     * 使用自定义配置训练GraphSAGE模型
     *
     * @param graphName 图名称
     * @param modelName 模型名称
     * @param config 训练配置
     * @return 是否成功
     */
    public boolean trainGraphSageModel(String graphName, String modelName, TrainConfig config) {
        validateGraphName(graphName);
        validateModelName(modelName);

        log.info("开始训练GraphSAGE模型: {} on graph: {}", modelName, graphName);

        try {
            // 检查并删除已存在的模型
            dropModelIfExists(modelName);

            // 构建训练Cypher
            String cypher = buildTrainCypher(graphName, modelName, config);
            Optional<Map<String, Object>> result = neo4jClient.query(cypher)
                .fetch()
                .one();

            if (result.isPresent()) {
                log.info("模型训练成功: {}", result.get());
                return true;
            }
            return false;
        } catch (Exception e) {
            log.error("模型训练失败: {}", e.getMessage(), e);
            return false;
        }
    }

    /**
     * 生成图嵌入
     *
     * @param graphName 图名称
     * @param modelName 模型名称
     * @return 节点ID到嵌入向量的映射
     */
    public Map<String, float[]> generateGraphEmbeddings(String graphName, String modelName) {
        return generateGraphEmbeddings(graphName, modelName, null);
    }

    /**
     * 按项目路径生成图嵌入
     *
     * @param graphName 图名称
     * @param modelName 模型名称
     * @param projectPath 项目路径（可选）
     * @return 节点ID到嵌入向量的映射
     */
    public Map<String, float[]> generateGraphEmbeddings(String graphName, String modelName, String projectPath) {
        validateGraphName(graphName);
        validateModelName(modelName);

        log.info("开始生成图嵌入: graph={}, model={}, projectPath={}", graphName, modelName, projectPath);

        Map<String, float[]> embeddings = new HashMap<>();

        try {
            String cypher = buildStreamEmbeddingsCypher(graphName, modelName, projectPath);

            Collection<Map<String, Object>> results = neo4jClient.query(cypher)
                .bind(projectPath != null ? projectPath : "").to("projectPath")
                .fetch()
                .all();

            for (Map<String, Object> row : results) {
                String nodeId = (String) row.get("nodeId");
                Object embeddingObj = row.get("embedding");

                float[] embedding = convertToFloatArray(embeddingObj);
                if (nodeId != null && embedding != null) {
                    embeddings.put(nodeId, embedding);
                }
            }

            log.info("生成嵌入完成，节点数: {}", embeddings.size());
        } catch (Exception e) {
            log.error("生成嵌入失败: {}", e.getMessage(), e);
        }

        return embeddings;
    }

    /**
     * 将生成的嵌入写入Neo4j节点属性
     *
     * @param graphName 图名称
     * @param modelName 模型名称
     * @return 是否成功
     */
    public boolean writeGraphEmbeddings(String graphName, String modelName) {
        validateGraphName(graphName);
        validateModelName(modelName);

        log.info("写入图嵌入到数据库: graph={}, model={}", graphName, modelName);

        try {
            String cypher = String.format(
                "CALL gds.beta.graphSage.write('%s', {modelName: '%s', writeProperty: 'graphEmbedding'}) " +
                "YIELD nodePropertiesWritten, computeMillis, writeMillis " +
                "RETURN nodePropertiesWritten, computeMillis, writeMillis",
                graphName, modelName
            );

            Optional<Map<String, Object>> result = neo4jClient.query(cypher)
                .fetch()
                .one();

            if (result.isPresent()) {
                log.info("写入嵌入成功: {}", result.get());
                return true;
            }
            return false;
        } catch (Exception e) {
            log.error("写入嵌入失败: {}", e.getMessage(), e);
            return false;
        }
    }

    /**
     * 删除图投影
     *
     * @param graphName 图名称
     * @return 是否成功
     */
    public boolean dropGraph(String graphName) {
        validateGraphName(graphName);

        log.info("删除图投影: {}", graphName);

        try {
            String cypher = String.format("CALL gds.graph.drop('%s', false) YIELD graphName RETURN graphName", graphName);
            Optional<Map<String, Object>> result = neo4jClient.query(cypher)
                .fetch()
                .one();

            return result.isPresent();
        } catch (Exception e) {
            log.warn("删除图投影失败（可能不存在）: {}", e.getMessage());
            return false;
        }
    }

    /**
     * 删除模型
     *
     * @param modelName 模型名称
     * @return 是否成功
     */
    public boolean dropModel(String modelName) {
        validateModelName(modelName);

        log.info("删除模型: {}", modelName);

        try {
            String cypher = String.format("CALL gds.beta.model.drop('%s') YIELD modelInfo RETURN modelInfo.modelName", modelName);
            Optional<Map<String, Object>> result = neo4jClient.query(cypher)
                .fetch()
                .one();

            return result.isPresent();
        } catch (Exception e) {
            log.warn("删除模型失败（可能不存在）: {}", e.getMessage());
            return false;
        }
    }

    /**
     * 检查图是否存在
     *
     * @param graphName 图名称
     * @return 是否存在
     */
    public boolean graphExists(String graphName) {
        validateGraphName(graphName);

        try {
            String cypher = String.format("CALL gds.graph.exists('%s') YIELD exists RETURN exists", graphName);
            Optional<Map<String, Object>> result = neo4jClient.query(cypher)
                .fetch()
                .one();

            return result.map(m -> (Boolean) m.get("exists")).orElse(false);
        } catch (Exception e) {
            log.warn("检查图存在失败: {}", e.getMessage());
            return false;
        }
    }

    /**
     * 检查模型是否存在
     *
     * @param modelName 模型名称
     * @return 是否存在
     */
    public boolean modelExists(String modelName) {
        validateModelName(modelName);

        try {
            String cypher = String.format("CALL gds.beta.model.exists('%s') YIELD exists RETURN exists", modelName);
            Optional<Map<String, Object>> result = neo4jClient.query(cypher)
                .fetch()
                .one();

            return result.map(m -> (Boolean) m.get("exists")).orElse(false);
        } catch (Exception e) {
            log.warn("检查模型存在失败: {}", e.getMessage());
            return false;
        }
    }

    // ================== 私有辅助方法 ==================

    private void validateGraphName(String graphName) {
        if (graphName == null || graphName.trim().isEmpty()) {
            throw new IllegalArgumentException("图名称不能为空");
        }
    }

    private void validateModelName(String modelName) {
        if (modelName == null || modelName.trim().isEmpty()) {
            throw new IllegalArgumentException("模型名称不能为空");
        }
    }

    private void dropGraphIfExists(String graphName) {
        try {
            if (graphExists(graphName)) {
                dropGraph(graphName);
                log.info("已删除已存在的图投影: {}", graphName);
            }
        } catch (Exception e) {
            log.debug("检查/删除已存在图时出错: {}", e.getMessage());
        }
    }

    private void dropModelIfExists(String modelName) {
        try {
            if (modelExists(modelName)) {
                dropModel(modelName);
                log.info("已删除已存在的模型: {}", modelName);
            }
        } catch (Exception e) {
            log.debug("检查/删除已存在模型时出错: {}", e.getMessage());
        }
    }

    private String buildProjectGraphCypher(String graphName) {
        return String.format(
            "CALL gds.graph.project(" +
            "  '%s'," +
            "  'Method'," +
            "  {CALLS: {orientation: 'NATURAL', aggregation: 'SINGLE'}}," +
            "  {nodeProperties: ['className', 'methodName', 'signature', 'comment', 'serviceName']}" +
            ") YIELD graphName, nodeCount, relationshipCount " +
            "RETURN graphName, nodeCount, relationshipCount",
            graphName
        );
    }

    private String buildTrainCypher(String graphName, String modelName, TrainConfig config) {
        return String.format(
            "CALL gds.beta.graphSage.train(" +
            "  '%s'," +
            "  {" +
            "    modelName: '%s'," +
            "    embeddingDimension: %d," +
            "    aggregator: '%s'," +
            "    sampleSizes: %s," +
            "    epochs: %d," +
            "    learningRate: %f" +
            "  }" +
            ") YIELD modelInfo RETURN modelInfo",
            graphName,
            modelName,
            config.getEmbeddingDimension(),
            config.getAggregator(),
            Arrays.toString(config.getSampleSizes()),
            config.getEpochs(),
            config.getLearningRate()
        );
    }

    private String buildStreamEmbeddingsCypher(String graphName, String modelName, String projectPath) {
        if (projectPath != null && !projectPath.isEmpty()) {
            return String.format(
                "MATCH (m:Method) WHERE m.projectPath = $projectPath " +
                "WITH collect(id(m)) AS nodeIds " +
                "CALL gds.beta.graphSage.stream('%s', {modelName: '%s'}) " +
                "YIELD nodeId, embedding " +
                "WHERE nodeId IN nodeIds " +
                "RETURN gds.util.asNode(nodeId).nodeId AS nodeId, embedding",
                graphName, modelName
            );
        }
        return String.format(
            "CALL gds.beta.graphSage.stream('%s', {modelName: '%s'}) " +
            "YIELD nodeId, embedding " +
            "RETURN gds.util.asNode(nodeId).nodeId AS nodeId, embedding",
            graphName, modelName
        );
    }

    private float[] convertToFloatArray(Object obj) {
        if (obj == null) {
            return null;
        }

        if (obj instanceof float[]) {
            return (float[]) obj;
        }

        if (obj instanceof double[]) {
            double[] doubleArray = (double[]) obj;
            float[] floatArray = new float[doubleArray.length];
            for (int i = 0; i < doubleArray.length; i++) {
                floatArray[i] = (float) doubleArray[i];
            }
            return floatArray;
        }

        if (obj instanceof List) {
            List<?> list = (List<?>) obj;
            float[] floatArray = new float[list.size()];
            for (int i = 0; i < list.size(); i++) {
                Object item = list.get(i);
                if (item instanceof Number) {
                    floatArray[i] = ((Number) item).floatValue();
                }
            }
            return floatArray;
        }

        log.warn("无法转换嵌入向量类型: {}", obj.getClass().getName());
        return null;
    }

    // ================== 配置类 ==================

    /**
     * GraphSAGE训练配置
     */
    @Data
    @Builder
    public static class TrainConfig {
        /**
         * 嵌入向量维度
         */
        @Builder.Default
        private int embeddingDimension = DEFAULT_EMBEDDING_DIMENSION;

        /**
         * 聚合器类型: mean, sum, max
         */
        @Builder.Default
        private String aggregator = "mean";

        /**
         * 每层采样邻居数量
         */
        @Builder.Default
        private int[] sampleSizes = new int[]{25, 10};

        /**
         * 训练轮数
         */
        @Builder.Default
        private int epochs = 10;

        /**
         * 学习率
         */
        @Builder.Default
        private float learningRate = 0.1f;

        /**
         * 批大小
         */
        @Builder.Default
        private int batchSize = 100;

        /**
         * 最大迭代次数
         */
        @Builder.Default
        private int maxIterations = 100;

        /**
         * 容差
         */
        @Builder.Default
        private float tolerance = 1.0E-4f;

        /**
         * 获取默认配置
         */
        public static TrainConfig defaultConfig() {
            return TrainConfig.builder().build();
        }
    }
}
