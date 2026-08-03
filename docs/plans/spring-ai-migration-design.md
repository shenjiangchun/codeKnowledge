# Spring AI 迁移与架构合并设计文档

> **状态**: 设计已确认（22 项决策，grill 阶段完成）  
> **日期**: 2026-07-25  
> **关联审计**: [00-reflact-overall-assessment.md](../audit/00-reflact-overall-assessment.md)  
> **关联调研**: [AI Agent 主流框架深度技术调研报告](../04-AI-Agent主流框架深度技术调研报告.md)

---

## 0. 整改流程规范（强制遵守）

本次重构采用**先废弃、再重建、全链路验证后清理**的安全流程：

```
Phase 0: 前置修复 (main 分支直接提交)
  ├── P0-4 checkpoint summary → 已提交 f3b0d3b5
  └── P0-5 extractTestClassName → 已修复，无需改动

Phase 1: 基础设施 (Step 1)
  ├── pom.xml 引入 spring-ai-bom 1.1.7 + anthropic + openai starters
  ├── application.yml 新增 spring.ai.* 配置
  ├── ChatClient Bean 配置
  └── 验证: mvn dependency:tree + mvn test

Phase 2: 逐模块前后端同步迁移 (Step 2-8, 可串行也可有限并行)
  ├── 每个 Step = 后端改动 + 前端改动 + agent-browser 验证
  ├── 后端: 替换 Controller/Service 中的 LLM 调用
  ├── 前端: 切到 useChatStream composable / useAgentWebSocket
  ├── mvn test 确保零回归 + jaCoCo >= 80%
  └── 每步独立 commit，失败时 revert 单步

Phase 3: 清理 (Step 9-10)
  ├── 删除所有 @Deprecated 类/方法 + ram/sdk 包 + 旧依赖
  ├── 删除旧 TypeScript 类型定义
  └── agent-browser 全链路回归 → snapshot 存档
```

**回滚策略**: 每个 Step 独立 commit。Step N 失败 → `git revert <step-N-commit>`。Step 9-10 不可逆，必须确认所有前置 Step 通过。

**覆盖率要求**: 整个迁移期间 jaCoCo 门禁保持不变（LINE >= 80%, BRANCH >= 70%）。每个 Step 新测试必须先通过再删除旧测试。

---

## 1. 全部决策汇总

| # | 决策 | 选择 |
|---|------|------|
| 1 | WebSocket 合并范围 | 合并 6/7 为 `/ws/agent`，6 个频道，保留 `/ws/terminal` |
| 2 | @Tool projectPath 注入 | ToolContext 注入，LLM 不可见 |
| 3 | SSE 事件格式 | Spring AI 原生 OpenAI-compatible 格式 |
| 4 | 统一 WebSocket 协议 | 1 网关 6 频道，全局 seq + lastSeqByChannel |
| 5 | 前端 SSE 统一 | useChatStream composable，ChatRequest context 打包 |
| 6 | 执行顺序 | Phase 0(前置)→1(基础设施)→2(逐模块 11 step)→3(清理) |
| 7 | FixFlowRunner | 9步→5步，ai_fix 变为 ReAct 循环，max 5 轮工具调用 |
| 8 | Spring AI 版本 | 1.1.7 (CVE-2026-41863 已修复) |
| 9 | AgentType 拆分 | `claude` 拆为 `apm-diagnose`/`call-chain-analysis`/`log-analysis`，共 9 个 |
| 10 | ZhipuService | 底层切 ChatClient (OpenAI compat)，保留 TokenBucketRateLimiter |
| 11 | HITL gate | ReAct 循环结束后一次性 diff + approve |
| 12 | ram/sdk 全量迁移 | 全量迁移后删除整个包 (8 文件, ~800 行) |
| 13 | 依赖 | OkHttp/Reactor 保留（有非 LLM 消费者） |
| 14 | naturalLanguageStore | 直接重构，不保留适配层 |
| 15 | 多 Provider | Agent 对话走 Anthropic ChatClient，KG 批处理走 Zhipu OpenAI compat |
| 16 | P0-4/P0-5 前置修复 | P0-4 已提交 f3b0d3b5；P0-5 已验证修复 |
| 17 | KgToolRegistry | 新建 AgentTools Bean，切换后删旧 |
| 18 | AgentOrchestrator + DiagnosticAgent | 完整保留，只替换内部 LLM 调用 |
| 19 | pom.xml | spring-ai-bom 1.1.7 + anthropic + openai starters |
| 20 | Step 6 拆分 | 6a: RamChatOrchestrator 迁移 → 6b: Phase2 节点 + 删 ram/sdk |
| 21 | TypeScript 类型 | 新建 types/chat.ts，旧接口 @deprecated，Step 9 删除 |
| 22 | jaCoCo 覆盖率 | 逐步补测试，不降门禁 |

