# Spring AI 1.1.7 全量迁移与架构合并

## Why

项目当前维护了 4 套独立的 LLM HTTP 客户端实现（`UnifiedTextService` 645行 RestTemplate、`AnthropicHttpClient` OkHttp SSE、`ApmClaudeLlmClient` RestTemplate+Semaphore、`ClaudeChatController` HttpURLConnection SSE），7 个独立 WebSocket 端点，3 份重复的事件构建代码（~400 行）。审计评分 58/100，FixFlow 仅 40 分（架构性缺陷：单次 LLM 调用无 ReAct 循环）。引入 Spring AI 1.1.7 统一 LLM 调用层，消除 ~1,500 行基础设施代码，为 9 个 Agent 类型提供声明式配置化扩展能力，将 FixFlowRunner 从 9 步硬编码流水线升级为 5 步 ReAct Agent 循环。

## What Changes

- **BREAKING**: REST Agent 端点路径变更 — `POST /api/claude/universal-chat` 等 6 组 33 个端点合并为 `POST /api/chat/{agentType}`（9 个 agentType）；SSE 事件格式切换为 Spring AI 原生 OpenAI-compatible 格式
- **BREAKING**: WebSocket 协议变更 — 7 个独立 WS 端点中 6 个合并为 `WS /ws/agent`（6 频道统一协议），保留 `/ws/terminal`
- 删除自研 LLM 客户端：`AnthropicHttpClient`、`ApmClaudeLlmClient`、`LlmClient`、`ClaudeChatController` SSE 手写部分、`UnifiedTextService` HTTP 逻辑（保留 prompt 模板）
- 删除 ram/sdk 全包（8 文件，~800 行）—— 由 Spring AI `ChatClient` + `@Tool` + `ToolContext` 替代
- 新增 `AgentChatController`（统一 Agent REST API）+ `AgentRegistry`（YAML 声明式 Agent 配置）+ `ChatEventPublisher`（Advisor 形态统一事件推送）
- 新建 `AgentTools` Bean（`@Tool` 注解替代 `KgToolRegistry.run()` 注册方式，`projectPath` 通过 ToolContext 注入）
- FixFlowRunner 9 步→5 步，ai_fix 升级为 ReAct Agent 循环（ToolCallingAdvisor，max 5 轮），HITL gate 采用 ReAct 结束后一次性 diff+approve
- 前端新增 `useChatStream` composable + `useAgentWebSocket` composable，统一 `ChatRequest`/`ServerMessage` 类型
- AgentOrchestrator + DiagnosticAgent 接口完整保留，仅替换内部 LLM 调用
- ZhipuService KG 批处理底层切 `ChatClient`（OpenAI compat），保留 `TokenBucketRateLimiter`
- Spring Boot 版本保持 3.2.0 + Java 17，不升级到 Boot 4

## Capabilities

### New Capabilities

- `unified-agent-chat`: 统一 Agent REST API — `POST /api/chat/{agentType}` 替代 33 个碎片端点，YAML 声明式 Agent 配置，Spring AI 原生 SSE 格式
- `unified-websocket-gateway`: 统一 WebSocket 网关 — `WS /ws/agent` 6 频道协议，全局 seq + per-channel lastSeq 断线重连，统一 JWT 认证
- `agent-tools-registry`: @Tool 注解工具注册 — `ToolContext` 注入 projectPath，替代 `KgToolRegistry` 的动态 ToolDefinition 构建
- `reactive-fix-flow`: ReAct 修复流程 — 5 步流水线（原 9 步），ai_fix 最大 5 轮工具调用（writeFix → compileCheck → runTests），ReAct 结束后一次性 HITL diff+approve

### Modified Capabilities

无现有 spec 变更。

## Impact

- 后端: 删除 ~1,500 行（AnthropicHttpClient/ApmClaudeLlmClient/LlmClient/ram-sdk/Phase2V2），新增 ~500 行（AgentChatController/AgentRegistry/ChatEventPublisher/AgentTools/UnifiedWSHandler）
- 前端: ~400 行重构（新增 useChatStream/useAgentWS composable，6 个旧 WS composable 替换，naturalLanguageStore 重构，types/chat.ts 新增）
- 依赖: 新增 `spring-ai-bom 1.1.7` + `spring-ai-starter-model-anthropic` + `spring-ai-starter-model-openai`；OkHttp/Reactor 保留（有非 LLM 消费者）
- 审计整合: P0-3 Prompt Caching → 0h（Spring AI 自动）；P0-1 HITL gate 并入 Step 9；P2-1 FixAgent ReAct 5-8d→1-2d；合计节省 ~12 人天

