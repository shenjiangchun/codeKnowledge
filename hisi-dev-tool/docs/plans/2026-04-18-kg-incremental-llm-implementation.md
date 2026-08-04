# 知识图谱增量生成与 LLM 自然语言描述 - 实现计划

## 概述

本文档是 `2026-04-18-kg-incremental-llm-design.md` 设计文档的详细实现计划。

---

## Phase 1: 后端基础设施 (预计 2 小时)

### Step 1.1: 添加 Maven 依赖

**文件**: `pom.xml`

添加 DJL (Deep Java Library) 依赖：
```xml
<!-- DJL for local embedding model -->
<dependency>
    <groupId>ai.djl</groupId>
    <artifactId>api</artifactId>
    <version>0.26.0</version>
</dependency>
<dependency>
    <groupId>ai.djl.huggingface</groupId>
    <artifactId>tokenizers</artifactId>
    <version>0.26.0</version>
</dependency>
<dependency>
    <groupId>ai.djl.onnxruntime</groupId>
    <artifactId>onnxruntime-engine</artifactId>
    <version>0.26.0</version>
</dependency>
```

### Step 1.2: 创建生成日志实体和仓库

**新增文件**: `model/KgGenerationLog.java`
**新增文件**: `repository/KgGenerationLogRepository.java`

```java
// KgGenerationLog.java
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class KgGenerationLog {
    private Long id;
    private String projectPath;
    private String commitHash;
    private String branch;
    private String generationMode; // FULL / INCREMENTAL
    private Integer totalMethods;
    private Integer newMethods;
    private Integer updatedMethods;
    private Integer deletedMethods;
    private LocalDateTime startTime;
    private LocalDateTime endTime;
    private Long costTimeMs;
    private String errorMessage;
}
```

创建 PostgreSQL 表（使用 @PostConstruct 自动创建）。

### Step 1.3: 修改 MethodNode 实体

**修改文件**: `model/MethodNode.java`

新增字段：
```java
private String contentHash;    // 内容哈希
private String description;    // LLM 生成的描述
```

---

## Phase 2: Git 状态服务 (预计 1 小时)

### Step 2.1: 创建 GitStatusService

**新增文件**: `service/GitStatusService.java`

```java
@Service
@Slf4j
public class GitStatusService {

    public GitStatus checkStatus(Path projectPath) {
        // 1. git status --porcelain 检查是否干净
        // 2. git rev-parse HEAD 获取 commitHash
        // 3. git rev-parse --abbrev-ref HEAD 获取分支名
    }

    public List<String> getChangedFiles(Path projectPath, String fromCommit, String toCommit) {
        // git diff --name-only fromCommit..toCommit
    }
}
```

**新增文件**: `model/GitStatus.java` (DTO)

---

## Phase 3: 本地向量生成服务 (预计 2 小时)

### Step 3.1: 创建 LocalEmbeddingService

**新增文件**: `service/LocalEmbeddingService.java`

```java
@Service
@Slf4j
public class LocalEmbeddingService {

    public static final int EMBEDDING_DIMENSION = 384;
    private Predictor<String, float[]> predictor;

    @PostConstruct
    public void init() {
        // 加载 ONNX 模型
        // 首次运行会自动下载模型
    }

    public float[] generateEmbedding(String text) {
        // 调用模型生成向量
    }

    public List<float[]> batchGenerate(List<String> texts) {
        // 批量生成
    }
}
```

### Step 3.2: 修改 EmbeddingService

**修改文件**: `service/EmbeddingService.java`

- 移除 LLM API 调用逻辑
- 改为调用 LocalEmbeddingService
- 保留 fallback 逻辑（本地模型加载失败时使用）

---

## Phase 4: LLM 描述生成服务 (预计 1.5 小时)

### Step 4.1: 创建 LLMDescriptionService

**新增文件**: `service/LLMDescriptionService.java`

```java
@Service
@Slf4j
public class LLMDescriptionService {

    @Value("${llm.base-url}")
    private String llmBaseUrl;

    private static final String PROMPT_TEMPLATE = """
        请用一句话描述以下Java方法的功能（50字以内）：
        类名：%s
        方法名：%s
        签名：%s
        注释：%s
        """;

    public String generateDescription(MethodNode node) {
        // 调用 LLM API 生成描述
    }

    public Map<String, String> batchGenerate(List<MethodNode> nodes) {
        // 批量生成（可以并行）
    }
}
```

---

## Phase 5: 增量更新服务 (预计 2 小时)

### Step 5.1: 创建 IncrementalUpdateService

**新增文件**: `service/IncrementalUpdateService.java`

