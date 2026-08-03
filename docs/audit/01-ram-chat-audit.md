# RAM Chat (RamChatOrchestrator) 架构审计报告

**审计日期**: 2026-07-17
**审计范围**: `RamChatOrchestrator.java` 及其依赖链（`TurnRegistry`, `ChatContextBuilder`, `KgToolRegistry`, `RamClaudeJsonClient`, `StreamCallbacks`, `ProjectOverviewTool`, `SendOptions`, `AgentEvent`）
**审计标准**: 2026 Agentic Engineering 最佳实践（ReflAct / RE-TRAC / Anthropic Prompt-Caching / SessionStart Hook 审计）

---

## 总分：67/100

---

## 逐项评分

### 1. ReAct 循环质量：6/10

**现状**：
- RamChatOrchestrator 本身没有显式的 ReAct 循环。它的循环在 `RamClaudeJsonClient.callJsonWithToolsAndStreamingMultiTurn()` 中实现：`for (int round = 0; round < MAX_TOOL_ROUNDS; round++)`，每轮调用 Anthropic SSE API，解析 `tool_use` 块，执行 handler，将结果追加到 messages 中。
- `MAX_TOOL_ROUNDS = 10`（在 `RamClaudeJsonClient` 第 46 行），系统 prompt 中也明确了"工具调用上限 10 轮"。
- 有预算告警机制：第 `MAX_TOOL_ROUNDS - 2 = 8` 轮注入 `[SYSTEM] You have used most of your tool budget` 的提示。
- 超限后有强制终止：去掉 tools 列表再次调用 API 要求 LLM 给出最终答案。

**差距**：
- **缺少目标状态检查（goal-state check）**。当前循环只检查 `stop_reason != "tool_use"` 就退出，但没有对"离目标还有多远"做结构化评估。ReflAct 论文（93.3% 准确率 vs 原始 ReAct 65.6%）的核心改进是每轮显式问"我是否已达成目标？还需要什么？"当前实现完全依赖 LLM 自己决定何时停止。
- **思考（Thought）不可见**。ReAct 模式要求 `Thought -> Action -> Observation` 的显式链条。当前 `RamClaudeJsonClient` 的 reasoningSteps 列表只记录了 "Round N: toolName(input) -> result"，没有任何"为什么选这个工具"的推理记录。
- Anthropic 原生 API 支持 `thinking` extended 模式（Opus 4.5 / Sonnet 4.6），可以通过 `thinking.budget_tokens` 让模型在调用工具前输出推理链。当前完全没有启用。

**建议**：
```java
// 在 SendOptions 中增加 thinking budget，让模型输出思考过程
// Anthropic API: "thinking": {"type": "enabled", "budget_tokens": 2000}
// 然后在回调中收集 thinking delta 事件，记录到 reasoningSteps

// 在 RamChatOrchestrator 层增加一个轻量级 goal-check:
// 每次 onRoundComplete 时，如果 stop_reason 是 tool_use，注入一个
// "progress prompt" 而不仅仅是被动等待 LLM 决定停止:
// "[PROGRESS CHECK] Based on the tools you've used so far, 
//  are you 80%+ confident you have enough information? 
//  If yes, stop. If no, what specific info are you still missing?"
```

---

### 2. 三层架构完整性：7/10

**现状**：
- **推理循环层**：在 `RamClaudeJsonClient.callJsonWithToolsAndStreamingMultiTurn()` 中，Plan（LLM 自行规划）、Execute（handler.apply）、Observe（tool result 追加到 messages）、Reflect（LLM 自行反思）均有体现。但这种隐形架构没有在代码中显式建模。
- **工具执行层**：`KgToolRegistry` 提供统一的 `Function<Map<String,Object>, Object>` handler 接口。每个工具 result 通过 `KgToolRegistry.serializeResult()` 序列化并截断到 `MAX_RESULT_CHARS = 50000`。这是不错的统一接口设计。
- **状态管理层**：`AgentEventRepository` 提供 append-only 事件日志。`TurnRegistry` 跟踪活跃 turn。`ChatContextBuilder` 从 checkpoint 事件中提取最近 3 轮摘要构建上下文。

