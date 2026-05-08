# 知识图谱 MCP 工具与业务流 Skill - 实施计划

## 文档信息
- **创建日期**: 2026-04-17
- **版本**: 1.0
- **设计文档**: [2026-04-17-knowledge-graph-mcp-design.md](./2026-04-17-knowledge-graph-mcp-design.md)

---

## 阶段 1: 基础设施搭建

### 任务 1.1: Python 向量服务搭建

#### 步骤 1.1.1: 创建项目结构
**文件**: `hisi-vector-service/`
```
hisi-vector-service/
├── app.py
├── requirements.txt
├── config.py
├── services/
│   ├── __init__.py
│   ├── chroma_service.py
│   └── embedding_service.py
├── models/
│   ├── __init__.py
│   └── schemas.py
└── Dockerfile
```

#### 步骤 1.1.2: 实现 ChromaDB 服务
**文件**: `hisi-vector-service/services/chroma_service.py`
- 初始化 ChromaDB PersistentClient
- 创建/获取 collection
- 实现 add, search, delete, status 方法

#### 步骤 1.1.3: 实现向量生成服务
**文件**: `hisi-vector-service/services/embedding_service.py`
- 支持 sentence-transformers 本地模型
- 支持 OpenAI API（可选）
- 实现方法描述生成

#### 步骤 1.1.4: 实现 Flask API
**文件**: `hisi-vector-service/app.py`
- POST `/api/vector/search` - 向量搜索
- POST `/api/vector/sync` - 同步向量
- GET `/api/vector/status` - 状态查询
- POST `/api/vector/add` - 添加向量
- DELETE `/api/vector/delete` - 删除向量

#### 步骤 1.1.5: 编写 Dockerfile
**文件**: `hisi-vector-service/Dockerfile`
```dockerfile
FROM python:3.11-slim
WORKDIR /app
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt
COPY . .
EXPOSE 8001
CMD ["python", "app.py"]
```

---

### 任务 1.2: Spring Boot 向量接口

#### 步骤 1.2.1: 创建配置类
**文件**: `hisi-dev-tool/src/main/java/com/huawei/hisi/vectorstore/config/VectorStoreConfig.java`
```java
@Configuration
@ConfigurationProperties(prefix = "vector.store")
public class VectorStoreConfig {
    private String pythonServiceUrl = "http://localhost:8001";
    // getters, setters
}
```

#### 步骤 1.2.2: 创建 DTO 类
**文件**: `hisi-dev-tool/src/main/java/com/huawei/hisi/vectorstore/model/`

- `VectorSearchRequest.java` - 搜索请求
- `VectorSearchResponse.java` - 搜索响应
- `VectorSyncRequest.java` - 同步请求
- `VectorSyncResponse.java` - 同步响应
- `VectorStatusResponse.java` - 状态响应

#### 步骤 1.2.3: 创建服务接口
**文件**: `hisi-dev-tool/src/main/java/com/huawei/hisi/vectorstore/service/VectorStoreService.java`
```java
public interface VectorStoreService {
    VectorSearchResponse search(VectorSearchRequest request);
    VectorSyncResponse sync(VectorSyncRequest request);
    VectorStatusResponse status();
    void addVectors(List<VectorItem> items);
    void deleteByProjectPath(String projectPath);
}
```

#### 步骤 1.2.4: 实现服务
**文件**: `hisi-dev-tool/src/main/java/com/huawei/hisi/vectorstore/service/impl/VectorStoreServiceImpl.java`
- 使用 RestTemplate 或 WebClient 调用 Python 服务
- 实现所有接口方法

#### 步骤 1.2.5: 创建控制器
**文件**: `hisi-dev-tool/src/main/java/com/huawei/hisi/vectorstore/controller/VectorStoreController.java`
```java
@RestController
@RequestMapping("/api/vector")
public class VectorStoreController {
    // 搜索、同步、状态等端点
}
```

#### 步骤 1.2.6: 更新配置文件
**文件**: `hisi-dev-tool/src/main/resources/application.properties`
```properties
vector.store.python-service-url=http://localhost:8001
```

---

### 任务 1.3: MCP Server 基础框架

#### 步骤 1.3.1: 初始化 TypeScript 项目
```bash
mkdir hisi-mcp-server && cd hisi-mcp-server
npm init -y
npm install @modelcontextprotocol/sdk typescript @types/node
```

#### 步骤 1.3.2: 配置 TypeScript
**文件**: `hisi-mcp-server/tsconfig.json`
```json
{
  "compilerOptions": {
    "target": "ES2020",
    "module": "commonjs",
    "outDir": "./dist",
    "strict": true
  }
}
```

