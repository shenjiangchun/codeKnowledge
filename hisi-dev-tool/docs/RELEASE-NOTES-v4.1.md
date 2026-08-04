# HiSi DevTool v4.1 版本说明

## 版本概览

**发布日期**: 2026-04-04
**版本代号**: 架构演进 - Agent框架搭建 + LLM语义理解基础能力

---

## 一、版本亮点

v4.1 是 HiSi DevTool 架构演进的重要里程碑，引入了 **多Agent协作诊断** 和 **LLM原生代码语义理解** 两大核心能力，为后续智能化功能奠定基础。

```
┌─────────────────────────────────────────────────────────────────┐
│                    v4.1 核心能力架构                             │
├─────────────────────────────────────────────────────────────────┤
│                                                                 │
│  ┌─────────────────────┐    ┌─────────────────────┐           │
│  │   多Agent协作诊断    │    │  LLM语义理解引擎    │           │
│  │                     │    │                     │           │
│  │  • StackTraceAgent  │    │  • IntentAnalyzer   │           │
│  │  • AgentOrchestrator│    │  • 向量嵌入存储     │           │
│  │  • 置信度机制       │    │  • 相似代码检索     │           │
│  └─────────────────────┘    └─────────────────────┘           │
│           │                          │                         │
│           └──────────┬───────────────┘                         │
│                      ▼                                         │
│           ┌─────────────────────┐                              │
│           │   WebSocket 实时推送  │                              │
│           │   前端诊断面板       │                              │
│           └─────────────────────┘                              │
│                                                                 │
└─────────────────────────────────────────────────────────────────┘
```

---

## 二、功能对比（v3.x vs v4.1）

### 2.1 核心功能对比表

| 功能模块 | v3.x | v4.1 | 变化说明 |
|---------|------|------|---------|
| **日志分析** | 基础日志查询 + LLM分析 | 基础日志查询 + LLM分析 | 保持不变 |
| **调用链分析** | 静态代码分析 | 静态代码分析 + 语义存储 | 新增语义数据存储 |
| **问题诊断** | 手动分析 | **AI多Agent协作诊断** | 🆕 重大升级 |
| **代码理解** | AST解析 | **LLM语义理解** | 🆕 新增能力 |
| **实时反馈** | 无 | **WebSocket实时进度** | 🆕 新增能力 |
| **前端交互** | 传统表单 | **智能对话式诊断** | 🆕 新增能力 |

### 2.2 新增功能详情

#### 🆕 功能一：多Agent协作诊断系统

**问题背景**: 传统日志诊断依赖人工经验，效率低、容易遗漏关键信息。

**解决方案**: 引入多Agent协作机制，每个Agent专注于特定诊断能力，通过编排器协调工作。

```
用户输入问题
     │
     ▼
┌─────────────────┐
│ AgentOrchestrator │ ← 编排器（任务分解、调度、结果聚合）
└────────┬────────┘
         │
    ┌────┴────┬─────────┐
    ▼         ▼         ▼
┌────────┐ ┌────────┐ ┌────────┐
│StackTrace│ │CodeContext│ │GitHistory│ ← 专业Agent分工
│ Agent   │ │ Agent    │ │ Agent   │
└────┬───┘ └────┬────┘ └────┬───┘
     │         │         │
     └────┬────┴────┬────┘
          ▼         ▼
     ┌─────────────────┐
     │  置信度加权聚合   │ ← 结果验证
     └─────────────────┘
```

**核心特性**:
- **置信度机制**: 每个Agent评估自身对当前问题的处理能力（0.0-1.0）
- **自动跳过**: 置信度 < 0.3 的Agent自动跳过，避免无效分析
- **并发执行**: 多个独立Agent并行工作，提升诊断效率
- **结果聚合**: 多Agent结果置信度加权，输出综合诊断结论

**已实现Agent**:

