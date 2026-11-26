# 核心 Agent 流现状与 v2 优化方案

> 基于 2026-11-24 工作区代码核实。后端路径前缀 `hisi-dev-tool/src/main/java/com/huawei/hisi/`，前端路径前缀 `hisi-dev-tool-frontend/src/`，下文省略。

## 0. 结论先行

| 流 | v1 入口 | 最突出的不合理点（证据） | v2 做法 |
|---|---|---|---|
| 异常日志分析 | `POST /api/log/analyze` | 堆栈帧本是精确坐标，却转成搜索词做模糊检索（`loganalysis/nodes/KgSearchNode.java:91-94`）；三轮 LLM 之间没有任何外部验证（`ClaudeAnalyzeNode.java:64-117`）；LLM 骨干是被标记移除的类（`ram/nodes/impl/RamClaudeJsonClient.java:36`） | 精确取证 → 假设 → 验证 → 综合，每条结论必须引用证据编号，验证不通过就降置信度 |
| 架构报告生成 | `POST /api/workflow/start`（`arch-deep-analysis`） | ReAct 每步重放全部历史、无压缩（`workflow/nodes/ArchReActAgentNode.java:89-157`）；abort 只断 SSE 不停执行（`WorkflowController.java:281-289`）；只校验图引用、不校验论断（`:114-123`） | 同协议的 v2 路由 + 历史压缩 + 可真正取消 + 独立评审节点 |
| RAM | `POST /api/ram/sessions` | 入口绕经进程内 MCP 分发（`ram/controller/RamController.java:287`）；整个模块 14 个 LLM Client 类共 4106 行；`RamStatusController` 整体 `@Deprecated`（`:44`） | 本轮先出方案，待日志、架构两条 v2 验证通过后落地 |

**协议原则**：v2 路由统一用 `/api/v2/<资源>/...`（沿用项目里唯一真实存在的先例 `/api/v2/knowledge-graph`），请求体与响应体不变，v1 路由一行不动，前端改一个基地址常量即可切换或回滚。

## 1. 现状

### 1.1 异常日志分析

| 环节 | 实现 | 位置 |
|---|---|---|
| 入口 | 建报告行（pending）→ 异步执行 → 立即返回 `reportId` | `controller/LogAnalysisController.java:78-142` |
| 触发 | `@Async("analysisTaskExecutor")`；有 `projectPath` 走 DAG，否则走规则分析 | `service/LogAnalysisExecutor.java:119-163`、`:137-145` |
| 编排 | 自研线性执行器，固定 5 节点，节点间传 `Map<String,Object>`；任一节点抛错即中断并记 `errorNode` | `loganalysis/orchestrator/LogAnalysisDagOrchestrator.java:49`、`:69-136` |
| 节点 | `ParseNode` → `KgSearchNode` → `CodeContextNode` → `ClaudeAnalyzeNode` → `ReportNode` | 同上 `:49` |
| 图谱用法 | 帧转搜索词做 `hybridSearch`；首批为空才对后续帧分批补搜；业务帧取前 3 个入口点；关键帧取深度 3 的下游调用树 | `KgSearchNode.java:91-94`、`:100-130`、`:153`、`:163-181` |
| LLM | 三轮：R1 模式识别(JSON, 2000 token) → R2 因果推理(markdown, 8000) → R3 修复方案(JSON, 4000)；任一轮失败走降级分支 | `ClaudeAnalyzeNode.java:64-117` |
| 报告 | `finalReport`：`errorSummary` / `keyStackFrames` / `rootCauseAnalysis{markdown,confidence,entryPointsCount}` / `fixSuggestions[]` / `detailedAnalysis` | `loganalysis/nodes/ReportNode.java:29-124` |
| 进度 | WebSocket `/ws/log-analysis?reportId=`，事件 `NODE_START/NODE_COMPLETE/NODE_ERROR/DAG_COMPLETE` | `loganalysis/event/LogNodeEvent.java:15-32`、前端 `composables/useLogAnalysisWebSocket.ts:32` |
| 追问 | 另一套带工具的对话循环，复用 RAM 的 `KgToolRegistry` 与会话表，独立 WS | `loganalysis/service/LogFollowupService.java:48-53`、`:155-250` |

事实要点：

- **没用上的图谱能力**：`ram/kg/KgMcpClient` 已提供 `representativeMethod`（按类精确取方法）、`affecting`（上游调用者）、`mybatisSql`、`bridges`、`downstream`，日志流一个都没调用。因果链只往下游看，不看上游。
- **依赖计划移除的类**：`RamClaudeJsonClient` 标注 `@Deprecated(since = "5.0", forRemoval = true)`，却是日志分析与追问的 LLM 入口。
- **并存的旧实现**：`service/RootCauseAnalysisServiceImpl.java`（475 行，规则提示词）未被 `LogAnalysisController` 注入（其字段见 `:42-49`）。
- **测试**：`loganalysis` 包只有 3 个测试文件，行覆盖 49.5%。

### 1.2 架构报告生成

| 层 | 入口 | 实现 | 位置 |
|---|---|---|---|
| 确定性报告 | `GET /api/architecture-diagnosis/report`（另有 `/snapshots`、`/report/snapshot`、`/report/export`） | 直接从图谱取数分章组装：项目 / 模块 / 类 / 方法、环、垂直域、异味、源码扫描、指标 | `archdiagnosis/controller/ArchitectureDiagnosisController.java:34-110`、`archdiagnosis/service/ArchDiagnosisReportService.java:61-76` |
| 智能体层 | `POST /api/workflow/start`，`workflowType = arch-deep-analysis` | 两节点：`arch_evidence_collect` → `arch_react_agent`；`skipHitl=true`、`emitNodeEvents=true` | `workflow/WorkflowAutoConfig.java:38-41` |
| 证据节点 | 调用报告服务与 KG 控制器组装证据包 | `workflow/nodes/ArchEvidenceCollectNode.java:28-29` |
| ReAct 节点 | 最多 10 步；每步输出过 JSON Schema（`arch/react-step`）；图引用做运行时校验；观察结果截断到 2000 字符；连续 2 次失败退出到降级模板 | `workflow/nodes/ArchReActAgentNode.java:54-55`、`:89-172` |
| LLM | `DeepseekJsonClient`（RestTemplate） | `knowledgegraph/aggregation/llm/DeepseekJsonClient.java` |
| 进度 | 事件写 `agent_event` 表；SSE 定时轮询整表再按 `seq` 过滤；合成 `RUN_COMPLETED/RUN_FAILED/RUN_ABORTED/CLARIFY_REQUIRED/HITL_REQUIRED` | `workflow/WorkflowController.java:197-277`、`:227-231` |