**差距**：
- **工具执行层缺少沙箱和超时控制**。`KgToolRegistry` 中的 handler 直接执行 `kgClient.hybridSearch()`, `Files.walk()`, `Files.readString()`，没有任何超时包装。如果一个项目根目录下有几十万个文件，`grep_project` 的 `Files.walk()` 会阻塞线程池直到超时。虽然 `limit(5000)` 限制了文件数，但 `Files.walk()` 本身在遍历时可能非常慢。
- **工具执行层缺少统一的 `Tool` 抽象**。目前工具定义和 handler 是分离的：`ToolDefinition`（含 schema）通过 `KgToolRegistry.buildToolDefinitions()` 构建，handler 通过 `buildToolHandlers()` 构建，两者通过 HashMap 的 name 关联。这导致 `RamChatOrchestrator` 需要分别构建 definitions 和 handlers 再组装（第 151-156 行）。
- **Checkpoint 持久化的 summary 字段始终为空字符串**（第 285 行 `String summary = ""`），checkpoint 虽然有 finalText 但没有自动生成的摘要。`ChatContextBuilder` 的 `extractSummary()` 会从 checkpoint payload 中取 `summary`，但永远是空。

**建议**：
```java
// 1. 创建统一的 ToolRecord 封装定义+handler，避免松散耦合
public record ToolRecord(
    ToolDefinition definition,
    Function<Map<String, Object>, Object> handler,
    Optional<Duration> timeout
) {}

// 2. 为文件系统工具添加超时（Files.walk 可能非常慢）
CompletableFuture.supplyAsync(() -> handler.apply(input))
    .orTimeout(10, TimeUnit.SECONDS)
    .join();

// 3. 自动生成 Checkpoint summary（利用 LLM 的最后一段输出或简单的 text 截断）
// 替换第 285 行的 `String summary = ""`
String summary = finalText.length() > 200 ? finalText.substring(0, 200) + "..." : finalText;
```

---

### 3. 上下文管理：5/10

**现状**：
- `ChatContextBuilder.buildContext()` 通过查询 `AgentEventRepository.findBySessionId()` 获取所有历史事件，过滤出 `EventType.CHECKPOINT`，取最近 3 轮（`RECENT_TURN_LIMIT = 3`），每轮截断到 `MAX_SUMMARY_LENGTH = 800` 字符，注入到 user prompt 的 `[历史会话上下文]` 部分。
- `RamClaudeJsonClient` 的多轮流式变体在第 316 行通过 `new ArrayList<>(messages)` 拷贝消息列表，每轮追加 `assistant` + `tool_result` 消息，消息列表会随之增长。

**差距**：
- **每轮上下文会无限膨胀**。`callJsonWithToolsAndStreamingMultiTurn` 中，每轮 tool_use 都会追加 `assistant` (含 tool_use 块) + `user` (含 tool_result) 两条消息。10 轮工具调用后，消息列表从 1 条（初始 user）膨胀到约 22 条。每个 tool_result 最多 50000 字符。在最坏情况下，消息列表可达约 500KB+。Anthropic API 按 input token 收费，这会产生巨大成本。
- **缺少上下文窗口感知**。没有检查 `cumulativeTokens`（AgentEvent 有此字段但总设为 0）。没有使用 Anthropic API 的 `usage` 字段反馈来动态调整窗口。
- **会话跨轮历史总结采用的是简单的 CHECKPOINT 截断而非语义压缩**。系统 prompt 中有"如果用户问题是追问，参考 [历史会话上下文] 中的前文，不要重复调用已调用过的工具"，这很好，但摘要方式是字符串截断而不是 LLM 驱动的压缩。RE-TRAC 论文的递归压缩方案是让 LLM 自己生成"当前已知事实"的 compact 表示，比简单的 substring(0,800) 效果好得多。
- **系统 prompt（约 450 字符）目前没有利用 Prompt Caching**。`CacheControl` 类定义了 `L1_SYSTEM / L2_PROJECT / L3_SESSION` 三级缓存但并没有被 `RamChatOrchestrator` 或 `ChatContextBuilder` 使用，也没有被 `AnthropicHttpClient` 消费（grep 结果显示无 breakpoint/cache 关键词）。

