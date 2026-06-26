# Phase2 V2 多 Agent 协作架构设计

> **设计日期**: 2026-06-26
> **设计者**: Claude Code + User
> **状态**: 已确认，待实现

---

## 1. 问题背景

### 1.1 当前 Phase2 的缺陷

现有 `Phase2AnalysisNode` 设计为"追问"工具（follow-up question），假设用户已看过 Phase1 初步分析概览。但实际使用中：

| 缺陷 | 影响 |
|------|------|
| 不继承 Phase1 数据 | 缺失 entryPoints、bridgeStats 等宏观视角 |
| 单线程执行 | 无法并行分析多条链路 |
| 仅 KG MCP 工具 | 缺乏代码验证、网络搜索等补充能力 |
| 仅文字输出 | 缺乏调用链流程图、时序图、状态流转图等可视化 |
| 无链路拆分 | 复杂领域问题（如"订单流程"）无法拆分为独立链路分析 |

### 1.2 用户期望

- **继承 Phase1 宏观数据**
- **多 Agent 协作**：编排器拆分 → 子 Agent 端到端执行 → 结果合并
- **丰富可视化**：调用链流程图、时序图、状态流转图（SVG/图片）
- **全工具支持**：KG MCP + Claude SDK 全工具（Read/Grep/Bash/WebFetch/Agent 等）
- **分层报告**：概览层 + 详细层，前端可展开

---

## 2. 核心架构

### 2.1 整体架构图

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                     Phase2 V2 Orchestrator                                   │
│  API: POST /api/ram/status/phase2/v2/start                                   │
│                                                                              │
│  ① 继承 Phase1 checkpoint 数据 (entryPoints, bridgeStats, coreMethods)       │
│  ② KG entryPoints 优先匹配 + grep 补充验证 → 拆分 N 条独立链路                 │
│  ③ 动态分配工具权限给每个 Chain Agent                                          │
│  ④ 并行调度 Chain Agents                                                       │
│  ⑤ 收集结果 → 分层合并报告                                                      │
└─────────────────────────────────────────────────────────────────────────────┘
                                    │
         ┌──────────────────────────┼──────────────────────────┐
         │                          │                          │
         ▼                          ▼                          ▼
┌─────────────────┐          ┌─────────────────┐          ┌─────────────────┐
│  Chain Agent #1 │          │  Chain Agent #2 │          │  Chain Agent #N │
│  订单创建链      │          │  MQ消费链       │          │  状态流转链     │
├─────────────────┤          ├─────────────────┤          ├─────────────────┤
│ 端到端完整能力: │          │ 端到端完整能力: │          │ 端到端完整能力: │
│ • KG MCP 工具   │          │ • KG MCP 工具   │          │ • KG MCP 工具   │
│ • Read/Grep     │          │ • Read/Grep     │          │ • Read/Grep     │
│ • Bash (动态)   │          │ • WebFetch (动态)│          │ • Agent (动态)  │
│ • Artifacts 图表│          │ • Artifacts 图表│          │ • Artifacts 图表│
│                 │          │                 │          │                 │
│ 输出:           │          │ 输出:           │          │ 输出:           │
│ • Markdown 分析 │          │ • Markdown 分析 │          │ • Markdown 分析 │
│ • 调用链流程图   │          │ • 时序图        │          │ • 状态流转图    │
│ • 代码片段+建议 │          │ • 代码片段+建议 │          │ • 代码片段+建议 │
└─────────────────┘          └─────────────────┘          └─────────────────┘
         │                          │                          │
         └──────────────────────────┼──────────────────────────┘
                                    │
                                    ▼
                    ┌─────────────────────────────┐
                    │   Orchestrator Result Merge │
                    │                             │
                    │  第一层: 领域概览报告         │
                    │   • 整体流程图 (合并链路)    │
                    │   • 关键发现汇总            │
                    │   • 跨链路影响分析          │
                    │                             │
                    │  第二层: 详细链路报告        │
                    │   • 每条链路完整分析        │
                    │   • 前端可展开查看          │
                    └─────────────────────────────┘