事实要点：

- **跨层直连**：节点和工具注册表直接注入 `KnowledgeGraphController`（`ArchEvidenceCollectNode.java:29`、`ArchDiagnosisReportService.java:42`）。
- **abort 不生效**：只把会话标成 ABORTED 并通知 SSE，DAG 继续跑到结束（`WorkflowController.java:281-289`）。
- **线程池**：`start` 用 `CompletableFuture.runAsync` 走公共池（`:123`），无背压。
- **LLM 传输三套并存**：日志走 Spring AI（经弃用类）、架构走 RestTemplate、APM 走 RestTemplate、旧 RAM 走 OkHttp。

### 1.3 RAM

| 流 | 入口 | 节点 / 实现 | 位置 |
|---|---|---|---|
| 需求分析主流程 | `POST /api/ram/sessions` | 经 `ramMcpServer.invoke("analyze_requirement")` 分发；Phase 1 为 `clarify → impact → implement → verify`，`tech_plan` 由单独端点按需触发 | `ram/controller/RamController.java:213-289`、`ram/mcp/RamDagNodes.java:26` |
| 交互 | SSE 流、clarify / resume / confirm、从节点或轮次重跑、导出 | `RamController.java:350`、`:553`、`:593`、`:615`、`:970`、`:1077`、`:1136` |
| 项目现状 | `POST /api/ram/status/start` | 单节点 `project_overview`；控制器整体 `@Deprecated` | `ram/controller/RamStatusController.java:44` |
| Phase 2 | `POST /api/ram/status/phase2/start` | 单节点 `phase2_analysis` | `ram/controller/RamPhase2Controller.java:86`、`:210` |
| 对话 | `/api/ram/chat/*`，含 `inject`、`interrupt` | `RamChatOrchestrator` + 独立 WS | `ram/chat/RamChatController.java:160`、`:187` |

事实要点：

- 引擎复用了 `workflow/DagExecutor`：线性执行、输入哈希缓存、`ClarifyRequiredException`、节点间 HITL 闸门（`workflow/DagExecutor.java:61-188`）。
- 14 个 `*Client` 类共 4106 行，其中 `AnthropicHttpClient` 亦为 `@Deprecated(forRemoval = true)`（`ram/sdk/impl/AnthropicHttpClient.java:35`）。
- 前端 `api/ram.ts:219-229` 调用 `/ram/status/phase2/v2/*`，后端没有对应处理，是悬空调用。

### 1.4 其它流（简述）

| 流 | 入口 | 形态 | 位置 |
|---|---|---|---|
| 诊断编排 | `/api/diagnosis/analyze`、`/analyze/async`、`/analyze/stream`（SSE）、`/analyze/reactive`（NDJSON） | `AgentOrchestrator`（575 行）+ `AgentTools`（535 行）+ 独立 WS 发布器 | `agent/controller/DiagnosisController.java:81-239` |
| APM 诊断 | `POST /api/apm/diagnose` → `GET /{reportId}/status` | `ApmClaudeLlmClient`（RestTemplate） | `apm/controller/DiagnoseController.java:60`、`:75` |
| 自动修复 | `/api/fix/sessions/*` | `FixChatService` + `FixOrchestrator`，表 `fix_session` | `fixengine/controller/FixSessionController.java:43-104` |

### 1.5 横向问题

| # | 问题 | 证据 |
|---|---|---|
| 1 | 六套编排栈并存：`DagExecutor`、`LogAnalysisDagOrchestrator`、`RequirementAnalysisOrchestrator`、`RamChatOrchestrator`、`LogFollowupService`、`AgentOrchestrator`、`FixOrchestrator` | 见 1.1–1.4 |
| 2 | 节点间全靠无类型 `Map<String,Object>` 黑板，键名靠约定 | `LogAnalysisDagOrchestrator.java:87-95`、`DagExecutor.java:102` |
| 3 | 实时通道 5 条各写一套：日志 WS、追问 WS、工作流 SSE、RAM SSE、RAM 对话 WS | 前端 `composables/use*WebSocket.ts`、`useWorkflow.ts:177`、`useRamSession.ts:190` |
| 4 | 前端存在悬空的 v2 调用 | `api/ram.ts:219-229` |

## 2. 同类 Agent 流设计对照

### 2.1 取到原文的来源

| 来源 | 关键结论 | 对本项目的意义 |
|---|---|---|
| Why Do AI Agents Systematically Fail at Cloud Root Cause Analysis?（arXiv:2602.09937v2，2026-03-04） | 1675 次执行、5 个模型：「解释性幻觉」71.2%——不忠实读数据而套自己的叙事；「把症状当根因」39.9%——遇到第一个异常就停；「不做交叉验证」18.6%。原话："Mitigating this failure requires architectural intervention, such as external verification modules, rather than further prompt refinement." | 日志流的病根就是这三条。v2 把“读证据的”和“下结论的”分开，并加确定性验证模块 |
| Anthropic：Effective harnesses for long-running agents（2025-11-26） | 初始化代理产出结构化特性清单（每项带 `passes` 状态），执行代理每次只推进一项，结束写进度并提交 | 中间产物要落成可核对的结构，而不是只在内存里流转 |
| Anthropic：Harness design for long-running application development（2026-03-24） | 生成器与评估器分离是强杠杆；评估器是否值得取决于任务超出模型可靠范围多少 | 评审节点做成配置开关，默认按需开启 |
| Claude Code 文档：workflows | 深度研究工作流：多路检索 → 交叉核对 → 未通过核对的结论剔除并标为“未验证”而非“已证伪”；可暂停、恢复、重启单个代理 | 报告里区分“已验证 / 未验证”，不把未验证写成否定 |
| Claude Code 文档：Agent SDK user-input、agent-loop | 审批与澄清通过统一回调挂起执行；工具调用有确定性门禁 | HITL 应是引擎能力而非各流自写 |
| OpenAI：Harness engineering（2026-02-11，经 WebFetch 取得） | 用 linter 与结构化测试机械地强制不变量；文档作为唯一事实源 | v2 的依赖规则用 ArchUnit 固化 |
| GitHub：Agent Host Protocol（2026-11-17） | 会话状态由服务端仲裁、支持重连重放；协议不规定 agent 如何工作 | 多条实时管道合并时的参照 |