#### 步骤 1.3.3: 创建 API 客户端
**文件**: `hisi-mcp-server/src/client/apiClient.ts`
```typescript
export class ApiClient {
  constructor(private baseUrl: string) {}

  async get<T>(path: string, params?: Record<string, string>): Promise<T>
  async post<T>(path: string, body: unknown): Promise<T>
}
```

#### 步骤 1.3.4: 创建主入口
**文件**: `hisi-mcp-server/src/index.ts`
```typescript
import { Server } from '@modelcontextprotocol/sdk/server/index.js';
import { StdioServerTransport } from '@modelcontextprotocol/sdk/server/stdio.js';

const server = new Server({
  name: 'hisi-knowledge-graph-mcp',
  version: '1.0.0'
}, {
  capabilities: { tools: {} }
});
```

---

## 阶段 2: MCP 工具实现

### 任务 2.1: 知识图谱工具 (25个)

#### 步骤 2.1.1: 创建工具定义文件
**文件**: `hisi-mcp-server/src/tools/knowledgeGraphTools.ts`

#### 步骤 2.1.2: 实现图谱管理工具
- `kg_generate` - POST `/api/knowledge-graph/generate`
- `kg_status` - GET `/api/knowledge-graph/status`
- `kg_task_status` - GET `/api/knowledge-graph/tasks/status`

#### 步骤 2.1.3: 实现方法查询工具
- `kg_callers` - GET `/api/knowledge-graph/callers`
- `kg_callees` - GET `/api/knowledge-graph/callees`
- `kg_method_detail` - GET `/api/knowledge-graph/method/detail`
- `kg_method_by_class` - GET `/api/knowledge-graph/method/by-class`

#### 步骤 2.1.4: 实现入口点查询工具
- `kg_entry_points` - GET `/api/knowledge-graph/entry-points`
- `kg_call_chain_by_key` - GET `/api/knowledge-graph/call-chain/by-key`
- `kg_call_chain_by_type` - GET `/api/knowledge-graph/call-chain/by-type`

#### 步骤 2.1.5: 实现调用链分析工具
- `kg_downstream` - GET `/api/knowledge-graph/call-chain/downstream`
- `kg_call_chain_graph` - GET `/api/knowledge-graph/call-chain/graph`
- `kg_affecting` - GET `/api/knowledge-graph/call-chain/affecting`
- `kg_bridges` - GET `/api/knowledge-graph/call-chain/{nodeId}/bridges`

#### 步骤 2.1.6: 实现其他工具
- 接口实现查询 (2个)
- 环检测 (1个)
- MyBatis 查询 (3个)
- 桥接查询 (3个)

---

### 任务 2.2: 向量搜索工具 (3个)

**文件**: `hisi-mcp-server/src/tools/vectorTools.ts`

- `vector_search` - POST `/api/vector/search`
- `vector_sync` - POST `/api/vector/sync`
- `vector_status` - GET `/api/vector/status`

---

### 任务 2.3: 日志查询工具 (1个)

**文件**: `hisi-mcp-server/src/tools/logTools.ts`

- `log_query` - POST `/api/log/query`

---

## 阶段 3: Skill 实现

### 任务 3.1: Skill 1 - git-code-review

**文件**: `.claude/skills/git-code-review.md`

**内容结构**:
```markdown
---
name: git-code-review
description: Git 提交代码审查，分析影响范围和业务流变化
trigger: /git-review
---

## 输入
- commit SHA 或 commit 范围

## 执行流程
1. 使用 `git diff` 获取变更文件列表
2. 解析变更文件，提取类名和方法签名
3. 调用 `kg_method_detail` 获取方法详情
4. 调用 `kg_affecting` 查询上游影响
5. 调用 `kg_downstream` 查询下游影响
6. 调用 `kg_cycles_detect` 检测环
7. 生成审查报告

## 输出格式
...
```

---

### 任务 3.2: Skill 2 - error-log-analysis

**文件**: `.claude/skills/error-log-analysis.md`

**内容结构**:
```markdown
---
name: error-log-analysis
description: 错误日志分析，结合知识图谱定位问题根因
trigger: /error-analysis
---

## 输入
- 错误日志内容（含时间戳、容器、堆栈）

## 执行流程
1. 解析错误日志，提取关键信息：
   - 异常类型和消息
   - 错误堆栈（类名、方法名、行号）
   - 时间戳、容器信息
2. 调用 `kg_callers` 定位调用链
3. 调用 `kg_method_detail` 获取方法异常声明
4. 调用 `kg_call_chain_by_key` 获取完整调用链上下文
5. 调用 `log_query` DSL 查询完整日志
6. AI 分析生成根因报告

## 输出格式
...
```

---

### 任务 3.3: Skill 3 - interface-flow-analysis

**文件**: `.claude/skills/interface-flow-analysis.md`

