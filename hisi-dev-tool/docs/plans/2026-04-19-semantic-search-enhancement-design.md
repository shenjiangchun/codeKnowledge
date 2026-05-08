# 语义搜索增强设计文档

**创建日期**: 2026-04-19
**作者**: Claude AI
**状态**: 待实现

---

## 一、背景与目标

### 1.1 背景

当前系统存在以下问题：

1. **本地模型推理未实现**: `LocalTextGenerationService.generateWithModel()` 只有 TODO 注释，ONNX 推理逻辑未实现，导致所有方法描述都是基于方法名的默认值（如 "diagnose" → "diagnose"）

2. **MCP 接口定义与后端不一致**:
   - `vector_search` 路径错误（`/api/vector/search` vs `/api/vector-search`）
   - 参数名称不匹配（`topK` vs `limit`）
   - `projectPath` 应为必填但定义为可选

3. **前端未集成语义搜索**: 向量搜索功能已实现但前端未调用

4. **Description 字段未展示**: 方法自然语言描述已生成但前端未展示

### 1.2 目标

1. 实现本地 ONNX 模型推理，为所有方法生成高质量描述
2. 修复 MCP 接口定义，新增混合搜索工具
3. 前端集成语义搜索功能，展示方法描述

---

## 二、系统架构

```
┌─────────────────────────────────────────────────────────────────────────┐
│                              整体架构                                    │
└─────────────────────────────────────────────────────────────────────────┘

                    ┌─────────────────┐
                    │   前端 (Vue3)   │
                    │  - 语义搜索Tab  │
                    │  - Description  │
                    │    展示         │
                    └────────┬────────┘
                             │ HTTP API
                             ▼
┌─────────────────────────────────────────────────────────────────────────┐
│                           后端 (Spring Boot)                            │
├─────────────────────────────────────────────────────────────────────────┤
│                                                                         │
│  ┌───────────────────┐    ┌───────────────────┐    ┌─────────────────┐ │
│  │ VectorSearchCtrl  │    │ HybridSearchSvc   │    │ LLMDescription  │ │
│  │ /api/vector-search│───▶│ 三层混合检索       │    │ Service         │ │
│  └───────────────────┘    │ 1.关键词过滤       │    │                 │ │
│                           │ 2.向量匹配         │    │ ONNX 推理       │ │
│                           │ 3.图遍历扩展       │───▶│ Phi-3-mini     │ │
│                           └───────────────────┘    │ 本地模型       │ │
│                                                    └─────────────────┘ │
│                                                                           │
│                           ┌───────────────────┐                         │
│                           │     Neo4j         │                         │
│                           │ - MethodNode      │                         │
│                           │ - description     │                         │
│                           │ - embedding       │                         │
│                           │ - CALLS 关系      │                         │
│                           └───────────────────┘                         │
└─────────────────────────────────────────────────────────────────────────┘
                             ▲
                             │ MCP Protocol
                             │
                    ┌────────┴────────┐
                    │  MCP Server      │
                    │  - vector_search │
                    │  - hybrid_search │
                    └─────────────────┘
```

---

## 三、模块详细设计

### 3.1 后端：ONNX 推理实现

#### 3.1.1 当前问题

```java
// LocalTextGenerationService.java
private String generateWithModel(...) {
    // TODO: 实现 ONNX 推理
    return generateDefaultDescription(...);  // 实际只返回默认描述
}
```

#### 3.1.2 解决方案

**文件**: `src/main/java/com/huawei/hisi/neo4j/service/LocalTextGenerationService.java`

```java
// 新增依赖
import ai.onnxruntime.*;
import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer;

@Service
public class LocalTextGenerationService {

    private OrtEnvironment ortEnv;
    private OrtSession session;
    private HuggingFaceTokenizer tokenizer;

    @PostConstruct
    public void init() {
        // 1. 初始化 ONNX Runtime 环境
        ortEnv = OrtEnvironment.getEnvironment();

        // 2. 加载 ONNX 模型
        session = ortEnv.createSession(modelPath);

        // 3. 加载 Tokenizer
        tokenizer = HuggingFaceTokenizer.newInstance(tokenizerPath);

        modelLoaded = true;
    }

    private String generateWithModel(String className, String methodName,
                                      String signature, String comment) {
        // 1. 构建 Phi-3 格式的 prompt
        String prompt = buildPhi3Prompt(className, methodName, signature, comment);

        // 2. Tokenize
        long[] inputIds = tokenizer.encode(prompt);

        // 3. 创建输入张量
        OnnxTensor inputTensor = OnnxTensor.createTensor(ortEnv,
            new long[][]{inputIds});

        // 4. 执行推理
        Map<String, OnnxTensor> inputs = Map.of("input_ids", inputTensor);
        OrtSession.Result result = session.run(inputs);

        // 5. 解码输出
        long[][] outputIds = (long[][]) result.get(0).getValue();
        String generated = tokenizer.decode(outputIds[0]);

        // 6. 提取描述（去掉 prompt 部分）
        return extractDescription(generated, prompt);
    }

    private String buildPhi3Prompt(String className, String methodName,
                                    String signature, String comment) {
        return "<|user|>\n" +
               "请用一句简洁的中文描述以下Java方法的功能（30字以内）：\n" +
               "类名：" + className + "\n" +
               "方法名：" + methodName + "\n" +
               "签名：" + signature + "\n" +
               "注释：" + (comment != null ? comment : "无") + "\n" +
               "<|end|>\n<|assistant|>\n";
    }
}
```