### 2.2 仅检索到、未取原文的线索（不作依据）

MAAD 多代理架构设计（arXiv:2507.21382）；Neo4j 的图谱工具 + 报告代理教程；SEAMS 2026 的动态子代理根因分析。

## 3. 优化方案

### 3.1 日志分析 v2

```
ParseNode（复用 v1）
  → KgEvidenceV2    精确优先的图谱取证，产出带编号的证据包
  → HypothesisV2    LLM 一次调用：给出至多 3 个按置信度排序的假设，每条必须引用证据编号
  → VerifyV2        确定性验证：引用是否存在、根因帧是否在图中、因果链是否被上游调用关系证实
                    （可选）独立评审：查幻觉解读、症状当根因、缺交叉验证
  → SynthesisV2     LLM 综合成 markdown 与修复建议；对产出再做一次引用校验
  → ReportNode（复用 v1，报告结构与 v1 完全一致）
```

| 设计点 | v1 | v2 |
|---|---|---|
| 定位方法 | 帧转词做混合检索 | 先 `representativeMethod` 精确匹配，未命中才回退 `hybridSearch` 并标记为模糊证据 |
| 因果方向 | 只看下游调用树 | 根因帧用 `affecting` 取上游调用链，并用它验证“根因 → 症状”是否连通 |
| 结论约束 | 无 | 每条假设必须引用 `[[E n]]`；引用不存在的编号会被标出并降置信度 |
| 验证 | 无 | 确定性验证 + 可选评审；报告里区分“已由图谱证实 / 未验证” |
| 降级 | 三轮各自回退到规则模板 | LLM 不可用时由证据包直接生成确定性报告，流程不中断 |
| LLM 调用 | 3 次 | 2 次（评审开启时 3 次）；模式类型改由异常类型规则判定，省掉 R1 |
| 协议 | — | 请求体、响应体、WS 事件不变；`rootCauseAnalysis` 下新增 `evidenceAnchors`、`verification` 两个附加字段 |

### 3.2 架构报告 v2

- 路由：`WorkflowV2Controller` 继承 v1 控制器并挂 `/api/v2/workflow`。会话读写端点（status / report / events / stream，以及 definitions / nodes）是同一份代码，只重写 `start` 与 `abort`。
- 节点替换：`start` 对登记的类型（`arch-deep-analysis`）沿用 v1 定义的节点顺序与执行策略，只把 `arch_react_agent` 换成 v2 实现；证据收集等其余节点与 v1 共用同一个实例。没有登记的类型与 v1 完全相同。
- **节点名不变**：节点名出现在事件与检查点里，前端 `views/architecture-diagnosis/AiDeepAnalysisPanel.vue:70-80`、`:105` 写死了 `arch_evidence_collect` / `arch_react_agent`，名字一变页面就不显示进度、也渲染不出报告。所以 v2 节点与 v1 同名；而 `WorkflowRegistry` 按名字收集所有节点 bean，同名会覆盖 v1，因此 v2 节点不注册为 bean，由 `ArchReActNodeV2Factory` 在启动流程时创建。
- v2 ReAct 节点的差异：历史压缩（最近 N 轮保留完整观察结果，更早的只留摘要，配置 `arch.react.v2.history-keep-last`、`arch.react.v2.history-summary-chars`）；每一步之前检查取消登记。
- abort 生效：v2 的 `abort` 额外登记取消标记；节点抛出 `AgentFlowCancelledException`，执行器把会话记成 FAILED，控制器再还原为 ABORTED，与 v1 中止后的状态一致。
- 对 v1 的改动只有可见性：`WorkflowController` 的 5 个字段与 `resolveBackendId`、`ArchReActAgentNode` 的 6 个字段、`SCHEMA_NAME` 与 8 个辅助方法由 `private` 改为 `protected`，没有行为变化。
- 本次未做、留待下一步：独立评审节点；对最终论断做证据引用校验。

### 3.3 RAM v2（方案，待落地）

- 入口直达编排器，去掉进程内 MCP 分发这一跳。
- `impact` 节点改为“精确检索优先 + 证据锚点”，与日志流共用同一套取证与验证组件。
- `verify` 节点接入独立评审；`tech_plan` 的每条方案必须指向受影响的图谱节点。
- 现状分析与 Phase 2 的悬空 v2 调用一并清理。

### 3.4 暂不做的事

- 不改请求/响应字段，不改 WS/SSE 事件类型。
- 不替换 LLM 客户端；v2 通过适配层隔离，后续换实现只动适配器。
- 不动 v1 的节点与控制器行为。

## 4. 落地顺序与验证

| 步骤 | 内容 | 验证 |
|---|---|---|
| 1 | 共享组件：证据包、引用校验、取消登记、流程执行器 | 纯单测 |
| 2 | 日志分析 v2：4 个节点 + 编排 + 执行器 + `/api/v2/log/*` | 节点单测（假图谱客户端、假 LLM）；隔离目录回归 |
| 3 | 前端日志页切到 v2 | `vue-tsc` 0 错误；页面发起分析并看到节点进度与报告 |
| 4 | 架构报告 v2 路由与节点 | 单测 + 页面切换 |
| 5 | RAM v2 | 待 1–4 验证后排期 |

## 5. 落地状态（2026-11-24）

### 5.1 已落地

| 流 | 后端 | 前端切换 | 测试 |
|---|---|---|---|
| 异常日志分析 | `agentflow/`（共享内核）、`loganalysis/v2/`，接口 `/api/v2/log` | `api/logAnalysis.ts` 的 `LOG_FLOW_BASE = '/v2/log'` | 新增 41 个用例 |
| 架构报告生成 | `workflow/v2/`，接口 `/api/v2/workflow` | `api/workflow.ts` 的 `BASE = '/v2/workflow'` | 新增 11 个用例 |

共享内核 `com.huawei.hisi.agentflow`：

