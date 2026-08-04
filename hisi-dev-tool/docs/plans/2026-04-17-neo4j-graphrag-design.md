# 知识图谱 Neo4j 迁移与 GraphRAG 自然语言检索设计

## 文档信息
- **创建日期**: 2026-04-17
- **版本**: 1.0
- **相关文档**:
  - 技术方案: GraphRAG + 代理向量混合检索方案
  - 原设计: 2026-04-17-knowledge-graph-mcp-design.md

---

## 一、背景与目标

### 1.1 背景
当前知识图谱存储在 PostgreSQL 中，现有向量搜索实现（ChromaDB Python 桥接）存在以下问题：
1. **向量化内容不当**：直接将整个文件内容（代码段）转向量，而非代理向量（元数据拼接）
2. **架构冗余**：Python 桥接服务增加了运维复杂度
3. **检索策略单一**：仅支持向量匹配，无法利用图结构进行上下文扩展
4. **缺少图嵌入**：无 GraphSAGE 等图结构特征向量

### 1.2 目标
1. 将知识图谱从 PostgreSQL 迁移到 Neo4j
2. 实现代理向量生成（元数据拼接），干掉 Python 桥接服务
3. 实现"关键词过滤→向量匹配→图遍历扩展"三层混合检索
4. 补充 GraphSAGE 图嵌入
5. 实现 RRF 结果融合

---

## 二、整体架构

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                              GraphRAG 混合检索架构                              │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                               │
│  ┌─────────────────────────────────────────────────────────────────────┐    │
│  │                         用户自然语言查询                               │    │
│  │              "查询订单创建的核心方法"                                    │    │
│  └────────────────────────────┬────────────────────────────────────────┘    │
│                               ▼                                               │
│  ┌─────────────────────────────────────────────────────────────────────┐    │
│  │                     意图识别模块 (GLM-5)                               │    │
│  │   输入: "查询订单创建的核心方法"                                        │    │
│  │   输出: {entity: "订单创建", methodType: "核心入口", serviceName: "order-service"}│
│  └────────────────────────────┬────────────────────────────────────────┘    │
│                               ▼                                               │
│  ┌─────────────────────────────────────────────────────────────────────┐    │
│  │                     混合检索执行模块                                    │    │
│  │  ┌─────────────┐   ┌─────────────┐   ┌─────────────┐                  │    │
│  │  │ 关键词过滤   │ → │ 向量匹配    │ → │ 图遍历扩展   │                  │    │
│  │  │ (Cypher)    │   │ (HNSW)      │   │ (1-3层)     │                  │    │
│  │  └─────────────┘   └─────────────┘   └─────────────┘                  │    │
│  └────────────────────────────┬────────────────────────────────────────┘    │
│                               ▼                                               │
│  ┌─────────────────────────────────────────────────────────────────────┐    │
│  │                     RRF 结果融合 + 排序增强                             │    │
│  └────────────────────────────┬────────────────────────────────────────┘    │
│                               ▼                                               │
│  ┌─────────────────────────────────────────────────────────────────────┐    │
│  │                         Neo4j 图数据库                                  │    │
│  │  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐                  │    │
│  │  │ Method Node  │  │ Call Relation│  │ Vector Index │                  │    │
│  │  │ + 代理向量    │  │ + 图嵌入     │  │ (HNSW)       │                  │    │
│  │  └──────────────┘  └──────────────┘  └──────────────┘                  │    │
│  │                                                                        │    │
│  │  模块: GenAI插件(嵌入生成) + GDS插件(GraphSAGE)                        │    │
│  └─────────────────────────────────────────────────────────────────────┘    │
│                                                                               │
└─────────────────────────────────────────────────────────────────────────────┘
```

### 核心改变

| 模块 | 原架构 | 新架构 |
|------|--------|--------|
| **图数据库** | PostgreSQL (10张表) | Neo4j (原生图结构) |
| **向量存储** | ChromaDB Python 桥接 | Neo4j Vector Index |
| **向量化内容** | 整个文件内容 | 代理向量（元数据拼接） |
| **嵌入生成** | Python sentence-transformers | Neo4j GenAI 插件 / Java ONNX |
| **检索策略** | 单一向量匹配 | 三层混合检索 + RRF 融合 |
| **图嵌入** | 无 | GraphSAGE |
| **意图识别** | 无 | GLM-5 LLM |

---

## 三、Neo4j 数据模型设计

### 3.1 节点类型

| 节点类型 | 标签 | 属性 |
|---------|------|------|
| **方法节点** | `Method` | nodeId, className, methodName, signature, filePath, startLine, endLine, complexity, thrownExceptions, caughtExceptions, methodBody, projectPath, commentSummary, serviceName, **embedding**, **graphEmbedding**, **fusedEmbedding** |
| **入口点** | `EntryPoint` | nodeId, entryType, entryKey, entryInfo, projectPath |
| **接口** | `Interface` | interfaceName, projectPath |
| **实现类** | `Implementation` | className, projectPath |
| **Mapper** | `Mapper` | mapperInterface, xmlPath, namespace, projectPath |
| **SQL** | `SqlStatement` | sqlId, statementType, sqlStatement, parameterType, resultType, projectPath |
| **服务** | `Service` | serviceName, projectPath |
| **项目** | `Project` | projectPath, projectName |

### 3.2 关系类型

| 关系类型 | 起点 | 终点 | 属性 |
|---------|------|------|------|
| `CALLS` | Method | Method | callType, callLine, bridgeType, sqlId, targetService, targetEndpoint |
| `ENTRY_OF` | EntryPoint | Method | - |
| `IMPLEMENTS` | Implementation | Interface | - |
| `HAS_MAPPER` | Service | Mapper | - |
| `HAS_SQL` | Mapper | SqlStatement | - |
| `BELONGS_TO` | Method | Service | - |
| `IN_PROJECT` | * | Project | - |

### 3.3 向量属性设计

每个 Method 节点包含三种向量：

```cypher
// 代理向量（元数据拼接生成）
// 输入文本: "{className} {methodName} {signature} {commentSummary} {serviceName}"
embedding: FLOAT[]  // 384维 (all-MiniLM-L6-v2)

