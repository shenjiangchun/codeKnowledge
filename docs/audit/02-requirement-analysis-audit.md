# 需求分析大师 -- 架构质量审计报告

> 审计日期：2026-07-17  
> 审计范围：`RequirementAnalysisOrchestrator` (DAG), `Phase2V2Orchestrator` (@Deprecated), `DagExecutor`, `InputsHasher`, `CircuitBreaker`  
> 审计标准：2026 Agentic Engineering 最佳实践

---

## 总体评分：6.5 / 10

| 维度 | 评分 | 状态 |
|---|---|---|
| 1. DAG 编排 vs ReAct 循环 | 7.0 | 基础扎实，缺乏深度 |
| 2. HITL 设计质量 | 8.0 | 设计优秀，实现完整 |
| 3. 事件溯源 | 7.5 | 覆盖全面，存在未消费事件 |
| 4. Phase2V2 迁移债 | 3.0 | **高风险** |
| 5. 安全与韧性 | 3.5 | **电路断路器是一颗"僵尸组件"** |
| 6. Agentic Engineering 对齐度 | 7.5 | 工程化思维清晰，缺乏 guard |

---

## 1. DAG 编排 vs ReAct 循环 -- 7.0 / 10

### 1.1 定位

系统当前呈现三层编排模式**并存**的局面：

| 层级 | 入口 | 模式 | 状态 |
|---|---|---|---|
| DAG 线性 Pipeline | `RequirementAnalysisOrchestrator` | 确定性工作流 | 活跃 |
| 多 Agent 并行 | `Phase2V2Orchestrator` | Split → Parallel Chains | @Deprecated 但生产在用 |
| 多轮对话 ReAct | `RamChatOrchestrator` | Tool-use loop + streaming | 活跃（新架构） |

### 1.2 证据

**DAG 执行器是线性列表，不是真正的 DAG：**

```java
// DagExecutor.java:54-56
public ExecutionResult run(List<DagNode> orderedNodes,
                           long sessionId,
                           Map<String, Object> initialInput) {
```

`orderedNodes` 是一个 `List`，节点按调用方传入的顺序依次执行。不存在拓扑排序、并行调度或多路输入汇聚。这本质上是一个 Pipeline 而非 DAG，命名有误导性。

**增量 Hash 缓存的实现质量：**

```java
// InputsHasher.java:21-23
private static final ObjectMapper CANONICAL = new ObjectMapper()
        .configure(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY, true)
        .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);
```

`InputsHasher` 使用 JDK 原生 `SHA-256` + Jackson 规范化 JSON 序列化，不依赖外部缓存中间件（如 Redis）。语义等价性测试覆盖了 Map 插入顺序、嵌套结构、List 排序差异。

**当前架构正在向"混合模式"演进：**

- `ClarifyNode` 内部调用 `ClarifyLlmClient`，LLM 可通过 tool_use 自主探索知识图谱（ReAct 叶子节点）。
- `Phase2AnalysisNode` 实现了 8 步 KG 数据收集工作流：`extractKeywords → inferDomain → hybridSearch × N → affecting → calleesTree → rootEntries → loadMethodBodies → bridges`。这是 DAG 骨架内的 ReAct 模式。
- `RamChatOrchestrator` 全面采用多轮 tool-use 循环 + WebSocket streaming。

### 1.3 评估

**正面：**
- 增量 Hash 缓存正确性良好。`clarify_history` 被纳入 hash 计算，因此"相同问题、不同对话上下文"会产生不同的 hash，缓存不会误命中。
- `InputsHasher` 是纯函数，无副作用，线程安全。
- 从线性 Pipeline → 节点内 ReAct → 全多轮对话的演进路径清晰。

**负面：**
- "线性列表叫 DAG" 这个命名误导。虽然不影响功能，但对于新加入的开发者而言，期望看到依赖图解析时会感到困惑。
- 增量 Hash 缓存有一个微妙的风险：如果 LLM 输出的某个非结构化字段（如 `markdown_report`）中包含了**时间戳或随机 token**，会导致同一语义的 hash 不同，造成缓存无效化。需要在 CHECKPOINT 处剥离这些`non-deterministic` 字段。
- Hash 碰撞风险：SHA-256 的碰撞概率可以忽略不计，但 `InputsHasher.hash()` 直接对 `Map<String, Object>` 做 JSON 序列化。如果下游节点的输入包含 BigDecimal、自定义对象等，Jackson 序列化可能有精度损失或循环引用风险。

