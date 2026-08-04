# 语义搜索增强实现计划

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** 实现本地 ONNX 模型推理生成高质量方法描述，修复 MCP 接口，前端集成语义搜索功能。

**Architecture:**
- 后端：使用 ONNX Runtime 加载 Phi-3-mini 模型，实现 `generateWithModel()` 推理逻辑
- MCP：修复 vector_search 接口定义，新增 hybrid_search 工具
- 前端：在知识图谱页面添加语义搜索 Tab，展示方法 description

**Tech Stack:** Spring Boot, ONNX Runtime, TypeScript, Vue 3, Element Plus

---

## Task 1: 添加 ONNX Runtime 依赖

**Files:**
- Modify: `hisi-dev-tool/pom.xml`

**Step 1: 添加 ONNX Runtime 依赖**

在 `pom.xml` 的 `<dependencies>` 中添加：

```xml
<!-- ONNX Runtime for local model inference -->
<dependency>
    <groupId>com.microsoft.onnxruntime</groupId>
    <artifactId>onnxruntime</artifactId>
    <version>1.18.0</version>
</dependency>
```

**Step 2: 验证依赖添加**

Run: `cd hisi-dev-tool && mvn dependency:resolve -DincludeArtifactIds=onnxruntime`
Expected: `onnxruntime:jar:1.18.0` resolved successfully

**Step 3: Commit**

```bash
git add hisi-dev-tool/pom.xml
git commit -m "build: add ONNX Runtime dependency for local model inference"
```

---

## Task 2: 实现 ONNX 模型加载

**Files:**
- Modify: `hisi-dev-tool/src/main/java/com/huawei/hisi/neo4j/service/LocalTextGenerationService.java`

**Step 1: 添加必要的 import 语句**

在文件顶部添加：

```java
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtSession;
import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtSession.Result;
```

**Step 2: 添加模型相关字段**

在类中添加字段（在 `private boolean modelLoaded = false;` 之后）：

```java
private OrtEnvironment ortEnv;
private OrtSession session;
```

**Step 3: 实现 init() 中的模型加载**

修改 `init()` 方法中的模型加载部分：

```java
try {
    // 1. 初始化 ONNX Runtime 环境
    ortEnv = OrtEnvironment.getEnvironment();

    // 2. 加载 ONNX 模型
    session = ortEnv.createSession(modelPath);

    // TODO: 加载 tokenizer (后续任务实现)
    // tokenizer = loadTokenizer(config.getTokenizerPath());

    modelLoaded = true;
    log.info("文本生成模型加载成功: {}", modelPath);
} catch (Exception e) {
    log.warn("文本生成模型加载失败: {}. 将使用基于方法名的默认描述.", e.getMessage());
    modelLoaded = false;
}
```

**Step 4: 实现 destroy() 资源释放**

修改 `destroy()` 方法：

```java
@PreDestroy
public void destroy() {
    try {
        if (session != null) {
            session.close();
            log.info("ONNX session closed");
        }
    } catch (Exception e) {
        log.warn("Error closing ONNX session: {}", e.getMessage());
    }
}
```

**Step 5: 验证编译**

Run: `cd hisi-dev-tool && mvn compile -DskipTests`
Expected: BUILD SUCCESS

**Step 6: Commit**

```bash
git add hisi-dev-tool/src/main/java/com/huawei/hisi/neo4j/service/LocalTextGenerationService.java
git commit -m "feat(local-llm): implement ONNX model loading and resource cleanup"
```

---

## Task 3: 实现 ONNX 推理逻辑

**Files:**
- Modify: `hisi-dev-tool/src/main/java/com/huawei/hisi/neo4j/service/LocalTextGenerationService.java`

**Step 1: 实现简单的 tokenizer (基于字符串分割)**

添加辅助方法：

```java
/**
 * 简单的 tokenizer，将文本转换为 token IDs
 * 注意：这是一个简化实现，生产环境应使用专业的 tokenizer
 */
private long[] simpleTokenize(String text) {
    // 使用字符级别的简单 tokenize
    // 实际应该使用 HuggingFace tokenizer
    long[] tokens = new long[Math.min(text.length(), 512)];
    for (int i = 0; i < tokens.length && i < text.length(); i++) {
        tokens[i] = text.charAt(i);
    }
    return tokens;
}

/**
 * 解码 token IDs 为文本
 */
private String simpleDecode(long[] tokenIds) {
    StringBuilder sb = new StringBuilder();
    for (long id : tokenIds) {
        if (id > 0 && id < Character.MAX_VALUE) {
            sb.append((char) id);
        }
    }
    return sb.toString();
}
```

