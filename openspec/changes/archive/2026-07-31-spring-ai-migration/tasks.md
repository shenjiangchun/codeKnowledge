# Spring AI 1.0.8 迁移任务清单

> **风险**: High | **估计工时**: 15-20 人天 | **Schema**: spec-driven

---

## Phase 0: 前端 composable — mock 测试独立验证

### Task 0.1: useChatStream composable

- **映射**: unified-agent-chat / Scenario: 统一 Agent REST API
- **依赖**: 无
- **文件**: `src/composables/useChatStream.ts` (新建), `src/composables/__tests__/useChatStream.test.ts` (新建)
- **范围**: 实现 `stream(agentType, message, sessionId, context, callbacks, signal)` + Spring AI SSE 格式解析 (`choices[0].delta.content` → onToken, `finish_reason=stop` → onDone, `data:[DONE]` → onDone, `usage` → onDone 携带 token 统计)
- **不在范围**: 不调用真实后端; 不修改现有 API 文件
- **实施步骤**:
  1. 创建 `src/composables/useChatStream.ts` — ReadableStream reader + SSE buffer 解析
  2. 创建 `src/composables/__tests__/useChatStream.test.ts` — mock fetch 返回 Spring AI SSE 格式
  3. 验证: `npx vitest run useChatStream.test.ts`
- **完成定义**: mock 测试通过; 支持 onToken/onToolCall/onToolResult/onDone/onError 回调

### Task 0.2: useAgentWebSocket composable + ChatRequest/ServerMessage 类型

- **映射**: unified-websocket-gateway / Scenario: 频道订阅
- **依赖**: 无
- **文件**: `src/composables/useAgentWebSocket.ts` (新建), `src/types/chat.ts` (新建), `src/composables/__tests__/useAgentWebSocket.test.ts` (新建)
- **范围**: 统一 ChannelHandler 客户端; subscribe/message/unsubscribe 生命周期; 自动重连 (exponential backoff max 5); per-channel lastSeq
- **实施步骤**:
  1. 创建 `src/types/chat.ts` — ChatRequest, ServerMessage, ClientMessage 接口
  2. 创建 `src/composables/useAgentWebSocket.ts`
  3. mock WebSocket 测试: subscribe → receive event → unsubscribe
  4. 验证: `npx vitest run useAgentWebSocket.test.ts`
- **完成定义**: mock 测试覆盖 subscribe/event/seq/reconnect/unsubscribe 全状态

---

## Phase 1: 基础设施

### Task 1.1: pom.xml 升级 + ChatClient Bean 配置

- **映射**: unified-agent-chat + agent-tools-registry
- **依赖**: 无
- **文件**: `pom.xml`, `application.yml`, `src/main/java/.../config/AgentConfig.java` (新建)
- **范围**: Boot 3.2.0→3.4.5; 新增 spring-ai-bom 1.0.8 + anthropic + openai starters; ChatClient Bean + Advisor 链; resilience4j @CircuitBreaker; application.yml spring.ai.* 和 hisi.agents 配置
- **实施步骤**:
  1. `pom.xml`: `<parent>` 版本改 3.4.5; 加 `<dependencyManagement>` spring-ai-bom 1.0.8; 加 anthropic + openai starters
  2. 运行 `mvn dependency:tree -Dincludes=com.fasterxml.jackson,io.projectreactor` 检查冲突; 必要时加 `<exclusions>`
  3. 运行 `mvn test` 零回归确认 Boot 升级不破坏现有功能
  4. 创建 `AgentConfig.java` — ChatClient Bean + Advisor 链 + resilience4j
  5. `application.yml` 加 `spring.ai.anthropic.*` + `spring.ai.openai.*` + `hisi.agents.*`
  6. 验证: `mvn test` (ChatClient Bean 注入成功)
- **完成定义**: mvn test 全绿; `AgentConfigTest` 验证 Bean 注入

### Task 1.2: AgentTools Bean

- **映射**: agent-tools-registry / Scenario: ToolContext 注入 projectPath
- **依赖**: Task 1.1
- **文件**: `src/main/java/.../agent/tools/AgentTools.java` (新建), `src/test/java/.../agent/tools/AgentToolsTest.java` (新建)
- **范围**: 10 个 @Tool 方法 (5 KG + 3 FS + generate_project_overview + lookup_log_report); ToolContext 注入 projectPath
- **实施步骤**:
  1. 创建 AgentTools — @Tool 注解 + ToolContext
  2. 每个 @Tool 方法内部委托现有 KgMcpClient / LogAnalysisRepository
  3. 编写 AgentToolsTest — mock KgMcpClient; 验证 ToolContext.get("projectPath") 传递给 kgClient
  4. 验证: `mvn test -Dtest=AgentToolsTest`
- **完成定义**: 10 个 @Tool 全部可用; projectPath 正确注入

---

## Phase 2: 逐模块前后端同步迁移

### Task 2: apm-diagnose 迁移

- **映射**: unified-agent-chat / Scenario: apm-diagnose 替代
- **依赖**: Task 1.1, 1.2, Phase 0
- **文件**:
  - 新建: `src/main/java/.../controller/AgentChatController.java`
  - 修改: `src/main/java/.../controller/ClaudeChatController.java` (@Deprecated apm-diagnose 路径)
  - 前端: `src/api/claude.ts` → useChatStream('apm-diagnose')