**判定：** 7.0/10。Pipeline 模式对"需求分析"这个固定流程是合适的，但"DAG"标签需要修正。增量 Hash 缓存质量高。混合编排的方向正确。

### 1.4 建议

| 优先级 | 建议 |
|---|---|
| HIGH | 重命名 `DagNode` → `PipelineNode` 或将 `orderedNodes` 改为真正的拓扑排序输出 |
| MEDIUM | 为 LLM 输出字段（如 `markdown_report`）建立 `non-deterministic` 字段清单，在 hash 计算前剥离 |
| LOW | 考虑为 `Phase2AnalysisNode` 的 8 步骤增加并行化（affecting/calleesTree/rootEntries 互不依赖） |

---

## 2. HITL 设计质量 -- 8.0 / 10

### 2.1 证据

**三种操作完整覆盖：**

```java
// RequirementAnalysisOrchestrator.java:221-246
public ExecutionResult confirmAndResume(long sessionId,
                                         String nodeName,
                                         String action,        // "approve" / "reject" / "edit"
                                         String feedback,      // reject 时的反馈
                                         Map<String, Object> editedOutput,  // edit 时的新输出
                                         List<DagNode> nodes) {
```

**reject 的反馈注入逻辑：**

```java
// DagExecutor.java:306-319
private String findRejectionFeedback(List<AgentEvent> events, String nodeName) {
    for (int i = events.size() - 1; i >= 0; i--) {
        AgentEvent ev = events.get(i);
        if (ev.getType() != EventType.HITL_RES) continue;
        Map<String, Object> payload = parsePayload(ev.getPayload());
        if (payload == null || !nodeName.equals(payload.get("nodeName"))) continue;
        if ("reject".equals(payload.get("action"))) {
            Object fb = payload.get("feedback");
            return fb instanceof String s ? s : null;
        }
        return null; // approved or edit — no feedback injection
    }
    return null;
}
```

反馈被注入到 `input["hitl_feedback"]`，改变了 inputsHash，从而强制缓存失效后重执行。

**edit 的级联失效：**

```java
// RequirementAnalysisOrchestrator.java:311-331
private void overwriteCheckpoint(long sessionId, String nodeName,
                                  Map<String, Object> editedOutput) {
    String newHash = InputsHasher.hash(editedOutput);
    // ... 写入新的 CHECKPOINT，payload["edited"] = true，inputsHash = newHash
}
```

新 CHECKPOINT 的 `inputsHash` 与原始不同，所有下游节点的 `findCachedOutput()` 会缓存失效并重新执行。

**多轮 CLARIFY 的 Q&A 配对：**

```java
// RequirementAnalysisOrchestrator.java:176-206
private List<Map<String, Object>> collectAllClarifyRounds(long sessionId) {
    // 配对连续 CLARIFY_REQ → CLARIFY_RES
    // 无对应 CLARIFY_RES 的 CLARIFY_REQ 被正确排除
}
```

### 2.2 评估

**正面：**
- approve/reject/edit 三种 HITL 操作的设计完备性很高。
- edit 的级联失效机制正确：新 CHECKPOINT 的 inputsHash 变化 → 下游全量重跑。
- reject 的反馈注入干净：反馈进入 `hitl_feedback` 字段，不污染业务字段。
- `collectAllClarifyRounds()` 正确排除了"只有 REQ 没有 RES"的当前挂起轮次。