```

### 2.2 关键组件

| 组件 | 职责 |
|------|------|
| **Phase2V2Controller** | REST API 入口，接收请求，创建 session |
| **Phase2V2Orchestrator** | 核心编排器：继承 Phase1 数据、链路拆分、工具分配、调度、合并 |
| **ChainAnalysisAgent** | 端到端链路分析 Agent，拥有完整分析能力 |
| **DynamicToolRegistry** | 根据链路复杂度动态分配 Claude SDK 工具集 |
| **ResultMerger** | 分层合并：概览层 + 详细层 |
| **ArtifactsGenerator** | 通过 Claude SDK Artifacts 生成 SVG 图表 |

---

## 3. 链路拆分流程

### 3.1 拆分逻辑流程图

```
Phase1 Checkpoint 数据
         │
         ▼
┌─────────────────────────────────────────────────────┐
│ Step 1: KG entryPoints 匹配                         │
│ kgClient.entryPoints(projectPath, "ALL")            │
│ → 获取所有 Controller/MQ/Feign 入口                 │
│ → 根据 question 关键词过滤相关入口点                 │
│ → 每个入口点 → 候选链路起点                          │
└─────────────────────────────────────────────────────┘
         │
         ▼
┌─────────────────────────────────────────────────────┐
│ Step 2: grep 补充验证                               │
│ grep -r "关键词" projectPath                        │
│ → 验证入口点是否真的涉及该领域                       │
│ → 补充遗漏的入口点 (注释/配置文件中的线索)           │
└─────────────────────────────────────────────────────┘
         │
         ▼
┌─────────────────────────────────────────────────────┐
│ Step 3: 完整性判断                                  │
│ 检查:                                               │
│ • 入口点数量是否覆盖问题范围                         │
│ • 是否有明显的缺失链路                              │
│ • KG 数据是否足够支持分析                           │
│                                                     │
│ 判断: 数据完整 → 开始分析                           │
│       数据不足 → 继续搜索或提示用户                 │
└─────────────────────────────────────────────────────┘
         │
         ▼
