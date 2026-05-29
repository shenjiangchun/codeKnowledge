# 知识图谱增量生成与 LLM 自然语言描述设计

## 概述

本设计文档描述知识图谱模块的三项核心增强功能：
1. **LLM 自然语言描述生成** - 使用 LLM 生成方法功能的自然语言描述
2. **增量知识图谱生成** - 基于 Git Diff 检测变更，实现增量更新
3. **本地向量生成** - 部署本地 embedding 模型替代伪随机向量

---

## 1. 当前实现分析

### 1.1 文本生成流程

```
方法节点 → ProxyVectorService.generateProxyVectorInput() → 拼接文本 → EmbeddingService
```

### 1.2 当前文本格式

```
{简化类名} {方法名} {简化签名} {注释摘要} {服务名}
```

示例：
```
CallChainController listProjects (String projectPath, int page, int size) 获取Projects
UserServiceImpl validateUser (String username, String password) 校验User
```

### 1.3 注释来源优先级

| 优先级 | 来源 | 示例 |
|--------|------|------|
| 1 | Javadoc 注释 | `/** 获取项目列表 */` → "获取项目列表" |
| 2 | 行注释 | `// 获取项目列表` → "获取项目列表" |
| 3 | 方法名推断 | `getUserName` → "获取UserName" |

### 1.4 当前问题

1. **缺乏语义连贯性**：拼接的文本不是自然语句
2. **方法名推断太简单**：只是模板替换，未转中文
3. **向量无语义意义**：使用伪随机向量，无实际语义

---

## 2. 设计方案

### 2.1 整体架构

```
┌─────────────────────────────────────────────────────────────────┐
│                        知识图谱生成流程                           │
├─────────────────────────────────────────────────────────────────┤
│                                                                 │
│  用户点击生成 → 检查 Git 状态 → 选择模式（全量/增量）              │
│       │                                                         │
│       ▼                                                         │
│  ┌─────────────────────────────────────────────────────────┐   │
│  │ 全量生成：解析所有文件 → 提取方法 → LLM生成描述 → 向量化  │   │
│  │ 增量生成：Git Diff → 解析变更文件 → 增量更新节点         │   │
│  └─────────────────────────────────────────────────────────┘   │
│       │                                                         │
│       ▼                                                         │
│  存储：Neo4j (图数据) + PostgreSQL (生成日志 + Git状态)          │
│                                                                 │
└─────────────────────────────────────────────────────────────────┘
```

### 2.2 方法唯一标识

```java
唯一键 = className + methodName + signature + returnType
```

示例：`com.example.UserService#getUser(String, int):User`

### 2.3 增量更新流程

```
1. 检查工作区是否干净 → 不干净则提示用户提交代码
2. 获取当前 commitHash
3. 读取上次生成记录的 commitHash
4. git diff <oldCommit>..<newCommit> --name-only 获取变更文件
5. 解析变更文件：
   - 新增方法 → 创建节点 + LLM描述 + 向量化
   - 修改方法 → 更新节点 + 重新生成描述和向量
   - 删除方法 → 删除节点及其关系
6. 更新生成日志中的 commitHash
```

### 2.4 强制提交策略

每次生成知识图谱前，强制检查是否有未提交的修改：
- 有未提交修改 → 弹窗提示用户先提交代码
- 工作区干净 → 允许生成，记录 commitHash

这样保证每次生成都对应一个确定的 Git 状态。

### 2.5 LLM 自然语言描述生成

```java
// Prompt 模板
String prompt = """
    请用一句话描述以下Java方法的功能（50字以内）：
    类名：%s
    方法名：%s
    签名：%s
    注释：%s
    """.formatted(className, methodName, signature, comment);

// 调用 LLM 生成描述
String description = llmService.generate(prompt);
```

示例输出：
```
输入：UserService, validateUser, (String username, String password), 校验User
输出：验证用户登录信息的方法，接收用户名和密码参数进行校验
```

### 2.6 本地向量生成

```
模型：sentence-transformers/paraphrase-multilingual-MiniLM-L12-v2
维度：384
部署方式：Java 集成 (DJL 或 ONNX Runtime)
支持：中英文，CPU 友好
```