**负面：**
- `isNodeConfirmed()` 的扫描逻辑有一个边界情况：如果用户在同一节点上 approve → reject（两次 HITL_RES），扫描逻辑正确取到最新的 HITL_RES（反向扫描 `return true` 在第一层 match 时），但 reject 的 `findRejectionFeedback` 扫描逻辑也取最新的 HITL_RES，可能存在竞态：approve 后再 reject，最新是 reject 但中间那次 approve 被忽略了。实际上这是正确行为 —— 用户 reject 后就应该用 reject 的反馈。
- HITL 门控被跳过时（`isFirstRun || forceRerun`），所有中间节点自动通过。如果 first-run 的某个节点输出了明显错误但又不触发 schema 验证失败，用户没有机会在 first-run 中 review。这是一个设计取舍："首次运行全速通过"vs"首次运行也逐节点确认"。

**判定：** 8.0/10。HITL 设计是整套架构中最成熟的部分。三种操作的完备性、反馈注入机制、级联失效逻辑均经过仔细设计。

### 2.3 建议

| 优先级 | 建议 |
|---|---|
| LOW | 考虑增加 `action = "skip"` 操作：用户确认某节点暂不需要，跳过而非 approve（跳过时写入 CHECKPOINT 但标记为 `skipped = true`，下游仍执行） |
| LOW | first-run 跳过 HITL 门控是可接受的默认行为，但应在配置中可切换（`ram.hitl.skip-on-first-run: true`） |

---

## 3. 事件溯源 -- 7.5 / 10

### 3.1 证据

**完整的事件类型：**

```java
// EventType.java
public enum EventType {
    USER_MSG,           // 初始输入
    ASSISTANT_DELTA,    // streaming delta (RamChatOrchestrator)
    TOOL_USE,           // tool 调用开始 (RamChatOrchestrator)
    TOOL_RESULT,        // tool 返回 (RamChatOrchestrator)
    CHECKPOINT,         // DAG 节点输出快照 + inputsHash
    CLARIFY_REQ,        // 澄清问题
    CLARIFY_RES,        // 用户澄清回答
    HITL_REQ,           // 人类确认请求
    HITL_RES,           // 人类确认结果 (approve/reject/edit)
    NODES_CLEARED,      // ← 未找到写入代码
    ERROR,              // 节点执行错误
    TURN_INTERRUPTED,   // 多轮对话中断 (RamChatOrchestrator)
    MESSAGE             // ← 未找到明确使用场景
}
```

**CHECKPOINT 事件携带的元数据：**

```java
// DagExecutor.java:203-221
private void appendCheckpoint(long sessionId, String nodeName,
                              String inputsHash, Map<String, Object> output) {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("nodeName", nodeName);
    payload.put("inputsHash", inputsHash);
    payload.put("output", output);
    // ... AgentEvent 本身的 inputsHash 字段也设置了
}
```

**AgentEvent 关键字段：**

```java
// AgentEvent.java:20-39
private Long id;
private long sessionId;
private long seq;              // ← DagExecutor 和 Orchestrator 均未设置
private EventType type;
private String payload;        // JSON blob
private String idempotencyKey; // 幂等键（UNIQUE 约束）
private Integer clarifyRoundNo; // CLARIFY_REQ/RES 的轮次编号
private String inputsHash;     // CHECKPOINT 的输入 hash
private String circuitState;   // 始终 "OK"
private String validatorStatus; // "OK" 或 "FAIL"
```

### 3.2 评估

**正面：**
- `idempotencyKey` 机制 + repository 层面的 UNIQUE 约束保障了事件的 exactly-once 语义，重试安全。
- CHECKPOINT 的 `nodeName` + `inputsHash` + `output` 三元组提供了完整的重放能力。
- `clarifyRoundNo` 使得多轮 CLARIFY 可以按轮次排序。
- 时间戳使用 `System.currentTimeMillis() / 1000L`（秒级 epoch），数据库友好。

**负面：**

1. **`seq` 字段为 0**：`DagExecutor.appendCheckpoint()` 和 `RequirementAnalysisOrchestrator` 的所有 `append*()` 方法均使用 `AgentEvent.builder()` 直接构建，不设置 `seq`。AgentEvent 的无参构造 `seq` 默认值为 0。如果一个 session 有多个 CHECKPOINT，它们的 `seq` 均为 0，无法排序。相比之下，`RamChatOrchestrator.appendEvent()` 同样不设置 `seq`，依赖 `AgentEventRepository.append()` 自动分配。需要确认 repository 是否在 `INSERT` 时写了 `seq`。