**建议**：
```java
// 1. 对 tool_result 做语义压缩而非简单截断
// 在 executeToolHandler 之后调用 LLM 对过大的结果做摘要
String compressToolResult(String raw, String toolName) {
    if (raw.length() < 5000) return raw;
    return llm.callText("compress", "Summarize this tool output in <200 chars: " + raw, ...);
}

// 2. 在 SendOptions 或 AnthropicHttpClient 中启用 Prompt Caching
// 对系统 prompt 和最近几轮的消息标记 cache_control: { type: "ephemeral" }
// CacheControl 类已经定义了数据结构，只需在 AnthropicHttpClient 的消息序列化中消费它

// 3. 增加 context-budget 感知
// 每次 API 调用后解析 usage.output_tokens，记录 cumulativeTokens
// 当 tokens 接近模型上下文窗口时，触发票斟压缩
```

---

### 4. 安全设计：7/10

**现状**：
- `KgToolRegistry` 的 `readFile` 和 `listFiles` 有路径穿越防护：`resolved.startsWith(root)` 检查（第 557 和 587 行）。
- `isExcluded()` 过滤了 `.git/`, `target/`, `node_modules/` 等敏感/大型目录。
- `RamChatOrchestrator` 通过 `TurnRegistry.interrupt()` 提供安全的中断机制，能原子性地移除活跃 turn 并 dispose Reactive 订阅。
- `injectAndContinue()` 在序列化失败时不提交新 turn，避免状态不一致。
- `TurnRegistry.complete()` 通过 compare-and-remove（`computeIfPresent` + turnId 相等性检查）防止 stale turn 污染。

**差距**：
- **没有 SessionStart Hook 安全审计**。2026 年已知攻击面：攻击者通过构造特殊的 projectPath（如 `/proc/self/environ`）和恶意 question 可以触发文件系统工具的信息泄露。虽然路径穿越有基本防护，但缺少全局的"这个 session 的 projectPath 是否在允许列表中"的检查。
- **LLM 工具调用输入没有沙箱隔离**。工具 handler 接收 LLM 提供的参数（如 `query`, `pattern`, `class_name`）直接传递给 `KgMcpClient` 或文件系统操作。如果 LLM 被 prompt-injected 输出恶意参数（如 `read_file` 的 `path=/etc/passwd`），虽然有 `isExcluded` 和路径穿越检查，但没有额外的输入回旋余地。
- **injectAndContinue 和 `POST /inject` 端点无速率限制**。如果没有外部的速率限制器，攻击者可以高速发送 inject 请求导致线程池耗尽（4 线程的固定池），造成 DoS。
- **错误信息暴露到 WebSocket**：`AgentEvent.ERROR` 的 payload 包含 `e.getMessage()`（第 317 行），如果 LLM API 返回包含敏感信息的错误消息（如 API key 片段），会直接推送给前端。

**建议**：
```java
// 1. 在 creatSession 时验证 projectPath 的合法性
private static final Set<String> ALLOWED_ROOT_PREFIXES = Set.of("/home/", "/projects/", "C:\\Users\\");
boolean isValidProjectPath(String path) {
    return ALLOWED_ROOT_PREFIXES.stream().anyMatch(path::startsWith);
}

// 2. 对推送到前端的错误消息做脱敏
String safeMessage = e.getMessage()
    .replaceAll("sk-[a-zA-Z0-9]+", "sk-***")
    .replaceAll("Bearer\\s+[^\\s]+", "Bearer ***");
wsHandler.pushEvent(sessionId, wsEvent(errEv, sessionId, Map.of(
    "type", "error", "turnId", turnId, "error", safeMessage)));

// 3. 为 /inject 和 /interrupt 端点增加基于 sessionId 的速率限制（Spring Interceptor 或 Filter 级别）
```

---

### 5. 可观测性：8/10

**现状**：
- 事件持久化完整：`USER_MSG`, `ASSISTANT_DELTA`, `TOOL_USE`, `TOOL_RESULT`, `CHECKPOINT`, `ERROR`, `TURN_INTERRUPTED` 全覆盖。
- WebSocket 推送的事件带有 `eventId`, `seq`, `sessionId`, `createdAt` 等权威字段（`wsEvent()` 辅助方法）。
- `RamClaudeJsonClient` 的 reasoningSteps 列表记录了每轮工具调用的输入输出摘要。
- 日志记录充分：每个 turn 的开始/完成/失败都有 `log.info/error`，附带 turnId、sessionId、延迟检测（"dropping late xxx for aborted turnId"）。
- `TurnResult` 返回给同步调用方，包含 `turnId`, `status`, `finalText`, `reasoning`, `error`。