---

## 2. 现状 vs 目标

### 当前碎片化

```
LLM 调用层 (4 套独立实现, ~1,500 行):
  UnifiedTextService (645行 RestTemplate)  → KG描述生成、向量生成
  AnthropicHttpClient (OkHttp SSE)         → RAM Chat 流式
  ApmClaudeLlmClient (RestTemplate+Semaphore) → APM 诊断
  ClaudeChatController (HttpURLConnection)  → 通用 Chat SSE

WebSocket 端点 (7 个独立):
  /ws/terminal, /ws/ram-chat, /ws/dialog, /ws/diagnosis,
  /ws/apm, /ws/log-analysis, /ws/log-followup

事件推送 (3 份重复, ~400 行):
  FixChatService, RamChatOrchestrator, LogAnalysisWebSocketHandler

Agent 对话 Controller (5 个):
  ClaudeChatController, RamChatController, DialogController,
  RamPhase2Controller, RamPhase2V2Controller (假废弃)
```

### 目标架构

```
                          ┌─────────────────────────┐
                          │   AgentChatController    │
                          │   POST /api/chat/{type}  │
                          └───────────┬─────────────┘
                                      │
                          ┌───────────▼─────────────┐
                          │     AgentRegistry        │
                          │  type → Config 映射       │
                          │  (YAML 声明式, 9 个 type) │
                          └───────────┬─────────────┘
                                      │
          ┌───────────────────────────┼───────────────────────────┐
          │                           │                           │
    ┌─────▼─────┐  ┌─────────┐  ┌─────▼─────┐  ┌────────────────┐
    │ 对话 Agent │  │KG 批处理 │  │AgtOrchstr│  │  事件溯源投影   │
    │ Anthropic │  │Zhipu   │  │(保留)    │  │(ram/merge/wf)  │
    └─────┬─────┘  └────┬────┘  └─────┬─────┘  └────────────────┘
          │              │             │
          └──────────────┼─────────────┘
                         │
              ┌──────────▼──────────┐
              │   共享基础设施       │
              │  ChatClient          │
              │  ChatEventPublisher  │
              │  ToolRegistry (@Tool)│
              │  PromptCache (auto)  │
              │  HITL Gate (Advisor) │
              │  TokenBucketLimiter  │
              └─────────────────────┘

WebSocket:  /ws/agent (6 频道) + /ws/terminal
```

---

## 3. 执行计划 (11 Steps)

```
feat/spring-ai-migration
  ├── Phase 0: 前置修复 (已完成)
  │     ├── ✅ P0-4: checkpoint summary → f3b0d3b5
  │     └── ✅ P0-5: extractTestClassName → 已验证无需修复
  │
  ├── Phase 1: 基础设施
  │     └── Step 1: pom.xml + ChatClient Bean + application.yml
  │         验证: mvn dependency:tree + mvn test
  │
  ├── Phase 2: 逐模块迁移 (前后端同步)
  │     ├── Step 2: ClaudeChat → AgentChatController 迁移 (apm-diagnose)
  │     │   后端: ClaudeChatController → AgentChatController
  │     │   前端: claude.ts → useChatStream('apm-diagnose')
  │     │   验证: agent-browser APM 诊断页
  │     │
  │     ├── Step 3: ClaudeChat → AgentChatController 迁移 (call-chain-analysis)
  │     │   后端: ImpactPredictionController → AgentChatController
  │     │   前端: callChain.ts → useChatStream('call-chain-analysis')
  │     │   验证: agent-browser 调用链分析页
  │     │
  │     ├── Step 4: ClaudeChat → AgentChatController 迁移 (log-analysis)
  │     │   后端: 复用 AgentChatController
  │     │   前端: claude.ts → useChatStream('log-analysis')
  │     │   验证: agent-browser 日志分析页
  │     │
  │     ├── Step 5: CodeAnalysis 迁移
  │     │   后端: AIAnalysisController → AgentChatController
  │     │   前端: codeAnalysis.ts → useChatStream('code-analysis')
  │     │   验证: agent-browser 代码分析页
  │     │
  │     ├── Step 6: Dialog 迁移 + naturalLanguageStore 重构
  │     │   后端: DialogController → AgentChatController
  │     │   前端: naturalLanguageStore.processInput() 重写
  │     │   验证: agent-browser 对话页
  │     │
  │     ├── Step 7a: RAM Chat 迁移 (RamChatOrchestrator)
  │     │   后端: RamChatOrchestrator 内部 LLM 调用切 ChatClient
  │     │   前端: ramChatStore + ramChat.ts → useChatStream
  │     │   验证: agent-browser RAM Chat 页 + 流式输出 + 工具调用
  │     │
  │     ├── Step 7b: RAM Phase2 节点迁移 + 删除 ram/sdk
  │     │   后端: 6 个 Phase2 节点切 ChatClient，删除 ram/sdk 全包
  │     │   验证: agent-browser RAM Phase2 + mvn test 零回归
  │     │
  │     ├── Step 8: MergeAnalysis + Workflow URL 迁移
  │     │   后端: GET 流式端点 URL 改名 (事件溯源投影，不改内部实现)
  │     │   验证: agent-browser 合并分析页 + 工作流页
  │     │
  │     └── Step 9: FixFlowRunner ReAct (含 HITL gate)
  │          后端: 9步→5步，ai_fix 使用 ToolCallingAdvisor
  │          验证: agent-browser 异常修复页 + diff approve 流程
  │
  └── Phase 3: 清理
        ├── Step 10: 删除废弃代码 + WebSocket 统一网关
        │   后端: 删 AnthropicHttpClient/ApmClaudeLlmClient/LlmClient/ram-sdk/Phase2V2
        │   后端: 统一 WebSocket 网关 (6 频道合并为 /ws/agent)
        │   前端: @deprecated 类型清理、旧 composable 清理
        │   验证: mvn test 零回归 + jaCoCo >= 80%
        │
        └── Step 11: agent-browser 全链路回归
            验证: 10 页完整用户流程 snapshot 存档
```