2. **两个"幽灵事件类型"**：`NODES_CLEARED` 和 `MESSAGE` 在枚举中定义，但 grep 全代码库找不到写入代码（`NODES_CLEARED` 只在 AgentEvent 的 `NODES_CLEARED` 枚举值声明处出现）。这些事件类型增加了维护者的认知负担。

3. **HITL_REQ 事件未被读取**：`DagExecutor.appendHitlReq()` 创建了 `HITL_REQ` 事件，但 `RequirementAnalysisOrchestrator.resume()` 和 `confirmAndResume()` 在重建 session state 时从未读取 HITL_REQ。换言之，HITL_REQ 是一个"仅写入、永不读取"的事件 —— 典型的幽灵事件。

4. **`circuitState` 始终为 "OK"**：所有写入代码硬编码 `circuitState("OK")`。电路状态监控没有实际运作。

**判定：** 7.5/10。事件模型设计完整，幂等性保障到位。但 `seq` 字段的缺失和幽灵事件类型降低了可维护性。

### 3.3 建议

| 优先级 | 建议 |
|---|---|
| HIGH | 确认 `AgentEventRepository.append()` 在 INSERT 时自动分配 `seq`；如不具备，添加 sequence 生成逻辑 |
| MEDIUM | 删除或注释掉 `NODES_CLEARED` 和 `MESSAGE` 枚举值（如确实未使用） |
| LOW | 在 `resume()` 中考虑读取 HITL_REQ 以完整重放 session 状态 |

---

## 4. Phase2V2 迁移债 -- 3.0 / 10

### 4.1 证据

**@Deprecated 但生产在用：**

```java
// Phase2V2Orchestrator.java:24-31
/**
 * @deprecated 由 {@link com.huawei.hisi.ram.chat.RamChatOrchestrator} 替代。
 *     新架构采用多轮对话 + 工具循环，取代 Phase1/Phase2 两阶段流水线。
 */
@Deprecated
public class Phase2V2Orchestrator {
```

```java
// RamPhase2V2Controller.java:33
private final Phase2V2Orchestrator orchestrator;  // 生产控制器直接注入
```

`RamPhase2V2Controller` 是活跃的 REST Controller（`POST /api/ram/status/phase2/v2/start`），在生产中调用 `Phase2V2Orchestrator`。

**RamChatOrchestrator 未覆盖 Phase2V2 的核心场景：**

```java
// Phase2V2Orchestrator 的核心功能：
// 1. 从 Phase1 CHECKPOINT 继承 entryPoints + phase1Summary
// 2. KG entryPoints fallback
// 3. 增强追问问题（合并 Phase1 摘要）
// 4. ChainSplitter.split() → 每个入口点一条链路
// 5. 多链路并行分析（每条 Chain 由 ClaudeChainAnalysisAgent 执行）
//
// RamChatOrchestrator 的功能：
// 1. 多轮对话 + 工具循环（KG 工具 + ProjectOverviewTool）
// 2. Streaming delta 推送（WebSocket）
// 3. 中断/继续（injectAndContinue）
//
// RamChatOrchestrator 完全不引用 Phase2V2Orchestrator，
// 也不引用 ChainSplitter 或 ClaudeChainAnalysisAgent。
```

**Phase2V2 的异步分析未真正执行：**

```java
// Phase2V2Orchestrator.java:76-98
// Step 5: 构建反映中间态的骨架报告（chainCount 和 chainSummaries 真实）
List<DetailLayer.ChainSummary> chainSummaries = chainContexts.stream()
        .map(ctx -> new DetailLayer.ChainSummary(
                ctx.chainId(),
                ctx.chainName(),
                "等待分析",          // ← 硬编码 "等待分析"
                true,                // ← 硬编码 true (pending)
                "/api/ram/status/phase2/v2/" + parentSessionId + "/chain/" + ctx.chainId() + "/report"
        ))
        .collect(Collectors.toList());

DetailLayer detailLayer = new DetailLayer(
        chainSummaries,
        chainContexts.size(),   // 真实 chainCount
        0,                      // totalMethodsAnalyzed = 0（未执行）
        0                       // totalCodeSnippets = 0（未执行）
);

return new Phase2V2Report(..., "PENDING", question);
// 注意：ClaudeChainAnalysisAgent 从未在此方法内被调用
```