**差距**：
- **缺少决策日志（Decision Log）**。没有记录"为什么 LLM 选择了工具 A 而不是 B"。Anthropic API 在 extended thinking 模式下支持 `thinking` content block，但当前没有启用，因此模型选择工具的内部推理不可见。
- **没有 token-usage 追踪**。AgentEvent 有 `cumulativeTokens` 和 `costUsdCents` 字段，但始终设为 0。API 响应中 `usage.input_tokens` / `usage.output_tokens` 没有被解析和记录。
- **WebSocket 缺少 `tool_use_complete` 事件**。当前只发送 `tool_use_start`（输入参数）和 `tool_result`（结果），但中间没有进度事件。对于执行时间较长的工具（如 `generate_project_overview`），前端只能等待，没有 loading 状态。
- **没有 trace 级别的事件关联**。turnId 在场但 tool_use/tool_result 事件之间没有明确的父子关联（`parentEventId` 字段存在但未使用）。

**建议**：
```java
// 1. 启用 extended thinking 并记录 thinking 文本到 AgentEvent
// Anthropic API: "thinking": {"type": "enabled", "budget_tokens": 2000}
// 解析 thinking_delta SSE 事件, 持久化为 THINKING_DELTA 事件类型

// 2. 在 streamAndCollectWithCallbacks 完成后解析 usage
// 从 message_delta 事件中读取 usage.output_tokens
// 从 message_start 事件中读取 usage.input_tokens

// 3. 为长执行工具增加 tool_use_progress 事件
callbacks.onToolProgress(toolName, "Fetching data...");
```

---

### 6. 成本控制：3/10

**现状**：
- `ChatModelProperties` 支持多个模型的声明式配置（`chat-models.yml`），`ModelSpec.scenarioMaxTokens` 支持按场景（"chat"）分配不同的 max_tokens。
- `SendOptions.forScenario()` 从配置中读取 `maxTokens`。
- `MAX_TOOL_ROUNDS = 10` 硬限制防止无限循环。

**差距**：
- **不支持模型路由（简单任务用小模型）**。所有聊天请求都使用同一个 `defaultModelId()`，不管用户问题是"hello"还是"分析整个项目的架构"。2026 年最佳实践是用分类器或 LLM-as-judge 先判断 query 复杂度，简单问题路由到 Haiku，复杂问题路由到 Sonnet/Opus。
- **未使用 Prompt Caching**。虽然 `CacheControl` 类已经定义了三级缓存基础设施（L1_SYSTEM / L2_PROJECT / L3_SESSION），但整个调用链路中没有一处使用。Anthropic 的 Prompt Caching 可以对系统 prompt + 最近消息标记 `cache_control: {"type": "ephemeral"}` 节省 90% 的 input token 成本。
- **没有 token 用量追踪和预算上限**。AgentEvent 的 `cumulativeTokens` / `costUsdCents` 始终为 0。没有 per-session 或 per-user 的 token 预算限制。
- **工具结果没有做结果缓存**。如果同一个用户连续两次问"列出入口点"，`entry_points` 工具会被调用两次，产生两次 API 成本。可以在 session 级别 caching 工具结果。

**建议**：
```java
// 1. 简单模型路由
String selectModel(String userText) {
    if (userText.length() < 50 && !containsKeywords(userText, "分析", "架构", "调用链"))
        return "claude-haiku-4-20250514";
    return chatProps.defaultModelId();
}

// 2. 在 AnthropicHttpClient 中消费 CacheControl
// 对系统 prompt 用 L1_SYSTEM，对最近 2-3 轮历史消息用 L3_SESSION
// 当前 CacheControl.CacheBlock.toAnthropicBlock() 已经返回带 cache_control 的 block

// 3. 工具结果缓存（session 级别）
private final Map<String, Map<String, Object>> toolResultCache = new ConcurrentHashMap<>();
// key = toolName + normalizedInput hash
// 在 handler.apply 前先查缓存
```

