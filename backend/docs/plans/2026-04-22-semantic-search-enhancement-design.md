# 知识图谱语义搜索增强设计方案

**版本**: 2.0（根据技术架构和应用架构评审意见修订）
**日期**: 2026-04-22
**作者**: Claude

## 一、背景与目标

### 1.1 当前问题

当前 `HybridSearchService` 存在以下缺陷：

| # | 问题 | 影响 |
|---|------|------|
| 1 | 向量仅基于 LLM 描述生成，缺少方法体内容 | 描述可能不准确，搜索精度低 |
| 2 | 只搜索 Method 节点，不搜 EntryPoint/Sql | 无法通过 SQL、入口 URI 定位代码 |
| 3 | 关键词过滤范围太窄 | 全限定名/类名搜索失效 |
| 4 | 不支持代码片段精确搜索 | 输入代码片段无法匹配 |
| 5 | 向量全表扫描，无索引 | 方法多时性能差 |
| 6 | 意图识别每次调用 LLM | 延迟高、成本高 |

### 1.2 目标

支持混合检索能力，根据输入类型精准定位：

| 输入类型 | 示例 | 目标搜索结果 |
|----------|------|-------------|
| 自然语言描述 | "查询用户订单信息的方法" | Method 节点 |
| 方法名/全限定名 | "selectById"、"UserMapper.selectById" | Method 节点 |
| 类名 | "UserService"、"OrderController" | Method 节点 |
| SQL 语句片段 | "SELECT * FROM user WHERE id=?" | Sql 节点 → 关联 Method |
| HTTP URI | "POST /api/user/login" | EntryPoint → Method |
| 代码片段 | "return userMapper.selectById(userId)" | Method 节点 |
| 注解 | "@Transactional"、"@Async" | Method 节点 |
| 异常类型 | "BusinessException"、"NullPointerException" | Method 节点 |

## 二、数据模型设计

### 2.1 Method 节点扩展

```cypher
// 当前属性
Method {
  nodeId: String,           // projectPath:className.methodName.signatureHash
  className: String,
  methodName: String,
  signature: String,
  methodBody: String,
  description: String,      // LLM 生成的描述
  embedding: [2048],        // 单一向量
  ...
}

// 扩展后属性
Method {
  nodeId: String,
  className: String,
  methodName: String,
  signature: String,
  methodBody: String,
  description: String,
  thrownExceptions: [String],
  caughtExceptions: [String],

  // 新增向量属性
  descriptionEmbedding: [2048],  // 基于方法体生成的 LLM 描述
  codeEmbedding: [2048],         // 基于方法签名+方法体原文

  ...
}
```

### 2.2 Sql 节点扩展

```cypher
Sql {
  nodeId: String,
  sqlId: String,
  sqlStatement: String,
  statementType: String,
  mapperInterface: String,

  // 新增向量属性
  sqlEmbedding: [2048],  // 基于 SQL 语句

  ...
}
```

### 2.3 EntryPoint 节点（无变化）

```cypher
EntryPoint {
  entryId: String,        // projectPath:type_className.methodName
  entryType: String,      // HTTP/SCHEDULED/MQ_CONSUMER
  entryKey: String,       // URI/cron/队列名
  methodNodeId: String,   // 外键 → Method.nodeId
  ...
}
```

### 2.4 向量索引与版本兼容

**启动时版本检测**（`Neo4jVectorIndexService`）：

```java
@Service
public class Neo4jVectorIndexService {

    private boolean vectorIndexAvailable = false;

    @PostConstruct
    public void checkVectorIndexSupport() {
        try {
            String version = neo4jClient.query(
                "CALL dbms.components() YIELD versions RETURN versions[0]"
            ).single(String.class);

            vectorIndexAvailable = isVersionAtLeast(version, "5.11.0");

            if (vectorIndexAvailable) {
                ensureVectorIndexesExist();
            } else {
                log.warn("Neo4j 版本 {} 不支持原生向量索引，将使用全表扫描", version);
            }
        } catch (Exception e) {
            log.warn("无法检测 Neo4j 版本，将使用全表扫描: {}", e.getMessage());
        }
    }

    private void ensureVectorIndexesExist() {
        // 创建向量索引（如不存在）
        // CREATE VECTOR INDEX IF NOT EXISTS method_description_idx ...
        // CREATE VECTOR INDEX IF NOT EXISTS method_code_idx ...
        // CREATE VECTOR INDEX IF NOT EXISTS sql_embedding_idx ...
    }
}
```