---

## 4. 代码变更清单

### 后端删除 (~1,500 行)

| 文件 | 行数 | Step |
|------|------|------|
| `AnthropicHttpClient.java` | ~200 | 10 |
| `ApmClaudeLlmClient.java` | 215 | 10 |
| `LlmClient.java` | 28 | 10 |
| `ClaudeChatController.java` | ~300 | 2-4 |
| `UnifiedTextService.java` | 645→~100 | 10 |
| `ram/sdk/*` (8 文件) | ~800 | 7b |
| `Phase2V2Orchestrator.java` | 235 | 10 |
| `RamPhase2V2Controller.java` | ~100 | 10 |
| `KgToolRegistry.java` (被 AgentTools 替代) | ~400 | 7b |

### 后端新增 (~500 行)

| 文件 | 行数 | Step |
|------|------|------|
| `AgentChatController.java` | ~80 | 2 |
| `AgentRegistry.java` | ~60 | 1 |
| `AgentProperties.java` | ~50 | 1 |
| `ChatEventPublisher.java` (Advisor) | ~60 | 1 |
| `AgentTools.java` (@Tool 注解) | ~120 | 1 |
| `UnifiedWebSocketHandler.java` | ~110 | 10 |
| `HitlGateAdvisor.java` | ~50 | 9 |

### 前端变更 (~400 行净变化)

| 文件 | 变更 | Step |
|------|------|------|
| `composables/useChatStream.ts` | +100 (新增) | Phase 0 |
| `composables/useAgentWebSocket.ts` | +120 (新增) | Phase 0 |
| `api/claude.ts` | -80 +15 | 2-4 |
| `api/codeAnalysis.ts` | -40 +15 | 5 |
| `api/naturalLanguage.ts` | -60 → 删除 | 6 |
| `composables/useRamChatWebSocket.ts` | -50 → 删除 | 10 |
| 其余 5 个 WS composable | -150 → 删除 | 10 |
| `stores/naturalLanguageStore.ts` | ~50 行重构 | 6 |
| `types/chat.ts` | +60 (新增) | Phase 0 |
| `types/session.ts` | -12 +2 (@deprecated) | 10 |
| `types/intent.ts` | -18 +2 | 10 |
| `types/dialog.ts` | -20 | 10 |
| `types/apm.ts` | -6 +2 | 10 |

---

## 5. 多 Provider 配置

```yaml
# application.yml
spring:
  ai:
    anthropic:
      api-key: ${ANTHROPIC_API_KEY}
      chat:
        options:
          model: claude-sonnet-4-20250514
    openai:
      api-key: ${ZHIPU_API_KEY}
      base-url: https://open.bigmodel.cn/api/paas/v4
      chat:
        options:
          model: glm-4-flash

hisi:
  agents:
    apm-diagnose:
      system-prompt: "你是APM诊断专家..."
      provider: anthropic
      tools: kgSearch, tracePath, logQuery
    call-chain-analysis:
      system-prompt: "你是调用链分析专家..."
      provider: anthropic
      tools: kgTrace, kgDownstream, kgUpstream
    log-analysis:
      system-prompt: "你是日志分析专家..."
      provider: anthropic
      tools: logQuery, kgEnrich
    code-analysis:
      system-prompt: "你是代码变更分析专家..."
      provider: anthropic
      tools: kgSearch, gitLog, gitDiff
    dialog:
      system-prompt: "你是自然语言交互助手..."
      provider: anthropic
      tools: kgSearch, intentParse
    fix:
      system-prompt: "你是代码修复专家..."
      provider: anthropic
      tools: kgSearch, readFile, writeFix, compileCheck, runTests
      hitl: { mode: post-react, require-approval: true }
      tool-call-limit: { max-iterations: 5 }
    ram:       # 事件溯源投影，不调用 LLM
    merge-analysis:  # 事件溯源投影
    workflow:  # 事件溯源投影
```

