# 知识图谱 Neo4j 存储与向量生成实施计划

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** 将知识图谱核心数据迁移到 Neo4j 存储，并实现向量生成功能

**Architecture:** 混合存储策略 - 知识图谱核心数据（方法、调用关系、入口点）存储到 Neo4j，任务状态继续使用 PostgreSQL

**Tech Stack:** Spring Boot 3.2 + Spring Data Neo4j + Neo4j 5.x + PostgreSQL

---

## 阶段 1: Neo4j 存储服务

### Task 1.1: 创建向量生成状态模型

**Files:**
- Create: `src/main/java/com/huawei/hisi/knowledgegraph/model/VectorGenerationTask.java`
- Create: `src/main/java/com/huawei/hisi/knowledgegraph/repository/VectorGenerationTaskRepository.java`

**Step 1: Write the failing test**

```java
// src/test/java/com/huawei/hisi/knowledgegraph/repository/VectorGenerationTaskRepositoryTest.java
@Test
void testSaveAndFindById() {
    VectorGenerationTask task = VectorGenerationTask.builder()
        .projectPath("test-project")
        .status("PENDING")
        .totalMethods(100)
        .processedMethods(0)
        .build();

    repository.save(task);

    Optional<VectorGenerationTask> found = repository.findById(task.getId());
    assertTrue(found.isPresent());
    assertEquals("PENDING", found.get().getStatus());
}
```

**Step 2: Run test to verify it fails**

Run: `mvn test -Dtest=VectorGenerationTaskRepositoryTest`
Expected: FAIL with "class not found"

**Step 3: Write minimal implementation**

```java
// VectorGenerationTask.java
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class VectorGenerationTask {
    private Long id;
    private String projectPath;
    private String status;  // PENDING, RUNNING, COMPLETED, FAILED
    private Integer totalMethods;
    private Integer processedMethods;
    private LocalDateTime startTime;
    private LocalDateTime endTime;
    private Long costTimeMs;
    private String errorMessage;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
```

**Step 4: Run test to verify it passes**

**Step 5: Commit**

```bash
git add src/main/java/com/huawei/hisi/knowledgegraph/model/VectorGenerationTask.java
git add src/main/java/com/huawei/hisi/knowledgegraph/repository/VectorGenerationTaskRepository.java
git commit -m "feat: add VectorGenerationTask model and repository"
```

---

### Task 1.2: 创建 Neo4j 存储服务接口

**Files:**
- Create: `src/main/java/com/huawei/hisi/knowledgegraph/service/storage/KnowledgeGraphStorageService.java`

**Step 1: Create interface**

```java
package com.huawei.hisi.knowledgegraph.service.storage;

public interface KnowledgeGraphStorageService {
    // 方法节点操作
    void saveMethodNode(com.huawei.hisi.knowledgegraph.model.MethodNode node);
    void saveMethodNodes(List<com.huawei.hisi.knowledgegraph.model.MethodNode> nodes);

    // 调用关系操作
    void saveCallRelation(com.huawei.hisi.knowledgegraph.model.CallRelation relation);
    void saveCallRelations(List<com.huawei.hisi.knowledgegraph.model.CallRelation> relations);

    // 入口点操作
    void saveEntryPoint(com.huawei.hisi.knowledgegraph.model.EntryPoint entry);
    void saveEntryPoints(List<com.huawei.hisi.knowledgegraph.model.EntryPoint> entries);

    // 接口实现操作
    void saveInterfaceImplementation(com.huawei.hisi.knowledgegraph.model.InterfaceImplementation impl);

    // 清理操作
    void cleanProjectData(String projectPath);

    // 统计操作
    int countMethodNodes(String projectPath);
    int countCallRelations(String projectPath);
    int countEntryPoints(String projectPath);
}
```

**Step 2: Commit**

---

### Task 1.3: 实现 PostgreSQL 存储服务

**Files:**
- Create: `src/main/java/com/huawei/hisi/knowledgegraph/service/storage/PostgresStorageService.java`

**Step 1: Implement PostgreSQL storage**

```java
@Service
@Primary
@ConditionalOnProperty(name = "knowledge-graph.storage", havingValue = "postgres", matchIfMissing = true)
public class PostgresStorageService implements KnowledgeGraphStorageService {

    private final MethodNodeRepository methodNodeRepository;
    private final CallRelationRepository callRelationRepository;
    private final EntryPointRepository entryPointRepository;
    private final InterfaceImplementationRepository interfaceImplRepository;

    // 委托给现有 Repository 实现
    @Override
    public void saveMethodNode(MethodNode node) {
        methodNodeRepository.save(node);
    }

    // ... 其他方法实现
}
```

**Step 2: Run tests**

**Step 3: Commit**

---

### Task 1.4: 实现 Neo4j 存储服务

**Files:**
- Create: `src/main/java/com/huawei/hisi/knowledgegraph/service/storage/Neo4jStorageService.java`