**Step 2: 实现 generateWithModel 推理逻辑**

修改 `generateWithModel()` 方法：

```java
private String generateWithModel(String className, String methodName, String signature, String comment) {
    if (session == null || ortEnv == null) {
        return generateDefaultDescription(className, methodName, signature, comment);
    }

    try {
        // 1. 构建 prompt
        String prompt = buildPhi3Prompt(className, methodName, signature, comment);

        // 2. Tokenize (简化实现)
        long[] inputIds = simpleTokenize(prompt);

        // 3. 创建输入张量
        long[][] inputArray = new long[1][inputIds.length];
        inputArray[0] = inputIds;
        OnnxTensor inputTensor = OnnxTensor.createTensor(ortEnv, inputArray);

        // 4. 执行推理
        Map<String, OnnxTensor> inputs = new HashMap<>();
        inputs.put("input_ids", inputTensor);

        Result result = session.run(inputs);

        // 5. 解码输出
        long[][] outputIds = (long[][]) result.get(0).getValue();
        String generated = simpleDecode(outputIds[0]);

        // 6. 提取描述（去掉 prompt 部分，限制长度）
        String description = extractDescription(generated, prompt);

        log.debug("ONNX generated description: {} -> {}", methodName, description);
        return description;

    } catch (Exception e) {
        log.warn("ONNX inference failed: {}", e.getMessage());
        return generateDefaultDescription(className, methodName, signature, comment);
    }
}

/**
 * 从生成结果中提取描述
 */
private String extractDescription(String generated, String prompt) {
    // 去掉 prompt 部分
    String description = generated;
    if (description.startsWith(prompt)) {
        description = description.substring(prompt.length());
    }

    // 清理特殊 token
    description = description.replace("<|end|>", "")
                             .replace("<|assistant|>", "")
                             .replace("<|user|>", "")
                             .trim();

    // 限制长度
    return truncateDescription(description);
}

/**
 * 构建 Phi-3 格式的 prompt
 */
private String buildPhi3Prompt(String className, String methodName, String signature, String comment) {
    String commentStr = (comment == null || comment.isEmpty()) ? "无" : comment;
    return "<|user|>\n" +
           "请用一句简洁的中文描述以下Java方法的功能（30字以内）：\n" +
           "类名：" + className + "\n" +
           "方法名：" + methodName + "\n" +
           "签名：" + signature + "\n" +
           "注释：" + commentStr + "\n" +
           "<|end|>\n<|assistant|>\n";
}
```

**Step 3: 添加 HashMap import**

确保 import 中有：
```java
import java.util.HashMap;
import java.util.Map;
```

**Step 4: 验证编译**

Run: `cd hisi-dev-tool && mvn compile -DskipTests`
Expected: BUILD SUCCESS

**Step 5: Commit**

```bash
git add hisi-dev-tool/src/main/java/com/huawei/hisi/neo4j/service/LocalTextGenerationService.java
git commit -m "feat(local-llm): implement ONNX inference for text generation"
```

---

## Task 4: 修复 MCP vector_search 接口

**Files:**
- Modify: `hisi-mcp-server/src/tools/vectorTools.ts`

**Step 1: 读取当前文件内容**

检查 vectorTools.ts 的当前实现。

**Step 2: 修复接口路径和参数**

修改 `vector_search` 工具定义：

```typescript
// 修复前：
// - 路径: /api/vector/search
// - 参数: topK, threshold
// - projectPath 可选

// 修复后：
{
  name: 'vector_search',
  description: '向量相似度搜索，使用自然语言查询代码方法',
  inputSchema: {
    type: 'object',
    properties: {
      query: {
        type: 'string',
        description: '自然语言查询，如"错误诊断"、"数据库查询"'
      },
      projectPath: {
        type: 'string',
        description: '项目路径（必填）'
      },
      limit: {
        type: 'number',
        description: '返回结果数量，默认10'
      },
      graphDepth: {
        type: 'number',
        description: '图遍历深度，默认2'
      }
    },
    required: ['query', 'projectPath']
  }
}
```

**Step 3: 修复 API 调用路径**

修改调用路径为 `/api/vector-search`：