**Phase2V2 使用 in-memory ConcurrentHashMap 存储报告：**

```java
// RamPhase2V2Controller.java:44
// In-memory storage for V2 reports (TODO: migrate to AgentEvent checkpoint)
private final Map<String, Phase2V2Report> reportStore = new ConcurrentHashMap<>();
```

这意味着服务重启后所有 Phase2V2 报告丢失。

**测试覆盖率极低：**

```java
// Phase2V2OrchestratorTest.java:11-16
@Test
void orchestrate_returnsLayeredReport() {
    Phase2V2Orchestrator orchestrator = new Phase2V2Orchestrator(null, null, null, null, null);
    assertThat(orchestrator).isNotNull();
    // 所有依赖为 null，根本无法测试任何实际行为
}
```

### 4.2 评估

**迁移状态矩阵：**

| Phase2V2 能力 | Phase2V2Orchestrator | RamChatOrchestrator | 覆盖状态 |
|---|---|---|---|
| multi-agent splitting (ChainSplitter) | YES | NO | **未覆盖** |
| Phase1 CHECKPOINT 数据继承 | YES | NO | **未覆盖** |
| 追问问题增强 (buildEnhancedQuestion) | YES | NO | **未覆盖** |
| KG entryPoints fallback | YES | NO | **未覆盖** |
| 每条链路独立深度分析 (ClaudeChainAnalysisAgent) | YES | NO | **未覆盖** |
| 分层报告 (SummaryLayer + DetailLayer) | YES | NO | **未覆盖** |
| 异步执行 | YES | YES | 已覆盖 |
| Streaming 输出 | NO | YES | 新能力 |
| 中断/继续 | NO | YES | 新能力 |
| 多轮对话 tool-use | NO | YES | 新能力 |

**风险评估：**

`@Deprecated` 但生产使用的组合构成了"虚假废弃"反模式。RamChatOrchestrator 没有实现 Phase2V2 的核心拆分-并行分析流程，因此不能算"替代品"。两者实际上解决的是**不同的问题**：Phase2V2 是多链路并行深度分析，RamChatOrchestrator 是单会话多轮对话探索。

`ClaudeChainAnalysisAgent` 虽然源码存在，但 `Phase2V2Orchestrator.orchestrate()` 不调用它。`RamPhase2V2Controller` 的异步执行块只调用了 `orchestrate()`，而 `orchestrate()` 返回 PENDING 骨架后从未启动实际的链分析。这意味着**多链路并行分析功能在当前的 Phase2V2 路径上是断裂的** —— report 永远是 PENDING 状态。

**预计迁移工作量：**

| 工作项 | 估时 |
|---|---|
| 删除 `@Deprecated` 标记（如确认不做迁移） | 10 min |
| 将 ChainSplitter + 并行分析逻辑集成到 RamChatOrchestrator | 3-5 days |
| 为 RamChatOrchestrator 添加分层报告生成 | 1-2 days |
| 将 ConcurrentHashMap 存储迁移到 AgentEvent CHECKPOINT | 0.5 day |
| 端到端测试覆盖 | 1-2 days |
| **总计（完整迁移）** | **6-10 个工作日** |
| **总计（仅清理 @Deprecated 标记）** | **10 分钟** |

**判定：** 3.0/10。@Deprecated 标记是对开发者的一种误导。Phase2V2 的"拆分-并行-深度分析"工作流在 RamChatOrchestrator 中没有等价物。如果没有迁移计划，应删除 @Deprecated 并继续维护 Phase2V2；如果有迁移计划，需要明确 RamChatOrchestrator 如何承载 Phase2V2 的 6 个未覆盖能力。

### 4.3 建议