// 图嵌入（GraphSAGE 生成）
// 捕捉节点在调用链中的位置特征
graphEmbedding: FLOAT[]  // 128维

// 融合向量（加权融合）
// fusedEmbedding = 0.7 * embedding + 0.3 * graphEmbedding
fusedEmbedding: FLOAT[]  // 384维
```

### 3.4 向量索引

```cypher
// 创建向量索引（HNSW）
CREATE VECTOR INDEX method_vector_index IF NOT EXISTS
FOR (m:Method)
ON m.fusedEmbedding
OPTIONS {
  indexConfig: {
    `vector.dimensions`: 384,
    `vector.similarity_function`: 'cosine'
  }
};
```

---

## 四、代理向量生成

### 4.1 元数据拼接模板

```java
/**
 * 代理向量输入文本生成
 * 格式: "{className} {methodName} {signature} {commentSummary} {serviceName}"
 */
public String generateProxyVectorInput(MethodNode node) {
    StringBuilder sb = new StringBuilder();

    // 1. 全限定类名
    sb.append(node.getClassName()).append(" ");

    // 2. 方法名
    sb.append(node.getMethodName()).append(" ");

    // 3. 方法签名
    sb.append(node.getSignature()).append(" ");

    // 4. 方法注释摘要（从代码注释提取）
    sb.append(node.getCommentSummary() != null ? node.getCommentSummary() : "").append(" ");

    // 5. 所属微服务名（从 projectPath 解析）
    sb.append(extractServiceName(node.getProjectPath()));

    return sb.toString().trim();
}
```

### 4.2 注释摘要提取

在知识图谱生成阶段，新增注释扫描逻辑：

```java
// 从 Java 源码提取方法注释
public String extractCommentSummary(CompilationUnit cu, MethodDeclaration method) {
    // 1. 获取 Javadoc 注释
    Javadoc javadoc = method.getJavadoc();
    if (javadoc != null) {
        return javadoc.getDescription().getText();
    }

    // 2. 获取行注释
    Comment comment = method.getComment();
    if (comment != null) {
        return comment.getContent().trim();
    }

    // 3. 从方法名推断（驼峰转可读）
    return inferFromMethodName(method.getNameAsString());
}
```

### 4.3 嵌入生成方式

**方式一：ONNX Runtime（Java 本地）**
```xml
<dependency>
    <groupId>com.microsoft.onnxruntime</groupId>
    <artifactId>onnxruntime</artifactId>
    <version>1.18.0</version>