---

## 3. 前端适配

### 3.1 生成模式选择

```
- 无历史记录 → 只显示"全量生成"按钮
- 有历史记录 → 显示"全量生成" + "增量生成（基于 commit abc123）"
- 检测到未提交修改 → 弹窗提示"请先提交代码后再生成知识图谱"
- 无代码变更 → 提示"无变更，无需更新"
```

### 3.2 UI 状态

```vue
<template>
  <!-- 生成按钮区域 -->
  <div class="generate-actions">
    <el-button @click="handleFullGenerate">全量生成</el-button>
    <el-button
      v-if="hasHistory"
      @click="handleIncrementalGenerate"
      :disabled="!canIncremental"
    >
      增量生成（基于 {{ lastCommitHash }}）
    </el-button>
  </div>

  <!-- 提示信息 -->
  <el-alert v-if="hasUncommittedChanges" type="warning">
    检测到未提交的代码修改，请先提交后再生成知识图谱
  </el-alert>
</template>
```

---

## 4. 数据模型变更

### 4.1 MethodNode 新增字段

```java
@Node
public class MethodNode {
    // 现有字段...

    /**
     * 内容哈希，用于快速判断方法内容是否变化
     */
    private String contentHash;

    /**
     * LLM 生成的自然语言描述
     */
    private String description;
}
```

### 4.2 新增生成日志表

```sql
CREATE TABLE kg_generation_log (
    id BIGSERIAL PRIMARY KEY,
    project_path VARCHAR(500) NOT NULL,
    commit_hash VARCHAR(40) NOT NULL,
    branch VARCHAR(100),
    generation_mode VARCHAR(20) NOT NULL,  -- FULL / INCREMENTAL
    total_methods INT DEFAULT 0,
    new_methods INT DEFAULT 0,
    updated_methods INT DEFAULT 0,
    deleted_methods INT DEFAULT 0,
    start_time TIMESTAMP NOT NULL,
    end_time TIMESTAMP,
    cost_time_ms BIGINT,
    error_message TEXT,

    CONSTRAINT uk_project_commit UNIQUE (project_path, commit_hash)
);

CREATE INDEX idx_kg_log_project ON kg_generation_log(project_path);
CREATE INDEX idx_kg_log_time ON kg_generation_log(start_time DESC);
```

---

## 5. 后端服务设计

### 5.1 新增服务

#### GitStatusService
```java
@Service
public class GitStatusService {

    /**
     * 检查工作区是否干净
     */
    public boolean isWorkingDirectoryClean(Path projectPath);

    /**
     * 获取当前 commitHash
     */
    public String getCurrentCommitHash(Path projectPath);

    /**
     * 获取两次提交之间的变更文件列表
     */
    public List<String> getChangedFiles(Path projectPath, String fromCommit, String toCommit);
}
```

#### LLMDescriptionService
```java
@Service
public class LLMDescriptionService {

    /**
     * 为方法生成自然语言描述
     */
    public String generateDescription(MethodNode node);

    /**
     * 批量生成描述
     */
    public Map<String, String> batchGenerateDescriptions(List<MethodNode> nodes);
}
```

#### IncrementalUpdateService
```java
@Service
public class IncrementalUpdateService {

    /**
     * 执行增量更新
     */
    public IncrementalUpdateResult incrementalUpdate(
        Path projectPath,
        String fromCommit,
        String toCommit
    );
}
```

### 5.2 API 端点

#### KnowledgeGraphController 新增接口

```java
/**
 * 检查 Git 状态
 */
GET /api/knowledge-graph/git-status?projectPath={path}

Response:
{
    "isClean": true,
    "commitHash": "abc123",
    "branch": "main",
    "hasHistory": true,
    "lastGeneratedCommit": "def456"
}

/**
 * 增量生成知识图谱
 */
POST /api/knowledge-graph/incremental
Request:
{
    "projectPath": "/path/to/project"
}

Response:
{
    "success": true,
    "newMethods": 5,
    "updatedMethods": 3,
    "deletedMethods": 2,
    "costTimeMs": 15000
}
```