| 优先级 | 建议 |
|---|---|
| CRITICAL | 明确决策：继续维护 Phase2V2 路径（删除 @Deprecated）还是完成迁移（删除 Phase2V2Orchestrator） |
| CRITICAL | 如果保留 Phase2V2：将 `ClaudeChainAnalysisAgent` 实际集成到 `orchestrate()` 中，使 report 不再是永远 PENDING 的骨架 |
| HIGH | 无论哪个决策，将 `reportStore` (ConcurrentHashMap) 迁移到 AgentEvent CHECKPOINT 持久化 |
| HIGH | 补全 `Phase2V2OrchestratorTest`，使其真正测试 orchestrate() 的行为而非 null-arg 构造 |

---

## 5. 安全与韧性 -- 3.5 / 10

### 5.1 证据

**电路断路器已实现但未集成（僵尸组件）：**

```
搜索 "CircuitBreaker" 的引用：
  ├── CircuitBreaker.java          ← 定义
  ├── CircuitBreakerTest.java      ← 单元测试（测试通过）
  ├── AgentManifest.java           ← 声明式配置（元数据）
  └── 无                          ← 无任何业务代码调用 check()
```

`DagExecutor.run()` 不检查电路状态：

```java
// DagExecutor.java:54-169 — 完整 run() 方法
// 无 CircuitBreaker.check() 调用
// 无超时控制
// 无重试逻辑
// 无 clarify 轮数上限检查
```

**CircuitPolicy 的默认安全边界：**

```java
// CircuitPolicy.java:93
return new CircuitPolicy(200_000L, 30L, 5, usdCap, 20, 3, 0.8);
// maxTokensGlobal = 200,000
// maxDurationMinutes = 30
// maxClarifyRounds = 5     ← 定义了但从未强制!
// maxCostUsd = derived     ← 定义了但从未强制!
// maxParallelSessions = 20
// maxRetriesPerNode = 3    ← 定义了但从未实现!
```

**唯一的超时控制：**

| 位置 | 超时值 | 作用范围 |
|---|---|---|
| `RamChatOrchestrator.timeoutSeconds` | 300s (default) | `orTimeout()` on CompletableFuture |
| `WorkflowController.sseTimeoutMs` | 300000ms (default) | SSE 连接超时 |
| `RamPhase2V2Controller.orchestrate()` | 5 min | `orTimeout()` on CompletableFuture |
| **DagExecutor.run()** | **无** | **DAG 节点执行** |
| **RequirementAnalysisOrchestrator** | **无** | **start/resume/confirmAndResume** |

### 5.2 评估

**这是一个关键的安全缺口。** CircuitBreaker/CircuitPolicy 是一个质量不错的安全框架设计，但完全是"写在纸上"的：

- Token 限制：定义了但从未累积和检查
- 费用限制：定义了 `maxCostUsd` 和 `USD_TO_CNY_RATE`，但没有任何地方跟踪每次 LLM 调用的 token 消耗
- Clarify 轮数上限：定义了 `maxClarifyRounds = 5`，但 `resume()` 可以无限次调用
- 节点重试上限：定义了 `maxRetriesPerNode = 3`，但 DagExecutor 遇到异常直接 FAIL 而不重试
- 并行会话上限：CircuitBreaker 需要外部传入 `currentParallelSessions`，但调用方没有统计这个值

即使 CircuitBreaker 被集成，由于没有 `SessionStats` 的 runtime 累加逻辑，所有检查都会基于零值状态（`cumulativeTokens=0, clarifyRounds=0, costUsd=0`），实际上不会触发任何保护。

**增量缓存可能导致 stale 结果：**

如果 KG 数据在两次 DAG 执行之间更新了（例如新代码提交触发了 KG 重建），而用户的输入完全相同时，`InputsHasher` 会返回相同的 hash，导致 `findCachedOutput()` 命中旧 CHECKPOINT。缓存没有 TTL，没有与 KG 版本的关联。这是一个"stale 缓存"风险。

**判定：** 3.5/10。CircuitBreaker 是一个"写好但没接线"的断路器 —— 所有安全边界都在策略记录中定义了，但没有一条被实际执行。这是一个系统性的安全问题。

### 5.3 建议