```typescript
// 调用时使用正确的路径
const response = await this.client.post('/api/vector-search', {
  query: params.query,
  projectPath: params.projectPath,
  ...(params.limit && { limit: params.limit }),
  ...(params.graphDepth && { graphDepth: params.graphDepth })
});
```

**Step 4: 验证 TypeScript 编译**

Run: `cd hisi-mcp-server && npm run build`
Expected: No errors

**Step 5: Commit**

```bash
git add hisi-mcp-server/src/tools/vectorTools.ts
git commit -m "fix(mcp): correct vector_search API path and parameters"
```

---

## Task 5: 新增 MCP hybrid_search 工具

**Files:**
- Modify: `hisi-mcp-server/src/tools/vectorTools.ts`

**Step 1: 添加 hybrid_search 工具定义**

在 vectorToolDefinitions 中添加：

```typescript
{
  name: 'hybrid_search',
  description: '三层混合检索：关键词过滤 + 向量匹配 + 图遍历扩展。' +
               '相比纯向量搜索，返回更完整的上下文（包括调用者和被调用者）',
  inputSchema: {
    type: 'object',
    properties: {
      query: {
        type: 'string',
        description: '自然语言查询'
      },
      projectPath: {
        type: 'string',
        description: '项目路径（必填）'
      },
      limit: {
        type: 'number',
        description: '返回结果数量，默认10'
      },
      graphDepth: {
        type: 'number',
        description: '图遍历深度，默认2。设置为0则不进行图遍历'
      }
    },
    required: ['query', 'projectPath']
  }
}
```

**Step 2: 实现 hybrid_search 处理函数**

```typescript
async function handleHybridSearch(params: HybridSearchParams) {
  const response = await client.post('/api/vector-search', {
    query: params.query,
    projectPath: params.projectPath,
    limit: params.limit ?? 10,
    graphDepth: params.graphDepth ?? 2
  });
  return response;
}
```

**Step 3: 验证 TypeScript 编译**

Run: `cd hisi-mcp-server && npm run build`
Expected: No errors

**Step 4: Commit**

```bash
git add hisi-mcp-server/src/tools/vectorTools.ts
git commit -m "feat(mcp): add hybrid_search tool for three-layer search"
```

---

## Task 6: 创建前端向量搜索 API

**Files:**
- Create: `frontend/src/api/vectorSearch.ts`

**Step 1: 创建 API 文件**

```typescript
import request from '@/utils/request'

export interface VectorSearchRequest {
  query: string
  projectPath: string
  limit?: number
  graphDepth?: number
}

export interface VectorSearchResult {
  nodeId: string
  className: string
  methodName: string
  signature: string
  description: string
  filePath: string
  startLine: number
  endLine: number
  complexity: number
  methodBody?: string
  callers?: any[]
  callees?: any[]
}

export interface VectorSearchResponse {
  query: string
  intent: {
    entity: string | null
    methodType: string | null
    serviceName: string | null
    keywords: string[]
  }
  results: VectorSearchResult[]
  totalCount: number
  costTimeMs: number
}

export const vectorSearchApi = {
  search(params: VectorSearchRequest): Promise<VectorSearchResponse> {
    return request.post('/vector-search', params)
  }
}
```

**Step 2: 验证 TypeScript 编译**

Run: `cd frontend && npm run build`
Expected: No errors

**Step 3: Commit**

```bash
git add frontend/src/api/vectorSearch.ts
git commit -m "feat(frontend): add vector search API module"
```

---

## Task 7: 创建语义搜索面板组件

**Files:**
- Create: `frontend/src/views/knowledge-graph/components/SemanticSearchPanel.vue`

**Step 1: 创建组件文件**

