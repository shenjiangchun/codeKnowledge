# ReFlAct 架构总体评估与优化路线图

**项目**: hisi_dev_tool v5.0  
**审计日期**: 2026-07-17  
**审计范围**: RAM Chat / 需求分析大师 / 异常日志分析 / 异常修复对话流  
**方法论**: Agentic Engineering (ReAct / ReflAct / SR-SAM / APEX)  
**代码审查验证**: 2026-07-17 完成 — 15/17 项验证通过 (88%)，6 项遗漏发现，工期已校准


## 附录 A: 代码审查验证报告 (2026-07-17)

> 由独立 code-reviewer 和 architect Agent 交叉验证。完整报告见 agent 输出。

### A.1 验证统计

| 结果 | 数量 |
|------|------|
| 准确 | 15 |
| 部分准确 | 2 |
| 不准确 | 0 |
| **准确率** | **88%** |

### A.2 审查发现的新问题 (审计遗漏)

| ID | 严重程度 | 发现 |
|----|---------|------|
| **M1** | **HIGH** | `AnthropicHttpClient.stream()` 丢弃 SSE 流中的 `message_start`/`message_delta` 事件，这些事件携带 `usage.input_tokens`/`usage.output_tokens`。这是 `cumulativeTokens` 恒为 0 的**根因**——不是没人读字段，而是 token 数据从未从 API 响应中提取。**P2-5 成本追踪的前提是修复此问题**。 |
| M2 | MEDIUM | `SendOptions.systemPrompt` 是 `String` 类型，但 Anthropic Prompt Caching 要求结构化 content blocks `[{type: "text", text: "...", cache_control: {...}}]`。启用缓存需要修改 `SendOptions` API 签名，影响所有调用方（`RamClaudeJsonClient` 的 4 个重载 + `ClaudeSessionServiceImpl` + `ChatContextBuilder`）。 |
| M3 | MEDIUM | `CircuitBreaker`（ram/safety/CircuitBreaker.java）没有状态机（CLOSED/OPEN/HALF_OPEN）、无滑动窗口、无自动恢复。它本质是 `(SessionStats, int) -> Decision` 的策略评估器，不是真正的熔断器。即使接入 DagExecutor，也只能评估前置条件，无法防护级联故障。 |
| M4 | MEDIUM | `LogAnalysisWebSocketHandler` 同一 reportId 重连时，旧 session 未被关闭，`reportIdByWsId` 留下孤立条目。 |
| M5 | MEDIUM | FixFlowRunner Step 2 (kg_search) 不仅是占位符，而是**完全无操作**——输入和输出完全相同。即使 KG 不可用，也没有 fallback 到 `sigToFilePath()`。 |
| M6 | MEDIUM | 系统缺少统一 trace ID：`turnId`(UUID)、`sessionId`(Long/String 混用)、`reportId`(String) 各自独立，无跨层追踪能力。 |

### A.3 工期校准

| 项目 | 审计原估 | 审查校准 | 原因 |
|------|---------|---------|------|
| P0-1 HITL gate | 1d | **2-3d** | 应使用 DagExecutor 状态机模式（非阻塞 await），需重构 FixFlowRunner 为可恢复状态机 |
| P0-3 Prompt Caching | 4h | **4-8h** | SendOptions API 需从 String → 结构化 blocks；全链路 L1+L2+L3 需 8-12h |
| P0-4 Checkpoint summary | 1h | **0.5h** | 比预估更简单 |
| P1-4 删除 Phase2V2 | 2h | **4-6h** | 需清理 RamPhase2V2Controller 端点 + 前端影响评估 + 整个 phase2v2 包 |
| P2-1 FixAgent ReAct | 3d | **5-8d** | 这是完整重写（不是升级），需接入 RamClaudeJsonClient 流式+工具调用管线 |
| P2-5 成本追踪 | 1d | **2d** | 需先修复 M1（AnthropicHttpClient 解析 usage） |

### A.4 缺失前提条件

| 目标项 | 必须先完成 |
|--------|-----------|
| P0-3 (Prompt Caching) | 决定 SendOptions API 变更方案（结构化 blocks 还是在 HttpClient 层拆分） |
| P1-4 (删除 Phase2V2) | 验证 RamChatOrchestrator 覆盖所有 Phase2V2 场景；检查前端 `/api/ram/status/phase2/v2/` 调用 |
| P2-1 (ReAct 升级) | P1-2 (KG 集成 Step 2) + P1-3 (工具超时) + FixAgent DI 重构（注入 WorktreeService/MavenExecutor/RamClaudeJsonClient） |
| P0-1 (HITL gate) | FixSession model 扩展（confirmationState + confirmedBy 字段） |

---

## 目录