**Step 1: Implement Neo4j storage**

```java
@Service
@ConditionalOnProperty(name = "knowledge-graph.storage", havingValue = "neo4j")
public class Neo4jStorageService implements KnowledgeGraphStorageService {

    private final Neo4jMethodNodeRepository methodNodeRepository;
    private final Neo4jEntryPointNodeRepository entryPointRepository;

    @Override
    public void saveMethodNode(MethodNode node) {
        // 转换为 Neo4j 模型
        com.huawei.hisi.neo4j.model.MethodNode neo4jNode = convertToNeo4j(node);
        methodNodeRepository.save(neo4jNode);
    }

    private com.huawei.hisi.neo4j.model.MethodNode convertToNeo4j(MethodNode pgNode) {
        return com.huawei.hisi.neo4j.model.MethodNode.builder()
            .nodeId(pgNode.getNodeId())
            .className(pgNode.getClassName())
            .methodName(pgNode.getMethodName())
            .signature(pgNode.getSignature())
            .filePath(pgNode.getFilePath())
            .startLine(pgNode.getStartLine())
            .endLine(pgNode.getEndLine())
            .complexity(pgNode.getComplexity())
            .projectPath(pgNode.getProjectPath())
            .build();
    }
}
```

**Step 2: Run tests**

**Step 3: Commit**

---

## 阶段 2: 向量生成服务

### Task 2.1: 改造 EmbeddingService 调用 LLM API

**Files:**
- Modify: `src/main/java/com/huawei/hisi/neo4j/service/EmbeddingService.java`

**Step 1: Add LLM client**

```java
@Service
public class EmbeddingService {

    @Value("${llm.base-url}")
    private String llmBaseUrl;

    @Value("${llm.api-key}")
    private String apiKey;

    private final RestTemplate restTemplate;

    public float[] generateEmbedding(String text) {
        // 调用 LLM embedding API
        String url = llmBaseUrl.replace("/chat/completions", "/embeddings");

        HttpHeaders headers = new HttpHeaders();
        headers.set("Authorization", "Bearer " + apiKey);
        headers.setContentType(MediaType.APPLICATION_JSON);

        Map<String, Object> body = Map.of(
            "input", text,
            "model", "text-embedding-ada-002"
        );

        HttpEntity<Map<String, Object>> request = new HttpEntity<>(body, headers);
        ResponseEntity<Map> response = restTemplate.postForEntity(url, request, Map.class);

        // 解析响应获取向量
        List<Double> embedding = (List<Double>)
            ((Map)((List)response.getBody().get("data")).get(0)).get("embedding");

        float[] result = new float[embedding.size()];
        for (int i = 0; i < embedding.size(); i++) {
            result[i] = embedding.get(i).floatValue();
        }
        return result;
    }
}
```

**Step 2: Run tests**

**Step 3: Commit**

---

### Task 2.2: 创建向量生成协调服务

**Files:**
- Create: `src/main/java/com/huawei/hisi/knowledgegraph/service/VectorGenerationService.java`

**Step 1: Create async service**

```java
@Service
@Slf4j
@RequiredArgsConstructor
public class VectorGenerationService {

    private final MethodNodeRepository methodNodeRepository;
    private final Neo4jMethodNodeRepository neo4jMethodNodeRepository;
    private final VectorGenerationTaskRepository taskRepository;
    private final EmbeddingService embeddingService;
    private final ProxyVectorService proxyVectorService;

    @Async
    public void generateVectorsAsync(String projectPath) {
        long startTime = System.currentTimeMillis();

        // 1. 创建任务
        VectorGenerationTask task = createTask(projectPath);

        try {
            // 2. 获取所有方法节点
            List<MethodNode> methods = methodNodeRepository.findByProjectPath(projectPath);
            task.setTotalMethods(methods.size());
            task.setStatus("RUNNING");
            taskRepository.save(task);

            log.info("开始向量生成: projectPath={}, totalMethods={}", projectPath, methods.size());

            // 3. 批量生成向量
            int processed = 0;
            for (MethodNode method : methods) {
                try {
                    // 生成代理向量输入文本
                    String inputText = proxyVectorService.generateProxyVectorInput(convertToNeo4jModel(method));

                    // 调用 LLM 生成向量
                    float[] embedding = embeddingService.generateEmbedding(inputText);

                    // 更新 Neo4j 节点
                    updateNeo4jEmbedding(method.getNodeId(), embedding);

                    processed++;
                    task.setProcessedMethods(processed);

                    // 每 100 个打印日志
                    if (processed % 100 == 0) {
                        long elapsed = System.currentTimeMillis() - startTime;
                        log.info("向量生成进度: {}/{}, 耗时: {}ms, 平均: {}ms/个",
                            processed, methods.size(), elapsed, elapsed / processed);
                    }
                } catch (Exception e) {
                    log.warn("向量生成失败: nodeId={}, error={}", method.getNodeId(), e.getMessage());
                }
            }

            // 4. 完成任务
            task.setStatus("COMPLETED");
            task.setEndTime(LocalDateTime.now());
            task.setCostTimeMs(System.currentTimeMillis() - startTime);
            taskRepository.save(task);

            log.info("向量生成完成: projectPath={}, totalMethods={}, costTimeMs={}",
                projectPath, methods.size(), task.getCostTimeMs());

        } catch (Exception e) {
            task.setStatus("FAILED");
            task.setErrorMessage(e.getMessage());
            taskRepository.save(task);
            log.error("向量生成失败: projectPath={}", projectPath, e);
        }
    }
}
```

