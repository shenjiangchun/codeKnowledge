# arch-deep-analysis 升级：ReAct 智能体流（设计）

## Context

当前 `arch-deep-analysis` 是**线性 3 节点 DAG**（见 `design.md` D5）：`arch_evidence_collect`（确定性）→ `arch_root_cause`（单次 `DeepseekJsonClient.chatJson`）→ `arch_status_report`（单次 `chatJson`）。根因与报告两个 LLM 节点各只发**一次性** JSON 抽取请求，没有「推理 → 调工具 → 观察 → 再推理」的循环，因此 AI 分析的输入被限定在证据收集节点拼好的 `evidencePack`（实时报告四章 + ego-net 摘录），看不到源码/方法体/全图，产出自然"没营养"。

用户诉求（本轮定案）：
1. **升级真 ReAct**：让 LLM 在分析过程中能**反复查询 KG/源码**，而不是吃一份静态摘要。
2. **补四层检测目标**：项目级（大泥球、全局循环依赖）、模块级（臃肿包、模块枢纽、实现类外泄、反向依赖）、类级（贫血数据类、幽灵类）、方法级（超长方法、过多参数、深层嵌套→过深调用链）——作为 ReAct 智能体的证据与工具基础。
3. **硬约束：不碰生成侧（KG 构建管线）**，所有新增检测只读现有图数据；不引入新编排框架。

**LLM 基础设施事实（防误判）**：
- `DeepseekJsonClient.chatJson(system, user, Class<T>)`：OpenAI 兼容 `json_object` 强制，单发无工具无多轮，失败返 null。当前 arch 流专用，且是**为绕开 anthropic→deepseek 网关的 JSON 污染问题而专门引入的**。
- `RamClaudeJsonClient` + `ToolDefinition` + `AgentTools`：Claude SDK 原生 function-calling + SSE 流式，RAM 聊天用。但该链路已知问题：网关 `tool_choice` 失效、thinking 块拼进 content。
- `FixFlowRunner`：写死的 9 步流水线，`tool_use_start`/`tool_result` 事件形状与 RAM 一致，但**步骤硬编码，不是 LLM 决策的 ReAct 循环**——代码库现无 ReAct 循环可复用。

## Goals / Non-Goals

**Goals:**

1. 把 `arch-deep-analysis` 从线性 3 节点改为「确定性证据收集 + 单个 ReAct 智能体节点」，LLM 在循环内通过只读工具查询 KG/源码，产出根因 + 四段式报告。
2. 新增**可复用的工具注册表**（每个工具 = 一个只读 KG 查询 + 名字/参数 schema/描述），ReAct 智能体按需调用；加工具 = 注册一行，不写个性化分支。
3. 补齐四层检测目标（免重建部分），作为确定性证据与 ReAct 工具的数据来源。
4. 保留现有 SSE 进度、checkpoint 缓存、降级契约，前端复用 `useWorkflow` 进度流。

**Non-Goals:**

- **不改 KG 生成侧**（`CodegraphToNeo4jTransformer`/`PythonAstVisitor`/`Neo4jStorageService` 等构建管线一行不动）。所有新增指标只读现有节点属性与关系。
- 不引入原生 function-calling 新客户端（本轮用 `DeepseekJsonClient` 手动 ReAct，见 D2）。
- 不重构 `DagExecutor` / `RequirementAnalysisOrchestrator` / RAM 聊天（零回归）。
- 不做对话式追问（深度分析仍是一次触发一次产出）。

## Decisions

### D1 — 节点结构：确定性证据收集 + 单个 ReAct 智能体节点

```
arch_evidence_collect (确定性, 保留)  →  arch_react_agent (单节点, 内部 ReAct 循环)
```

- 保留 `arch_evidence_collect`：确定性、可 checkpoint 缓存、秒级，继续产出 `evidencePack`（四章 + issues + ego-net 摘录）。
- 移除 `arch_root_cause` + `arch_status_report` 两个单发 LLM 节点，合并为 **`ArchReActAgentNode`**（单 DagNode，`execute()` 内部跑 ReAct 循环），最终产出 `{rootCauseResult, markdownReport, degraded}` 与旧契约兼容。