| Agent名称 | 功能 | 优先级 | 状态 |
|----------|------|-------|------|
| StackTraceAgent | 堆栈解析、**LLM深度分析**、修复建议 | 10（最高）| ✅ 已实现 |
| CodeContextAgent | 代码上下文关联分析 | 20 | 📋 v4.2计划 |
| GitHistoryAgent | 历史提交关联分析 | 30 | 📋 v4.2计划 |
| ConsensusAgent | 多Agent结果验证 | 40 | 📋 v4.3计划 |

**LLM集成说明**:

StackTraceAgent 已集成 LLMService，工作流程如下：

```
用户堆栈信息
     │
     ├──────────────────────────────┐
     │                              │
     ▼                              ▼
┌─────────────┐              ┌─────────────────┐
│ 规则解析    │              │ LLM 深度分析   │
│ (正则匹配)  │              │ (语义理解)     │
└──────┬──────┘              └────────┬────────┘
       │                              │
       │  ┌───────────────────────────┘
       │  │
       ▼  ▼
┌─────────────────────────────────────┐
│ 结果合并：                          │
│ • 结论 (LLM优先，规则回退)          │
│ • 根因分析 (LLM深度)                │
│ • 修复建议 (LLM生成 + 规则补充)     │
│ • 置信度 (+0.15 LLM加成)            │
└─────────────────────────────────────┘
```

**降级策略**: 当 LLM 服务不可用时，自动回退到规则分析，保证服务稳定。

---

#### 🆕 功能二：LLM原生代码语义理解

**问题背景**: 传统AST解析只能理解代码结构，无法理解代码意图。

**解决方案**: 使用LLM分析方法意图，结合向量嵌入实现语义级代码检索。

```
┌───────────────────────────────────────────────────────────────┐
│                    语义理解流程                                │
├───────────────────────────────────────────────────────────────┤
│                                                               │
│  源代码 ──▶ LLM意图分析 ──▶ 结构化意图描述                    │
│               │                                               │
│               ▼                                               │
│         向量嵌入(1536维) ──▶ pgvector存储                     │
│               │                                               │
│               ▼                                               │
│         相似度检索 ◀── 自然语言查询                           │
│                                                               │
└───────────────────────────────────────────────────────────────┘
```

**核心能力**:

1. **方法意图分析** (IntentAnalyzer)
   - 输入：方法源代码
   - 输出：结构化意图描述
     - 用途描述 (purpose)
     - 输入参数含义 (inputs)
     - 返回值含义 (outputs)
     - 副作用 (sideEffects)
     - 错误场景 (errorConditions)

2. **语义数据存储**
   - `code_nodes`: 存储代码元素（类、方法、字段）的语义信息
   - `code_relations`: 存储代码关系（调用、继承、依赖）
   - `code_embeddings`: 存储向量嵌入（1536维，OpenAI兼容）
   - `exception_paths`: 存储异常传播路径

3. **向量相似度检索**
   - 支持自然语言查询代码
   - 基于pgvector的余弦相似度计算
   - IVFFlat索引加速检索

---

#### 🆕 功能三：实时诊断反馈

**问题背景**: 传统诊断过程黑盒，用户无法感知进度。

**解决方案**: WebSocket实时推送Agent执行状态。

```
┌────────────┐     WebSocket      ┌────────────┐
│   后端      │ ═════════════════▶ │   前端      │
│            │                    │            │
│ Agent执行  │  AGENT_STARTED     │ 显示Agent卡片
│            │  ────────────────▶ │            │
│            │                    │            │
│ 进度更新   │  AGENT_PROGRESS    │ 更新进度条
│            │  ────────────────▶ │ (45%)      │
│            │                    │            │
│ 执行完成   │  AGENT_COMPLETED   │ 展示结果
│            │  ────────────────▶ │            │
└────────────┘                    └────────────┘
```

**事件类型**:

| 事件类型 | 说明 | 前端响应 |
|---------|------|---------|
| REQUEST_RECEIVED | 诊断请求已接收 | 显示等待状态 |
| ORCHESTRATION_START | 编排开始 | 初始化诊断面板 |
| AGENT_STARTED | Agent开始执行 | 显示Agent卡片 |
| AGENT_PROGRESS | 执行进度更新 | 更新进度条 |
| AGENT_COMPLETED | Agent执行完成 | 展示Agent结果 |
| AGENT_FAILED | Agent执行失败 | 显示错误信息 |
| AGENT_SKIPPED | Agent被跳过 | 灰色标记 |
| ORCHESTRATION_END | 编排结束 | 汇总展示 |
| FINAL_RESULT | 最终诊断结论 | 展示综合结论 |

---

## 三、API 变更

### 3.1 新增 API

#### 诊断 API

```
POST /api/diagnosis/analyze
描述: 同步诊断（阻塞等待结果）
请求体:
{
  "projectPath": "/path/to/project",
  "errorMessage": "NullPointerException at UserService.java:123",
  "stackTrace": "java.lang.NullPointerException\n\tat com.example...",
  "logContent": "可选：相关日志内容"
}
响应:
{
  "requestId": "uuid-xxx",
  "primaryConclusion": "空指针异常：userService未注入",
  "primaryRootCause": "在UserService.login()第123行访问了null对象",
  "primaryConfidence": 0.92,
  "combinedFixSuggestions": [
    "检查userService是否正确注入",
    "添加null检查: if (userService != null) { ... }"
  ],
  "agentResults": [...]
}
```

```
POST /api/diagnosis/analyze/async
描述: 异步诊断（WebSocket推送结果）
请求体: 同上
响应:
{
  "requestId": "uuid-xxx",
  "status": "PROCESSING",
  "webSocketUrl": "/ws/diagnosis"
}
```

```
GET /api/diagnosis/agents
描述: 获取已注册的Agent列表
响应:
{
  "agents": [
    {"type": "STACK_TRACE", "name": "堆栈解析 Agent", "priority": 10},
    {"type": "CODE_CONTEXT", "name": "代码上下文 Agent", "priority": 20}
  ]
}
```

### 3.2 WebSocket 端点

```
端点: /ws/diagnosis
协议: WebSocket

客户端发送:
{
  "action": "subscribe",
  "requestId": "uuid-xxx"
}

服务端推送:
{
  "requestId": "uuid-xxx",
  "eventType": "AGENT_PROGRESS",
  "agentType": "STACK_TRACE",
  "message": "正在解析堆栈信息...",
  "progress": 45,
  "timestamp": "2026-04-04T14:00:00"
}
```

---

## 四、数据库变更

### 4.1 新增表

#### code_nodes（代码节点表）

| 字段 | 类型 | 说明 |
|-----|------|------|
| id | UUID | 主键 |
| type | VARCHAR(20) | 节点类型：CLASS/METHOD/FIELD/EXCEPTION/ANNOTATION |
| name | VARCHAR(255) | 简单名称 |
| full_name | VARCHAR(500) | 全限定名 |
| source_code | TEXT | 源代码 |
| intent | TEXT | LLM分析的意图 |
| embedding | vector(1536) | 语义向量嵌入 |
| file_path | VARCHAR(512) | 文件路径 |
| metadata | JSONB | 扩展元数据 |

#### code_relations（代码关系表）

| 字段 | 类型 | 说明 |
|-----|------|------|
| id | UUID | 主键 |
| source_id | UUID | 源节点ID |
| target_id | UUID | 目标节点ID |
| type | VARCHAR(50) | 关系类型：CALLS/IMPLEMENTS/EXTENDS/THROWS |
| weight | FLOAT | 关系权重 |

#### code_embeddings（向量嵌入表）

| 字段 | 类型 | 说明 |
|-----|------|------|
| id | UUID | 主键 |
| node_id | UUID | 关联节点ID |
| embedding | vector(1536) | 向量嵌入 |
| embedding_model | VARCHAR | 嵌入模型名称 |

#### exception_paths（异常路径表）

| 字段 | 类型 | 说明 |
|-----|------|------|
| id | UUID | 主键 |
| exception_type | VARCHAR(255) | 异常类型 |
| propagation_path | JSONB | 传播路径 |
| cause_pattern | VARCHAR(100) | 根因模式 |