| 类 | 职责 |
|---|---|
| `EvidenceAnchor` / `EvidencePack` | 带编号的证据与证据包（E1、E2 …） |
| `Citations` / `GroundingVerifier` / `GroundingResult` | 从模型输出提取 `[[E n]]` 引用，区分真实引用与编造引用 |
| `AgentLlmClient` / `RamClaudeLlmAdapter` | v2 流只依赖的最小 LLM 接口；适配器隔离已被标记移除的旧客户端，并在其上补了空正文重试与单次调用时限 |
| `AgentFlowCancelRegistry` / `AgentFlowCancelledException` | 取消登记，让中止真正停掉执行 |
| `AgentLlmTimeoutException` | LLM 调用超过时限时抛出，调用方据此走确定性兜底 |

日志分析 v2（`com.huawei.hisi.loganalysis.v2`）：

| 类 | 职责 |
|---|---|
| `LogKgEvidenceNodeV2` | 精确优先的图谱取证；按堆栈顺序确定抛出点与调用方；图谱里没有的方法体从本地源码读 |
| `SourceSnippetReader` | 按堆栈的文件名与行号从本地源码读出所在方法，带行号并标出目标行 |
| `LogHypothesisNodeV2` | 一次 LLM 调用产出结构化假设 |
| `LogVerifyNodeV2` | 确定性验证 + 可选评审（`loganalysis.v2.evaluator.enabled`，默认关闭） |
| `LogSynthesisNodeV2` | 综合成文，写完再校验一次引用 |
| `LogAnalysisV2Orchestrator` | 编排；首尾复用 v1 的 `ParseNode` 与 `ReportNode` |
| `LogAnalysisExecutorV2` | 异步执行与落库；没有产出报告时回退 v1 |
| `LogAnalysisV2Controller` | `/api/v2/log`；读接口委托 v1 控制器 |

架构报告 v2（`com.huawei.hisi.workflow.v2`）：`ArchReActAgentNodeV2`、`ArchReActNodeV2Factory`、`WorkflowV2Controller`。

配置项（均有默认值，不配置也能运行）：`loganalysis.v2.max-frames`、`max-bodies`、`body-chars`、`upstream-depth`、`hypothesis-max-tokens`、`evidence-max-chars`、`evaluator.enabled`、`evaluator-max-tokens`、`synthesis-max-tokens`；`arch.react.v2.history-keep-last`、`history-summary-chars`；`agentflow.llm.call-timeout-seconds`（单次 LLM 调用时限，默认 300 秒）。

真实验证过程中改到的 v1 代码，都不改变 v1 原有行为：

| 文件 | 改动 | 性质 |
|---|---|---|
| `workflow/WorkflowController.java`、`workflow/nodes/ArchReActAgentNode.java` | 若干成员由 `private` 改为 `protected` | 可见性 |
| `workflow/DagExecutor.java` | 新增对取消异常的单独处理 | v1 的节点不会抛这种异常 |
| `config/AdminOnlyInterceptor.java` | 「工作流接口一律要求登录」的条件同时覆盖 `/api/v2/workflow/` | 只影响新增的 v2 前缀 |
| `controller/LogAnalysisController.java` | `estimateRemainingTime` 对空的 `analysisStatus` 补判空 | 把原本报错的接口修成正常返回 |

前端除两处接口基地址外，另改了日志报告详情页：`components/log-analysis/NodeTimeline.vue`（时间线由事件驱动）、`views/log-analysis/ReportDetail.vue`（重新分析后显示节点进度、轮询任务状态、结束后自动刷新；四个内容栏改为结构化展示）、`types/log.ts`（报告类型改成与后端一致的对象 / 数组）。新增端到端测试 `e2e/agent-flow-v2.spec.ts` 与替身 `e2e/helpers/agent-flow-v2-backend.ts`，运行方式：`npx playwright test --project=chromium e2e/agent-flow-v2.spec.ts`。

回滚方式：把 `api/logAnalysis.ts` 的 `LOG_FLOW_BASE` 改回 `'/log'`、`api/workflow.ts` 的 `BASE` 改回 `'/workflow'`。v1 路由与 v1 节点行为没有变化。

### 5.2 验证情况

- 后端：隔离目录全量回归两次通过。只含日志 v2 时 1446 个用例（1405 旧 + 41 新）；两条 v2 都包含时 1457 个用例（1405 旧 + 52 新）。两次都是 0 失败、0 错误、27 跳过，`@SpringBootTest` 的完整上下文正常启动，说明新增 bean 与并存的 v1 / v2 两个工作流控制器装配无误。
- 前端：`vue-tsc -b` 0 错误；Vitest 303 个用例通过（`api/__tests__/workflow.spec.ts` 的 SSE 地址断言随 `BASE` 更新为 v2 路径）。
- 单测覆盖不到、需要页面确认的点：真实 LLM 对新提示词的遵循程度（引用格式、是否编造证据编号）、图谱查询在真实项目上的命中情况、WebSocket / SSE 在真实前后端之间的进度显示、中止按钮的实际效果。
- 页面验证的进展与首轮暴露的问题见 5.3。

### 5.3 首轮真实验证（2026-11-24 晚，重启后端后）

在真实后端、真实 LLM、真实图谱上验证，结果如下。

| 流 | 验证方式 | 结果 |
|---|---|---|
| 架构报告 v2 · 中止 | 接口：发起后等 ReAct 进入第 1 轮再中止 | 生效。当前这次 LLM 调用返回后节点即抛出取消异常，没有进入第 2 轮；会话状态为 ABORTED。v1 的中止只断事件流，流程会跑完并标成 DONE |
| 架构报告 v2 · 完整 | 页面：架构诊断页选项目 → 开始分析 | 通过。约 4 分 20 秒，ReAct 走 3 轮（中途调用 `injection_scan` 工具核对证据），两个节点显示完成，报告含 9 个问题、5 张图、8 个章节，`degraded=false`；输出键与 v1 一致 |
| 异常日志分析 v2 | 接口：用测试项目真实调用链构造空指针堆栈 | 跑通但暴露 6 个问题，见下 |

日志分析首轮结果里成立的部分：`/api/v2/log/analyze` 受理并完成；图谱精确命中抛出点方法，取到入口点与下游调用；模型给出 2 个假设，引用的证据编号全部真实存在，没有编造；报告含 v1 的全部字段，另带 `evidenceAnchors` 与 `verification`。