</dependency>
```

**方式二：Neo4j GenAI 插件（推荐）**
```cypher
// 批量生成向量
MATCH (m:Method)
WHERE m.proxyVectorInput IS NOT NULL
WITH m, genai.vector.encode(
  m.proxyVectorInput,
  "HuggingFace",
  { model: "sentence-transformers/all-MiniLM-L6-v2" }
) AS embedding
SET m.embedding = embedding
```

---

## 五、混合检索策略

### 5.1 三层检索流程

```java
/**
 * 混合检索：关键词过滤 → 向量匹配 → 图遍历扩展
 */
public List<MethodNode> hybridSearch(String query, QueryIntent intent) {
    // 第一层：关键词过滤（缩小候选集）
    List<MethodNode> candidates = keywordFilter(intent);

    // 第二层：向量匹配（语义召回）
    List<MethodNode> vectorResults = vectorSearch(query, candidates);

    // 第三层：图遍历扩展（上下文补充）
    List<MethodNode> graphResults = graphExpansion(vectorResults);

    // RRF 结果融合
    return fuseResults(vectorResults, graphResults);
}
```

### 5.2 关键词过滤（Cypher）

```cypher
// 根据意图生成过滤条件
MATCH (m:Method)-[:BELONGS_TO]->(s:Service)
WHERE s.name = $serviceName  // 微服务名过滤
  AND m.methodName CONTAINS $keyword  // 关键词过滤
RETURN m
```

### 5.3 向量匹配（HNSW）

```cypher
// 向量相似度搜索
CALL db.index.vector.queryNodes('method_vector_index', $topK, $queryEmbedding)
YIELD node AS m, score
WHERE m.projectPath = $projectPath
RETURN m, score
ORDER BY score DESC
LIMIT $topK
```

### 5.4 图遍历扩展

```cypher
// 以 Top-K 节点为起点，扩展 1-3 层调用链
MATCH (start:Method)-[:CALLS*1..3]->(related:Method)
WHERE start.nodeId IN $topKNodeIds
  AND related.projectPath = $projectPath
RETURN DISTINCT related,
       [path IN (start)-[:CALLS*1..3]->(related) | nodes(path)] AS callPaths
```

### 5.5 RRF 结果融合

```java
/**
 * Reciprocal Rank Fusion 算法
 */
public List<MethodNode> fuseResults(
    List<MethodNode> vectorResults,
    List<MethodNode> graphResults
) {
    Map<String, Double> scoreMap = new HashMap<>();
    double k = 60.0; // RRF 平滑参数

    // 向量结果权重
    for (int i = 0; i < vectorResults.size(); i++) {
        String nodeId = vectorResults.get(i).getNodeId();
        scoreMap.merge(nodeId, 1.0 / (k + i + 1), Double::sum);
    }

    // 图遍历结果权重（稍低）
    for (int i = 0; i < graphResults.size(); i++) {
        String nodeId = graphResults.get(i).getNodeId();
        scoreMap.merge(nodeId, 0.8 / (k + i + 1), Double::sum);
    }

    // 按分数排序返回
    return scoreMap.entrySet().stream()
        .sorted(Map.Entry.comparingByValue(Comparator.reverseOrder()))
        .map(e -> findById(e.getKey()))
        .collect(Collectors.toList());
}
```

---

## 六、GraphSAGE 图嵌入

### 6.1 图嵌入原理

GraphSAGE 通过采样节点的邻居信息，生成能够捕捉图结构特征的嵌入向量：
- 能够捕捉调用链中的位置特征
- 相同业务流程的节点嵌入相似
- 支持增量更新

### 6.2 Neo4j GDS 实现

```cypher
// 1. 投影图结构
CALL gds.graph.project(
  'callGraph',
  ['Method', 'EntryPoint'],
  ['CALLS', 'ENTRY_OF']
);

// 2. 训练 GraphSAGE 模型
CALL gds.beta.graphSage.train('callGraph', {
  modelName: 'methodGraphEmbedding',
  featureProperties: ['complexity'],
  embeddingDimension: 128,
  epochs: 10,
  aggregation: 'mean'
});

// 3. 生成嵌入并存储
CALL gds.beta.graphSage.inference('callGraph', 'methodGraphEmbedding')
YIELD nodeId, embedding
MATCH (m:Method) WHERE elementId(m) = nodeId
SET m.graphEmbedding = embedding;
```

### 6.3 向量融合

```cypher
// 融合代理向量和图嵌入
MATCH (m:Method)
WHERE m.embedding IS NOT NULL AND m.graphEmbedding IS NOT NULL
WITH m,
     [i IN range(0, 383) |
       CASE WHEN i < 128
         THEN 0.7 * m.embedding[i] + 0.3 * m.graphEmbedding[i]
         ELSE 0.7 * m.embedding[i]
       END
     ] AS fused