- 备选：三节点全保留、仅中间节点改 ReAct —— 否，报告节点也是单发 LLM，拆开无法共享 ReAct 循环里的观察上下文，徒增两次 LLM 往返。
- 备选：证据收集也并入 ReAct 节点 —— 否，证据收集是确定性秒级查询，checkpoint 缓存价值高；并入会让首次 LLM 调用前就重跑全部查询，且丧失"无 LLM 也能看证据"的降级面。

### D2 — ReAct 循环机制：DeepseekJsonClient 上的手动 JSON 循环

**已确认（2026-10-10 定案）**：在 `DeepseekJsonClient.chatJson` 之上实现手动 ReAct 循环，**不用原生 function-calling**。

> 定案依据（原生 function-calling 不可用的代码事实）：
> 1. 中转网关把推理模型的 thinking 块拼进 content，`tool_choice` 在中转层失效（`DeepseekJsonClient` Javadoc 明示）；
> 2. `AnthropicHttpClient` / `RamClaudeJsonClient` 均 `@Deprecated(forRemoval=true)`，且 `buildRequestBody` 从不发 `tool_choice` 字段；
> 3. `AnthropicHttpClient.stream` 丢弃 SSE 里的 token usage（cumulativeTokens=0，无法做 token 预算护栏）；
> 4. 直接复用 RAM 客户端会把 arch 耦合到废弃链路上，扩大回归面，违背「零影响其他功能」约束。

每次迭代发一个 `json_object` 请求，契约 `schemas/arch/react-step.json`：

```json
{
  "thought": "需要确认 controller→repository 的反向依赖是否真实存在",
  "action": { "tool": "class_ego_net", "args": { "className": "com.foo.OrderController" } }
}
// 或终止：
{ "thought": "证据已充分", "final_answer": { "issues": [...], "report": { ... } } }
```

循环体（`ArchReActAgentNode` 内部）：

1. system prompt 固定（工具清单 + 各工具签名/用途 + "一次只调一个工具" + 输出格式约束）。
2. user prompt = `evidencePack` 摘要 + 已积累的 `[thought, action, observation]*` 历史（截断窗口）。
3. `chatJson` → 解析 JSON → 若 `action`，经工具注册表派发（只读 KG 查询）→ 追加 observation → 下一轮；若 `final_answer`，SchemaValidator 校验后终止。
4. 终止条件：`final_answer`、达到 `maxSteps`（默认 6）、token 预算超限、连续 2 次 JSON 解析失败。

- 为什么不用 `RamClaudeJsonClient` 原生 tool-calling：该链路已知网关 `tool_choice` 失效 + thinking 污染 content，且会引入 arch→RAM 的客户端耦合，回归面大。`DeepseekJsonClient` 正是为绕开这些问题引入的，`json_object` 强制下每次输出天然干净 JSON，配合 `SchemaValidator` 即可稳定解析。
- 为什么不用 `response_format` 之外的流式：ReAct 的"流式"价值在于轮次间进度（见 D6），而非单次输出的 token 流；`chatJson` 单发 + 轮次间 `NODE_DELTA` 已够前端展示"正在查 X / 观察到了 Y"。
- 备选：原生 function-calling —— 后续若 deepseek 网关 tool 支持稳定，可把 D2 循环体替换为 `ToolDefinition` 派发而不改节点结构（工具注册表已解耦），保留演进空间。

### D3 — 工具集：只读 KG 查询注册表

定义 `ArchAgentTool`（`name()/description()/parameterSchema()/invoke(args, projectPaths)`）+ `ArchAgentToolRegistry`（`Map<String, ArchAgentTool>`）。每个工具委托 `KnowledgeGraphController` 既有只读方法，**不新增端点、不改生成侧**。