| 优先级 | 建议 |
|---|---|
| CRITICAL | 在 `DagExecutor.run()` 循环中调用 `CircuitBreaker.check()`（至少每节点一次） |
| CRITICAL | 实现 SessionStats runtime 累加：每次 LLM 调用后更新 `cumulativeTokens` + `costUsd`，每次 CLARIFY_REQ 时 `withClarifyRound()` |
| HIGH | 添加 DAG 节点级超时：每个 `node.execute()` 包装在 `CompletableFuture.orTimeout()` 中 |
| MEDIUM | 为 CHECKPOINT 缓存添加 KG 版本标记，避免 KG 更新后的 stale 缓存命中 |
| MEDIUM | DagExecutor 异常处理中增加重试逻辑（`maxRetriesPerNode` 次后走 fallback） |
| LOW | 考虑为增量缓存添加 TTL（基于创建时间戳） |

---

## 6. 与 Agentic Engineering 的对齐度 -- 7.5 / 10

### 6.1 "先拆解 → 逐节点验证 → 最终合成"的工程化思维

**拆解阶段：**

系统有清晰的节点职责分离。以 DAG Pipeline 为例：

```
CLARIFY → PROJECT_OVERVIEW → TECH_PLAN → PHASE2_ANALYSIS → IMPLEMENT → VERIFY
```

每个节点的职责单一、可独立测试。`DagNode` 接口只有 3 个方法（`name()`, `agentId()`, `execute()`），是干净的抽象。

**验证阶段：**

```java
// ClarifyNode.java:97-103
ValidationResult result = schemaValidator.validate(SCHEMA_NAME, extracted);
if (!result.passed()) {
    List<String> questions = buildQuestions(result);
    throw new ClarifyRequiredException(questions);
}
```

每个节点通过 `SchemaValidator` 验证其输出，不通过则抛 `ClarifyRequiredException` 请求用户输入。使用 JSON Schema draft-07 做类型安全契约。

**合成阶段：**

DAG 节点的输出通过 `previousOutput` 作为下一个节点的 `input` 传递。`ExecutionResult` (record) 包含 `lastNodeOutput` 作为最终合成结果。

### 6.2 节点间数据契约

**正面：**
- SchemaValidator 基于 JSON Schema draft-07，有 6 个 schema 文件（`clarify.input/output`, `impact.output`, `implement.output`, `tech_plan.output`, `verify.output`）
- Schema 定义了 `required` 字段，validator 区分 `missingFields` 和 `violations`

**负面：**
- Schema 验证仅在 ClarifyNode 中实际调用。其他节点（ProjectOverviewNode 等）可能没有强制 schema 验证。
- `additionalProperties: true` 意味着 schema 验证不会拒绝未知字段，弱化了类型安全契约。
- 节点间传递的是 `Map<String, Object>`，是弱类型的。没有编译时类型检查。下游节点使用 `(String) input.get("projectPath")` 这样的强制转型。

### 6.3 "不应该执行"的 guard

**现状：该系统完全没有 guard。**

```java
// RequirementAnalysisOrchestrator.start()
public ExecutionResult start(String userId,
                             Map<String, Object> userInput,
                             List<DagNode> nodes) {
    AgentSession session = sessionRepo.save(AgentSession.newRunning(userId, SessionType.DEMAND));
    storeInitialInput(session.getId(), userInput);
    return executor.run(nodes, session.getId(), userInput);
    // 无任何 guard check：
    // - 不检查 CircuitBreaker（会话是否已超过令牌/费用/时间限制？）
    // - 不检查用户并发会话数
    // - 不验证输入完整性
    // - nodes 参数可能为空列表，会静默"成功"返回 DONE 状态
}
```

### 6.4 评估

**正面：**
- "分解-验证-合成"的管道模式清晰。
- SchemaValidator 提供了基本的类型安全契约。
- DagNode 接口设计简洁，遵循接口隔离原则。
- 不可变性模式被正确使用（`List.copyOf()`, `Map.copyOf()` in ExecutionResult）。

**负面：**
- `Map<String, Object>` 作为节点间数据契约是弱类型的天花板。虽然 JSON Schema 验证提供了运行时保障，但不如编译时类型安全。
- 所有 guard 检查缺失。一个恶意的或错误的调用可以轻易触发 LLM 调用而不受任何限制。
- `execute()` 可以接受 `null` 输入（DagExecutor 传入 `Map.of()` 作为默认值），但 ClarifyNode 此时会抛 `IllegalArgumentException`，说明契约不一致。