SET m.fusedEmbedding = fused
```

---

## 七、意图识别模块

### 7.1 GLM-5 提示词设计

```java
private static final String INTENT_PROMPT = """
你是一个Java代码检索助手，需要将用户的自然语言问题转换为结构化的查询条件。
请按照以下JSON格式输出，不要输出其他内容：
{
  "entity": "要查询的核心实体（如订单、用户）",
  "methodType": "方法类型（如核心入口、工具方法、异步任务）",
  "serviceName": "所属微服务包名（如order-service、user-service）",
  "keywords": ["关键词1", "关键词2"]
}
注意：如果无法识别某个字段，请将其设为null。

用户问题：%s
""";
```

### 7.2 结构化输出解析

```java
public QueryIntent parseIntent(String userQuery) {
    String prompt = String.format(INTENT_PROMPT, userQuery);
    String response = llmService.generateText(prompt);

    // 解析 JSON
    return objectMapper.readValue(response, QueryIntent.class);
}
```

---

## 八、文件修改清单

### 8.1 新增文件

| 文件路径 | 说明 |
|---------|------|
| `config/Neo4jConfig.java` | Neo4j 连接配置 |
| `config/VectorSearchConfig.java` | 向量搜索配置 |
| `repository/neo4j/*.java` | Neo4j Repository 层 |
| `service/VectorSearchService.java` | 向量搜索服务接口 |
| `service/impl/VectorSearchServiceImpl.java` | 向量搜索实现 |
| `service/IntentRecognitionService.java` | 意图识别服务 |
| `service/GraphEmbeddingService.java` | 图嵌入服务 |
| `service/ProxyVectorService.java` | 代理向量生成服务 |
| `util/CommentExtractor.java` | 注释提取工具 |
| `util/RRFFusion.java` | RRF 结果融合工具 |

### 8.2 修改文件

| 文件路径 | 修改内容 |
|---------|---------|
| `model/MethodNode.java` | 增加 commentSummary, serviceName, embedding, graphEmbedding, fusedEmbedding 字段 |
| `knowledgegraph/scanner/*.java` | 增加注释扫描逻辑 |
| `pom.xml` | 添加 Neo4j Java Driver、ONNX Runtime 依赖 |
| `application.yml` | 添加 Neo4j、向量搜索配置 |

### 8.3 删除文件

| 文件路径 | 说明 |
|---------|------|
| `vectorstore/*` | 删除原 ChromaDB 桥接相关代码 |
| Python 向量服务 | 可选删除 hisi-vector-service 目录 |

---

## 九、迁移实施计划

### 阶段 1: Neo4j 环境搭建（1-2 天）

1. 安装 Neo4j Desktop 或 Docker 版本
2. 安装 APOC、GDS、GenAI 插件
3. 创建数据库和约束
4. 验证连接和基本查询

### 阶段 2: 数据模型迁移（3-4 天）

1. 创建 Neo4j 节点和关系定义
2. 实现 PostgreSQL 到 Neo4j 数据迁移脚本
3. 验证数据完整性
4. 创建向量索引

### 阶段 3: 代理向量实现（2-3 天）

1. 实现注释提取逻辑
2. 实现代理向量输入文本生成
3. 批量生成嵌入向量
4. 验证向量质量

### 阶段 4: 图嵌入与融合（2-3 天）

1. 配置 GDS 插件
2. 训练 GraphSAGE 模型
3. 生成图嵌入
4. 实现向量融合

### 阶段 5: 混合检索实现（3-4 天）

1. 实现关键词过滤
2. 实现向量匹配
3. 实现图遍历扩展
4. 实现 RRF 融合

### 阶段 6: 意图识别集成（2-3 天）

1. 实现意图识别提示词
2. 实现 GLM-5 调用
3. 实现结构化输出解析
4. 集成到检索流程

### 阶段 7: 测试与优化（2-3 天）

1. 单元测试
2. 性能测试
3. 效果评估
4. 参数调优

---

## 十、验收标准

1. ✅ Neo4j 数据库正常运行，10 张表数据完整迁移
2. ✅ 方法节点包含代理向量和图嵌入
3. ✅ 混合检索响应时间 < 200ms (P99)
4. ✅ Recall@3 ≥ 85%
5. ✅ MRR ≥ 0.8
6. ✅ 意图识别准确率 ≥ 85%
7. ✅ Python 桥接服务已下线