#### 3.1.3 依赖添加

**文件**: `pom.xml`

```xml
<!-- ONNX Runtime -->
<dependency>
    <groupId>com.microsoft.onnxruntime</groupId>
    <artifactId>onnxruntime</artifactId>
    <version>1.18.0</version>
</dependency>
```

#### 3.1.4 增强 Prompt 信息

当前传递：类名、方法名、签名、注释

建议增加：
- 方法体摘要（前 200 字符）
- 抛出异常信息
- 所属服务名

```java
private String buildEnhancedPrompt(MethodNode node) {
    StringBuilder prompt = new StringBuilder();
    prompt.append("请用一句简洁的中文描述以下Java方法的功能（30字以内）：\n");
    prompt.append("类名：").append(node.getClassName()).append("\n");
    prompt.append("方法名：").append(node.getMethodName()).append("\n");
    prompt.append("签名：").append(node.getSignature()).append("\n");

    // 新增：方法体摘要
    if (node.getMethodBody() != null && !node.getMethodBody().isEmpty()) {
        String bodySummary = node.getMethodBody().length() > 200
            ? node.getMethodBody().substring(0, 200) + "..."
            : node.getMethodBody();
        prompt.append("方法体摘要：").append(bodySummary).append("\n");
    }

    // 新增：异常信息
    if (node.getThrownExceptions() != null && !node.getThrownExceptions().isEmpty()) {
        prompt.append("抛出异常：").append(String.join(", ", node.getThrownExceptions())).append("\n");
    }

    prompt.append("注释：").append(node.getComment() != null ? node.getComment() : "无").append("\n");

    return prompt.toString();
}
```

---

### 3.2 MCP：接口修复与新增

#### 3.2.1 修复 vector_search

**文件**: `hisi-mcp-server/src/tools/vectorTools.ts`

```typescript
// 修复前
export const vectorToolDefinitions = [
  {
    name: 'vector_search',
    inputSchema: {
      properties: {
        query: { type: 'string' },
        topK: { type: 'number' },           // 错误参数名
        threshold: { type: 'number' },      // 后端不支持
        projectPath: { type: 'string' },    // 未标记必填
      },
      required: ['query'],
    },
  },
];

// 修复后
export const vectorToolDefinitions = [
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
        },
      },
      required: ['query', 'projectPath'],
    },
  },
];

// 修复 API 调用
async search(params: VectorSearchParams): Promise<unknown> {
  return this.client.post('/api/vector-search', {  // 修复路径
    query: params.query,
    projectPath: params.projectPath,
    ...(params.limit && { limit: params.limit }),
    ...(params.graphDepth && { graphDepth: params.graphDepth }),
  });
}
```

#### 3.2.2 新增 hybrid_search 工具

**文件**: `hisi-mcp-server/src/tools/vectorTools.ts`

```typescript
export const vectorToolDefinitions = [
  // ... 现有工具

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
        },
      },
      required: ['query', 'projectPath'],
    },
  },
];

// 实现调用
async hybridSearch(params: HybridSearchParams): Promise<unknown> {
  return this.client.post('/api/vector-search', {
    query: params.query,
    projectPath: params.projectPath,
    ...(params.limit && { limit: params.limit }),
    graphDepth: params.graphDepth ?? 2,
  });
}
```

---

### 3.3 前端：语义搜索与 Description 展示

#### 3.3.1 知识图谱添加语义搜索 Tab

**文件**: `src/views/knowledge-graph/KnowledgeGraphView.vue`

```vue
<template>
  <div class="knowledge-graph-view">
    <el-tabs v-model="activeTab">
      <el-tab-pane label="入口点" name="entryPoints">
        <EntryPointList />
      </el-tab-pane>
      <el-tab-pane label="调用链" name="callChain">
        <CallChainGraph />
      </el-tab-pane>
      <el-tab-pane label="环检测" name="cycles">
        <CycleDetection />
      </el-tab-pane>
      <!-- 新增：语义搜索 Tab -->
      <el-tab-pane label="语义搜索" name="semanticSearch">
        <SemanticSearchPanel :project-path="projectPath" />
      </el-tab-pane>
    </el-tabs>
  </div>
</template>
```

**新文件**: `src/views/knowledge-graph/components/SemanticSearchPanel.vue`

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
import { vectorSearchApi } from '@/api/vectorSearch'

const props = defineProps<{
  projectPath: string
}>()

const searchQuery = ref('')
const results = ref<any[]>([])
const loading = ref(false)
const totalCount = ref(0)
const costTimeMs = ref(0)