┌─────────────────────────────────────────────────────┐
│ Step 4: 拆分 Chain Agents                          │
│ 每条链路 → 一个 Chain Agent                         │
│ 并行执行 (CompletableFuture / ExecutorService)     │
└─────────────────────────────────────────────────────┘
```

### 3.2 完整性判断标准

| 维度 | 完整阈值 | 不完整处理 |
|------|---------|-----------|
| 入口点数量 | ≥ 1 个相关入口 | 扩大关键词搜索范围 |
| KG 数据覆盖 | entryPoints + bridgeStats 存在 | 提示用户先运行 Phase1 |
| 代码体可加载 | 核心方法有代码体 | 用 Read 工具补充读取源文件 |

---

## 4. 动态工具权限分配

### 4.1 链路复杂度分级

```java
enum ChainComplexity {
    SIMPLE,          // 单服务单模块链路
    CROSS_SERVICE,   // 跨服务 Feign/MQ 链路
    DOMAIN_ANALYSIS, // 领域级复杂分析
    VERIFICATION     // 需要编译/测试验证
}
```

### 4.2 工具集映射

```java
ToolSet getToolsForComplexity(ChainComplexity complexity) {
    switch (complexity) {
        case SIMPLE:
            return ToolSet.of(
                KG_MCP,      // hybridSearch, calleesTree, affecting, etc.
                Read,        // 读取源文件
                Grep,        // 搜索关键词
                Glob,        // 文件模式匹配
                Artifacts    // SVG 图表生成
            );
            
        case CROSS_SERVICE:
            return ToolSet.of(
                KG_MCP, Read, Grep, Glob, Artifacts,
                WebFetch     // 查询外部服务文档
            );
            
        case DOMAIN_ANALYSIS:
            return ToolSet.of(
                KG_MCP, Read, Grep, Glob, WebFetch, Artifacts,
                Bash         // 执行构建/依赖分析
            );
            
        case VERIFICATION:
            return ToolSet.of(
                KG_MCP, Read, Grep, Glob, WebFetch, Bash, Artifacts,
                Agent        // 嵌套启动子 Agent (仅允许一层)
            );
    }
}
```

### 4.3 复杂度判断逻辑

```java
ChainComplexity inferComplexity(ChainContext chain) {
    // 有 Feign/MQ 桥接点 → CROSS_SERVICE
    if (chain.bridgePoints().stream().anyMatch(b -> 
        "FEIGN".equals(b.bridgeType()) || "MQ".equals(b.bridgeType()))) {
        return ChainComplexity.CROSS_SERVICE;
    }
    
    // 链路节点数 > 10 或 跨多个包 → DOMAIN_ANALYSIS
    if (chain.nodeCount() > 10 || chain.packages().size() > 3) {
        return ChainComplexity.DOMAIN_ANALYSIS;
    }
    
    // 用户问题包含"验证"、"测试"关键词 → VERIFICATION
    if (chain.question().contains("验证") || chain.question().contains("测试")) {
        return ChainComplexity.VERIFICATION;
    }
    
    return ChainComplexity.SIMPLE;
}
```

---

## 5. Chain Agent 输出结构

### 5.1 JSON Schema

```json
{
  "chain_id": "order-create-chain",
  "chain_name": "订单创建链路",
  "entry_point": {
    "type": "Controller",
    "className": "OrderController",
    "methodName": "createOrder",
    "nodeId": "path:com.example.OrderController.createOrder.hash"
  },
  
  "analysis": {
    "summary": "OrderController#createOrder 是订单创建入口，调用 OrderService 进行业务处理...",
    "call_chain_flow": "<svg>...</svg>",  // Claude Artifacts 生成的 SVG
    "sequence_diagram": "<svg>...</svg>",  // 时序图 SVG
    "state_diagram": "<svg>...</svg>",     // 状态流转图 SVG (如适用)
    
    "code_snippets": [
      {
        "nodeId": "...",
        "className": "OrderService",
        "methodName": "createOrder",
        "filePath": "src/main/java/.../OrderService.java",
        "snippet": "public Order createOrder(OrderDTO dto) {...}",
        "relevance": "核心业务逻辑入口"
      }
    ],
    
    "recommendations": [
      {
        "sequence": 1,
        "action": "检查",
        "target": "OrderService#createOrder",
        "reason": "事务边界不清晰，建议加 @Transactional"
      }
    ],
    
    "confidence": {
      "level": "high",
      "kg_coverage": {
        "upstream_complete": true,
        "downstream_complete": true,
        "code_bodies_loaded": 8,
        "missing_info": []
      },
      "limitations": []
    }
  },
  
  // KG 原始数据 (供 Orchestrator 合并)
  "kg_data": {
    "upstream_chains": [...],
    "downstream_chains": [...],
    "method_bodies": [...],
    "bridge_points": [...]
  }
}
```

---

## 6. 分层报告结构

### 6.1 第一层：领域概览

```json
{
  "summary_layer": {
    "domain_overview": "订单处理领域包含 4 条核心链路：订单创建、支付调用、MQ消费回调、状态流转...",
    
    "overall_flow_diagram": "<svg>合并所有链路的整体流程图</svg>",
    
    "key_findings": [
      {
        "id": 1,
        "type": "dependency",
        "description": "订单创建与支付调用存在同步时序依赖",
        "chains": ["order-create-chain", "payment-call-chain"]
      },
      {
        "id": 2,
        "type": "pattern",
        "description": "MQ 消费链路采用重试补偿机制，失败后延迟重试",
        "chains": ["mq-consume-chain"]
      }
    ],
    
    "cross_chain_impacts": [
      {
        "from_chain": "order-create-chain",
        "to_chain": "payment-call-chain",
        "relation": "同步依赖",
        "description": "订单创建后立即调用支付服务"
      },
      {
        "from_chain": "payment-call-chain",
        "to_chain": "mq-consume-chain",
        "relation": "异步触发",
        "description": "支付成功后发送 MQ 消息触发后续处理"
      }
    ],
    
    "overall_recommendations": [
      "建议梳理订单状态机，明确各状态转换触发条件",
      "MQ 消费链路建议增加幂等性校验"
    ]
  }
}
```

### 6.2 第二层：详细链路报告

```json
{
  "detail_layer": {
    "chains": [
      {
        "chain_id": "order-create-chain",
        "chain_name": "订单创建链路",
        "summary": "OrderController → OrderService → OrderRepository",
        "expandable": true,
        "report_ref": "/api/ram/status/phase2/v2/{sid}/chain/order-create-chain/report"
      },
      {
        "chain_id": "mq-consume-chain",
        "chain_name": "MQ消费回调链路",
        "summary": "OrderMQConsumer → OrderProcessor → CompensationService",
        "expandable": true,
        "report_ref": "/api/ram/status/phase2/v2/{sid}/chain/mq-consume-chain/report"
      }
    ],
    
    "chain_count": 4,
    "total_methods_analyzed": 32,
    "total_code_snippets": 15
  }
}
```

---

## 7. API 设计

### 7.1 V2 API 端点

```
POST /api/ram/status/phase2/v2/start
Body: {
  "sessionId": "parent-session-id",   // Phase1 session ID (用于继承数据)
  "question": "订单处理流程是怎样的？支付失败如何处理？"
}
Response: {
  "sessionId": "phase2-v2-xxx",
  "status": "RUNNING",
  "estimatedChains": 4  // 预估拆分链路数量
}