---

## 6. 本地向量生成集成

### 6.1 技术选型

使用 **DJL (Deep Java Library)** 集成 sentence-transformers 模型：

```xml
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

### 6.2 LocalEmbeddingService

```java
@Service
public class LocalEmbeddingService {

    private static final int EMBEDDING_DIMENSION = 384;
    private Predictor<String, float[]> predictor;

    @PostConstruct
    public void init() {
        // 加载 ONNX 模型
        Criteria<String, float[]> criteria = Criteria.builder()
            .setTypes(String.class, float[].class)
            .optModelUrls("djl://ai.djl.huggingface.pytorch/sentence-transformers/paraphrase-multilingual-MiniLM-L12-v2")
            .optEngine("OnnxRuntime")
            .build();

        predictor = criteria.loadModel().newPredictor();
    }

    public float[] generateEmbedding(String text) {
        return predictor.predict(text);
    }
}
```

### 6.3 EmbeddingService 修改

```java
@Service
public class EmbeddingService {

    private final LocalEmbeddingService localEmbeddingService;

    // 优先使用本地模型，移除 LLM API 调用
    public float[] generateEmbedding(String text) {
        if (text == null || text.isEmpty()) {
            return createZeroVector();
        }
        return localEmbeddingService.generateEmbedding(text);
    }
}
```

---

## 7. 验收标准

### 7.1 功能验收

- [ ] 全量生成能正确生成所有方法节点
- [ ] 增量生成能正确检测变更文件
- [ ] 新增方法正确创建节点
- [ ] 修改方法正确更新描述和向量
- [ ] 删除方法正确移除节点
- [ ] 有未提交修改时正确提示用户
- [ ] 无变更时正确提示无需更新
- [ ] LLM 描述生成正确（语义连贯、50字以内）
- [ ] 本地向量生成正常工作

### 7.2 性能验收

- [ ] 本地 embedding 生成 < 50ms/方法
- [ ] 增量更新 100 个方法 < 30 秒
- [ ] 前端响应时间 < 200ms

---

## 8. 文件变更清单

### 8.1 后端新增文件

| 文件 | 说明 |
|------|------|
| `service/GitStatusService.java` | Git 状态检查服务 |
| `service/LLMDescriptionService.java` | LLM 描述生成服务 |
| `service/IncrementalUpdateService.java` | 增量更新服务 |
| `service/LocalEmbeddingService.java` | 本地向量生成服务 |
| `model/KgGenerationLog.java` | 生成日志实体 |
| `repository/KgGenerationLogRepository.java` | 生成日志仓库 |

### 8.2 后端修改文件

| 文件 | 修改内容 |
|------|---------|
| `model/MethodNode.java` | 新增 contentHash、description 字段 |
| `service/KnowledgeGraphBuilder.java` | 集成 LLM 描述生成 |
| `service/EmbeddingService.java` | 改用本地模型 |
| `controller/KnowledgeGraphController.java` | 新增增量生成 API |

### 8.3 前端修改文件

| 文件 | 修改内容 |
|------|---------|
| `views/knowledge-graph/index.vue` | 增量/全量生成按钮适配 |
| `api/knowledgeGraph.ts` | 新增 API 接口 |

---

## 9. 风险与缓解

| 风险 | 影响 | 缓解措施 |
|------|------|---------|
| LLM API 超时 | 描述生成失败 | 增加重试机制，失败时回退到简单拼接 |
| 本地模型内存占用 | 内存不足 | 支持按需加载，空闲时卸载 |
| Git 命令执行失败 | 增量检测失败 | 提供详细错误信息，支持手动指定范围 |
| 并发生成冲突 | 数据不一致 | 使用分布式锁 |

---

## 10. 后续优化

1. **批量 LLM 调用**：将多个方法合并成一个请求，减少 API 调用次数
2. **描述缓存**：相同签名的方法复用描述
3. **增量进度展示**：前端实时显示增量更新进度
4. **回滚机制**：支持回滚到历史生成版本