**Cypher 向量索引定义**（Neo4j 5.11+）：

```cypher
CREATE VECTOR INDEX method_description_idx IF NOT EXISTS
FOR (m:Method) ON m.descriptionEmbedding
OPTIONS {indexConfig: {
  `vector.dimensions`: 2048,
  `vector.similarity_function`: 'cosine'
}};

CREATE VECTOR INDEX method_code_idx IF NOT EXISTS
FOR (m:Method) ON m.codeEmbedding
OPTIONS {indexConfig: {
  `vector.dimensions`: 2048,
  `vector.similarity_function`: 'cosine'
}};

CREATE VECTOR INDEX sql_embedding_idx IF NOT EXISTS
FOR (s:Sql) ON s.sqlEmbedding
OPTIONS {indexConfig: {
  `vector.dimensions`: 2048,
  `vector.similarity_function`: 'cosine'
}};
```

**降级策略**：不支持向量索引时，回退到 `gds.similarity.cosine` 全表扫描。

## 三、向量生成策略

### 3.1 Method - descriptionEmbedding

**用途**: 自然语言搜索

**生成文本**:
```
Prompt 输入:
类名: com.example.service.UserService
方法名: getUserById
签名: User getUserById(String id)
注释: 根据ID查询用户
方法体:
public User getUserById(String id) {
    if (id != null && !id.isEmpty()) {
        return userMapper.selectById(Long.parseLong(id));
    }
    return null;
}

LLM 输出:
根据ID从数据库查询用户信息，若ID无效则返回null
```

**存储**: `Method.descriptionEmbedding`

### 3.2 Method - codeEmbedding

**用途**: 代码片段搜索

**生成文本**:
```
输入文本 = 方法签名 + 方法体原文（不经过 LLM）

例如:
"public User getUserById(String id) {
     if (id != null && !id.isEmpty()) {
         return userMapper.selectById(Long.parseLong(id));
     }
     return null;
 }"
```

- 方法体超过 2000 字符时，截取签名 + 前 1500 字符 + return 语句（如有）

**存储**: `Method.codeEmbedding`

### 3.3 Sql - sqlEmbedding

**用途**: SQL 片段搜索

**生成文本**:
```
输入文本 = SQL 语句原文

例如:
"SELECT id, name, email FROM user WHERE id = ?"
```

**存储**: `Sql.sqlEmbedding`

### 3.4 API 调用次数与优化

| 节点类型 | LLM 调用 | Embedding 调用 | 总计 |
|----------|----------|---------------|------|
| Method | 1 (描述生成) | 2 (双向量) | 3 |
| Sql | 0 | 1 | 1 |

**优化措施**：

1. **查询向量缓存**：对相同查询文本的 embedding 结果缓存 1 小时
   ```java
   // 使用 Caffeine 缓存
   private final Cache<String, float[]> queryEmbeddingCache = Caffeine.newBuilder()
       .maximumSize(1000)
       .expireAfterAccess(Duration.ofHours(1))
       .build();
   ```

2. **批量 Embedding 生成**：同一批次方法并行生成 embedding，利用已有并发线程池

3. **断点续传**：向量生成记录已处理 nodeId，中断后从上次位置继续

## 四、搜索策略设计

### 4.1 查询类型识别（本地规则，多规则评分）

