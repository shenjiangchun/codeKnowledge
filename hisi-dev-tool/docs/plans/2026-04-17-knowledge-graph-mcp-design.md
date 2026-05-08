# 知识图谱 MCP 工具与业务流 Skill 设计方案

## 文档信息
- **创建日期**: 2026-04-17
- **版本**: 1.0
- **状态**: 已确认

## 一、背景与目标

### 1.1 背景
当前项目已具备完整的知识图谱模块（10张数据表、28个查询接口），但 Claude 仅通过提示词输入方式使用，缺乏结构化的能力暴露和业务流固化。

### 1.2 目标
1. 将知识图谱能力转为 MCP 工具，供 Claude 在业务流执行过程中调用
2. 为 4 个关键业务流定制 Skill，固化分析流程
3. 集成向量搜索能力，支持自然语言匹配代码

---

## 二、整体架构

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                              整体架构                                         │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                               │
│  ┌─────────────────┐                                                         │
│  │   Claude Code   │                                                         │
│  │   (AI 助手)     │                                                         │
│  └────────┬────────┘                                                         │
│           │ 调用 MCP Tools                                                    │
│           ▼                                                                   │
│  ┌─────────────────────────────────────────────────────────────────┐         │
│  │                     MCP Server (TypeScript)                      │         │
│  │  ┌──────────────┐ ┌──────────────┐ ┌──────────────┐             │         │
│  │  │ 知识图谱工具  │ │  日志查询工具 │ │  向量搜索工具 │             │         │
│  │  └──────┬───────┘ └──────┬───────┘ └──────┬───────┘             │         │
│  └─────────┼────────────────┼────────────────┼─────────────────────┘         │
│            │                │                │                                │
│            └────────────────┼────────────────┘                                │
│                             ▼                                                 │
│           ┌─────────────────────────────────────────┐                        │
│           │        Spring Boot API (:8080)          │                        │
│           │                                           │                        │
│           │  /api/knowledge-graph/*  (已有)          │                        │
│           │  /api/log/*             (已有)           │                        │
│           │  /api/vector/*          (新增)           │                        │
│           └───────────────┬─────────────────────────┘                        │
│                           │                                                   │
│            ┌──────────────┼──────────────┐                                    │
│            ▼              ▼              ▼                                    │
│  ┌──────────────┐ ┌─────────────┐ ┌─────────────────┐                         │
│  │ PostgreSQL   │ │ 日志平台 API │ │ Python Service  │                         │
│  │ 知识图谱数据  │ │ (已有)      │ │ ChromaDB嵌入式   │                         │
│  │ (10张表)     │ │             │ │ :8001           │                         │
│  └──────────────┘ └─────────────┘ └─────────────────┘                         │
│                                                                               │
└─────────────────────────────────────────────────────────────────────────────┘
```

### 关键设计决策

| 项目 | 决定 |
|------|------|
| **向量搜索** | Python 桥接服务（ChromaDB 本地嵌入式） |
| **向量生成触发** | 手动触发 |
| **MCP 模式** | 知识图谱能力作为 MCP 工具 |
| **日志平台** | 已有 API，需要对接 |

---

## 三、MCP 工具设计

### 3.1 工具清单 (共 29 个工具)

```
【图谱管理】
├── kg_generate          # 同步生成知识图谱
├── kg_status            # 获取图谱状态
└── kg_task_status       # 查询任务状态

【方法查询】
├── kg_callers           # 查询调用者
├── kg_callees           # 查询被调用者
├── kg_method_detail     # 查询方法详情
└── kg_method_by_class   # 按类查询方法

【入口点查询】
├── kg_entry_points      # 查询入口点列表
├── kg_call_chain_by_key # 按入口键查询调用链
└── kg_call_chain_by_type# 按入口类型查询调用链

【调用链分析】
├── kg_downstream        # 向下调用链追踪
├── kg_call_chain_graph  # DAG 图数据
├── kg_affecting         # 影响分析（向上追踪）
└── kg_bridges           # 方法桥接信息

【接口实现】
├── kg_implementations   # 查询接口实现
└── kg_interfaces        # 查询所有接口

【环检测】
└── kg_cycles_detect     # 检测调用环

【MyBatis】
├── kg_mybatis_mappers   # Mapper 列表
├── kg_mybatis_sql       # SQL 列表
└── kg_mapper_sql        # Mapper 的 SQL

【桥接查询】
├── kg_feign_chain       # Feign 调用链
├── kg_mq_chain          # MQ 调用链
└── kg_bridge_stats      # 桥接统计

【向量搜索】(新增)
├── vector_search        # 向量相似度搜索
├── vector_sync          # 手动触发向量化
└── vector_status        # 向量库状态

【日志查询】
└── log_query            # DSL 日志查询
```

---

## 四、Skill 设计

### 4.1 Skill 1: git-code-review (Git 提交代码审查)

**触发**: `/git-review`
**输入**: commit SHA 或 commit 范围

**执行流程**:
```
获取变更文件列表 → 变更文件解析 → 知识图谱关联 → 生成审查报告
```

**MCP Tools 调用**:
- `kg_method_detail` - 获取变更方法详情
- `kg_affecting` - 查询受影响的上游调用
- `kg_downstream` - 查询影响的下游调用
- `kg_cycles_detect` - 检测是否有环影响

**输出**:
- 影响范围分析
- 测试回归范围
- 技术规范检查
- 业务流变化

---

### 4.2 Skill 2: error-log-analysis (错误日志分析)

**触发**: `/error-analysis`
**输入**: 错误日志内容（含时间戳、容器、堆栈）

**执行流程**:
```
解析错误日志 → 定位错误代码 → 查询完整日志 → 生成根因分析
```

**MCP Tools 调用**:
- `kg_callers` - 谁调用了报错方法
- `kg_method_detail` - 获取方法详情、异常声明
- `kg_call_chain_by_key` - 查询调用链上下文
- `log_query` - DSL查询完整日志

**输出**:
- 错误位置定位
- 调用链上下文
- 相关变量/参数
- 根因分析

---

### 4.3 Skill 3: interface-flow-analysis (接口业务流分析)

**触发**: `/interface-flow`
**输入**: 接口 URI 或方法签名

**执行流程**:
```
查询入口点 → 构建调用链图 → 输出业务流逻辑描述
```

**MCP Tools 调用**:
- `kg_entry_points` - 查询入口点
- `kg_call_chain_graph` - 获取DAG图
- `kg_method_detail` - 每个节点详情
- `kg_bridges` - 桥接调用信息

**输出**: 业务流程文档

**用户下一步选择**:
- A. 测试代码生成（补完API/单元测试）
- B. 安全重构（分步执行+确认）

**分支 B: 安全重构流程**:
```
生成完整单测 → 运行单测确认 → 执行重构步骤(需人工确认) → 单测回归验证 → 循环直到完成
```

**重构目标**:
- 提高可读性
- 去除性能风险
- 去除安全风险
- 提高可靠性

---

### 4.4 Skill 4: tech-design (技术方案设计)

**触发**: `/tech-design`
**输入**: 需求描述（自然语言）

**执行流程**:
```
需求分析 → 向量搜索 → 关联代码定位 → 方案设计
```

**MCP Tools 调用**:
- `vector_search` - 向量搜索相关代码
- `kg_method_detail` - 获取匹配方法的详情
- `kg_call_chain_graph` - 分析调用链
- `kg_entry_points` - 确定入口点
- `kg_affecting` - 分析影响范围

**输出**: 技术方案文档

---

## 五、Python 桥接服务设计

### 5.1 服务配置
- **服务名**: hisi-vector-service
- **端口**: 8001
- **框架**: Flask

### 5.2 API 接口

| 接口 | 方法 | 说明 |
|------|------|------|
| `/api/vector/search` | POST | 向量相似度搜索 |
| `/api/vector/sync` | POST | 手动触发向量化 |
| `/api/vector/status` | GET | 向量库状态 |
| `/api/vector/add` | POST | 添加向量 |
| `/api/vector/delete` | DELETE | 删除向量 |

### 5.3 向量生成策略

**选项1: 本地模型（免费）**
```python
from sentence_transformers import SentenceTransformer
model = SentenceTransformer('paraphrase-multilingual-MiniLM-L12-v2')
```

**选项2: OpenAI API（付费，效果更好）**
```python
from openai import OpenAI
response = client.embeddings.create(
    model="text-embedding-3-small",
    input="用户登录相关的代码"
)
```

### 5.4 方法描述生成

**模板方式（低成本）**:
```
"{className}类的{methodName}方法，功能：{从注释或方法名推断}"
```

**LLM方式（高成本，效果更好）**:
```
"分析以下代码的功能并用一句话描述: {methodBody}"
```

---

## 六、文件修改清单

### 6.1 后端新增文件

```
hisi-dev-tool/src/main/java/com/huawei/hisi/
├── vectorstore/
│   ├── controller/
│   │   └── VectorStoreController.java
│   ├── service/
│   │   ├── VectorStoreService.java
│   │   └── impl/VectorStoreServiceImpl.java
│   └── model/
│       ├── VectorSearchRequest.java
│       ├── VectorSearchResponse.java
│       └── VectorSyncRequest.java
└── config/
    └── VectorStoreConfig.java
```

### 6.2 Python 服务（新建项目）

```
hisi-vector-service/
├── app.py
├── requirements.txt
├── config.py
├── services/
│   ├── chroma_service.py
│   └── embedding_service.py
└── Dockerfile
```

### 6.3 MCP Server（新建项目）

```
hisi-mcp-server/
├── src/
│   ├── index.ts
│   ├── tools/
│   │   ├── knowledgeGraphTools.ts
│   │   ├── vectorTools.ts
│   │   └── logTools.ts
│   └── client/
│       └── apiClient.ts
├── package.json
└── tsconfig.json
```

### 6.4 Skill 定义文件

```
.claude/skills/
├── git-code-review.md
├── error-log-analysis.md
├── interface-flow-analysis.md
└── tech-design.md
```

---

## 七、实施计划

| 阶段 | 内容 | 预计工作量 |
|------|------|-----------|
| 阶段1 | 基础设施搭建 | 2-3 天 |
| 阶段2 | MCP 工具实现 | 2-3 天 |
| 阶段3 | Skill 实现 | 3-4 天 |
| 阶段4 | 测试与文档 | 1-2 天 |

---

## 八、验收标准

1. MCP Server 能正确暴露 29 个工具
2. 4 个 Skill 能按流程执行
3. 向量搜索支持中文自然语言查询
4. 安全重构流程支持分步确认
5. 所有功能有单元测试覆盖