**内容结构**:
```markdown
---
name: interface-flow-analysis
description: 接口业务流分析，输出完整业务流逻辑
trigger: /interface-flow
---

## 输入
- 接口 URI 或方法签名

## 执行流程
1. 调用 `kg_entry_points` 查询入口点
2. 调用 `kg_call_chain_graph` 获取 DAG 图
3. 遍历每个节点，调用 `kg_method_detail` 获取详情
4. 调用 `kg_bridges` 获取桥接调用信息
5. 生成业务流程文档

## 用户选择
- A. 测试代码生成
- B. 安全重构

### 分支 B: 安全重构流程
1. 生成完整单测
2. 运行单测确认通过
3. 循环：
   a. 执行一个重构步骤
   b. 需要人工确认
   c. 单测回归验证
   d. 直到完成所有重构目标
```

---

### 任务 3.4: Skill 4 - tech-design

**文件**: `.claude/skills/tech-design.md`

**内容结构**:
```markdown
---
name: tech-design
description: 技术方案设计，根据需求生成完整技术方案
trigger: /tech-design
---

## 输入
- 需求描述（自然语言）

## 执行流程
1. 分析需求，提取关键词
2. 调用 `vector_search` 向量搜索相关代码
3. 对匹配的代码调用 `kg_method_detail` 获取详情
4. 调用 `kg_call_chain_graph` 分析调用链
5. 调用 `kg_entry_points` 确定入口点
6. 调用 `kg_affecting` 分析影响范围
7. 生成技术方案文档

## 输出格式
### 需求理解
### 现有代码分析
### 设计方案
### 影响范围
### 实施步骤
```

---

### 任务 3.5: 注册 Skills

**文件**: `hisi-dev-tool/src/main/resources/codeai-skills/skill-definitions.json`

添加 4 个 Skill 定义：
```json
{
  "skills": [
    {
      "id": "git-code-review",
      "name": "Git代码审查",
      "category": "general-skill",
      "triggerKeywords": ["/git-review", "代码审查", "commit"],
      "skillPath": ".claude/skills/git-code-review.md"
    },
    {
      "id": "error-log-analysis",
      "name": "错误日志分析",
      "category": "general-skill",
      "triggerKeywords": ["/error-analysis", "错误分析", "异常定位"],
      "skillPath": ".claude/skills/error-log-analysis.md"
    },
    {
      "id": "interface-flow-analysis",
      "name": "接口业务流分析",
      "category": "general-skill",
      "triggerKeywords": ["/interface-flow", "业务流", "调用链分析"],
      "skillPath": ".claude/skills/interface-flow-analysis.md"
    },
    {
      "id": "tech-design",
      "name": "技术方案设计",
      "category": "general-skill",
      "triggerKeywords": ["/tech-design", "技术方案", "需求设计"],
      "skillPath": ".claude/skills/tech-design.md"
    }
  ]
}
```

---

## 阶段 4: 测试与文档

### 任务 4.1: 单元测试

#### Python 服务测试
**文件**: `hisi-vector-service/tests/`
- `test_chroma_service.py` - ChromaDB 服务测试
- `test_embedding_service.py` - 向量生成测试
- `test_api.py` - API 端点测试

#### Java 服务测试
**文件**: `hisi-dev-tool/src/test/java/com/huawei/hisi/vectorstore/`
- `VectorStoreServiceTest.java` - 服务层测试
- `VectorStoreControllerTest.java` - 控制器测试

#### MCP Server 测试
**文件**: `hisi-mcp-server/src/__tests__/`
- `knowledgeGraphTools.test.ts`
- `vectorTools.test.ts`

---

### 任务 4.2: 集成测试

- 启动 Python 服务
- 启动 Spring Boot 服务
- 启动 MCP Server
- 测试完整调用链

---

### 任务 4.3: 用户文档

**文件**: `docs/mcp-tools-guide.md`

内容：
- MCP Server 安装配置
- 29 个工具使用说明
- 4 个 Skill 使用示例

---

## 依赖关系

```
阶段1.1 ─────► 阶段1.2 ─────► 阶段2
  │                            │
  │                            ▼
  │                         阶段3
  │                            │
  └────────────────────────────┼──────► 阶段4
```

---

## 验收清单

### 阶段 1 验收
- [ ] Python 服务启动成功，5 个 API 可用
- [ ] Spring Boot 向量接口可用
- [ ] MCP Server 可以启动并连接

### 阶段 2 验收
- [ ] 25 个知识图谱工具可用
- [ ] 3 个向量搜索工具可用
- [ ] 1 个日志查询工具可用

### 阶段 3 验收
- [ ] 4 个 Skill 可以触发执行
- [ ] 每个工具返回正确结果

### 阶段 4 验收
- [ ] 所有单元测试通过
- [ ] 集成测试通过
- [ ] 用户文档完成