### 4.2 索引优化

- `idx_code_nodes_embedding`: 向量索引（IVFFlat，余弦相似度）
- `idx_code_nodes_type`: 节点类型索引
- `idx_code_relations_source/target`: 关系查询索引

---

## 五、前端变更

### 5.1 新增页面

#### 智能诊断页面 (`/diagnostic`)

```
┌─────────────────────────────────────────────────────────────┐
│  🔍 智能诊断                                                 │
├─────────────────────────────────────────────────────────────┤
│                                                             │
│  问题描述:                                                   │
│  ┌─────────────────────────────────────────────────────┐   │
│  │ NullPointerException at UserService.login()         │   │
│  └─────────────────────────────────────────────────────┘   │
│                                                             │
│  堆栈信息: (可选)                                            │
│  ┌─────────────────────────────────────────────────────┐   │
│  │ java.lang.NullPointerException                      │   │
│  │   at com.example.service.UserService.login()        │   │
│  │   at com.example.controller.AuthController.login()  │   │
│  └─────────────────────────────────────────────────────┘   │
│                                                             │
│  [ 开始诊断 ]                                               │
│                                                             │
├─────────────────────────────────────────────────────────────┤
│  诊断进度:                                                  │
│  ┌─────────────────────────────────────────────────────┐   │
│  │ ✓ 堆栈解析 Agent ━━━━━━━━━━━━━━━━━━━━ 100%          │   │
│  │   置信度: 0.92 | 耗时: 125ms                        │   │
│  │                                                     │   │
│  │ ○ 代码上下文 Agent ━━━━━━━━━░░░░░░░░ 45%           │   │
│  │   正在分析相关代码...                               │   │
│  └─────────────────────────────────────────────────────┘   │
│                                                             │
├─────────────────────────────────────────────────────────────┤
│  诊断结论:                                                  │
│  ┌─────────────────────────────────────────────────────┐   │
│  │ 🎯 根因分析:                                        │   │
│  │ 在 UserService.login() 第123行访问了 null 对象      │   │
│  │                                                     │   │
│  │ 💡 修复建议:                                        │   │
│  │ 1. 检查 userService 是否正确注入                   │   │
│  │ 2. 添加 null 检查: if (userService != null) {...}  │   │
│  │ 3. 使用 Optional 避免空指针                        │   │
│  └─────────────────────────────────────────────────────┘   │
│                                                             │
└─────────────────────────────────────────────────────────────┘
```

#### 语义搜索页面 (`/search`)

```
┌─────────────────────────────────────────────────────────────┐
│  🔎 语义搜索                                                 │
├─────────────────────────────────────────────────────────────┤
│                                                             │
│  搜索: [处理用户登录的方法                    ] [搜索]       │
│                                                             │
│  过滤: 类型 [全部 ▼] 语言 [Java ▼] 相关度 [>0.7]           │
│                                                             │
├─────────────────────────────────────────────────────────────┤
│  搜索结果 (3):                                              │
│                                                             │
│  ┌─────────────────────────────────────────────────────┐   │
│  │ UserService.login()                    相关度: 0.95 │   │
│  │ 处理用户登录验证，生成JWT令牌                       │   │
│  │ src/main/java/com/example/service/UserService.java │   │
│  │ [查看代码] [分析意图]                               │   │
│  └─────────────────────────────────────────────────────┘   │
│                                                             │
│  ┌─────────────────────────────────────────────────────┐   │
│  │ AuthController.login()                 相关度: 0.87 │   │
│  │ 登录接口入口，调用UserService处理登录请求           │   │
│  │ src/main/java/com/example/controller/AuthController│   │
│  └─────────────────────────────────────────────────────┘   │
│                                                             │
└─────────────────────────────────────────────────────────────┘
```

### 5.2 新增路由