**Step 2: Run tests**

**Step 3: Commit**

---

### Task 2.3: 集成到知识图谱生成流程

**Files:**
- Modify: `src/main/java/com/huawei/hisi/knowledgegraph/service/KnowledgeGraphBuilder.java`

**Step 1: Add vector generation trigger**

```java
@Service
@RequiredArgsConstructor
public class KnowledgeGraphBuilder {

    private final VectorGenerationService vectorGenerationService;

    public Map<String, Object> buildKnowledgeGraph(String projectPath) {
        // ... 现有知识图谱生成逻辑 ...

        // 知识图谱生成完成后，异步启动向量生成
        vectorGenerationService.generateVectorsAsync(projectPath);

        return result;
    }
}
```

**Step 2: Run tests**

**Step 3: Commit**

---

## 阶段 3: 前端状态展示

### Task 3.1: 创建向量生成状态 API

**Files:**
- Create: `src/main/java/com/huawei/hisi/knowledgegraph/controller/VectorGenerationController.java`

**Step 1: Create controller**

```java
@RestController
@RequestMapping("/api/vector-generation")
@RequiredArgsConstructor
public class VectorGenerationController {

    private final VectorGenerationTaskRepository taskRepository;

    @GetMapping("/status")
    public ApiResponse<VectorGenerationTask> getStatus(@RequestParam String projectPath) {
        VectorGenerationTask task = taskRepository.findLatestByProjectPath(projectPath);
        return ApiResponse.success(task);
    }
}
```

**Step 2: Run tests**

**Step 3: Commit**

---

### Task 3.2: 前端状态灯组件

**Files:**
- Modify: `hisi-dev-tool-frontend/src/views/project/ProjectList.vue`

**Step 1: Add status indicator**

```vue
<template>
  <el-table-column label="向量状态" width="120">
    <template #default="{ row }">
      <el-tag :type="getVectorStatusType(row.vectorStatus)">
        {{ getVectorStatusText(row.vectorStatus) }}
      </el-tag>
    </template>
  </el-table-column>
</template>

<script setup>
const getVectorStatusType = (status) => {
  switch (status) {
    case 'COMPLETED': return 'success'
    case 'RUNNING': return 'warning'
    case 'FAILED': return 'danger'
    default: return 'info'
  }
}

const getVectorStatusText = (status) => {
  switch (status) {
    case 'COMPLETED': return '已完成'
    case 'RUNNING': return '生成中'
    case 'FAILED': return '失败'
    default: return '未生成'
  }
}
</script>
```

**Step 2: Test in browser**

**Step 3: Commit**

---

## 验证清单

### 后端测试
```bash
# 运行所有测试
mvn test

# 启动应用
mvn spring-boot:run

# 测试向量生成 API
curl "http://localhost:8080/api/vector-generation/status?projectPath=test"
```

### 前端测试
```bash
cd hisi-dev-tool-frontend
npm run dev
```

### 集成测试
1. 创建知识图谱生成任务
2. 观察向量生成任务自动启动
3. 检查状态灯显示正确

---

## 文件修改总结

### 新增文件 (7个)
| 文件 | 说明 |
|------|------|
| `model/VectorGenerationTask.java` | 向量生成任务模型 |
| `repository/VectorGenerationTaskRepository.java` | 任务状态 Repository |
| `service/storage/KnowledgeGraphStorageService.java` | 存储服务接口 |
| `service/storage/PostgresStorageService.java` | PostgreSQL 实现 |
| `service/storage/Neo4jStorageService.java` | Neo4j 实现 |
| `service/VectorGenerationService.java` | 向量生成协调服务 |
| `controller/VectorGenerationController.java` | 状态查询 API |

### 修改文件 (3个)
| 文件 | 修改内容 |
|------|---------|
| `service/EmbeddingService.java` | 改造为调用 LLM API |
| `service/KnowledgeGraphBuilder.java` | 集成向量生成触发 |
| `views/project/ProjectList.vue` | 新增状态灯展示 |

---

## 注意事项

1. **LLM API 配置**: 确保 `llm.base-url` 和 `llm.api-key` 配置正确
2. **异步配置**: 确保主应用类有 `@EnableAsync` 注解
3. **Neo4j 连接**: 确保 Neo4j 服务运行正常
4. **性能监控**: 查看日志中的向量生成性能数据