```vue
<template>
  <div class="semantic-search-panel">
    <!-- 搜索输入 -->
    <div class="search-input-section">
      <el-input
        v-model="searchQuery"
        placeholder="输入自然语言描述搜索方法，如：错误诊断、数据库查询"
        size="large"
        clearable
        @keyup.enter="handleSearch"
      >
        <template #append>
          <el-button :icon="Search" @click="handleSearch" :loading="loading">
            搜索
          </el-button>
        </template>
      </el-input>
    </div>

    <!-- 搜索结果 -->
    <div class="search-results" v-if="results.length > 0">
      <el-card v-for="result in results" :key="result.nodeId" class="result-card">
        <template #header>
          <div class="result-header">
            <span class="method-name">{{ result.methodName }}</span>
            <el-tag size="small">{{ result.className.split('.').pop() }}</el-tag>
          </div>
        </template>

        <div class="result-content">
          <!-- 功能描述 -->
          <div class="description-section">
            <span class="label">功能描述：</span>
            <span class="description">{{ result.description }}</span>
          </div>

          <!-- 签名 -->
          <div class="signature-section">
            <span class="label">签名：</span>
            <code>{{ result.signature }}</code>
          </div>

          <!-- 操作按钮 -->
          <div class="actions">
            <el-button size="small" @click="viewDetail(result)">
              查看详情
            </el-button>
            <el-button size="small" @click="viewCallChain(result)">
              调用链
            </el-button>
          </div>
        </div>
      </el-card>
    </div>

    <!-- 空状态 -->
    <el-empty v-if="!loading && searched && results.length === 0" description="未找到相关方法" />

    <!-- 耗时提示 -->
    <div class="search-info" v-if="costTimeMs">
      <el-text type="info">
        搜索耗时 {{ costTimeMs }}ms，共找到 {{ totalCount }} 个结果
      </el-text>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref } from 'vue'
import { Search } from '@element-plus/icons-vue'
import { vectorSearchApi, type VectorSearchResult } from '@/api/vectorSearch'

const props = defineProps<{
  projectPath: string
}>()

const emit = defineEmits<{
  (e: 'viewDetail', result: VectorSearchResult): void
  (e: 'viewCallChain', result: VectorSearchResult): void
}>()

const searchQuery = ref('')
const results = ref<VectorSearchResult[]>([])
const loading = ref(false)
const searched = ref(false)
const totalCount = ref(0)
const costTimeMs = ref(0)

async function handleSearch() {
  if (!searchQuery.value.trim()) return

  loading.value = true
  searched.value = true
  try {
    const response = await vectorSearchApi.search({
      query: searchQuery.value,
      projectPath: props.projectPath,
      limit: 10
    })
    results.value = response.results
    totalCount.value = response.totalCount
    costTimeMs.value = response.costTimeMs
  } catch (error) {
    console.error('Semantic search failed:', error)
  } finally {
    loading.value = false
  }
}

function viewDetail(result: VectorSearchResult) {
  emit('viewDetail', result)
}

function viewCallChain(result: VectorSearchResult) {
  emit('viewCallChain', result)
}
</script>

<style scoped>
.semantic-search-panel {
  padding: 20px;
}

.search-input-section {
  margin-bottom: 20px;
}

.result-card {
  margin-bottom: 16px;
}

.result-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.method-name {
  font-weight: bold;
  font-size: 16px;
}

.result-content {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.description-section .description {
  color: var(--el-text-color-primary);
}

.signature-section code {
  background-color: var(--el-fill-color-light);
  padding: 2px 6px;
  border-radius: 4px;
  font-size: 12px;
}

.actions {
  display: flex;
  gap: 8px;
}

.search-info {
  margin-top: 16px;
  text-align: center;
}
</style>
```

**Step 2: 验证 Vue 编译**

Run: `cd frontend && npm run build`
Expected: No errors

**Step 3: Commit**

```bash
git add frontend/src/views/knowledge-graph/components/SemanticSearchPanel.vue
git commit -m "feat(frontend): add semantic search panel component"
```

---

## Task 8: 集成语义搜索 Tab 到知识图谱视图

**Files:**
- Modify: `frontend/src/views/knowledge-graph/KnowledgeGraphView.vue`

**Step 1: 导入 SemanticSearchPanel 组件**

```typescript
import SemanticSearchPanel from './components/SemanticSearchPanel.vue'
```

**Step 2: 添加语义搜索 Tab**

在 el-tabs 中添加新的 tab-pane：

```vue
<el-tab-pane label="语义搜索" name="semanticSearch">
  <SemanticSearchPanel
    :project-path="projectPath"
    @view-detail="handleViewDetail"
    @view-call-chain="handleViewCallChain"
  />
</el-tab-pane>
```

**Step 3: 添加事件处理函数**

```typescript
function handleViewDetail(result: any) {
  // 跳转到方法详情或打开弹窗
  console.log('View detail:', result)
}

function handleViewCallChain(result: any) {
  // 跳转到调用链视图
  console.log('View call chain:', result)
}
```