```java
public enum QueryType {
    NATURAL_LANGUAGE,    // "查询用户订单信息的方法"
    METHOD_NAME,         // "selectById"
    FULL_QUALIFIED_NAME, // "com.example.mapper.UserMapper.selectById"
    CLASS_NAME,          // "UserService"、"OrderController"
    SQL_SNIPPET,         // "SELECT * FROM user"
    HTTP_URI,            // "POST /api/user/login"
    CODE_SNIPPET,        // "return userMapper.selectById(id)"
    ANNOTATION,          // "@Transactional"、"@Async"
    EXCEPTION_TYPE       // "BusinessException"、"NullPointerException"
}

/**
 * 多规则评分机制：每种类型有多个规则打分，取得分最高的类型
 * 避免单一正则误判
 */
public QueryType detectQueryType(String query) {
    Map<QueryType, Integer> scores = new EnumMap<>(QueryType.class);
    String q = query.trim();

    // === HTTP_URI 评分 ===
    // 强特征: 以 HTTP 方法 + 空格 + / 开头
    if (q.matches("^(GET|POST|PUT|DELETE|PATCH)\\s+/.*")) {
        scores.merge(HTTP_URI, 20, Integer::sum);
    }
    // 中特征: 以 / 开头且含路径段
    if (q.matches("^/[a-zA-Z][a-zA-Z0-9/_{}-]*(\\?.*)?$") && q.length() > 3) {
        scores.merge(HTTP_URI, 10, Integer::sum);
    }

    // === SQL_SNIPPET 评分 ===
    // 强特征: 以 SQL 关键字 + 空格 开头（排除方法名混淆）
    if (q.matches("(?i)^(SELECT|INSERT|UPDATE|DELETE|WITH)\\s+\\S+.*")) {
        scores.merge(SQL_SNIPPET, 15, Integer::sum);
    }
    // 排除: selectById 这种不跟空格的方法名
    if (q.matches("(?i)^(select|insert|update|delete)[A-Z].*")) {
        scores.merge(METHOD_NAME, 10, Integer::sum);  // 更可能是方法名
    }

    // === FULL_QUALIFIED_NAME 评分 ===
    // 强特征: 含 3+ 个点分隔组件，最后一个点前是大写字母开头的类名
    if (q.matches("^[a-z][a-z0-9_]*(\\.[a-z][a-z0-9_]*){2,}\\.[A-Z][a-zA-Z0-9_]*\\.[a-zA-Z][a-zA-Z0-9_]*$")) {
        scores.merge(FULL_QUALIFIED_NAME, 20, Integer::sum);
    }
    // 中特征: 含包名.类名.方法 格式
    if (q.matches("^[a-z]+(\\.[a-z]+)*\\.[A-Z][a-zA-Z0-9]*\\.[a-z][a-zA-Z0-9]*$")) {
        scores.merge(FULL_QUALIFIED_NAME, 12, Integer::sum);
    }

    // === ANNOTATION 评分 ===
    if (q.matches("^@[A-Z][a-zA-Z0-9]*$")) {
        scores.merge(ANNOTATION, 20, Integer::sum);
    }

    // === EXCEPTION_TYPE 评分 ===
    // 特征: 以大写字母开头，含 Exception/Error/Exception 结尾
    if (q.matches("^[A-Z][a-zA-Z0-9]*(Exception|Error)$")) {
        scores.merge(EXCEPTION_TYPE, 15, Integer::sum);
    }

    // === CLASS_NAME 评分 ===
    // 特征: 以大写字母开头，含驼峰，不含空格和特殊字符
    if (q.matches("^[A-Z][a-zA-Z0-9]*$") && q.length() > 2) {
        scores.merge(CLASS_NAME, 12, Integer::sum);
    }
    // 排除: 可能是异常类型
    if (q.matches("^[A-Z][a-zA-Z0-9]*(Exception|Error)$")) {
        scores.merge(CLASS_NAME, -5, Integer::sum);  // 降权
    }

    // === CODE_SNIPPET 评分 ===
    long codeFeatures = 0;
    if (q.contains("(") && q.contains(")")) codeFeatures += 3;
    if (q.contains("{") || q.contains("}")) codeFeatures += 2;
    if (q.contains(";")) codeFeatures += 2;
    if (q.matches(".*\\b(return|if|for|while|new|throw|try|catch)\\b.*")) codeFeatures += 3;
    if (q.contains("->") || q.contains("::")) codeFeatures += 2;
    if (codeFeatures >= 4) {
        scores.merge(CODE_SNIPPET, (int) codeFeatures, Integer::sum);
    }

    // === METHOD_NAME 评分 ===
    if (q.matches("^[a-z][a-zA-Z0-9_]*$")) {
        scores.merge(METHOD_NAME, 10, Integer::sum);
    }
    if (q.matches("^[A-Z][a-zA-Z0-9_]*\\.[a-z][a-zA-Z0-9_]*$")) {
        scores.merge(METHOD_NAME, 12, Integer::sum);  // ClassName.methodName
    }

    // === NATURAL_LANGUAGE 评分 ===
    // 兜底: 含中文或空格分隔的多词
    if (q.matches(".*[\\u4e00-\\u9fa5].*") || q.split("\\s+").length > 3) {
        scores.merge(NATURAL_LANGUAGE, 8, Integer::sum);
    }

    // 取得分最高的类型
    return scores.entrySet().stream()
        .max(Map.Entry.comparingByValue())
        .map(Map.Entry::getKey)
        .orElse(NATURAL_LANGUAGE);
}
```