首轮暴露并已修复的问题（单测在假数据上看不出来）：

| # | 现象 | 根因 | 修复 |
|---|---|---|---|
| 1 | `/api/v2/workflow/**` 的读接口不带 token 也返回 200，而 v1 返回 401 | `config/AdminOnlyInterceptor.java` 用硬编码前缀 `startsWith("/api/workflow/")` 判断「工作流接口一律要求登录」，v2 前缀没被覆盖 | 条件同时覆盖 `/api/v2/workflow/`，补 3 条用例 |
| 2 | 页面收不到日志分析的节点进度 | 执行器调用了不带报告 ID 的编排入口，`reportId=-1`，整条流程不推事件 | 编排器补带报告 ID 的入口，执行器改用它 |
| 3 | 综合成文等了 74 秒后回退成模板 | 底层客户端返回空正文：输出预算 4000 被推理模型的思考用完 | 默认预算提到 8000 / 12000；适配器在空正文时放宽预算重试一次并记日志；回退模板时打告警 |
| 4 | 模型断言「`getUserById` 返回 null」，与源码矛盾（该方法总会返回对象） | 证据包里只有堆栈帧自己的源码，没有它调用的方法的源码，模型无从核对 | 取证节点一并取出抛出点的直接下游方法源码；提示词增加「源码与异常信息矛盾时必须指出」 |
| 5 | 两个假设的因果链判定都不准 | v1 的 `ParseNode` 只有在调用方传入项目包前缀时才区分业务帧与根因帧，本次两者都是 0 | 取证节点按堆栈原文的先后顺序确定抛出点与调用方，不依赖包前缀 |
| 6 | 状态接口返回「查询任务状态失败」 | v1 既有缺陷：`LogAnalysisController.estimateRemainingTime` 对空的 `analysisStatus` 做 `switch`，旁边两个同类方法都有判空 | 补判空，补用例。该缺陷同样影响 v1 自己的状态接口 |

其中第 1、6 两项改的是 v1 的类：前者只影响新增的 v2 前缀，后者是把原本报错的接口修成正常返回。

修复后的相关测试 107 个通过（隔离副本）。修复后的全量回归：1468 个用例（1405 旧 + 63 新），0 失败、0 错误、27 跳过，BUILD SUCCESS。

**第二轮验证（2026-11-25 早，再次重启后端后）**

上一轮 6 项修复在真实后端上逐项确认：v2 工作流读接口不带 token 返回 401；状态接口恢复正常；编排日志里的 `reportId` 是真实报告 ID；综合成文由 LLM 完成（96 秒，未触发重试）；抛出点被标出，因果链判定为 `at_throw_site`；模型读到下游方法源码后，把第一个假设改成了「NPE 与下游 `getUserById` 总返回非空对象存在矛盾」，不再顺着异常消息编造根因。

页面上暴露的既有缺口（v1 就存在，与 v2 无关，但它决定了「切页面验证」有没有意义）：

| 现象 | 根因 | 处理 |
|---|---|---|
| 日志页没有「新建分析」入口，页面上能走到分析接口的只有「重新分析」 | 新报告由定时拉取日志后自动触发，走的是 v1 执行器 | 页面验证以「重新分析」为入口。彻底切换 v2 时需要把定时拉取那条路径一并改掉 |
| 报告详情页点「重新分析」后没有任何进度 | 页面只在 3 秒后重新加载一次报告，而报告接口在处理中会直接返回「尚未完成」 | 详情页改为连上 WebSocket、轮询任务状态，结束后自动刷新 |
| 节点时间线上 5 个节点永远显示未开始 | `NodeTimeline.vue` 把节点名写死成 `parse / kg_search / …`，后端事件里的节点名是 `ParseNode / KgEvidenceV2 / …` | 时间线改为由事件驱动，按出现顺序显示；显示名同时覆盖 v1、v2 |

改完后在页面上实测「重新分析」：时间线显示「日志解析 ✅ 4ms → 图谱取证 ✅ 521ms → 根因假设 ⏳ …」，分析期间「重新分析」按钮置灰，结束后报告自动刷新。前端 `vue-tsc -b` 0 错误，Vitest 303 个用例通过。

这一轮新发现并已改代码的问题：

| # | 现象 | 根因 | 修复 |
|---|---|---|---|
| 7 | 同一次 LLM 调用耗时 48 秒、62 秒、378 秒不等，最慢的一轮整体用了 432 秒 | 模型服务延迟波动大；底层客户端读超时长达 10 分钟，v1 最坏要等这么久才降级 | 适配器加单次调用时限 `agentflow.llm.call-timeout-seconds`，默认 300 秒，超时即中断并走确定性兜底 |
| 8 | 证据里有下游方法的源码，却没有抛出点自己的源码；模型在建议里写「获取第 20 行源码」 | 图谱节点里没有这个方法的方法体 | 新增 `SourceSnippetReader`：按堆栈的文件名与行号从本地源码读出所在方法，带行号并标出抛出行 |
| 9 | 报告通篇都是「（未验证）」，连有证据直接支撑的事实也被标 | 提示词要求「每个结论」都标，范围过宽 | 收窄为只标根因结论；事实陈述与修复建议标题不加 |
| 10 | 任务状态的阶段与进度一直是 `pending / 0`，包括已完成时 | v2 执行器只写了 `status`，没写 `analysisStatus`，而接口是从后者算的 | 执行器维护 `analysisStatus`：pending → analyzing → completed / failed |
| 11 | 用户点中止，后端记了一条 ERROR 并打了整段堆栈 | 取消异常走了执行器的通用失败分支 | 执行器单独处理取消异常，记 INFO。v1 的节点不会抛这种异常，行为不变 |

这一批改动之后，相关测试 125 个通过；全量回归 1479 个用例（1405 旧 + 74 新），0 失败、0 错误、27 跳过。第 8–11 项与第 7 项的时限需要再次重启后端才能在真实环境验证。

验证过程中留在后端日志里的两类报错，供排查时参考：验证脚本在发起中止前主动关闭了 SSE 连接，服务端往已断开的连接写数据时留下一条 `IOException`（来自 v1 既有的 SSE 代码，一次性，无害）；用户中止时执行器原先记的 `FAILED … flow cancelled by user`（第 11 项已改为 INFO）。