---

### 7. 错误处理与韧性：6/10

**现状**：
- `RamChatOrchestrator.runTurnInternal()` 的顶层 `catch(Exception e)` 捕获所有异常，持久化为 ERROR 事件，推送到 WebSocket，返回 FAILED status。
- `RamClaudeJsonClient.executeToolHandler()` 捕获工具 handler 异常，返回 `{"error": "Tool execution failed: ..."}` 让 LLM 能感知错误并尝试替代方案。
- `appendEvent()` 方法有 `try-catch` 保护（序列化失败时记录 warn 而非崩溃）。
- `TurnRegistry.interrupt()` 的 `dispose()` 调用有 try-catch 保护。
- `Future.orTimeout(timeoutSeconds, TimeUnit.SECONDS)` 提供超时保护。

**差距**：
- **工具调用失败后没有重试或 Circuit Breaker**。如果 `hybrid_search` 因为 Neo4j 短暂不可用而失败，没有重试逻辑也没有熔断器。AgentEvent 有 `circuitState` 字段但始终为 "OK"。
- **`Future.orTimeout` 之后没有清理**。虽然 `TurnRegistry.complete()` 在 finally 块中被调用，但如果 `orTimeout` 触发 `TimeoutException`，底层 SSE 流仍在运行（因为 `Disposable` 是 proxied 的，但 `orTimeout` 不会自动调用 `dispose()`）。这会导致线程泄露。
- **异常分类不精确**。所有 Exception 都被笼统地记录为 ERROR 事件，没有区分可恢复错误（如工具超时）和不可恢复错误（如 API key 无效）。
- **`asyncExecutor` 没有背压（backpressure）**。4 线程的固定池在请求洪峰时没有队列大小限制。`Executors.newFixedThreadPool` 默认使用无界 `LinkedBlockingQueue`。

**建议**：
```java
// 1. 超时后主动 dispose
try {
    result = future.orTimeout(timeoutSeconds, TimeUnit.SECONDS).join();
} catch (CompletionException | TimeoutException e) {
    proxyDisposable.dispose(); // 显式取消底层 SSE 流
    throw e;
} finally {
    turnRegistry.complete(sessionId, turnId);
}

// 2. 使用有界线程池 + 拒绝策略
new ThreadPoolExecutor(4, 4, 60L, TimeUnit.SECONDS,
    new LinkedBlockingQueue<>(100),
    new ThreadPoolExecutor.CallerRunsPolicy());

// 3. 为工具执行增加简单重试（仅对临时性错误）
int retries = 0;
while (retries < 3) {
    try {
        return handler.apply(block.input);
    } catch (IOException | TimeoutException e) {
        retries++;
        if (retries >= 3) throw e;
        Thread.sleep(1000L * retries);
    }
}
```

---

### 8. 架构僵化度：7/10

**现状**：
- `KgToolRegistry` 将工具注册集中管理，通过 `buildToolDefinitions()` 和 `buildToolHandlers()` 动态构建。支持单项目和多项目路径两种重载。
- `ProjectOverviewTool` 作为独立组件注入，通过 `buildDefinition()` + `buildHandler()` 方法与主工具集分开。
- `RamChatOrchestrator` 在第 151-156 行硬编码了工具的组装逻辑：先构建 KG 工具，再追加 ProjectOverview，再合并 handler。如果要加第三个工具源（如 `DatabaseQueryTool`），需要修改 `RamChatOrchestrator` 代码。

**差距**：
- **工具注册不是真正的插件式**。虽然 `KgToolRegistry` 和 `ProjectOverviewTool` 是独立的 Spring Bean，但 `RamChatOrchestrator` 需要显式知道每一个工具源的存在并手动组装。如果要添加新工具，必须修改 Orchestrator 的 `runTurnInternal()` 方法（违反开闭原则）。
- **没有工具发现机制**。Spring 的 `@Autowired List<XxxToolProvider>` 可以做到自动发现实现了某个接口的所有工具提供者。当前只能通过直接注入 `KgToolRegistry` 和 `ProjectOverviewTool` 两个具体类。
- **流式回调（StreamCallbacks）接口过于固定**。4 个方法（onAssistantDelta/onToolUseStart/onToolResult/onRoundComplete）无法扩展，不能添加新的回调类型而不破坏接口。