---

# 需求与代码事实简报

## 意图

### 目标与成功标准
- 目标：用 Spring AI 1.1.7 统一全项目 LLM 调用层，消除 4 套自研 HTTP 客户端，实现 9 个 Agent 类型的声明式配置化，将 FixFlowRunner 升级为 ReAct Agent 循环
- 可观察的成功结果：
  1. `mvn test` 零回归，jaCoCo LINE >= 80% / BRANCH >= 70%
  2. agent-browser 全链路回归 10 页功能正常
  3. 删除 ~1,500 行基础设施代码
  4. 新增一个 Agent 类型仅需 10 行 YAML 配置

### 边界与非目标
- 本次范围：替换 LLM 调用层 + 合并 REST/WS 端点 + FixFlowRunner ReAct 化 + 前端 composable 统一
- 非目标：不升级 Spring Boot 4 / Java 21；不修改 KG / Neo4j / 搜索 / 配置 / Git / 项目等 120+ REST 端点；不替换 AgentOrchestrator 多 Agent 编排逻辑
- 禁止修改路径：`AgentOrchestrator.java` 编排逻辑、`DiagnosticAgent.java` 接口签名、`FixFlowRunner.java` 的 kg_search/repro/commit/push 确定性步骤逻辑

## 代码事实

### 现状摘要

项目 LLM 调用层碎片化为 4 套独立 HTTP 客户端实现，分布在 5 个包中：
- `UnifiedTextService` (645行): RestTemplate + TokenBucket + 自研重试 + thinking 禁用 + finish_reason 兜底。调用方: KG 描述生成、向量生成、QueryDecomposer、LlmDiagnoseAdapter
- `AnthropicHttpClient` (OkHttp SSE): 手写 SSE 解析，TokenUsage 提取有 Bug (审计 M1: `cumulativeTokens` 恒为 0)。调用方: RAM Chat 流式对话
- `ApmClaudeLlmClient` (RestTemplate + Semaphore): 独立 URL 解析、独立并发控制。调用方: APM 诊断管线
- `ClaudeChatController` (HttpURLConnection + SSE): 手写 SSE，CachedThreadPool。调用方: 前端 Universal Chat

`LlmClient` (@FunctionalInterface) 被 26 个文件引用，是 LLM 调用的抽象接口。

WebSocket 层 7 个独立端点各自处理连接生命周期、断线重连、消息协议。

FixAgent 是单次 `llm.chat()` 调用（无工具循环、无自我验证），审计评分 40/100。

### 可复用 / 需扩展 / 冲突

#### 可直接复用
- `AgentOrchestrator` + `DiagnosticAgent` 接口：多 Agent 置信度计算 + 依赖图 + 批次并发 + 加权聚合完全保留
- `TokenBucketRateLimiter`：保留为 Spring AI `RequestInterceptor`
- `ZhipuService` prompt 模板构建逻辑（`buildCodeDescriptionPrompt` 等）

#### 需要扩展
- `FixSession` model：需新增 `confirmationState` + `confirmedBy` 字段（HITL gate）
- `FixFlowRunner`：Step 5-7（ai_fix/compile_fix/test_fix）合并为单个 ReAct 循环

#### 需求与现状冲突
- `KgToolRegistry.buildToolDefinitions()` 动态构建 `ToolDefinition` → 需改为 `@Tool` 注解静态定义，`projectPath` 通过 `ToolContext` 注入
- 旧 SSE 事件格式（`event:session`/`event:done`）→ Spring AI 原生格式（`data: {"choices":[...]}`）——前端 3 个 fetch SSE consumer 需全量重写
- 前端 7 个 WS composable 各自 handleMessage → 统一 `useAgentWebSocket` + `ServerMessage` 协议

### 挂载点候选