首期工具（全部只读，按需增补即注册）：

| 工具名 | 委托端点 | 用途 |
|---|---|---|
| `dashboard` | `getDashboard` | 项目全景/KPI/风险 |
| `dsm` | `getDsm` | 依赖矩阵 |
| `package_cycles` / `module_cycles` | `getPackageCycles`/`getModuleCycles` | 循环依赖 |
| `class_ego_net` | `getClassEgoNet` | 类邻域下钻 |
| `callees_tree` / `downstream` | `getCalleesTree`/`getDownstreamCallChain` | 下游调用链 |
| `upstream` / `root_entries` | `getCallChainsAffecting`/`getRootEntries` | 上游影响 |
| `method_detail` / `method_by_class` | `getMethodDetail`/`getMethodsByClass` | 方法体/签名 |
| `blast_radius` | `getBlastRadius` | 爆炸半径 |
| `implementations` / `interfaces` | `getImplementations`/`getInterfaces` | 实现类外泄判定 |
| `class_dependencies` | `getClassDependencies` | 类依赖 |
| `hotspots` | `getHotspots` | 方法热点 |
| `service_topology` / `domains` | `getServiceTopology`/`getDomains` | 服务拓扑/领域 |

- 每个工具 `invoke` 返回**截断后**的 JSON 摘要（避免 ego-net/下游树撑爆上下文，截断阈值可配）。
- 备选：直接注入 `KnowledgeGraphController` 的 30+ 方法而不做注册表 —— 否，注册表是"可扩展可复用"的关键，且能把参数 schema 与截断策略内聚到一处。

### D4 — 上下文与观察管理

- 上下文 = system（工具清单）+ 首轮 user（evidencePack 摘要）+ 每轮追加 `observation`。
- 观察窗口：保留最近 `K` 轮（默认 8）的 `thought/action/observation`，超出截断，防 token 膨胀。
- observation 由工具返回值经 `ObservationTrimmer` 截断（`maxChars` 默认 2000）后拼入。

### D5 — 终止与护栏

- `maxSteps` 默认 6（可配 `arch.react.max-steps`）。
- 总 token 预算：每轮 `max_tokens=16384`（复用 `DeepseekJsonClient` 现有上限），达到 `maxSteps` 未产出 `final_answer` → 用最后一轮已收集的 observation 走降级报告。
- 连续 2 次 JSON 解析失败 → 提前终止降级（不空转）。

### D6 — SSE 进度：轮次与工具事件

复用 `NODE_DELTA`（现有 `NodeEventSink`，见 `design.md`/`DagExecutor`），ReAct 循环内每轮 emit：
- `thought` → "推理：<摘要>"
- 工具调用前 → "查询工具：<tool>(<args摘要>)"
- 工具返回后 → "观察到：<N 条结果摘要>"
- 终止 → "分析完成：发现 N 个问题"

前端 `AiDeepAnalysisPanel.vue` 现有 process-log 直接复用，无需新事件类型。若需更细粒度区分工具事件，可后续引入 `TOOL_USE`/`TOOL_RESULT`（与 RAM 同形状），本轮不做以控制面。

### D7 — 降级与失败契约（兼容现有前端）

`ArchReActAgentNode.execute` 输出与旧节点同形：`{rootCauseResult, markdownReport, degraded}`。

- `final_answer` 过 SchemaValidator → 正常产出，`degraded=false`。
- ReAct 未收敛 / JSON 全失败 → 回退为模板报告（复用 `ArchStatusReportNode` 现有模板逻辑），`degraded=true`，前端降级提示不变。
- 证据收集节点失败（KG 未构建）→ 仍快速失败（`IllegalStateException`），前端可见原因，与现状一致。

## 四层检测扩展（补检测）与 ReAct 的关系

新增检测作为**确定性证据层**落地：`ArchDiagnosisReportService` 各章补写缺失项，使 `evidencePack` 变厚；同时这些检测结果可直接作为 ReAct 工具 `dashboard`/`dsm` 等返回内容的一部分被查询。