**判定：** 7.5/10。工程化思维清晰，但从"设计"到"执行"有断层 —— Schema 定义了但验证不完整，CircuitBreaker 定义了但未集成。这是典型"纸面工程质量高，运行工程质量低"的模式。

### 6.5 建议

| 优先级 | 建议 |
|---|---|
| HIGH | 在 `start()` / `resume()` / `confirmAndResume()` 入口添加 guard check（至少检查 CircuitBreaker） |
| MEDIUM | 为 `DagNode.execute()` 添加 `@NonNull` 注解或 JSR-380 验证 |
| MEDIUM | 将 `states` 参数空列表检查添加到 `start()`，返回明确的错误而非静默成功 |
| LOW | 考虑为节点输出引入 Java record 类型替代 `Map<String, Object>`（如 `ClarifyOutput`, `OverviewOutput`），提升编译时类型安全 |

---

## 附录 A：审计发现的 Bug 模式

| # | 描述 | 严重度 | 文件 |
|---|---|---|---|
| B1 | CircuitBreaker 从未被业务代码调用 -- 所有安全边界形同虚设 | CRITICAL | DagExecutor.java, RequirementAnalysisOrchestrator.java |
| B2 | Phase2V2.orchestrate() 返回永远 PENDING 的报告 -- 实际分析从未执行 | CRITICAL | Phase2V2Orchestrator.java:76-98 |
| B3 | Phase2V2Orchestrator @Deprecated 但 RamChatOrchestrator 未覆盖其核心场景 | CRITICAL | Phase2V2Orchestrator.java:30 |
| B4 | AgentEvent.seq 在 DAG 路径中始终为 0 -- 事件无法排序 | HIGH | DagExecutor.java 所有 append*() 方法 |
| B5 | NODES_CLEARED, MESSAGE 枚举值为幽灵事件类型 -- 从未写入 | MEDIUM | EventType.java |
| B6 | HITL_REQ 事件仅写入、永不读取 -- 幽灵事件 | MEDIUM | DagExecutor.java:266-281 |
| B7 | Phase2V2 报告存储在 ConcurrentHashMap 中 -- 重启丢失 | MEDIUM | RamPhase2V2Controller.java:44 |
| B8 | DAG 节点执行无超时控制 | HIGH | DagExecutor.java:122 |
| B9 | Clarify 轮数上限在 CircuitPolicy 中定义但从未强制执行 | HIGH | RequirementAnalysisOrchestrator.java:76-93 |
| B10 | 增量缓存无 TTL 且不与 KG 版本关联 -- stale 缓存风险 | MEDIUM | DagExecutor.java:108 |

---

## 附录 B：评分细则

| 维度 | 原始分 | 加权 | 加权分 | 说明 |
|---|---|---|---|---|
| DAG 编排 vs ReAct | 7.0 | 1.5 | 10.5 | 基础扎实，Pipeline 命名误导，混合演进方向正确 |
| HITL 设计 | 8.0 | 1.0 | 8.0 | 最成熟的部分，三种操作完整，反馈注入干净 |
| 事件溯源 | 7.5 | 1.0 | 7.5 | 设计完整，幽灵事件和 seq 为 0 是瑕疵 |
| Phase2V2 迁移债 | 3.0 | 2.0 | 6.0 | **高风险**，虚假废弃，实际分析未执行 |
| 安全与韧性 | 3.5 | 2.0 | 7.0 | **僵尸断路器**，系统性安全缺口 |
| Agentic Engineering 对齐 | 7.5 | 1.0 | 7.5 | 设计思维清晰，运行层面有断层 |
| **综合** | | **8.5** | **46.5/70 = 6.64** | 四舍五入 **6.5** |

> 关键风险：安全维度（CircuitBreaker 未集成）和迁移维度（Phase2V2 假废弃+假执行）是最严重的拖分项。如果这两项修复，整体评分可提升到 7.5-8.0。