| 优先级 | 路径/符号 | 理由 |
|---|---|---|
| 必选 | `hisi-dev-tool/pom.xml` | 引入 spring-ai-bom 1.1.7 |
| 必选 | `ClaudeChatController.java` | 首个迁移目标（最简单） |
| 必选 | `AnthropicHttpClient.java` | 删除——被 spring-ai-anthropic 替代 |
| 必选 | `LlmClient.java` | 删除——26 个引用点逐一迁移 |
| 必选 | `FixFlowRunner.java` | ReAct 化：9→5 步 |
| 必选 | `ram/sdk/*` (8 文件) | 全量迁移后删除 |
| 必选 | 前端 `api/claude.ts`、`api/codeAnalysis.ts`、`api/naturalLanguage.ts` | 切到 useChatStream |
| 必选 | 前端 6 个 WS composable | 替换为 useAgentWebSocket |
| 备选 | `UnifiedTextService.java` | 底层切 ChatClient，保留 prompt 模板 |

### 波及线索

- **后端波及**: `ram/chat/RamChatOrchestrator`（对话流）、《`ram/nodes/impl/` 6 个 Phase2 节点》→ Step 7b；`loganalysis/service/LogFollowupService` → LLM 调用替换；`mergeanalysis/service/` 2 个文件 → URL 改名
- **前端波及**: `stores/naturalLanguageStore.ts`（300+ 行，processInput 重写）、《stores/ramChatStore.ts》（appendEvent 适配新 WS 格式）、《types/session.ts, intent.ts, dialog.ts, apm.ts, agent.ts》（8 个旧接口标记 @deprecated）
- **测试波及**: 所有 mock `LlmClient.chat()` 的测试 → 改为 mock `ChatClient`；旧 AnthropicHttpClient 测试 → 随类删除
- **依赖波及**: `spring-ai-bom 1.1.7` 拖入 `anthropic-java` 传递依赖，需 `mvn dependency:tree` 验证无版本冲突

### 证据表

| 类型 | 结论 | 证据 |
|---|---|---|
| 事实 | LlmClient 被 26 个文件引用 | Grep: `import.*LlmClient` → 26 matches |
| 事实 | AnthropicHttpClient.stream() 丢弃 token usage | 审计 M1: `usage.input_tokens`/`usage.output_tokens` 未从 SSE 事件解析 |
| 事实 | FixAgent 是单次 llm.chat()，无工具循环 | `FixAgent.java:63` — 一次调用返回字符串 |
| 事实 | UnifiedTextService 645 行中 ~500 行是 HTTP 基础设施 | `UnifiedTextService.java` — read 确认 |
| 事实 | 前端 naturalLanguageStore 定义了 6 种自定义 SSE 回调 | `naturalLanguageStore.ts:288-320` — onIntent/onOutput/onProgress/onDone/onError |
| 事实 | 前端当前调用 150+ REST 端点、7 WS 端点、10 SSE 端点 | Explore Agent 精确追踪完成 |
| 决策 | AgentOrchestrator + DiagnosticAgent 接口完整保留 | Grill #18 用户确认 |
| 决策 | FixFlowRunner HITL 采用 ReAct 后一次性 diff+approve | Grill #11 用户选择方案 C |
| 决策 | 前端 SSE 解析全量重写，不保留适配层 | Grill #3 用户选择方案 B |
| 决策 | Spring AI 版本锁定 1.1.7 | Grill #8 用户确认 |
| 决策 | ZhipuService 底层切 ChatClient，TokenBucketRateLimiter 保留 | Grill #10 用户确认 |
| 决策 | ram/sdk 全量迁移后删除，不保留旧代码 | Grill #12 用户选择方案 A |
| 决策 | jaCoCo 门禁不变，逐步补测试保证覆盖率 | Grill #22 用户确认 |
| 决策 | Step 6 拆为 6a(RamChatOrch)+6b(Phase2+删 ram/sdk) | Grill #20 用户确认 |

## 消歧与闸门

### 开放问题清单

| 优先级 | 问题 | 代码事实背景 | 选项与影响（摘要） | 建议 | 状态 | 最终决策 |
|---|---|---|---|---|---|---|
| 必选 | agent-browser 测试用例何时制定 | Step 2-9 每步需验证，全链路需 10 页 snapshot | A) 实施过程中每步即时编写；B) 现在先全部制定完再开 Step 1 | 建议：A — 每步验证时即时编写，利用 agent-browser 的 snapshot 能力快速存档 | open | |
| 必选 | 前端新 composable (useChatStream/useAgentWebSocket) 是 Phase 0 一起做完还是一步一加 | 6 个旧 WS composable 需统一替换 | A) Phase 0 一次性做完，Step 2 开始直接用；B) 每步迁移时同步添加对应 channel | 建议：A — 先写完两个核心 composable + mock 测试，后续各 Step 直接消费 | open | |