---

## 6. 统一端点契约

### REST

```
POST /api/chat/{agentType}
  Content-Type: application/json
  Accept: text/event-stream
  Body: { "message": "...", "sessionId": "...", "context": {...} }
  Response: SSE stream (Spring AI 原生 format)

GET /api/chat/{agentType}/{sessionId}/stream?afterSeq=N
  事件溯源投影端点（ram/merge-analysis/workflow）
  Response: SSE stream of AgentEvent JSON
```

### WebSocket

```
WS /ws/agent
  客户端 → 服务端:
    { "type": "subscribe", "channel": "ram-chat", "sessionId": "...", "payload": {} }
    { "type": "message", "channel": "ram-chat", "sessionId": "...", "payload": {...} }
    { "type": "unsubscribe", "channel": "ram-chat", "sessionId": "..." }

  服务端 → 客户端:
    { "type": "event", "channel": "ram-chat", "eventType": "assistant_delta",
      "sessionId": "...", "seq": 42, "payload": {...}, "timestamp": 1234567890 }
    { "type": "error", "channel": "ram-chat", "eventType": "...",
      "sessionId": "...", "seq": 43, "payload": { "message": "..." } }

WS /ws/terminal  — 保留不变，PTY 二进制流
```

---

## 7. 审计路线图整合

引入 Spring AI 后的审计任务变化：

| 审计项 | 原估 | 迁移后 | 原因 |
|--------|------|--------|------|
| P0-1 HITL gate | 2-3d | 并入 Step 9 | 方案 C: ReAct 完毕后一次性 diff+approve |
| P0-2 WebSocket 认证 | 2h | 并入 Step 10 | 统一 WS 网关自带 JWT 验证 |
| P0-3 Prompt Caching | 4-8h | 0h | Spring AI Anthropic starter 自动启用 |
| P0-4 checkpoint summary | 1h | ✅ 已完成 | f3b0d3b5 |
| P0-5 extractTestClassName | 0.5h | ✅ 已验证 | 当前代码已修复 |
| P1-1 ChatEventPublisher | 2d | 1d | Advisor 形态减少样板代码 |
| P1-2 KG 集成 Step 2 | 1d | 不变 | 独立工作，与迁移无关 |
| P1-3 工具超时 | 1d | 0.5d | ToolTimeoutAdvisor 内置 |
| P1-4 Phase2V2 清理 | 4-6h | 并入 Step 10 | 一次性清理 |
| P2-1 FixAgent ReAct | 5-8d | 1-2d | ToolCallingAdvisor 内置 |
| P2-2 模型路由 | 1d | 0.5d | ModelRoutingAdvisor |
| P2-4 extended thinking | 1d | 0.5d | Anthropic starter 原生 |
| P2-5 成本追踪 | 2d | 0.5d | CostTrackingAdvisor |
| **合计** | — | **~12 人天节省** | |

---

## 8. 风险矩阵

| 风险 | 等级 | 缓解 |
|------|------|------|
| pom.xml 依赖冲突 | 中 | Step 1 第一时间 `mvn dependency:tree` 暴露，排除冲突 |
| ChatClient 行为不一致 | 中 | 逐模块切换 + agent-browser 验证，不一致时加适配层 |
| 覆盖率下降 | 中 | 新测试先通过再删旧测试，jaCoCo 门禁不变 |
| Step 7b 回滚范围大 | 中 | 拆为 7a+7b，7a 验证通过后再做 7b |
| 智谱参数透传失效 | 低 | ChatOptions.extra 透传 `thinking: disabled` |
| 前端向后不兼容 | 低 | TypeScript 编译期发现，旧接口 @deprecated 保留到 Step 10 |
| WebSocket 断线重连态 | 中 | Step 10 统一网关需要 seq 重放，per-channel lastSeq |

---

## 9. agent-browser 验证策略

每步验证采用相同模板（具体操作清单另行制定）：

```
Step N 验证流程:
  1. mvn spring-boot:run + npm run dev 启动
  2. agent-browser open <step对应页面URL>
  3. agent-browser snapshot → docs/e2e-artifacts/step-N-before.png
  4. agent-browser 执行用户操作流程
  5. agent-browser snapshot → docs/e2e-artifacts/step-N-after.png
  6. 人工对比 before/after + 检查功能
  7. 记录: PASS/FAIL/异常行为
  8. mvn test 确认覆盖率 >= 80%
```

完整 agent-browser 测试用例将在后续单独制定。