### 4.2 搜索策略路由

| 查询类型 | 搜索策略 | 数据源 |
|----------|----------|--------|
| NATURAL_LANGUAGE | descriptionEmbedding 向量检索 + 图扩展 | Method |
| METHOD_NAME | methodName 模糊匹配 + 向量补充 + 图扩展 | Method |
| FULL_QUALIFIED_NAME | className + methodName 精确匹配 + 图扩展 | Method |
| CLASS_NAME | className 精确/模糊匹配 + 图扩展 | Method |
| SQL_SNIPPET | sqlEmbedding 向量检索 → EXECUTES_SQL 反查 | Sql → Method |
| HTTP_URI | entryKey 模糊匹配 → methodNodeId 关联 | EntryPoint → Method |
| CODE_SNIPPET | codeEmbedding 向量检索 + 图扩展 | Method |
| ANNOTATION | methodBody/comment CONTAINS 匹配 | Method |
| EXCEPTION_TYPE | thrownExceptions/caughtExceptions CONTAINS 匹配 | Method |

### 4.3 搜索流程图

```
用户输入
    │
    ▼
┌──────────────────────────────────┐
│ QueryTypeDetector.detect()       │  ← 本地多规则评分，无 LLM 调用
└──────────────────────────────────┘
    │
    ▼
┌─────────────────────────────────────────────────────────────────┐
│ 根据类型路由：                                                   │
│                                                                 │
│ NATURAL_LANGUAGE:                                               │
│   向量化(缓存优先) → 向量索引/全表扫描 → 图遍历扩展              │
│                                                                 │
│ METHOD_NAME / CLASS_NAME / FULL_QUALIFIED_NAME:                 │
│   Cypher 属性匹配 → 向量补充 → 图遍历扩展                       │
│                                                                 │
│ SQL_SNIPPET:                                                    │
│   向量化(缓存优先) → sqlEmbedding 向量检索                       │
│   → (m:Method)-[:EXECUTES_SQL]->(sql) 反查 Method               │
│                                                                 │
│ HTTP_URI:                                                       │
│   MATCH EntryPoint WHERE entryKey CONTAINS → methodNodeId       │
│                                                                 │
│ CODE_SNIPPET:                                                   │
│   向量化(缓存优先) → codeEmbedding 向量检索 → 图遍历扩展         │
│                                                                 │
│ ANNOTATION:                                                     │
│   MATCH Method WHERE methodBody CONTAINS '@Transactional'       │
│                                                                 │
│ EXCEPTION_TYPE:                                                 │
│   MATCH Method WHERE thrownExceptions/caughtExceptions CONTAINS │
└─────────────────────────────────────────────────────────────────┘
    │
    ▼
┌───────────────────────────────┐
│ 结果增强：                      │
│ - 补充关联 SQL (EXECUTES_SQL)  │
│ - 补充关联 EntryPoint          │
│ - 补充调用者/被调用者摘要       │
│ - 计算相似度分数               │
└───────────────────────────────┘
    │
    ▼
返回 SearchResult
```