| 路由 | 组件 | 说明 |
|-----|------|------|
| `/diagnostic` | DiagnosticView | AI智能诊断入口 |
| `/search` | SemanticSearchView | 语义搜索入口 |

---

## 六、技术架构变更

### 6.1 后端架构

```
新增模块:
com.huawei.hisi.agent/
├── DiagnosticAgent.java          # Agent接口
├── impl/
│   └── StackTraceAgent.java      # 堆栈解析Agent
├── orchestrator/
│   └── AgentOrchestrator.java    # 编排服务
├── event/
│   └── AgentEventPublisher.java  # WebSocket推送
├── controller/
│   └── DiagnosisController.java  # REST API
└── model/
    ├── AgentContext.java         # 上下文
    ├── AgentResult.java          # 结果
    └── AgentEvent.java           # 事件

com.huawei.hisi.repository/
├── CodeNodeRepository.java       # 代码节点存储
├── CodeRelationRepository.java   # 代码关系存储
└── CodeEmbeddingRepository.java  # 向量嵌入存储

com.huawei.hisi.service/
└── IntentAnalyzerService.java    # 意图分析服务
```

### 6.2 依赖变更

```xml
<!-- 新增依赖 -->
<!-- pgvector (已包含在PostgreSQL驱动中) -->

<!-- 已有依赖复用 -->
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-websocket</artifactId>
</dependency>
```

### 6.3 配置变更

```yaml
# application.yml 新增配置
diagnosis:
  agent:
    confidence-threshold: 0.3      # 置信度阈值
    execution-timeout: 30000       # 执行超时(ms)
    max-concurrent-agents: 4       # 最大并发数
```

---

## 七、升级指南

### 7.1 数据库升级

```bash
# Flyway 自动执行迁移
mvn flyway:migrate

# 或手动执行
psql -d hisi_devtool -f V11__create_semantic_tables.sql
psql -d hisi_devtool -f V12__create_code_embeddings_table.sql
```

### 7.2 配置升级

无需额外配置，默认配置即可运行。

### 7.3 兼容性说明

- **向后兼容**: v3.x 所有API保持不变
- **数据库兼容**: 新增表不影响现有数据
- **前端兼容**: 新增路由不影响现有页面

---

## 八、已知限制

| 限制项 | 说明 | 计划解决 |
|-------|------|---------|
| 仅支持Java堆栈解析 | StackTraceAgent仅支持Java异常格式 | v4.2扩展支持其他语言 |
| 需要pgvector扩展 | 语义搜索依赖PostgreSQL pgvector | 保持依赖 |
| 向量维度固定1536 | 与OpenAI embedding维度对齐 | v4.2支持配置 |
| Agent数量有限 | 目前仅实现StackTraceAgent | v4.2增加更多Agent |

---

## 九、后续规划

### v4.2 计划 (Week 3-4)

- [ ] CodeContextAgent - 代码上下文关联分析
- [ ] GitHistoryAgent - 历史提交关联分析
- [ ] 意图识别器 - 自然语言意图识别
- [ ] WebSocket对话 - 多轮对话支持

### v4.3 计划 (Week 5-6)

- [ ] ConsensusAgent - 多Agent结果验证
- [ ] 知识图谱构建 - 代码关系图谱
- [ ] 多轮对话管理 - 上下文追踪

### v5.0 愿景

- 完整的AI开发者效能平台
- 自然语言驱动的全流程诊断
- 多项目知识迁移
- IDE深度集成

---

## 十、贡献者

| 角色 | 成员 | 贡献 |
|-----|------|------|
| 后端开发 | backend-dev-1 | 语义数据模型、IntentAnalyzer服务 |
| 后端开发 | backend-dev-2 | Agent框架、编排服务、WebSocket推送 |
| 前端开发 | frontend-dev-1 | 诊断面板、语义搜索界面 |
| 测试工程师 | tester | 333个测试用例、覆盖率报告 |
| 代码提交 | committer | 代码审查、版本管理 |

---

**文档版本**: v1.0
**更新日期**: 2026-04-04
**文档状态**: 正式发布