**建议**：
```java
// 1. 定义 ToolProvider 接口，让 Spring 自动注入所有实现
public interface ToolProvider {
    List<ToolRecord> build(List<String> projectPaths);
}

// RamChatOrchestrator 改为:
private final List<ToolProvider> toolProviders; // Spring 自动注入

// runTurnInternal 中:
List<ToolRecord> allTools = new ArrayList<>();
for (ToolProvider provider : toolProviders) {
    allTools.addAll(provider.build(projectPaths));
}

// 2. 用事件总线替代固定回调接口
// 定义 StreamEvent 类层次: TextDelta, ToolUseStart, ToolResult, RoundComplete
// Orchestrator 中的回调实现不变，但接口扩展性更好
```

---

### 9. 多 Agent 协作：5/10

**现状**：
- `RamChatOrchestrator` 是一个**单 Agent** 架构。没有 sub-agent spawn 机制，没有 Agent-to-Agent 通信协议。
- `Phase2V2Orchestrator` 被标记为 `@Deprecated` 但代码仍在，实现了多链并行分析 + Phase1 checkpoint 查询 + 子会话创建 + 并行链分析（每个链一个 `CompletableFuture`）+ 报告聚合。
- `RamChatController` 有 `POST /interrupt` 和 `POST /inject` 端点，支持 Human-in-the-Loop 干预。

**差距**：
- **多 Agent 设计（Phase2V2）已被废弃，但迁移路径不清晰**。当前 RamChatOrchestrator 不支持 sub-agent spawn。如果用户问一个需要并行分析多个入口点链的复杂问题，Orchestrator 只能串行处理或让 LLM 自己管理。
- **缺少 Agent 编排能力**。没有 "planner agent" 分解问题、"worker agent" 并行执行、"synthesizer agent" 整合的管道。2026 年多 Agent 模式的关键价值在于并行执行独立子任务。
- **没有"为多 Agent 而多 Agent"的过度设计** -- 这是好的，当前选择了务实的单 Agent 方案。

**建议**：
```java
// 如果确实需要多 Agent（当前可能不需要），可以先从简单的并行工具调用开始:
// 1. 在 RamClaudeJsonClient 中支持并行 tool_use 块执行
// Anthropic API 可以在单个 turn 中返回多个 tool_use 块
// 当前代码逐块顺序执行，可以改为并行:
List<CompletableFuture<Map<String, Object>>> futures = result.toolUseBlocks.stream()
    .map(block -> CompletableFuture.supplyAsync(() -> executeTool(handlers, block)))
    .toList();
CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

// 2. Phase2V2 迁移计划要明确:
//    - 如果 RamChat 已经覆盖了所有场景, Phase2V2 应该被彻底删除
//    - 如果还有场景 RamChat 不支持, 应该列出清单并规划迁移
```

---

### 10. 与 Agentic Engineering 的对齐度：4/10

**现状**：
- `RamChatOrchestrator` 是一个 chat-first 设计：用户发消息，系统直接调用 LLM + 工具，返回结果。没有 plan → approve → execute 的 gating。
- `POST /interrupt` 提供了 Human-in-the-Loop 的中断能力，但这是被动的（用户必须主动打断），而非主动的"先审计划再执行"。
- 系统 prompt 中有输出约束（Markdown 格式、先结论后细节、代码位置引用），但没有执行计划约束。

**差距**：
- **缺少 "propose plan -> user approve -> execute" 的 gate**。2026 年 Agentic Engineering 强调：大模型生成的执行计划在执行前必须经过用户审查。当前 Orchestrator 直接开始工具调用，没有给用户提供计划预览和审批步骤。
- **没有显式的 spec → execute → verify 循环**。用户问题被视为"单次请求"，Agent 执行工具调用后直接返回结果。没有"我验证了结果满足你的需求吗？"的步骤。
- **Human-in-the-Loop 是被动而非主动的**。用户只能通过 inject/interrupt 打断，但 Agent 不会主动暂停并询问："我打算做以下 X 步，确认后我将继续。"
- **没有 Confidence Scoring**。Agent 不输出对结果置信度的评估（"我对此分析的信心是 80%"）。