**关于回归测试本身的两点事实（验证时才弄清，记在这里免得再误判）：**

- 项目的测试套件里有直接调用真实模型和真实图谱的用例，例如 `LogAnalysisFlowDebugTest.testFullFlowWithRealKgProject`。每跑一次全量回归，测试线程上大约出现 7 次完整的三轮分析（约 21 次模型调用）外加若干次 DeepSeek 调用。这些调用产生真实费用，也让回归耗时从 21 分钟到 41 分钟不等。
- 测试进程的日志与运行中的后端写的是同一个文件 `~/.hisi-devtool/devtool.log`。线程名可以作线索但不可靠：`http-nio-*`、`log-analysis-*` 一定是运行中的后端；`[main]` 是测试进程；`ForkJoinPool.*`、`arch-analysis-consumer` 两者都可能是（例如 `WorkflowControllerStartTest` 把任务丢进公共线程池）。稳妥的做法是结合时间窗口和内容判断，测试夹具的值很好认（`backendId=42`、`projectPaths=[p1]`、`boom`）。日志里所有「Empty response from Claude」只有一条来自运行中的后端（2026-11-24 20:30:56，`log-analysis-1`），其余都是测试进程里 v1 客户端调用真实模型时产生的。测试用的数据库是独立的 `target/test-devtool.db`，不碰 `~/.hisi-devtool/devtool.db`。

**第三轮（2026-11-25 上午）：鉴权矩阵、页面渲染缺陷、端到端测试**

这一轮起因是一个追问：回归测试是不是端到端？鉴权场景是不是都测了？两头的答案都是「没有」，于是补了下面这些。

真实后端上的鉴权矩阵（脚本 `/tmp/hisi_kb/v2_auth_matrix.py`，临时注册了一个 MEMBER 账号 `v2-verify-tmp`，用户接口没有删除账号的功能，它会留在库里）：

| 端点 | 不带 token | 无效 token | 成员 | 管理员 |
|---|---|---|---|---|
| `GET /api/v2/workflow/definitions`、`/nodes`、`/sessions/{sid}/status`、`/report`、`/events`、`/stream` | 401 | 401 | 200 | 200 |
| `POST /api/v2/workflow/start`、`/sessions/{sid}/abort` | 401 | 401 | 403 | 通过校验 |
| `POST /api/v2/log/analyze`、`/report/{id}/reanalyze` | 401 | 401 | 403 | 通过校验 |
| `GET /api/v2/log/report/{id}`、`/{id}/status` | 200 | 200 | 200 | 200 |

- 工作流接口的行为与 v1 完全一致。事件流用 `?token=` 传凭据（页面里 EventSource 只能这样传）能收到事件，不带 token 返回 401。七对 v1 / v2 只读接口的返回体逐字节相同。
- 顺带暴露两个 v1 就有的现象，没有改：**日志报告的读接口对任何人开放**，不带 token 也能读到报告全文（含堆栈与源码片段），v1 的 `/api/log/report/{id}` 同样如此；**节点进度的 WebSocket 通道不鉴权**，不带 token 也能握手成功。这两点需要产品层面决定要不要收紧。

报告详情页的渲染缺陷（v1 就有，第二轮里我曾误报为「能正常渲染」——当时只看了首屏）：

| 现象 | 根因 | 修复 |
|---|---|---|
| 「错误摘要」「根本原因」「修复建议」「相关代码」四栏显示的是对象的原始转储：先是长长的证据 JSON，然后 `markdown: ## 根因结论 …` | 前端把这四个字段声明成字符串并直接当 markdown 渲染，后端返回的是对象与数组。v2 加的 `evidenceAnchors` 排在转储最前面，把结论挤到了后面 | 四栏改为结构化展示：摘要按字段列出；根因正文渲染 markdown，并带置信度、验证结果表、证据表；修复建议是带优先级的列表；「相关代码」改名「关键堆栈帧」。旧报告若存的是字符串仍按 markdown 渲染 |
| 正文与导出文件里引用显示成 `[[E5]]` | 校验用的标记原样留在了成文里 | 后端成文时转写成 `[E5]`，未验证的写成 `[未验证引用]`；页面对已存的旧报告做同样的转写 |
| 时间线在分析结束瞬间多出一个空节点 | `DAG_COMPLETE` 事件带着节点名 `_dag`，被「由事件驱动」的时间线当成了节点 | 时间线只登记节点级事件 |

新增端到端测试 `e2e/agent-flow-v2.spec.ts`（替身在 `e2e/helpers/agent-flow-v2-backend.ts`）。后端接口与 WebSocket 都替换成固定应答，不依赖后端是否启动，也不产生模型调用；没有登记的接口一律 404，页面若调了不该调的接口（例如 v1 的）测试会失败。Chromium 下 4 个用例全部通过：

| 用例 | 覆盖 |
|---|---|
| v2 报告按结构渲染 | 摘要字段、根因 markdown、置信度、验证结果、证据表、修复建议、关键堆栈帧；页面上没有原始引用标记；读接口走 v2 |
| 重新分析 | 连上进度通道 → 节点按后端顺序逐个出现（6 个）→ 分析期间按钮置灰 → 结束后报告自动刷新；只调了一次 v2 的重新分析，没有碰 v1 |
| 分析进行中打开页面 | 直接显示节点进度，不去取未完成的报告；结束后报告加载出来 |
| 架构深度分析 | 开始分析走 `/api/v2/workflow/start` 且请求体正确；事件流地址带 `token` 查询参数；两个节点显示完成；结构化报告各小节渲染出来；没有碰 v1 |

写这几条用例的过程本身抓到了上面第三行那个 `_dag` 的缺陷。

测试结果：新增端到端用例 Chromium 4 / 4 通过；前端 `vue-tsc -b` 0 错误、Vitest 303 个用例通过；后端相关用例 103 个通过（引用标记转写这次改动之后没有再跑全量回归，理由见上一节：全量会产生真实模型调用）。项目原有的 26 个端到端规格文件里，只跑了不产生写操作的三个（首页、登录、模块导航）：3 个通过、6 个失败，失败的是首页跳转地址、页面标题和选择器，涉及的路由、布局、页面我都没有改；其余 23 个没有运行，其中 `workflow-api.spec.ts` 会通过真实后端创建会话并触发模型调用。