### 澄清完整性扫描
- 已检查的适用维度：LLM 调用层替换、REST 端点合并、WebSocket 协议统一、前端 composable 统一、FixFlowRunner ReAct 化、HITL gate 集成、多 Provider 配置、jaCoCo 覆盖率、TypeScript 类型兼容
- 由证据解决的缺失事实：26 个文件引用 LlmClient（Grep 已确认）；150+ REST/7 WS/10 SSE 端点（Explore Agent 已追踪）；645 行 UnifiedTextService 结构（Read 已确认）
- 新增开放问题及处理状态：agent-browser 测试用例制定时机 → 已列入开放问题；前端 composable 开发顺序 → 已列入开放问题
- 明确不适用 / 不在范围的维度：Auth/permission 变更（不触及）；Payment（不涉及）；Privacy（不涉及）；Data migration（无 schema 变更）
- 结论：无实质阻塞项 — 2 个非阻塞开放问题待用户决策后即可通过规格闸门

### 风险定级与闸门建议
- 建议车道/风险：**High**
- 命中的风险特征：
  - **公共 API/协议字段或兼容性破坏** — REST 端点路径变更（`POST /api/claude/universal-chat` → `POST /api/chat/apm-diagnose` 等），SSE 事件格式变更（自定义格式 → Spring AI 原生格式），WebSocket 消息协议变更（6 种独立协议 → 统一协议）
  - **跨模块/跨服务边界** — 涉及 6+ 包（agent/config/apm/ram/fixengine/loganalysis/mergeanalysis）+ 前端 8 文件
  - **删除大量代码** — ~1,500 行后端 + ~300 行前端旧 composable
- 未命中的高风险特征：
  - Auth/permission — 不修改鉴权策略；WebSocket 统一网关新加 JWT 验证，不改变现有认证逻辑
  - Payment — 不涉及
  - Privacy — 不修改个人信息处理
  - Data migration — 无数据库 schema 变更
  - Destructive — 每步独立 commit，失败时可 revert 单步
- 不确定点：
  - pom.xml 依赖冲突 — Step 1 首次 `mvn dependency:tree` 才能暴露
  - ChatClient 行为与旧 HTTP 客户端不完全一致 — 逐模块验证时可能需适配
- 闸门建议：规格闸门通过后进入 `delivery-plan-tasks`，产生 11 步执行计划 + design.md + delta specs + 安全/回滚/迁移方案
- 可用验证：`mvn test`（后端全覆盖 + jaCoCo 80%/70%）、`npm run build`（前端编译）、agent-browser 逐步 snapshot（10 页功能验证）
- 缺失验证：旧 Anthropic SSE 事件格式的回归对比（迁移后格式不同，无法直接对比）

### Explore 交接消费

- [x] `chosen_direction` → 已写入「意图」：Spring AI 1.1.7 全量迁移，删除自研 LLM 客户端，统一 9 个 Agent 端点，合并 WebSocket 网关
- [x] `non_goals` → 已写入「意图」边界：不升级 Spring Boot 4 / Java 21；不动 KG / Neo4j 等端点；保留 AgentOrchestrator
- [x] `code_anchors` → 已驱动「挂载点候选」检查：`LlmClient.java`(26 引用)、`AnthropicHttpClient.java`、`FixFlowRunner.java`、`ram/sdk/*`
- [x] `risk_signal` → 仅作线索；「风险定级」已按代码事实重算：标准→高风险，因公共 API 协议变更命中红线
- [x] `unknowns` → 已写入「开放问题清单」：agent-browser 测试用例制定时机、前端 composable 开发顺序

落点摘要：意图=Spring AI 1.1.7 全量迁移；挂载=LlmClient/ClaudeChatController/FixFlowRunner；Risk=High（公共 API 协议变更）；开放问题=2

### 状态源与工件位置
- 后端：OpenSpec change `spring-ai-migration`
- 路径：`openspec/changes/spring-ai-migration/`
- 闸门记录：规格批准待确认 / 待批准人 / 附加约束：11 步执行计划中每步前后端同步迁移 + agent-browser 验证