**建议**：
```java
// 1. 可选的两阶段模式：Plan → Execute
// 在 ChatContextBuilder 中增加 system prompt 指令:
// "当用户提出复杂问题时，先输出一个分析计划（编号列表），
//  然后用 [等待确认] 标记暂停。用户回复'继续'后你再开始执行。"

// 2. 增加 PlanConfirm 事件类型和前端交互
// RamChatOrchestrator 监听 LLM 输出中的特定标记，
// 遇到后暂停执行，推送 plan_proposed 事件，等待用户确认消息

// 注：这个功能在当前对话场景中可能过度设计。建议先以系统 prompt 
// directive 的方式做轻量级实现，而非改动核心循环逻辑。
```

---

## 关键发现

### 已经做对的 3 件事

1. **Turn 生命周期管理（TurnRegistry + 中断机制）**：`TurnRegistry` 的 `register/interrupt/complete` + proxy Disposable 模式非常精巧。Pre-register（在 supplyAsync 调度之前注册）+ compare-and-remove（防 stale 污染）+ interrupt 原子性移除 + `isActive()` 防止 late events -- 这是一套经过深思熟虑的并发控制方案。

2. **事件溯源（append-only event log + WebSocket push）**：`AgentEventRepository.append()` 支持幂等（idempotencyKey）+ `EventType` 覆盖完整生命周期 + WebSocket 实时推送带有权威 ID 的事件。前端可以完全重建会话状态。

3. **工具安全防护（路径穿越 + 排除列表）**：`KgToolRegistry` 的 `readFile`/`listFiles` 有 `resolved.startsWith(root)` 路径穿越检查，`isExcluded` 过滤敏感目录，`MAX_RESULT_CHARS/MAX_READ_CHARS/MAX_LIST_ENTRIES` 三重结果大小限制。这是成熟的防御性设计。

### 最需要改进的 3 个问题

1. **Prompt Caching 完全未启用**（成本影响最大）。`CacheControl` 基础设施已就绪，但无人使用。系统 prompt 每次 API 调用都原样发送。对高频使用场景，启用缓存可节省 50-90% input token 成本。

2. **上下文管理过于依赖字符串截断**（质量影响最大）。Checkpoint 摘要为空、历史上下文只截前 800 字符、消息列表逐轮膨胀无压缩。缺少 token 预算追踪和语义压缩。

3. **缺少 cost 追踪和模型路由**（运维可观测性影响最大）。`cumulativeTokens`/`costUsdCents` 始终为 0，没有 per-session 成本统计，没有简单/复杂问题的模型选择区分。

---

## 优先改进路线

1. **P0（立即）**：
   - 启用 Prompt Caching：在 `AnthropicHttpClient` 中消费 `CacheControl`，对系统 prompt 标记 `cache_control: {"type": "ephemeral"}`
   - 修复 checkpoint summary 为空的问题：用 finalText 前 200 字符或调用 LLM 生成摘要
   - 添加 token usage 追踪：解析 API 响应的 `usage` 字段，更新 `cumulativeTokens`
   - 修复 `orTimeout` 后底层流未清理的潜在线程泄露

2. **P1（本迭代）**：
   - 实现工具执行超时：为 `grep_project`/`read_file` handler 添加 `CompletableFuture.orTimeout`
   - 错误信息脱敏：过滤 API key 等敏感信息后再推送到 WebSocket
   - 统一工具注册接口：定义 `ToolProvider` 接口，消除 `RamChatOrchestrator` 对具体工具类的硬编码依赖
   - 添加工具结果缓存：session 级别缓存相同参数的工具调用结果

3. **P2（下迭代）**：
   - 简单/复杂查询的模型路由：根据用户问题长度和关键词选择 Haiku vs Sonnet
   - 启用 Anthropic extended thinking 模式：记录 thinking 过程到 AgentEvent
   - 实现轻量级 Plan Gate：在系统 prompt 中引导 LLM 对复杂问题先输出计划再执行
   - 决策日志：记录每轮工具选择的推理过程
   - 异常分类：区分可恢复/不可恢复错误，增加工具调用重试