1. [总体评分概览](#1-总体评分概览)
2. [各 Agent 一句话诊断](#2-各-agent-一句话诊断)
3. [跨模块共性问题](#3-跨模块共性问题)
4. [Agentic Engineering 成熟度评估](#4-agentic-engineering-成熟度评估)
5. [架构现状 vs 目标状态](#5-架构现状-vs-目标状态)
6. [优先重构路线图 (P0/P1/P2)](#6-优先重构路线图-p0p1p2)
7. [各 Agent 详细重构方案](#7-各-agent-详细重构方案)
8. [风险与迁移策略](#8-风险与迁移策略)

---

## 1. 总体评分概览

```
┌─────────────────────────────────────────────────────────────────┐
│                    Agent 架构评分总览                             │
├────────────────────┬────────┬────────────────────────────────────┤
│ Agent              │ 评分    │ 一句话                              │
├────────────────────┼────────┼────────────────────────────────────┤
│ RAM Chat           │ 67/100 │ 基础扎实但成本失控、上下文粗放       │
│ 需求分析大师        │ 65/100 │ DAG+HITL 设计好，但半成品代码留隐患  │
│ 异常日志分析        │ 70/100 │ DAG 模型正确，安全合规待补           │
│ 异常修复对话流      │ 40/100 │ 架构性缺陷，不可用于生产             │
├────────────────────┼────────┼────────────────────────────────────┤
│ 加权平均            │ 58/100 │ 整体处于"原型可用，生产需重构"状态   │
└────────────────────┴────────┴────────────────────────────────────┘
```

### 各维度雷达图数据

| 维度 | RAM Chat | 需求分析 | 日志分析 | 异常修复 | 平均 |
|------|----------|----------|----------|----------|------|
| 流程编排合理性 | 7 | 7 | 8 | 4 | 6.5 |
| Agent 智能程度 | 6 | 5 | 7 | 2 | 5.0 |
| 上下文/知识管理 | 5 | 7 | 6 | 2 | 5.0 |
| 安全设计 | 7 | 8 | 3 | 3 | 5.3 |
| 可观测性 | 8 | 6 | 6 | 5 | 6.3 |
| 容错/降级策略 | 6 | 6 | 8 | 5 | 6.3 |
| 代码质量/DRY | 7 | 6 | 7 | 3 | 5.8 |
| 成本控制 | 3 | 4 | 5 | 3 | 3.8 |
| 多Agent协作 | 5 | 6 | N/A | N/A | 5.5 |
| Agentic对齐度 | 4 | 7 | 7 | 2 | 5.0 |
| **总分 (加权)** | **67** | **65** | **70** | **40** | **58** |

> **评分解读**: 60+ = 架构方向正确，有明确优化项；50-59 = 有设计缺陷需修复；<50 = 需要架构级重构。三个 Agent 在及格线上方，一个需要根本性重构。

---

## 2. 各 Agent 一句话诊断

### RAM Chat (67/100) — "基础设施完善，运营指标缺失"

**做对了什么**: TurnRegistry 的并发控制（pre-register + compare-and-remove + proxy Disposable）是精心设计的并发模式；事件溯源（append-only event log + WebSocket push）让前端可完整重建状态；路径穿越防护 + 排除列表是成熟的防御性设计。

**做错了什么**: `CacheControl` 三级缓存基础设施已定义但未使用（每次 API 调用都原样发送系统 prompt，浪费 50-90% input token 成本）；checkpoint summary 始终为空字符串；上下文管理靠字符串截断而非语义压缩；`cumulativeTokens`/`costUsdCents` 始终为 0。

**核心矛盾**: 基础设施层投入充足（事件溯源、Turn 生命周期、工具安全），但运营层（成本、上下文、质量）几乎空白。

### 需求分析大师 (65/100) — "DAG 设计好，但半成品代码污染了架构"

**做对了什么**: DAG 编排 + 增量哈希缓存（SHA-256 + Jackson canonical JSON）是优秀的工程实践；HITL（approve/reject/edit）设计评分 8.0/10；Clarify Q&A 历史累积避免重复问相同问题。

**做错了什么**: `CircuitBreaker` 是"僵尸代码"——设计良好但从未接入 `DagExecutor`；Phase2V2Orchestrator 是"假废弃"——标记 `@Deprecated` 但仍在代码中，`orchestrate()` 返回 PENDING 骨架报告（`totalMethodsAnalyzed=0`），`ClaudeChainAnalysisAgent` 从未被调用；DAG 无超时/重试机制。

**核心矛盾**: 优秀的 DAG 设计被两个半成品组件（CircuitBreaker 僵尸、Phase2V2 假废弃）拖累，这些组件增加了认知负担和代码量但没有提供价值。

### 异常日志分析 (70/100) — "四个 Agent 中架构最干净的"

**做对了什么**: DAG 模型选择正确（日志分析是确定性流水线，无需 ReAct 循环）；ClaudeAnalyzeNode 的三轮递进分析（模式识别→因果推理→修复方案）设计深思熟虑；多层降级策略（DAG 级→API 级→Round 级→KG 级）是亮点。

**做错了什么**: WebSocket 无认证（任何人可连接 `/ws/log-analysis?reportId=X` 监听任意报告）；ConcurrentHashMap 内存泄漏风险（客户端异常断开后 session 永不清理）；输出契约是隐式的（`Map<String, Object>` 字符串 key）。

**核心矛盾**: DAG 分析核心设计优秀（可达 8.0+），但外围服务层（WebSocket 安全、事件推送）是明显的短板。

### 异常修复对话流 (40/100) — "架构性缺陷，不可用于生产"

**做对了什么**: 9 步流水线中 7 步是正确的确定性步骤；lenient policy 的意图（不因环境问题误杀修复）是合理的；事件推送 + 多轮对话能力已具备基础。

**做错了什么**: 核心矛盾——用确定性工作流解决探索性问题。FixAgent 是单次 LLM 调用（无工具循环、无自我验证、无错误恢复）；AI 生成的代码直接写入文件系统并 commit（无人工审核）；KG Step 2 是占位符（只原样传参）；测试类命名硬编码为 `ReproTest`（覆盖风险）；方法体提取靠脆弱的字符串解析；~200 行事件构建代码与 RamChatOrchestrator 重复。

**核心矛盾**: SWE-bench 级别的问题（从异常日志到 commit 的端到端自动修复）需要 Agent 循环 + 工具调用 + 从失败中学习的能力。当前实现把这个问题当作确定性流水线处理，预计修复成功率 < 10%。

---

## 3. 跨模块共性问题

### 3.1 重复代码：事件构建逻辑三处复制

```
FixChatService.wsEvent()     ≈  RamChatOrchestrator.wsEvent()
FixChatService.wsPush()      ≈  RamChatOrchestrator (内联)
FixChatService.toJson()      ≈  RamChatOrchestrator.toJson()
FixChatService.user_msg      ≈  RamChatOrchestrator.user_msg
FixChatService.assistant_delta ≈ RamChatOrchestrator.assistant_delta
FixChatService.checkpoint    ≈  RamChatOrchestrator.checkpoint
FixChatService.error         ≈  RamChatOrchestrator.error
```

**影响**: ~200 行重复代码，事件格式变更需要同步修改 3 个地方。  
**根因**: 三者都复用 `RamChatWebSocketHandler` 推送事件，但都自己实现了事件构建。  
**修复**: 提取 `ChatEventPublisher` 统一事件服务（见 §7.1）。

### 3.2 Prompt Caching 基础设施存在但未使用

`CacheControl` 类定义了 `L1_SYSTEM / L2_PROJECT / L3_SESSION` 三级缓存，`CacheBlock.toAnthropicBlock()` 已实现。但 `AnthropicHttpClient` 的消息序列化中完全没有消费它。

**影响**: 每次 API 调用都原样发送系统 prompt + 历史消息。对高频聊天场景，启用缓存可节省 50-90% input token 成本。这是当前项目**成本优化收益最大的单项改动**。

### 3.3 类型安全缺失：Map<String, Object> 满天飞

三个 DAG 相关模块（需求分析、日志分析、异常修复）都使用 `Map<String, Object>` 作为节点间数据传递载体。类型强制转换无编译时保护，节点输出 key 未经文档化。

**影响**: 运行时 `ClassCastException` 风险；新增节点时不知道有哪些 key 可用；IDE 无法提供自动补全。  
**修复**: 定义 record 类型作为节点间契约（见 §7.4）。

### 3.4 工具执行缺少超时和沙箱

所有 Agent 的工具 handler（`hybridSearch`, `Files.walk`, `Files.readString`）都没有超时包装。如果文件系统遍历卡住或 KG 服务不响应，会阻塞线程池直到 JVM 级别的超时。

**影响**: 4 线程固定池 + 无界队列 → 请求洪峰时线程耗尽 → DoS。  
**修复**: `CompletableFuture.orTimeout(30s)` + 有界线程池 + CallerRunsPolicy。

### 3.5 Token 使用量和成本追踪全面缺失

所有 Agent 的 `AgentEvent.cumulativeTokens` 和 `costUsdCents` 字段始终为 0。API 响应的 `usage.input_tokens`/`usage.output_tokens` 未被解析。

**影响**: 无法回答"这个功能花了多少钱"；无法按用户/session 做预算控制；无法评估优化效果。  

---

## 4. Agentic Engineering 成熟度评估

对照 2026 年 Agentic Engineering 最佳实践，评估项目当前成熟度：

| 能力维度 | 当前状态 | 目标状态 | 差距 |
|----------|----------|----------|------|
| **ReAct 循环** | RamChat 有隐式循环（10 轮），无思考记录 | 显式 Thought→Action→Observation，启用 extended thinking | 中 |
| **ReflAct 目标检查** | 无（LLM 自行决定何时停止） | 每轮注入 goal-check prompt | 大 |
| **工具沙箱** | 有路径穿越防护，无超时 | 超时+重试+熔断 | 中 |
| **上下文压缩** | 字符串截断 | LLM 驱动的语义压缩 (RE-TRAC 模式) | 大 |
| **Prompt Caching** | 基础设施就绪，未使用 | L1_SYSTEM + L2_PROJECT + L3_SESSION 全链路启用 | 小（基础设施已就绪） |
| **HITL (人在回路)** | 需求分析有 approve/reject/edit；修复流程无 | 修复流程 Step 6→8 间插入确认 gate | 中 |
| **Plan→Execute Gate** | 无 | 复杂任务先输出计划，用户确认后执行 | 大 |
| **模型路由** | 全部用 default model | 简单问题→Haiku，复杂问题→Sonnet/Opus | 中 |
| **多Agent协作** | Phase2V2 假废弃，无真多Agent | 并行工具调用 + 可选 sub-agent spawn | 中 |
| **审计与追溯** | 事件日志完整但无 diff 审计 | 完整决策日志（含 thinking）+ diff 审计 | 中 |
| **成本可观测** | cumulativeTokens/costUsdCents 始终为 0 | 实时 token 计数 + per-session 成本统计 | 大 |
| **置信度评分** | 无 | Agent 输出置信度评估 | 小 |

**总体成熟度**: **Level 2 / 5**（原型可用，部分生产就绪）  
**目标**: 3 个月内达到 Level 3（生产就绪，核心指标可观测）  
**终极目标**: Level 4（自我优化，成本感知）

---

## 5. 架构现状 vs 目标状态

### 5.1 当前架构

```
┌──────────────────────────────────────────────────────────────┐
│                        CURRENT STATE                          │
├──────────────────────────────────────────────────────────────┤
│                                                               │
│  ┌──────────┐  ┌──────────┐  ┌──────────┐  ┌──────────┐     │
│  │ RAM Chat │  │需求分析大师│  │ 日志分析  │  │ 异常修复  │     │
│  │ 67/100   │  │ 65/100   │  │ 70/100   │  │ 40/100   │     │
│  └────┬─────┘  └────┬─────┘  └────┬─────┘  └────┬─────┘     │
│       │              │              │              │          │
│  ┌────┴──────────────┴──────────────┴──────────────┴────┐    │
│  │              共享层 (现状: 薄)                         │    │
│  │  ┌──────────┐  ┌──────────┐  ┌──────────────────┐    │    │
│  │  │LLM Client│  │KG Client │  │WebSocket Handler │    │    │
│  │  │(无Cache) │  │(正常)    │  │(无认证)          │    │    │
│  │  └──────────┘  └──────────┘  └──────────────────┘    │    │
│  └──────────────────────────────────────────────────────┘    │
│                                                               │
│  问题:                                                        │
│  × 无统一事件服务 → 事件构建代码三处重复                        │
│  × Prompt Caching 未启用 → 成本失控                            │
│  × 无成本追踪 → 无法评估优化效果                               │
│  × 工具执行无超时 → 线程泄露风险                               │
│  × 异常修复无 HITL → 安全红线                                 │
│  × Phase2V2 假废弃 / CircuitBreaker 僵尸代码                  │
└──────────────────────────────────────────────────────────────┘
```

### 5.2 目标架构

```
┌──────────────────────────────────────────────────────────────┐
│                        TARGET STATE                           │
├──────────────────────────────────────────────────────────────┤
│                                                               │
│  ┌──────────┐  ┌──────────┐  ┌──────────┐  ┌──────────┐     │
│  │ RAM Chat │  │需求分析大师│  │ 日志分析  │  │ 异常修复  │     │
│  │ 80+/100  │  │ 75+/100  │  │ 80+/100  │  │ 65+/100  │     │
│  └────┬─────┘  └────┬─────┘  └────┬─────┘  └────┬─────┘     │
│       │              │              │              │          │
│  ┌────┴──────────────┴──────────────┴──────────────┴────┐    │
│  │              共享层 (目标: 厚)                         │    │
│  │  ┌────────────┐ ┌──────────┐ ┌──────────────────┐    │    │
│  │  │ChatEvent   │ │LLM Client│ │Tool Executor     │    │    │
│  │  │Publisher   │ │+Cache    │ │+Timeout+Retry    │    │    │
│  │  │(消除重复)  │ │+Routing  │ │+CircuitBreaker   │    │    │
│  │  └────────────┘ └──────────┘ └──────────────────┘    │    │
│  │  ┌────────────┐ ┌──────────┐ ┌──────────────────┐    │    │
│  │  │Cost Tracker│ │HITL Gate │ │Context Compressor│    │    │
│  │  │(token/$)   │ │(通用组件) │ │(语义压缩)        │    │    │
│  │  └────────────┘ └──────────┘ └──────────────────┘    │    │
│  └──────────────────────────────────────────────────────┘    │
│                                                               │
│  改进:                                                        │
│  ✓ 统一事件发布 → 消除 ~200 行重复代码                         │
│  ✓ Prompt Caching 全链路启用 → 50-90% input token 节省        │
│  ✓ 实时成本追踪 → per-session 统计                            │
│  ✓ 工具执行超时+重试+熔断 → 韧性提升                          │
│  ✓ 异常修复 HITL → 消除安全红线                               │
│  ✓ 僵尸代码清理 → Phase2V2 真删除 / CircuitBreaker 接入        │
│  ✓ FixAgent 升级为 ReAct Agent → 修复成功率从 <10% → 30%+     │
└──────────────────────────────────────────────────────────────┘
```

---

## 6. 优先重构路线图 (P0/P1/P2)

### P0: 安全底线 + 成本止血 (1 周, 必须立即执行)

| # | 修复项 | 影响 Agent | 成本 | 收益 |
|---|--------|-----------|------|------|
| P0-1 | **FixAgent: Step 6→8 间插入 HITL gate** | 异常修复 | 1d | 消除 AI 代码未经审核即 commit 的安全红线 |
| P0-2 | **WebSocket 认证接入** | 日志分析 | 2h | 消除任意用户可监听他人分析报告的安全漏洞 |
| P0-3 | **启用 Prompt Caching** | RAM Chat | 4h | 节省 50-90% input token 成本（基础设施已就绪，仅需消费 CacheControl） |
| P0-4 | **修复 checkpoint summary 为空** | RAM Chat | 1h | 历史上下文从空变为有意义，提升多轮对话质量 |
| P0-5 | **异常修复 extractTestClassName 唯一化** | 异常修复 | 0.5h | 消除测试类覆盖风险 |

**P0 完成后，消除 3 条安全红线，成本降低 50%+。**

### P1: 架构债务清理 (2 周)

| # | 修复项 | 影响 Agent | 成本 | 收益 |
|---|--------|-----------|------|------|
| P1-1 | **提取 ChatEventPublisher 统一事件服务** | RAM Chat + 异常修复 | 2d | 消除 ~200 行重复代码，事件格式变更只需改一处 |
| P1-2 | **KG 集成到 FixFlowRunner Step 2** | 异常修复 | 1d | 方法定位从不可靠字符串解析升级为 KG 精确查询 |
| P1-3 | **工具执行超时 + 有界线程池** | 全部 | 1d | 消除线程耗尽 DoS 风险 |
| P1-4 | **清理 Phase2V2 假废弃代码** | 需求分析 | 2h | 减少认知负担和死代码维护成本 |
| P1-5 | **CircuitBreaker 接入 DagExecutor** | 需求分析 | 2h | 僵尸代码变生产价值 |
| P1-6 | **WebSocket 内存泄漏修复** | 日志分析 | 1h | 消除长期运行后的 OOM 风险 |
| P1-7 | **输出契约 record 化** | 日志分析 + 需求分析 | 1d | 类型安全，IDE 自动补全 |
| P1-8 | **消除 parseProjectPaths 重复** | 日志分析 | 0.5h | 代码质量 |

**P1 完成后，共享层厚度翻倍，代码重复显著减少，架构评分整体 +10 分。**

### P2: 智能升级 (3-4 周)

| # | 修复项 | 影响 Agent | 成本 | 收益 |
|---|--------|-----------|------|------|
| P2-1 | **FixAgent 升级为 ReAct Agent 循环** | 异常修复 | 3d | 修复成功率从 <10% → 30%+ |
| P2-2 | **模型路由 (Haiku for simple, Sonnet for complex)** | RAM Chat | 1d | 进一步降低 30-50% 成本 |
| P2-3 | **上下文语义压缩 (RE-TRAC 模式)** | RAM Chat | 2d | 长对话质量显著提升 |
| P2-4 | **启用 extended thinking + 决策日志** | RAM Chat + 需求分析 | 1d | 可解释性、调试能力大幅提升 |
| P2-5 | **成本追踪 (token usage + per-session 统计)** | 全部 | 1d | 运维可观测性从 0 到 1 |
| P2-6 | **Plan→Execute Gate (轻量级)** | RAM Chat + 需求分析 | 1d | Agentic 对齐度从 4→7 |
| P2-7 | **降级策略可视化 (degradationLevel 字段)** | 日志分析 | 0.5h | 前端可展示分析可信度 |
| P2-8 | **DAG 节点幂等性 + 重试** | 日志分析 + 需求分析 | 1d | 韧性 |

**P2 完成后，整体架构评分达到 75+，核心 Agent 生产就绪。**

### P3: 远期优化 (评估后决定)

| # | 修复项 | 影响 Agent |
|---|--------|-----------|
| P3-1 | 多 Agent 协作框架 (真·sub-agent spawn) | RAM Chat |
| P3-2 | 修复成功率追踪 + 回归检测 | 异常修复 |
| P3-3 | 工具结果 session 级缓存 | RAM Chat |
| P3-4 | Anthropic extended thinking 完整集成 | 全部 |
| P3-5 | Agent 置信度评分 + 前端展示 | 全部 |

---

## 7. 各 Agent 详细重构方案

### 7.1 RAM Chat: 成本止血 + 上下文升级

**目标**: 67 → 80+

#### 重构 1: 消费 CacheControl（P0-3, 4h）

```java
// AnthropicHttpClient.java — 在消息序列化中消费 CacheControl

// 当前 (无缓存):
messages.add(Map.of("role", "system", "content", systemPrompt));

// 目标 (L1_SYSTEM 缓存):
messages.add(Map.of(
    "role", "system",
    "content", List.of(
        Map.of("type", "text", "text", systemPrompt,
               "cache_control", Map.of("type", "ephemeral"))
    )
));

// 对最近 2 轮历史消息标记 L3_SESSION:
for (int i = Math.max(0, messages.size() - 4); i < messages.size(); i++) {
    // 给最后 2 轮 (4条消息: user+assistant × 2) 加 cache_control
    markCacheable(messages.get(i), "ephemeral");
}
```

#### 重构 2: 修复 checkpoint summary（P0-4, 1h）

```java
// RamChatOrchestrator.java:285 — 当前: String summary = "";
// 改为:
String summary = finalText.length() > 200 
    ? finalText.substring(0, 200) + "..." 
    : finalText;
```

#### 重构 3: 提取 ChatEventPublisher（P1-1, 2d）

```java
// 新文件: ChatEventPublisher.java
@Component
public class ChatEventPublisher {
    
    public void publishUserMsg(String sessionId, String turnId, String text) { ... }
    public void publishAssistantDelta(String sessionId, String turnId, String delta) { ... }
    public void publishCheckpoint(String sessionId, String turnId, String summary, String finalText) { ... }
    public void publishError(String sessionId, String turnId, String error) { ... }
    public void publishToolUse(String sessionId, String turnId, String toolName, Map<String,Object> input) { ... }
    public void publishToolResult(String sessionId, String turnId, String toolName, Object result) { ... }
    
    // 内部: 持久化到 AgentEventRepository + WS 推送
}
```

#### 重构 4: 模型路由（P2-2, 1d）

```java
// RamChatOrchestrator.java — 在 runTurnInternal 开始时:
String modelId = selectModel(userMessage);
// ...
private String selectModel(String userText) {
    if (userText.length() < 50 && !containsComplexKeywords(userText))
        return "claude-haiku-4-20250514";  // 简单问题用 Haiku
    return chatProps.defaultModelId();       // 复杂问题用 Sonnet
}
```

#### 重构 5: 工具执行超时 + 有界线程池（P1-3, 1d）

```java
// RamChatOrchestrator.java — asyncExecutor 改为:
new ThreadPoolExecutor(4, 4, 60L, TimeUnit.SECONDS,
    new LinkedBlockingQueue<>(100),
    new ThreadPoolExecutor.CallerRunsPolicy());

// KgToolRegistry — handler 包装:
CompletableFuture.supplyAsync(() -> handler.apply(input))
    .orTimeout(30, TimeUnit.SECONDS)
    .exceptionally(e -> Map.of("error", "Tool timed out: " + e.getMessage()))
    .join();
```

---

### 7.2 需求分析大师: 半成品代码清理 + 韧性加固

**目标**: 65 → 75+

#### 重构 1: 删除 Phase2V2 假废弃代码（P1-4, 2h）

```
删除文件:
  - Phase2V2Orchestrator.java (235 lines)
  - ClaudeChainAnalysisAgent.java (如果只被 Phase2V2 使用)
  - 相关的 Controller 端点 (如果有专门的 Phase2V2 端点)

确认 RamChatOrchestrator 覆盖所有场景后执行删除。
```

#### 重构 2: CircuitBreaker 接入 DagExecutor（P1-5, 2h）

```java
// DagExecutor.java — 在 executeNode 中接入:
private Object executeNode(DagNode node, Map<String, Object> context) {
    return circuitBreaker.execute(() -> {
        try {
            return node.execute(context);
        } catch (Exception e) {
            // 记录失败，circuitBreaker 自动跟踪
            throw e;
        }
    });
}
```

#### 重构 3: DAG 超时 + 重试（P2-8, 1d）

```java
// DagExecutor.java:
private static final Duration NODE_TIMEOUT = Duration.ofSeconds(120);
private static final int MAX_RETRIES = 2;

private Object executeNodeWithRetry(DagNode node, Map<String, Object> context) {
    for (int attempt = 0; attempt <= MAX_RETRIES; attempt++) {
        try {
            return CompletableFuture
                .supplyAsync(() -> node.execute(context))
                .orTimeout(NODE_TIMEOUT.toSeconds(), TimeUnit.SECONDS)
                .join();
        } catch (TimeoutException e) {
            if (attempt == MAX_RETRIES) throw new DagTimeoutException(node.name());
            log.warn("Node {} timeout, retry {}/{}", node.name(), attempt + 1, MAX_RETRIES);
        }
    }
    throw new IllegalStateException("unreachable");
}
```

---

### 7.3 异常日志分析: 安全合规 + 可观测性

**目标**: 70 → 80+

#### 重构 1: WebSocket 认证（P0-2, 2h）

```java
// LogAnalysisWebSocketHandler.java:
@Override
public void afterConnectionEstablished(WebSocketSession session) {
    // 1. 从 session attributes 获取认证用户 (Spring Security 在握手阶段验证)
    String userId = (String) session.getAttributes().get("userId");
    if (userId == null) {
        session.close(CloseStatus.POLICY_VIOLATION);
        return;
    }
    
    // 2. 验证 reportId 属于当前用户
    String reportId = extractReportId(session);
    if (!logAnalysisService.isOwner(reportId, userId)) {
        session.close(CloseStatus.POLICY_VIOLATION);
        return;
    }
    
    sessionByReportId.put(reportId, session);
}
```

#### 重构 2: 定时清理死 session（P1-6, 1h）

```java
@Scheduled(fixedRate = 60000)
public void cleanDeadSessions() {
    sessionByReportId.entrySet().removeIf(e -> {
        WebSocketSession session = e.getValue();
        return session == null || !session.isOpen();
    });
}
```

#### 重构 3: 异步事件推送（P1-6 范围内, 1h）

```java
// LogAnalysisEventEmitter.java:
private final ExecutorService asyncExecutor = Executors.newSingleThreadExecutor();

public void emit(String reportId, EventType type, Map<String, Object> data) {
    asyncExecutor.submit(() -> {
        try {
            wsHandler.pushEvent(reportId, buildEvent(type, data));
        } catch (Exception e) {
            log.error("Failed to push event for report {}", reportId, e);
            // 可选: 缓存到内存队列，待重连后推送
        }
    });
}
```

#### 重构 4: 输出契约 record 化（P1-7, 0.5d）

```java
// 新文件: DagOutputContracts.java
public record ParsedErrorOutput(
    String exceptionType,
    String exceptionMessage,
    String throwPointSig,
    List<KeyFrame> keyFrames,
    Map<String, Object> raw  // 向下兼容
) {}

public record KgSearchOutput(
    List<MatchedMethod> matchedMethods,
    List<String> searchTerms,
    Map<String, Object> raw
) {}

// 各 DAG 节点返回 record 而非 Map<String, Object>
```

---

### 7.4 异常修复对话流: 核心重构

**目标**: 40 → 65+ (根本性重构，不是小修小补)

这是四个 Agent 中唯一需要**架构级重构**的模块。以下方案分两个阶段执行。

> ⚠️ **审查校准 (2026-07-17)**: P2-1 ReAct 升级工期从 3d → 5-8d。这不是升级，是完整重写——FixAgent 当前使用简单的 `LlmClient.chat()` 阻塞调用，目标架构需要接入 `RamClaudeJsonClient` 流式+工具调用管线，涉及 DI 重构、工具定义、Maven 编译超时处理等。

#### 阶段 A: 安全底线（P0, 与 P0 路线图同步）

##### A1: HITL Gate（P0-1, 2-3d）⚠️ 工期已校准

**审查发现**: 原方案使用 `session.awaitConfirmation()` 阻塞等待——这在线程池中执行会导致线程饥饿。应复用 `DagExecutor` 已有的状态机 HITL 模式（设置 `WAITING_HITL` 状态 → 返回 → 通过 `confirmAndResume` 恢复）。

在 FixFlowRunner 的 Step 6 和 Step 8 之间插入 HITL 检查点:

```java
// FixFlowRunner.java — 采用 DagExecutor 的非阻塞 HITL 模式:
case "ai_fix" -> {
    result = step6_aiFix(session, context);
    // 获取 diff 并推送到前端
    String diff = worktreeService.getDiff(session.getBranch());
    chatService.pushCheckpoint(session.getId(), session.getTurnId(),
        "AI generated fix. Please review the diff.", diff);
    
    // 设置 WAITING_CONFIRMATION 状态，返回（不阻塞线程）
    session.setConfirmationState(ConfirmationState.WAITING);
    eventRepo.append(session.getId(), AgentEvent.hitlReq(session.getTurnId(), diff));
    return Map.of("status", "WAITING_CONFIRMATION");
}
case "commit" -> {
    // 仅当用户已确认后才执行 commit
    if (session.getConfirmationState() != ConfirmationState.APPROVED) {
        return Map.of("status", "SKIPPED", "reason", "not confirmed");
    }
    result = step8_commit(session, context);
}

// 新增确认端点 (FixSessionController):
@PostMapping("/{sessionId}/confirm")
public void confirmFix(@PathVariable String sessionId, 
                       @RequestBody ConfirmRequest req) {
    FixSession session = fixService.getSession(sessionId);
    session.setConfirmationState(req.isApproved() ? APPROVED : REJECTED);
    session.setConfirmedBy(getCurrentUser());
    
    if (req.isApproved()) {
        // 从 checkpoint 恢复执行，继续 Step 7-9
        fixFlowRunner.resumeFromCheckpoint(session);
    }
}
```

**前置条件**: FixSession model 需扩展 `confirmationState` 和 `confirmedBy` 字段。FixFlowRunner 需从单体 `run()` 重构为可恢复状态机。

##### A2: 测试类唯一化（P0-5, 0.5h）

```java
// FixFlowRunner.java:590 — 当前硬编码返回 "ReproTest":
private static String extractTestClassName(TestGenInput input) {
    return "FixTest_" + input.getSessionId().substring(0, 8);
}
```

#### 阶段 B: Agent 能力升级（P2-1, 3d）

将 FixAgent 从单次 LLM 调用升级为 ReAct Agent:

```
当前 FixAgent.generateFix():
  1. 加载 prompt 模板
  2. 替换占位符
  3. llm.chat()  // 单次调用
  4. 返回字符串

目标 FixAgent.generateFix():
  1. 加载 prompt 模板 (含 KG 上下文、调用链、项目规范)
  2. ReAct 循环 (max 3 rounds):
     Think: "我需要修改 X 方法来修复 Y 异常"
     Act: write_fix(content)  // 写入 worktree
     Observe: compile_check()  // 编译
     if (编译失败):
       Think: "编译错误在 Line Z: 'variable not found'，需要添加 import"
       Act: write_fix(corrected_content)
       Observe: compile_check()
     if (编译通过):
       Act: run_test("ReproTest")
       Observe: test_result
       if (测试通过):
         break  // 成功
       else:
         Think: "测试失败: expected X but got Y，需要调整修复逻辑"
         // 继续下一轮
  3. 如果 3 轮后仍未通过 → 标记 UNVERIFIED，推送 diff 供人工审核
  4. 返回修复结果 + 置信度
```

```java
// FixAgent.java — 新接口:
public FixResult generateFix(FixContext context) {
    List<Message> messages = buildInitialMessages(context);
    
    for (int round = 0; round < MAX_FIX_ROUNDS; round++) {
        // 1. LLM 生成修复
        String fixCode = llm.chat(systemPrompt, messages);
        
        // 2. 写入 worktree
        worktreeService.writeFix(context.getFilePath(), fixCode);
        
        // 3. 编译检查
        CompileResult compile = worktreeService.compileCheck();
        if (!compile.isSuccess()) {
            // 反馈编译错误给 LLM
            messages.add(buildUserMessage("Compilation failed:\n" + compile.getErrors()));
            continue;
        }
        
        // 4. 运行测试
        TestResult test = worktreeService.runTest(context.getTestClass());
        if (test.isPassed()) {
            return FixResult.success(fixCode, round + 1);
        }
        
        // 反馈测试失败给 LLM
        messages.add(buildUserMessage("Test failed:\n" + test.getFailureMessage()));
    }
    
    return FixResult.unverified(lastFixCode, MAX_FIX_ROUNDS);
}
```

#### 阶段 C: KG 集成（P1-2, 1d）

```java
// FixFlowRunner.java — Step 2 当前是占位符:
case "kg_search" -> {
    // 调用 KgMcpClient.hybridSearch 获取精确方法位置
    List<KgSearchResult> results = kgClient.hybridSearch(
        query = throwPointSig,
        projectPaths = getProjectPaths(session),
        limit = 5
    );
    return Map.of(
        "kgResults", results,
        "methodLocation", results.isEmpty() ? fallbackSigToFilePath(throwPointSig) 
                                            : results.get(0).getFilePath()
    );
}
```

#### 阶段 D: 方法体提取替换（P1-2 范围内）

从脆弱的字符串解析 (`extractMethodBody`, 9 种失效场景) 切换到 KG 方法体查询:

```java
// 替换 FixFlowRunner.extractMethodBody():
private String extractMethodBody(String className, String methodName) {
    // 优先使用 KG
    List<MethodDetail> methods = kgClient.loadMethodBodies(className, methodName, projectPath);
    if (!methods.isEmpty()) {
        return methods.get(0).getBody();
    }
    // Fallback: JavaParser AST 解析
    return javaParserExtractMethodBody(className, methodName);
}
```

---

## 8. 风险与迁移策略

### 8.1 关键风险

| 风险 | 概率 | 影响 | 缓解 |
|------|------|------|------|
| HITL gate 引入后修复流程变慢 | 中 | 用户体验下降 | 异步确认（推送通知 + 30 分钟超时），用户可随时 approve |
| FixAgent ReAct 循环增加 API 调用量 | 高 | 修复成本上升 | 限制最多 3 轮，每轮用 Haiku 做编译错误修复（便宜） |
| 清理 Phase2V2 破坏现有功能 | 低 | 功能回退 | 先确认 RamChat 覆盖所有场景，添加集成测试后再删除 |
| Prompt Caching 引入后系统 prompt 变更不生效 | 低 | 缓存未失效 | 用 system prompt hash 作为 cache key 的一部分 |

### 8.2 推荐迁移顺序

```
Week 1 (P0):  ─────────────────────────────────────────────
  Day 1-2: P0-1 (HITL gate) — FixFlowRunner 状态机重构 + FixSession model 扩展
  Day 2: P0-2 (WebSocket 认证) + P0-4 (checkpoint summary)
  Day 3: P0-3 (Prompt Caching) — SendOptions API 变更 + AnthropicHttpClient 改造
  Day 4: P0-5 (test class naming) + 集成测试 + 全链路回归
  Day 5: 缓冲 + bug fix

Week 2-3 (P1):  ───────────────────────────────────────────
  Week 2: P1-1 (ChatEventPublisher) + P1-6 (WS 内存泄漏+去重) + P1-8 (去重)
  Week 3: P1-2 (KG 集成 Step 2 + 方法体提取替换) + P1-3 (工具超时) 
          + P1-4/5 (Phase2V2 清理 + CircuitBreaker 接入)
          + P1-7 (输出契约 record 化)

Week 4-7 (P2):  ───────────────────────────────────────────
  Week 4-5: P2-1 (FixAgent ReAct 循环) — 5-8d 完整重写
  Week 5-6: P2-2 (模型路由) + P2-3 (语义压缩) + P2-4 (extended thinking)
  Week 6-7: P2-5 (成本追踪, 需先修复 M1) + P2-6 (Plan Gate) + P2-7/8

Week 8+: P3 评估 ─────────────────────────────────────────
  根据 P0-P2 效果决定是否推进 P3
```

### 8.3 回滚策略

- **Prompt Caching**: 只需移除 `cache_control` 字段，不改变 API 行为
- **HITL gate**: 可配置跳过（`fix.auto-approve=true`），紧急情况关闭
- **FixAgent ReAct**: 保留旧的单步调用作为 fallback（`fix.agent-mode=single|react`）
- **Phase2V2 删除**: Git history 保留，如需要可 revert

---

## 9. 总结

### 当前状态

项目整体处于 **"原型可用，生产需重构"** 状态。三个 Agent（RAM Chat、需求分析、日志分析）架构方向正确，有明确的优化路径；一个 Agent（异常修复）需要架构级重构才能达到生产级别。

### 核心发现

1. **最大的成本浪费**: Prompt Caching 基础设施已就绪但未使用，每次 API 调用都多花 50-90% 的 input token 费用。这是**投入产出比最高的单项优化**（4 小时开发 → 持续节省大额成本）。

2. **最大的安全风险**: 异常修复流程中 AI 代码未经审核即 commit。虽然没有自动 push，但工作树中的 commit 后续仍可被推送。**P0 最高优先级**。

3. **最大的架构债务**: FixAgent 的单步 LLM 调用 vs SWE-bench Agent 的多轮工具循环。这是"把探索性问题当确定性问题处理"的根本性架构错误。**需要 3 天重构，但预计修复成功率可从 <10% 提升至 30%+**。

4. **最大的代码债务**: 事件构建逻辑三处复制（~200 行），Phase2V2 假废弃（235 行死代码），CircuitBreaker 僵尸代码。这些不产生价值但增加维护成本的代码**应该在本迭代清理**。

5. **最大的运维盲区**: `cumulativeTokens`/`costUsdCents` 始终为 0。无法回答"哪个功能花了多少钱"这个基本问题。**成本追踪需要在 P2 阶段从零建立**。

### 如果只能做三件事

1. **启用 Prompt Caching** → 立竿见影的成本降低 + 零风险
2. **异常修复加 HITL gate** → 消除最大的安全红线
3. **提取 ChatEventPublisher** → 消除重复代码 + 降低后续变更风险

这三项合计约 3.5 天工作量，投入产出比最高。

---

> **审计方法论**: 本次审计基于 2026 年 Agentic Engineering 最佳实践（ReAct / ReflAct / SR-SAM / APEX / RE-TRAC / Prompt Caching），对照 SWE-bench、OpenHands、Claude Code 等前沿 Agent 系统的设计模式，对项目 4 个核心 Agent 进行了 10 维度交叉评估。审计报告详见 `docs/audit/01-04`。