一个需要说明的副作用：Playwright 每次运行会清空 `test-results/`，而这个目录下有 341 个文件曾被提交进仓库。我的运行把它们显示成了「已删除 / 已修改」，`login-success.png` 也被重新截图。发现后已用 `git checkout` 恢复到提交状态，并清掉了我这次运行新产生的产物。以后在这台机器上跑端到端前要先知道这一点。把输出目录指到仓库之外（`--output <目录>`）就不会碰这些文件，第四轮起改用这种方式。

**第四轮（2026-11-25 上午）：调用面清点、补缺口、真实页面复测**

起因还是那个追问：所有场景都测完了吗。这次不凭印象回答，先把前端切到 v2 的调用面逐个清点，再对照已测清单找差。

调用面清点结果：

- `src/api/workflow.ts` 的 11 个函数全部指向 `/v2/workflow`。它们只被 `composables/useWorkflow.ts` 使用，而使用这个组合式函数的只有架构诊断页的 AI 深度分析面板，页面上只会发起 `arch-deep-analysis` 一种类型。
- `src/api/logAnalysis.ts` 里 `analyze`、`getReport`、`getStatus`、`reanalyze` 指向 `/v2/log`，调用方是报告详情页和日志查询页。列表、删除、导出、追问等其余函数仍走 v1。

清点中发现的既有问题（没有改）：

| 现象 | 说明 |
|---|---|
| `submitWorkflowClarify`、`submitWorkflowConfirm`、`rerunWorkflowFromNode` 三个函数后端没有对应接口 | v1、v2 的 `WorkflowController` 都没有 clarify / confirm / rerun-from 端点（同名端点只存在于 RAM 的 `RamController`）。它们在 v1 路径下同样打不到任何东西，面板上也没有触发入口，是早已存在的死代码 |
| 架构页面上没有中止按钮 | `useWorkflow` 暴露了 `abort()`，面板没有接。一次四分钟的分析开始后，页面上停不下来，中止只能通过接口发起 |
| 不存在的路径返回 500 并记 ERROR | `GlobalExceptionHandler` 把 `NoResourceFoundException` 当成未处理异常。v1 既有 |

补上的两个缺口（只动 v2 新代码，接口协议不变）：

| # | 现象 | 修复 |
|---|---|---|
| 12 | 同一报告的「重新分析」没有并发保护：列表页和详情页各点一次，或接口被调用两次，会起两条分析，并发写同一行，模型调用花两遍，节点事件交错。v1 同样如此 | v2 执行器加在途集合。占位失败时直接返回：不重置状态、不清结果、不再起一轮。接口返回值不变。v2 失败后回退到 v1 的那段时间不在保护范围内 |
| 13 | 中止一个已经结束的会话，会在取消登记里留下一个没人再清的标记 | `abort` 只为仍在运行的会话登记取消。会话状态照旧记成 ABORTED，与 v1 一致 |

这一轮新增的验证：

| 验证 | 结果 |
|---|---|
| 真实后端上 v1 / v2 错误路径对照（脚本 `/tmp/hisi_kb/v2_error_parity.py`，27 组，全是非法请求或不存在的对象，不产生模型调用） | 24 组返回体逐字节相同。其余 3 组是上面那三个不存在的接口，两边同样返回 500「No static resource」，只差消息里回显的路径 |
| 已结束会话的 SSE 回放，v1 对 v2 | 一致：24 个事件、411,795 字符 |
| 端到端新增用例「分析失败」 | 通过：提示「重新分析失败」、进度条收起、按钮恢复、显示失败状态与空结果，不保留上一轮的正文。Chromium 下共 5 个用例全过 |
| 真实页面「重新分析」两轮（后端为 08:35 启动的那一版） | 第一轮 2 分 11 秒（假设 96 秒、成文 29 秒），第二轮 2 分 25 秒（假设 58 秒、成文 85 秒）。第二轮在页面里装了探针持续记录：后端确实推送了 `DAG_COMPLETE`（节点名 `_dag`），时间线上从头到尾只出现过 6 个节点名；成文里引用显示为 `[E1]`，页面上没有 `[[` |
| 这两轮运行期间的后端日志 | 分别 29 条、30 条，全部 INFO，没有 WARN / ERROR |
| 后端相关单元测试（隔离副本，排除两个调用真实服务的测试类） | 160 个通过 |
| 前端 | `vue-tsc -b` 0 错误；Vitest 303 个通过 |

探针同时原样记录了运行中后端的一个已知问题：47 次任务状态查询全部返回 `stage=pending`、`progress=0`，连完成那一刻也是。这正是第 10 项，代码已改，但运行中的后端是 08:35 启动的，尚未包含。

运行中的后端自 08:35 启动以来的 13 条 ERROR、20 条 WARN 也逐条对过来源。测试进程写在同一个文件里的另有 21 条 ERROR、91 条 WARN（线程名 `[main]`）。

| 日志 | 条数 | 来源 |
|---|---|---|
| SSE：`AsyncRequestNotUsableException`、「你的主机中的软件中止了一个已建立的连接」、`[SSE] tick failed` | 3 ERROR + 2 WARN | 10:08 鉴权矩阵脚本读完事件流后主动断开连接。v1 的 SSE 代码把每一次客户端断开都记成 ERROR 并打堆栈，页面上中途关掉也会留下同样的日志 |
| `[UnhandledException] … NoResourceFoundException`（clarify / confirm / rerun-from） | 6 ERROR | 11:34 错误路径对照脚本访问那三个不存在的接口 |
| `参数校验失败` | 4 WARN | 同一脚本发送的超长堆栈与消息，属于预期校验 |
| `查询报告失败 (id=999999)` | 11 WARN | 两轮脚本访问不存在的报告。查不到一条记录在仓储层记 WARN，v1 既有的日志级别 |
| `sendMessage failed: Connection reset by peer` | 1 WARN | 10:08 WebSocket 握手探针主动关闭连接 |
| `[MissingParam] GET /api/v2/knowledge-graph/method/detail` | 2 ERROR | 08:52 知识图谱页发出的请求缺少 `projectPaths`（选中项目为空时前端会丢掉这个参数）。与本次改动无关，没有处理 |
| `[ArchAnalysis] 执行异常: sqlite locked` 及前后两条 | 1 ERROR + 2 WARN | 09:37 测试进程里起的 Spring 上下文（`ArchitectureAnalysisServiceTest` 的夹具值 `D:/proj-b`）。线程名 `arch-analysis-consumer` 两边都可能有 |
| `[Workflow][start] DAG execution failed … backendId=42 … boom` | 1 ERROR | 测试进程（`WorkflowControllerStartTest` 把任务丢进了公共线程池） |