async function handleSearch() {
  if (!searchQuery.value.trim()) return

  loading.value = true
  try {
    const response = await vectorSearchApi.search({
      query: searchQuery.value,
      projectPath: props.projectPath,
      limit: 10
    })
    results.value = response.results
    totalCount.value = response.totalCount
    costTimeMs.value = response.costTimeMs
  } finally {
    loading.value = false
  }
}
</script>
```

#### 3.3.2 API 文件

**新文件**: `src/api/vectorSearch.ts`

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

#### 3.3.3 Description 展示

**位置1：方法详情弹窗**

修改现有 `MethodDetail` 组件，添加 description 字段：

```vue
<!-- 在现有字段后添加 -->
<el-descriptions-item label="功能描述">
  <el-text type="primary">{{ methodNode.description || '暂无描述' }}</el-text>
</el-descriptions-item>
```

**位置2：调用链图节点 Tooltip**

修改 `CallChainGraph.vue` 中的节点配置：

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

---

## 四、数据流

### 4.1 描述生成流程

```
知识图谱生成完成
        │
        ▼
触发向量生成 (VectorGenerationService)
        │
        ▼
遍历每个方法节点
        │
        ▼
┌─────────────────────────────────────┐
│ LLMDescriptionService               │
│                                     │
│ 1. 检查本地模型是否可用              │
│    └─ modelLoaded = true?           │
│                                     │
│ 2. 本地模型推理 (ONNX)              │
│    ├─ 构建 Phi-3 格式 prompt        │
│    ├─ Tokenize                      │
│    ├─ ONNX 推理                     │
│    └─ 解码输出                      │
│                                     │
│ 3. 回退：远程 LLM API (可选)        │
│                                     │
│ 4. 最终回退：基于方法名的默认描述    │
└─────────────────────────────────────┘
        │
        ▼
保存 description 到 Neo4j
        │
        ▼
生成 embedding 向量
```

### 4.2 语义搜索流程

```
用户输入自然语言查询
        │
        ▼
┌─────────────────────────────────────┐
│ HybridSearchService                 │
│                                     │
│ 第1层：意图识别 + 关键词过滤        │
│    IntentRecognitionService         │
│    → keywords, methodType           │
│    → 查询方法名匹配的方法           │
│                                     │
│ 第2层：向量搜索                     │
│    EmbeddingService.generateEmbedding│
│    → Neo4j 向量相似度查询           │
│                                     │
│ 第3层：图遍历扩展                   │
│    从种子节点沿 CALLS 关系扩展      │
│    → 查询调用者和被调用者           │
│                                     │
│ RRF融合排序                         │
└─────────────────────────────────────┘
        │
        ▼
返回 SearchResult (含 description)
```

---

## 五、验证计划

### 5.1 ONNX 推理验证

```bash
# 1. 清空现有 description
curl -X POST "http://localhost:7474/db/neo4j/tx/commit" \
  -u "neo4j:12345678" \
  -d '{"statements":[{"statement":"MATCH (m:Method) SET m.description = null"}]}'

# 2. 重新生成向量（会触发描述生成）
curl -X POST "http://localhost:8080/api/vector-generation/start?projectPath=xxx"

# 3. 检查生成的描述
curl -s "http://localhost:7474/db/neo4j/tx/commit" \
  -u "neo4j:12345678" \
  -d '{"statements":[{"statement":"MATCH (m:Method) RETURN m.methodName, m.description LIMIT 5"}]}'
```

**预期结果**: 描述应为有意义的中文句子，如 "接收诊断请求并执行诊断分析"

### 5.2 MCP 接口验证

```bash
# 测试 vector_search
curl -X POST "http://localhost:8080/api/vector-search" \
  -H "Content-Type: application/json" \
  -d '{"query": "错误诊断", "projectPath": "xxx", "limit": 3}'

# 预期：返回结果含 description 字段
```

### 5.3 前端功能验证

1. 访问知识图谱页面
2. 点击 "语义搜索" Tab
3. 输入 "错误诊断" 搜索
4. 验证结果显示 description
5. 点击方法查看详情，验证 description 展示

---

## 六、风险评估

| 风险 | 影响 | 缓解措施 |
|------|------|----------|
| ONNX 模型推理性能 | 首次生成描述耗时较长 | 异步批量处理，显示进度 |
| 模型内存占用 | 内存压力大 | 使用 INT4 量化模型 |
| Prompt 质量影响描述质量 | 描述不准确 | 增强 prompt，添加上下文信息 |
| 前端 Tab 布局变化 | 用户体验变化 | 保持与其他 Tab 一致的交互 |

---

## 七、实施计划

| 阶段 | 任务 | 预估工时 |
|------|------|----------|
| 1 | 后端：ONNX 推理实现 | 2h |
| 2 | 后端：增强 Prompt | 1h |
| 3 | MCP：修复接口 + 新增工具 | 1h |
| 4 | 前端：语义搜索 Tab | 2h |
| 5 | 前端：Description 展示 | 1h |
| 6 | 测试与验证 | 1h |

**总计**: 约 8 小时