GET /api/ram/status/phase2/v2/{sid}/status
Response: {
  "status": "RUNNING|DONE|FAILED",
  "progress": {
    "chainsTotal": 4,
    "chainsCompleted": 2,
    "currentChain": "payment-call-chain",
    "estimatedTimeRemaining": 60
  }
}

GET /api/ram/status/phase2/v2/{sid}/report
Response: {
  "status": "DONE",
  "report": {
    "summary_layer": {...},
    "detail_layer": {...}
  }
}

GET /api/ram/status/phase2/v2/{sid}/chain/{chainId}/report
Response: {
  "chain_id": "order-create-chain",
  "analysis": {...},  // 单条链路的完整详细报告
  "kg_data": {...}
}
```

### 7.2 与 V1 API 的对比

| 维度 | V1 API (旧) | V2 API (新) |
|------|------------|-------------|
| 入口 | `/phase2/start` | `/phase2/v2/start` |
| 数据来源 | 仅 projectPath | 继承 Phase1 checkpoint |
| 执行模式 | 单 Agent | 多 Agent 并行 |
| 输出 | 单层 Markdown | 分层报告 (概览 + 详细) |
| 图表 | 无 | SVG 流程图/时序图/状态图 |

---

## 8. 前端适配

### 8.1 报告展示组件

```vue
<template>
  <!-- 第一层：概览 -->
  <Phase2V2Summary :report="report.summary_layer" />
  
  <!-- 第二层：详细链路（可展开） -->
  <el-collapse>
    <el-collapse-item v-for="chain in report.detail_layer.chains" :key="chain.chain_id">
      <template #title>
        <span>{{ chain.chain_name }}</span>
        <span class="summary">{{ chain.summary }}</span>
      </template>
      <Phase2V2ChainDetail :chainId="chain.chain_id" :sessionId="sessionId" />
    </el-collapse-item>
  </el-collapse>
</template>
```

### 8.2 SVG 图表渲染

- 使用 `<img src="data:image/svg+xml;base64,...">` 直接嵌入 SVG
- 或使用 `<svg v-html="svgContent">` 动态渲染
- 支持点击节点跳转到代码详情

---

## 9. 实现计划概要

### 9.1 阶段划分

| 阶段 | 内容 | 预估时间 |
|------|------|---------|
| Phase 1 | 创建基础架构：Controller + Orchestrator 框架 | 2 天 |
| Phase 2 | 实现链路拆分逻辑 + 动态工具分配 | 2 天 |
| Phase 3 | 实现 ChainAnalysisAgent 端到端逻辑 | 3 天 |
| Phase 4 | 实现 Artifacts 图表生成 + 分层合并 | 2 天 |
| Phase 5 | 前端适配 + V1/V2 平切验证 | 2 天 |
| Phase 6 | 稳定后移除 V1 API | 1 天 |

### 9.2 关键依赖

- **Claude SDK Artifacts**: 用于生成 SVG 图表
- **KG MCP Server**: 已有，无需改动
- **Phase1 Checkpoint**: 需确保 RamStatusController 正确存储

---

## 10. 风险与缓解

| 险险 | 缓解措施 |
|------|---------|
| Chain Agent 并行执行超时 | 设置 5 分钟超时 + 单链路降级为简化分析 |
| SVG 图表生成失败 | 降级为 Mermaid DSL 文本，前端用 mermaid.js 渲染 |
| 链路拆分不准确 | 提供用户手动指定入口点的选项 |
| 工具权限过大导致误操作 | 动态权限最小化原则，Bash 仅允许安全命令 |

---

## 11. 后续扩展方向

1. **用户自定义分析模式**: 支持通过配置文件定义新的分析流程
2. **嵌套分析**: 复杂链路支持嵌套一层子 Agent
3. **增量分析**: 链路变更后仅分析变化部分
4. **历史对比**: 与上次分析结果对比，标注变化点

---

> **下一步**: 调用 `writing-plans` skill 生成详细实现计划