```java
@Service
@Slf4j
public class IncrementalUpdateService {

    public IncrementalUpdateResult update(Path projectPath, String fromCommit, String toCommit) {
        // 1. 获取变更文件列表
        // 2. 解析变更文件，提取方法变更
        // 3. 对比现有节点，确定新增/修改/删除
        // 4. 执行更新操作
    }
}
```

**新增文件**: `model/IncrementalUpdateResult.java`

---

## Phase 6: API 扩展 (预计 1 小时)

### Step 6.1: 扩展 KnowledgeGraphController

**修改文件**: `controller/KnowledgeGraphController.java`

新增接口：
```java
// GET /api/knowledge-graph/git-status
public GitStatusResponse getGitStatus(@RequestParam String projectPath);

// POST /api/knowledge-graph/incremental
public IncrementalResponse incrementalGenerate(@RequestBody IncrementalRequest request);
```

---

## Phase 7: 前端适配 (预计 1.5 小时)

### Step 7.1: 更新 API 接口

**修改文件**: `hisi-dev-tool-frontend/src/api/knowledgeGraph.ts`

新增接口定义。

### Step 7.2: 更新知识图谱页面

**修改文件**: `hisi-dev-tool-frontend/src/views/knowledge-graph/index.vue`

- 添加增量生成按钮
- 添加 Git 状态检查逻辑
- 添加提示信息展示

---

## Phase 8: 测试与验证 (预计 1 小时)

### Step 8.1: 单元测试

为新增服务编写单元测试：
- `GitStatusServiceTest.java`
- `LocalEmbeddingServiceTest.java`
- `LLMDescriptionServiceTest.java`
- `IncrementalUpdateServiceTest.java`

### Step 8.2: 集成测试

1. 启动后端服务
2. 测试全量生成流程
3. 修改代码并提交
4. 测试增量生成流程
5. 验证向量语义相似度

---

## 实现顺序

```
Phase 1 (基础设施) ──→ Phase 2 (Git服务) ──→ Phase 3 (向量服务)
                                                    │
                                                    ↓
Phase 4 (LLM描述) ──────────────────────────────────┘
                              │
                              ↓
                      Phase 5 (增量更新)
                              │
                              ↓
                      Phase 6 (API扩展)
                              │
                              ↓
                      Phase 7 (前端适配)
                              │
                              ↓
                      Phase 8 (测试验证)
```

---

## 文件清单

### 新增文件 (10 个)

| # | 文件路径 | 说明 |
|---|---------|------|
| 1 | `knowledgegraph/model/KgGenerationLog.java` | 生成日志实体 |
| 2 | `knowledgegraph/repository/KgGenerationLogRepository.java` | 日志仓库 |
| 3 | `knowledgegraph/model/GitStatus.java` | Git 状态 DTO |
| 4 | `knowledgegraph/service/GitStatusService.java` | Git 状态服务 |
| 5 | `knowledgegraph/service/LocalEmbeddingService.java` | 本地向量服务 |
| 6 | `knowledgegraph/service/LLMDescriptionService.java` | LLM 描述服务 |
| 7 | `knowledgegraph/service/IncrementalUpdateService.java` | 增量更新服务 |
| 8 | `knowledgegraph/model/IncrementalUpdateResult.java` | 更新结果 DTO |
| 9 | `knowledgegraph/model/IncrementalRequest.java` | 增量请求 DTO |
| 10 | `knowledgegraph/model/IncrementalResponse.java` | 增量响应 DTO |

### 修改文件 (5 个)

| # | 文件路径 | 修改内容 |
|---|---------|---------|
| 1 | `pom.xml` | 添加 DJL 依赖 |
| 2 | `knowledgegraph/model/MethodNode.java` | 新增 contentHash、description |
| 3 | `neo4j/service/EmbeddingService.java` | 改用本地模型 |
| 4 | `knowledgegraph/controller/KnowledgeGraphController.java` | 新增 API |
| 5 | `hisi-dev-tool-frontend/src/views/knowledge-graph/index.vue` | 增量按钮适配 |

---

## 验收检查点

### Phase 3 完成后
- [ ] 本地模型能正常加载
- [ ] 能生成 384 维向量
- [ ] 向量有语义意义（相似文本向量相似度高）

### Phase 5 完成后
- [ ] 能检测 Git 变更文件
- [ ] 能识别新增/修改/删除的方法
- [ ] 增量更新能正确执行

### Phase 8 完成后
- [ ] 全量生成流程正常
- [ ] 增量生成流程正常
- [ ] 前端按钮交互正确
- [ ] 所有单元测试通过