- **实施步骤**:
  1. 创建 AgentChatController — `POST /api/chat/apm-diagnose`
  2. 旧 ClaudeChatController 路径标记 @Deprecated
  3. 前端 claude.ts 改调 useChatStream
  4. `mvn test` 后端零回归
  5. `npm run build` 前端编译通过
  6. agent-browser: 启动项目 → 打开 APM 诊断页 → 发送诊断请求 → 验证流式输出 → snapshot
- **完成定义**: APM 诊断功能正常; SSE 流式 token 逐字输出

### Task 3: call-chain-analysis 迁移

- 同 Task 2 模式, agentType='call-chain-analysis'; 旧端点 `POST /api/callchain/analyze` → 废弃
- 前端: `src/api/callChain.ts` → useChatStream('call-chain-analysis')

### Task 4: log-analysis 迁移

- 同 Task 2 模式, agentType='log-analysis'

### Task 5: code-analysis 迁移

- 同 Task 2 模式, agentType='code-analysis'; 前端 codeAnalysis.ts → useChatStream('code-analysis')

### Task 6: dialog 迁移 + naturalLanguageStore 重构

- **依赖**: Task 2-5 (AgentChatController 稳定)
- **额外范围**: `naturalLanguageStore.processInput()` 重写; onIntent→onToolCall('parse_intent') 映射; onProgress→LifecycleAdvisor; onOutput→onToken
- **前端文件**: `src/api/naturalLanguage.ts`, `src/stores/naturalLanguageStore.ts`

### Task 7a: RAM Chat 迁移 (RamChatOrchestrator)

- **依赖**: Task 1.1, 1.2
- **文件**: `src/main/java/.../ram/chat/RamChatOrchestrator.java` (修改 LLM 调用); 旧 AnthropicHttpClient 标记 @Deprecated
- **范围**: RamChatOrchestrator 内部 AnthropicHttpClient → ChatClient; parseJsonResponse → 直接消费 Markdown 文本; ReasoningSteps → ReasoningAdvisor
- **不在范围**: 不动 ram/sdk 包

### Task 7b: RAM Phase2 节点迁移

- **依赖**: Task 7a
- **文件**: ClaudeClarifyLlmClient, ClaudeTechPlanLlmClient, ClaudeImplementLlmClient (ChatClient + BeanOutputConverter); 删除 ram/sdk 全包 (8 文件 ~800 行); 删除 ram/safety 全包 (4 文件 ~200 行)
- **不在范围**: 不动 AgentOrchestrator

### Task 8: MergeAnalysis + Workflow URL 迁移

- **范围**: GET 端点 URL 改名 (事件溯源投影, 内部实现不变)
- 验证: agent-browser 事件仍以原格式推送

### Task 9: FixFlowRunner ReAct + HITL

- **映射**: reactive-fix-flow / 全部 Scenario
- **依赖**: Task 1.1, 1.2
- **文件**: `FixFlowRunner.java` (9→5步); `FixSession.java` (confirmationState 字段); `HitlGateAdvisor.java` (新建); `@Scheduled CleanupJob`
- **实施步骤**:
  1. FixSession model 加 confirmationState + confirmedBy
  2. FixFlowRunner Step 6 (ai_fix) 改为 ChatClient + ToolCallingAdvisor (max 5 轮)
  3. HitlGateAdvisor 实现
  4. @Scheduled cleanupExpiredHITL
  5. TestGenAgent/FixAgent LLM 调用切 ChatClient
  6. `mvn test -Dtest='FixFlowRunner*'` 验证 ReAct 循环
  7. agent-browser: 触发修复 → ReAct 循环 → diff 弹出 → approve → commit
- **完成定义**: ReAct 循环执行; HITL approve/reject 正常; 超时清理正常

---

## Phase 3: 清理

### Task 10a: 删除所有旧 LLM 客户端 + @Deprecated 类型

- **删除文件**: AnthropicHttpClient, ApmClaudeLlmClient, LlmClient, ClaudeChatController, Phase2V2Orchestrator, RamPhase2V2Controller, ram/safety/*, 旧 TypeScript 类型 (session.ts/intent.ts/dialog.ts/apm.ts/agent.ts @deprecated 接口)
- **验证**: `mvn test` 零回归; `npm run build` 零报错

### Task 10b: Unified WebSocket 网关

- **映射**: unified-websocket-gateway / 全部 Scenario
- **新建**: UnifiedWebSocketHandler, ChannelHandler 接口, 6 ChannelHandler 实现
- **前端**: 6 个旧 WS composable → useAgentWebSocket({channel: '...'})
- **验证**: agent-browser 6 页面 WS 连接正常

### Task 11: agent-browser 全链路回归

- **范围**: 10 页完整用户流程 snapshot 存档 `docs/e2e-artifacts/`
- **验证页面**: APM诊断/调用链分析/日志分析/代码分析/对话/RAM Chat/RAM Phase2/合并分析/工作流/异常修复
- **验证**: 所有页面功能正常 + snapshot diff 无异常