## 五、API 设计

### 5.1 请求

```http
POST /api/vector-search
Content-Type: application/json

{
  "query": "SELECT * FROM user WHERE id=?",
  "projectPath": "C:/projects/my-project",
  "limit": 10,
  "graphDepth": 2,
  "includeSql": true
}
```

### 5.2 响应

```json
{
  "query": "SELECT * FROM user WHERE id=?",
  "queryType": "SQL_SNIPPET",
  "results": [
    {
      "nodeId": "path:UserServiceImpl.getUserById.hash",
      "nodeType": "Method",
      "className": "com.example.service.impl.UserServiceImpl",
      "methodName": "getUserById",
      "signature": "User getUserById(String id)",
      "filePath": "src/main/java/.../UserServiceImpl.java",
      "startLine": 25,
      "endLine": 30,
      "description": "根据ID从数据库查询用户信息",
      "similarityScore": 0.85,
      "callers": [
        {
          "className": "UserController",
          "methodName": "getUser",
          "signature": "Result getUser(@PathVariable String id)"
        }
      ],
      "callees": [
        {
          "className": "UserMapper",
          "methodName": "selectById",
          "signature": "User selectById(Long id)"
        }
      ],
      "entryPoints": [
        {
          "entryType": "HTTP",
          "entryKey": "GET /api/user/{id}"
        }
      ],
      "sqlNodes": [
        {
          "sqlId": "UserMapper.selectById",
          "statementType": "SELECT",
          "sqlStatement": "SELECT * FROM user WHERE id = ?"
        }
      ]
    }
  ],
  "totalCount": 5,
  "costTimeMs": 180,
  "suggestions": ["getUserById", "findUserById"]
}
```

### 5.3 无结果场景

```json
{
  "query": "xxx不存在的方法",
  "queryType": "METHOD_NAME",
  "results": [],
  "totalCount": 0,
  "costTimeMs": 50,
  "searchTips": "未找到匹配结果，建议：1. 尝试使用更简短的关键词 2. 使用自然语言描述功能 3. 搜索关联的类名",
  "suggestions": ["相似方法1", "相似方法2"]
}
```

### 5.4 错误码

| 错误码 | 含义 | 用户提示 |
|--------|------|---------|
| QUERY_TOO_SHORT | 查询过短 | "请输入至少 2 个字符" |
| EMBEDDING_SERVICE_UNAVAILABLE | 向量服务不可用 | "语义搜索暂时不可用，已切换到关键词搜索" |
| GRAPH_SERVICE_ERROR | 图谱服务异常 | "图谱服务异常，请稍后重试" |
| TIMEOUT | 搜索超时 | "搜索超时，请尝试简化搜索条件" |

## 六、实施计划

### 6.1 文件清单

| 文件 | 操作 | 说明 |
|------|------|------|
| `MethodNode.java` | 修改 | 新增 descriptionEmbedding、codeEmbedding |
| `SqlNode.java` | 修改 | 新增 sqlEmbedding |
| `LLMDescriptionService.java` | 修改 | Prompt 含方法体 |
| `VectorGenerationService.java` | 修改 | 生成多向量、断点续传 |
| `Neo4jVectorIndexService.java` | 新增 | 版本检测、索引管理、降级策略 |
| `Neo4jMethodNodeRepository.java` | 修改 | 向量索引查询、多向量更新 |
| `Neo4jSqlNodeRepository.java` | 修改 | SQL 向量查询 |
| `QueryTypeDetector.java` | 新增 | 多规则评分识别 |
| `SearchResult.java` | 修改 | 新增 queryType、similarityScore、上下文字段 |
| `HybridSearchService.java` | 重构 | 多策略路由 |
| `VectorSearchController.java` | 修改 | 扩展响应字段、错误码 |
| `QueryEmbeddingCache.java` | 新增 | Caffeine 查询向量缓存 |