**第五轮（2026-11-25 中午，再次重启后端后）**

新进程 12:01:58 启动，改过的类都在 12:01:50 重新编译，启动日志没有装配错误。上一轮列在 5.4 里「已改代码、尚未在真实后端验证」的几项，这次逐项实测：

| 项 | 验证方式 | 结果 |
|---|---|---|
| 任务状态的阶段与进度（第 10 项） | 页面探针记录的 46 次状态查询 | 分析期间 `stage=analyzing`、`progress=75`，结束后 `completed`、`100`。重启前全部是 `pending / 0` |
| 抛出点本地源码兜底（第 8 项） | 同一轮的证据与报告 | 证据里多出 E6：按堆栈行号从本地文件读到的 `UserController.getUser()` 源码。模型据此提出「`userService` 依赖为 null」的假设——第 20 行唯一的解引用就是它——不再把「下游返回 null」当成根因 |
| 「未验证」标注收窄（第 9 项） | 成文正文 | 只出现在三条根因假设的标题上，共 3 处；因果链与修复建议里的事实陈述不再带。重启前每句话后面都有 |
| 中止不记 ERROR（第 11 项） | 接口：发起架构分析，ReAct 节点开始输出 3 秒后中止 | 后端日志是一行 INFO：`[DagExecutor] sid=147 node=arch_react_agent CANCELLED by user`，没有堆栈，没有 ERROR。中止请求发出 12.7 秒后节点停下（等当前这次模型调用返回），会话状态保持 ABORTED |
| 引用标记转写 | 接口返回的报告 | `markdown` 字段里没有 `[[`，引用是 `[E1]`、`[E3]`、`[E4]`、`[E6]`，没有未验证引用 |
| 同一报告重复触发（第 12 项） | 页面点「重新分析」，8 秒后从接口对同一报告再触发两次 | 两次接口调用都返回 200「已触发重新分析」，协议不变；后端日志 1 次「开始分析」、2 次「忽略重复触发」、1 次「分析完成」，WebSocket 上只有一条干净的节点序列 |
| 中止已结束的会话（第 13 项） | 中止之后、节点尚未停下时再中止一次 | 返回 200，没有报错。取消标记本身只在进程内存里，从外部看不到，这一半只有单元测试 |
| 时间线 `_dag` | 同一轮的探针 | 后端推送了 `DAG_COMPLETE`（`_dag`），时间线上只有 6 个节点名 |
| 鉴权 | 新进程上的抽查 | v2 工作流读接口不带 token、无效 token 都是 401，管理员 200；已结束会话的 report 接口 v1、v2 返回体一致 |

这一轮特意改了验证脚本的做法：事件流连接不再由脚本主动关闭，等服务端发完 `RUN_ABORTED` 自己结束，所以没有再出现上几轮的 SSE 断开报错。后端自启动以来的日志共 196 条：132 条 INFO、61 条 DEBUG、3 条 WARN、0 条 ERROR。3 条 WARN 是启动期的配置提示（没有配置 `HISI_ENCRYPT_KEY`、代理关闭了 SSL 证书校验），与本次改动无关。

### 5.4 未做与未验证

**已改代码、尚未在真实后端验证**：

| 项 | 说明 |
|---|---|
| 单次调用时限（第 7 项） | 第五轮的两次模型调用分别用了 89 秒、51 秒，没有触发 300 秒的时限。只有单元测试（替身客户端故意阻塞）。要实测需要把 `agentflow.llm.call-timeout-seconds` 调小后重启 |
| 中止已结束的会话不登记取消（第 13 项） | 取消标记在进程内存里，外部观察不到。真实环境里只确认了「再中止一次返回 200、没有报错」 |

其余各项（第二批改进、引用标记转写、时间线 `_dag`）已在第五轮于真实后端确认。

**没有测过的场景**（既没有端到端用例，也没有在真实后端上走过）：

| 场景 | 说明 |
|---|---|
| LLM 超时、返回空正文、不可用 | 只有单元测试。真实环境里需要改配置或等模型出问题才能触发 |
| 评审开关打开 | `loganalysis.v2.evaluator.enabled=true` 从未在真实环境运行过 |
| 项目没有建图、传多个项目路径、HISI_CAPTURE 载荷、Python 项目 | 日志分析 v2 的输入变体，只有单元测试覆盖其中一部分 |
| 追问对话、自动修复对 v2 报告 | 只确认了「按报告查修复会话」的接口能返回；没有实际发起一次追问或修复 |
| 定时拉取产生的报告 | 走的是 v1 执行器，与 v2 无关，也没有验证两条路径写同一张表是否互相影响 |
| 非 `arch-deep-analysis` 的工作流类型经 v2 控制器 | `demand`、`status`、`phase2` 会回退到 v1 节点，只有单元测试 |
| 架构页面上中止 | 页面上没有中止按钮，中止只能通过接口发起 |
| 同时发起多个不同报告的分析 | 线程池与队列行为没有看过 |
| 中止之后 | 中止过的会话再次发起分析、事件流重连，没有在真实后端上走过 |
| 其它浏览器 | 端到端只在 Chromium 下跑过；Firefox、WebKit、移动端没有跑 |

**没有做的功能**：

| 项 | 说明 |
|---|---|
| 架构 v2 的评审节点与论断证据校验 | 见 3.2 |
| RAM v2 | 见 3.3 |
| 日志报告匿名可读、WebSocket 通道不鉴权 | v1 既有的行为，是否收紧需要产品决定 |
| 架构页面的中止按钮；清理三个没有后端接口的前端函数 | 见第四轮「清点中发现的既有问题」 |
| SSE 客户端断开、路径不存在时的日志级别 | v1 既有的行为，每次都记 ERROR 并打堆栈；改起来不大，但动的是 v1 的事件流代码，等确认再做 |