**Step 4: 验证编译**

Run: `cd frontend && npm run build`
Expected: No errors

**Step 5: Commit**

```bash
git add frontend/src/views/knowledge-graph/KnowledgeGraphView.vue
git commit -m "feat(frontend): integrate semantic search tab into knowledge graph view"
```

---

## Task 9: 方法详情弹窗添加 Description 展示

**Files:**
- Modify: `frontend/src/views/knowledge-graph/components/MethodDetail.vue` (或对应的方法详情组件)

**Step 1: 在详情描述中添加 Description 字段**

找到方法详情展示部分，添加：

```vue
<el-descriptions-item label="功能描述">
  <el-text type="primary">{{ methodNode.description || '暂无描述' }}</el-text>
</el-descriptions-item>
```

**Step 2: 验证编译**

Run: `cd frontend && npm run build`
Expected: No errors

**Step 3: Commit**

```bash
git add frontend/src/views/knowledge-graph/components/MethodDetail.vue
git commit -m "feat(frontend): display method description in detail popup"
```

---

## Task 10: 调用链图节点 Tooltip 添加 Description

**Files:**
- Modify: `frontend/src/views/knowledge-graph/components/CallChainGraph.vue`

**Step 1: 修改节点 Tooltip 配置**

找到节点 tooltip 配置，添加 description：

```typescript
const nodeTooltip = (node: GraphNode) => {
  return `
    <div class="node-tooltip">
      <div class="method-name">${node.name}</div>
      <div class="class-name">${node.className}</div>
      ${node.description ? `<div class="description">${node.description}</div>` : ''}
    </div>
  `
}
```

**Step 2: 添加 tooltip 样式**

```css
.node-tooltip .description {
  color: #666;
  font-size: 12px;
  margin-top: 4px;
  border-top: 1px solid #eee;
  padding-top: 4px;
}
```

**Step 3: 验证编译**

Run: `cd frontend && npm run build`
Expected: No errors

**Step 4: Commit**

```bash
git add frontend/src/views/knowledge-graph/components/CallChainGraph.vue
git commit -m "feat(frontend): add description to call chain graph node tooltip"
```

---

## Task 11: 集成测试与验证

**Step 1: 启动后端服务**

Run: `cd hisi-dev-tool && mvn spring-boot:run`
Expected: Application started successfully

**Step 2: 测试 ONNX 推理**

```bash
# 清空现有 description
curl -X POST "http://localhost:7474/db/neo4j/tx/commit" -u neo4j:12345678 \
  -d '{"statements":[{"statement":"MATCH (m:Method) SET m.description = null"}]}'

# 重新生成向量（会触发描述生成）
curl -X POST "http://localhost:8080/api/vector-generation/start?projectPath=xxx"

# 检查生成的描述
curl -s "http://localhost:7474/db/neo4j/tx/commit" -u neo4j:12345678 \
  -d '{"statements":[{"statement":"MATCH (m:Method) RETURN m.methodName, m.description LIMIT 5"}]}'
```

Expected: description 字段包含有意义的中文描述

**Step 3: 测试 MCP 接口**

```bash
curl -X POST "http://localhost:8080/api/vector-search" \
  -H "Content-Type: application/json" \
  -d '{"query": "错误诊断", "projectPath": "xxx", "limit": 3}'
```

Expected: 返回结果包含 description 字段

**Step 4: 测试前端功能**

1. 访问知识图谱页面
2. 点击 "语义搜索" Tab
3. 输入 "错误诊断" 搜索
4. 验证结果显示 description
5. 点击方法查看详情，验证 description 展示

**Step 5: 最终 Commit**

```bash
git add -A
git commit -m "feat: complete semantic search enhancement implementation"
```

---

## Verification Summary

| 测试项 | 验证方法 | 预期结果 |
|--------|----------|----------|
| ONNX 依赖 | mvn dependency:resolve | onnxruntime:jar:1.18.0 |
| 模型加载 | 后端启动日志 | "文本生成模型加载成功" |
| 描述生成 | Neo4j 查询 | description 包含有意义的中文描述 |
| MCP vector_search | curl 测试 | 返回正确格式结果 |
| MCP hybrid_search | curl 测试 | 返回包含 callers/callees |
| 前端语义搜索 | UI 测试 | Tab 显示，搜索功能正常 |
| Description 展示 | UI 测试 | 详情和 tooltip 显示描述 |