### 6.2 实施阶段

```
Phase 1: 数据模型改造
├── MethodNode、SqlNode 新增向量属性
├── Repository 新增更新方法
├── Neo4jVectorIndexService 版本检测和索引创建
└── 降级策略实现

Phase 2: 向量生成改造
├── LLMDescriptionService Prompt 含方法体
├── VectorGenerationService 生成双向量 + Sql 向量
├── 断点续传机制
└── 查询向量缓存

Phase 3: 搜索服务重构
├── QueryTypeDetector 多规则评分识别
├── HybridSearchService 多策略路由
├── 搜索结果上下文增强
└── 无结果场景处理

Phase 4: API 和错误处理
├── VectorSearchController 扩展 API
├── 错误码和用户提示
└── SearchResult 扩展字段

Phase 5: 前端集成
├── 知识图谱页面搜索框（防抖 300ms）
├── 搜索结果展示（列表 + 上下文信息）
├── 点击定位图谱节点
└── 无结果提示和搜索建议

Phase 6: 测试验证
├── 各搜索策略单元测试
├── 查询类型识别准确率测试
├── 集成测试
└── 效果验证
```

### 6.3 数据迁移

已生成知识图谱的项目需重新生成向量：

```cypher
// 清除旧向量
MATCH (m:Method {projectPath: $projectPath})
SET m.embedding = null, m.descriptionEmbedding = null, m.codeEmbedding = null;

MATCH (s:Sql {projectPath: $projectPath})
SET s.sqlEmbedding = null;
```

前端点击"重新生成向量"触发新的向量生成流程，支持断点续传。

## 七、风险评估

| 风险 | 影响 | 缓解措施 |
|------|------|---------|
| Neo4j 版本不支持向量索引 | 性能不达预期 | 启动时版本检测，自动降级为全表扫描 |
| API 调用次数增加 | 成本增加 | 查询向量缓存、批量处理 |
| 向量维度 2048 过大 | 存储开销大 | 首期使用 2048，后续可评估降维 |
| 方法体过长 | Prompt 超限 | 截取签名+前1500字符+return语句 |
| 查询类型识别误判 | 搜索策略错误 | 多规则评分机制，降低误判率 |
| 向量生成中断 | 数据不完整 | 断点续传机制 |

## 八、性能指标

| 场景 | 指标 | 目标值 |
|------|------|--------|
| 精确匹配（方法名/类名/URI） | P95 延迟 | < 100ms |
| 向量搜索（自然语言/代码/SQL） | P95 延迟 | < 800ms |
| 图遍历扩展 | P95 延迟 | < 200ms |
| 1000 方法规模 | 全类型搜索 | < 500ms（精确）/ < 1000ms（向量） |
| 查询向量缓存命中 | 命中率 | > 60%（重复查询） |

## 九、验收标准

1. 支持 9 种输入类型的正确识别（准确率 > 90%）
2. 各输入类型搜索返回相关结果
3. SQL 搜索能关联到执行该 SQL 的方法
4. HTTP URI 搜索能关联到入口方法
5. 搜索结果包含关联 SQL、入口点、调用者/被调用者
6. 无结果时提供搜索建议和提示
7. Neo4j 版本兼容，不支持向量索引时自动降级
8. 查询向量缓存有效降低重复搜索延迟
9. 向量生成支持断点续传

## 十、修订记录

| 版本 | 日期 | 修改内容 |
|------|------|---------|
| 1.0 | 2026-04-22 | 初始版本 |
| 2.0 | 2026-04-22 | 根据技术架构和应用架构评审意见修订：1.新增Neo4j版本检测和降级策略 2.扩展查询类型（类名/注解/异常类型） 3.多规则评分识别替代单一正则 4.搜索结果增加上下文信息（调用者/被调用者/入口点/相似度） 5.新增查询向量缓存 6.新增断点续传 7.新增无结果场景处理 8.新增错误码 9.修正性能指标 10.方法体智能截取 |