免重建可落地项（读现有图数据，不碰生成侧）：

| 层级 | 新增检测 | 数据依据 |
|---|---|---|
| 项目 | 全局循环依赖 | 聚合 domain/module/package 三级循环计数 + 涉及面 |
| 项目 | 大泥球 | 模块耦合密度 + 跨层依赖占比（DSM + 分层违规聚合） |
| 模块 | 臃肿包 | `packageName` 聚合类数/方法数 + percentile |
| 模块 | 模块枢纽 | 包级扇入扇出（DSM 依赖矩阵） |
| 模块 | 反向依赖明确化 | `layerOrder` + 分层违规归类（下层→上层） |
| 模块 | 实现类外泄 | `IMPLEMENTS`/`EXTENDS` 边（**已完整落库**，接口在 A、实现在 B 且被外泄） |
| 类 | 幽灵类 | `methodCount==0` 或（inDegree==0 且 outDegree==0） |
| 类 | 贫血数据类 | 类内方法名全为 get/set/is 前缀（启发式） |
| 方法 | 超长方法 | `startLine`/`endLine` → LOC |
| 方法 | 过多参数 | `signature` 字符串逗号计数（启发式，免重建） |
| 方法 | 过深调用链 | 替代"深层嵌套"：方法所在 CALLS 链深度（BFS，图谱天然支持） |

**明确不做**：真·深层嵌套（需 methodBody 解析，碰生成侧）、paramCount 落字段（需重建）。二者待构建侧成本评估后再单独决策。

## Risks / Trade-offs

- [ReAct 循环耗时/费用上升] → `maxSteps` + token 预算 + observation 截断硬护栏；降级路径保证无 LLM 也有模板报告。
- [工具返回过大致上下文膨胀] → `ObservationTrimmer` 截断；首期工具全走已截断的摘要接口。
- [JSON 契约漂移致解析失败] → `SchemaValidator` + 连续失败提前终止降级；契约走 `classpath:schemas/arch/`。
- [对 RAM / FixFlow 的回归] → 不改 `DagExecutor` 循环语义、不改 RAM 客户端；仅新增 `ArchReActAgentNode` 与工具注册表，旧 `arch_root_cause`/`arch_status_report` 节点随测试一起更新而非保留死代码。
- [四层检测误报] → 阈值走 percentile（相对项目规模自适应），每项检测单测覆盖。

## Migration Plan

1. 新增 `ArchAgentTool` + `ArchAgentToolRegistry` + 首期 ~12 个只读工具 + `ObservationTrimmer`。
2. 新增 `ArchReActAgentNode`（内部 ReAct 循环）+ `schemas/arch/react-step.json`；`WorkflowAutoConfig` 注册改为 证据收集 + react_agent 两节点。
3. 四层检测：`ArchDiagnosisReportService` 各章补写免重建项（项目/模块/类/方法各 1 个可复用检测器）。
4. 前端：`AiDeepAnalysisPanel.vue` 复现 process-log 已有能力（无需新事件）；节点进度从 3 节点改为 2 节点。
5. 回归：`mvn test` 全绿（旧节点测试改写为 ReAct 节点测试）；前端 `npm run build` 通过。

## Open Questions

1. ~~D2 机制确认~~ → **已确认：手动 JSON ReAct（DeepseekJsonClient）**。
2. **节点进度展示**：前端从 3 节点进度卡改为「证据收集 → ReAct 分析」2 节点 + 轮次日志，是否接受？（待确认）
3. ~~深层嵌套的替代口径~~ → **已确认：过深调用链（BFS on CALLS 边）替代，不碰生成侧**。
4. **旧节点去留**：`ArchRootCauseNode`/`ArchStatusReportNode` 是被 `ArchReActAgentNode` 取代（删除）还是暂留？建议删除（避免死代码），其降级模板逻辑内联进 ReAct 节点复用。（待确认）
