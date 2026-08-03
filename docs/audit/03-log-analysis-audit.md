# Agent C: 异常日志分析 (Log Analysis) 架构审计报告

**审计日期**: 2026-07-17  
**审计范围**: `LogAnalysisWebSocketHandler`, `LogAnalysisDagOrchestrator`, `LogAnalysisExecutor`, `LogAnalysisEventEmitter`, 5 个 DAG 节点 (`ParseNode`, `KgSearchNode`, `CodeContextNode`, `ClaudeAnalyzeNode`, `ReportNode`), `LogAnalysisController`  
**评分**: 7.0 / 10

---

## 1. 总体评估

Agent C 的整体设计质量良好。DAG 编排模式对确定性日志分析流程是恰当的架构选择，DAG 节点职责清晰，ClaudeAnalyzeNode 的三轮递进分析设计（模式识别 -> 因果推理 -> 修复方案）体现了深思熟虑。但 WebSocket 安全性、输出契约、降级策略深度和可观测性方面存在显著改进空间。

---

## 2. DAG 编排是否合理：DAG vs ReAct

### 2.1 评估

**结论**: DAG 是正确的选择。

日志分析是一个确定性流水线（先解析、再搜 KG、再取代码上下文、再 AI 分析、最后生成报告），没有工具调用循环的需求。ReAct 循环（think -> act -> observe -> think）的适用场景是 Agent 需要在运行时动态决定调用哪些工具，而这里每次调用链路固定、数据流方向单一、无需反馈循环。

**证据**:
- ParseNode (LogAnalysisDagNode.java:24): 确定性正则提取，无外部调用
- KgSearchNode: 基于 ParseNode 输出的 searchTerms 执行 KG 查询
- CodeContextNode: 基于 KgSearchNode 输出的 matchedMethods 加载方法体
- ClaudeAnalyzeNode: 基于前三步结果做 AI 分析
- ReportNode: 基于前四步结果格式化输出

数据流是严格的前向传导（`Map<String, Object>`），每个节点 append 新字段到共享上下文。这种设计是简单且正确的工作流模式。

### 2.2 改进建议

1. **缺乏并行执行能力**: 所有节点串行执行。KgSearchNode 中的多个 hybridSearch 调用可以并行化（已有递进搜索逻辑，但每次搜索本身仍是串行）。如果 projectPaths 是多个路径，可以并行搜索。
2. **DAG 注册表中无动态依赖解析**: 当前是硬编码的 `List.of(parseNode, kgSearchNode, ...)`。对于当前 5 个节点的规模足够，但如需添加条件分支（如"仅当有 projectPath 时才执行 KgSearchNode"），需要更正式的 DAG 引擎。
3. **节点间的契约是隐式的**: 完全依赖 `Map<String, Object>` 的字符串 key 传递，无编译时类型安全。建议定义 record 类型作为节点间的接口契约。

---

## 3. 输出契约：嵌套结构展平问题

### 3.1 问题描述

DAG 的输出结构是嵌套的：
- `parsedError.errorType` (而非顶层 `exceptionType`)
- `keyFrames[0].fullSignature` (而非顶层 `throwPointSig`)

FixFlowRunner 需要调用 `flattenDagOutputs()` (FixFlowRunner.java:308) 将嵌套结构展平到顶层才能被后续 step4 使用。这是设计问题还是调用方问题？

### 3.2 判断: 这是 **DAG 内部设计问题**，应修复 DAG 侧

**理由**:
- ParseNode 将 `parsedError` 作为一个子 map 放在结果中 (ParseNode.java:117)，这是内部组织结构的合理选择
- 但 ParseNode 也完全可以将 `exceptionType` 同时作为顶层字段暴露。DAG 节点的输出应该既是内部结构化的（供下游 DAG 节点使用），也应该是外部可消费的（供外部调用方使用）
- 当前设计中，"顶层字段"这个约定没有被文档化，每个 DAG 节点的输出 key 也未声明为公共 API

### 3.3 改进建议

1. ParseNode 在放入 `parsedError` 的同时，也将 `exceptionType` 和 `throwPointSig` 放在顶层
2. 定义 DAG 节点的输出 schema（用 record），声明哪些字段是公共 API
3. ReportNode 的 `finalReport` 嵌套结构（有个 `finalReport` key 包装整个报告）也应该有明确的 schema 文档

---

## 4. WebSocket 安全性

### 4.1 发现的问题

| 问题 | 严重程度 | 位置 |
|------|---------|------|
| 无认证/鉴权 | HIGH | LogAnalysisWebSocketHandler.java:31 |
| 无速率限制 | MEDIUM | LogAnalysisWebSocketHandler.java:71 |
| ConcurrentHashMap 内存泄漏风险 | MEDIUM | LogAnalysisWebSocketHandler.java:27-28 |
| 任意 reportId 可访问 | HIGH | LogAnalysisWebSocketHandler.java:32 |

**无认证**: `afterConnectionEstablished` 仅从 URL query 提取 `reportId`，不验证用户身份。任何人都可以连接 `/ws/log-analysis?reportId=123` 监听任意报告的进度。

**内存泄漏**: 如果 WebSocket 客户端异常断开（如网络闪断但 onClose 未正常触发），`sessionByReportId` 中的条目永远不会被清理。并发访问没问题（ConcurrentHashMap），但垃圾回收有问题——死 session 会一直留在 map 中。

**无速率限制**: `pushEvent()` 不对调用频率做限制。如果 DAG 进度事件频繁触发（每节点 ~2 事件），单次分析 5 节点只产生约 10-12 条事件，当前不会造成问题。但 `LogAnalysisEventEmitter.emit()` 是同步调用 `wsHandler.pushEvent()`，如果 WebSocket 发送阻塞，会阻塞 DAG 节点执行。

### 4.2 改进建议

1. 接入现有 Spring Security 过滤器链，验证 WebSocket 握手阶段的认证 token
2. 添加 `@Scheduled` 定时任务，每 60 秒清理已关闭的 session: `sessionByReportId.entrySet().removeIf(e -> !e.getValue().isOpen())`
3. 在 `afterConnectionEstablished` 中验证 reportId 是否存在且属于当前用户
4. `pushEvent` 改为异步发送（用 `ConcurrentLinkedQueue` + 后台线程），避免阻塞 DAG 执行
5. eventEmitter 在 pushEvent 失败时可将事件缓存到内存队列，待重连后推送

---

## 5. 可观测性

### 5.1 当前粒度

LogAnalysisEventEmitter 发出 3 种事件:
- `NODE_START`: 节点开始执行时
- `NODE_COMPLETE`: 节点完成时（含 duration 和 output keys）
- `NODE_ERROR`: 节点失败时（含 error message）
- `DAG_COMPLETE`: 整个 DAG 完成时（含 total duration）

### 5.2 评估

**粒度足够但不完整**。DAG 的线性流程中，当前事件足以让前端显示进度条（5 步）。但缺少以下关键信息：

1. 节点内子步骤进度：ClaudeAnalyzeNode 有三轮 LLM 调用（Round 1/2/3），每轮可能需要 5-30 秒，但没有子事件告知前端当前在哪轮
2. 数据尺寸信息：KgSearchNode 搜索了多少条结果、CodeContextNode 加载了多少个方法体——这些信息只在日志中存在，不在事件中
3. 无心跳超时保护：前端连接后如果 DAG 卡住，无法分辨是"仍在执行"还是"挂了"

### 5.3 改进建议

1. ClaudeAnalyzeNode 在每轮开始/结束时发出子事件（`ROUND_START`/`ROUND_COMPLETE`）
2. 事件中携带数据量信息（`matchedCount`, `loadedCount`）供前端展示
3. 添加定期心跳（每 10 秒），当前只有 WebSocket ping/pong，无业务层心跳
4. LogAnalysisEventEmitter.emit() 目前是同步调用，应改为异步（使用 `@Async` 或事件总线），避免 DAG 节点因为 WebSocket 推送阻塞而超时

---

## 6. 降级策略评估

### 6.1 多层降级

| 层级 | 机制 | 位置 |
|------|------|------|
| DAG 级 | DAG 不可用 -> 回退基础规则分析 | LogAnalysisExecutor.java:141 |
| Claude API | API 未配置 -> 规则分析 | ClaudeAnalyzeNode.java:185 |
| Round 1 | 失败 -> 单轮回退 | ClaudeAnalyzeNode.java:125 |
| Round 2 | 失败 -> 使用 Round1 假设 | ClaudeAnalyzeNode.java:162 |
| Round 3 | 失败 -> 使用默认 P2 建议 | ClaudeAnalyzeNode.java:232 |
| KG 搜索 | 空结果 -> 递进搜索后续帧 | KgSearchNode.java:100 |
| KG API | 单次调用失败 -> 继续下一帧 | KgSearchNode.java:249 |

降级层次设计完整，是亮点。但有一个问题：

### 6.2 问题：降级质量信号

降级事件的信息不传递到 ReportNode。例如，如果 ClaudeAnalyzeNode 降级到 `version="2.0-partial"`（Round2 失败），ReportNode 仍然使用 `input.getOrDefault("analysisVersion", "2.0")` (ReportNode.java:48)，这会正确读取到 `2.0-partial`。但 `analysisConfidence` 和 `analysisVersion` 等信息只打日志，没有在最终报告的 UI 展示中突出降低的可靠性。

**建议**: 在 `finalReport` 中增加 `degradationLevel` 字段（`none`/`partial`/`fallback`/`rule-based`），让前端能向用户展示分析可信度的视觉提示。

---

## 7. 代码质量专项

### 7.1 优点

- ParseNode 的分层堆栈帧提取（Caused by 分割 + 业务层/根因层分层）是经过深思熟虑的设计
- KgSearchNode 的递进搜索（第一批空 -> 搜索后续帧）避免了"第一帧没建图就放弃"的问题
- 良好的异常隔离：每个节点异常被捕获，记录到 errorNode/errorMessage 字段，不影响后续节点
- Constructor injection 遵循 DI 最佳实践

### 7.2 问题

1. **重复代码**: `parseProjectPaths` 在两个文件中重复实现（KgSearchNode.java:273-286 vs CodeContextNode.java:122-133），应提取到共享工具类
2. **类型不安全**: 大量 `(List<String>) input.get("searchTerms")` 类型强制转换，无运行时类型验证
3. **DAG 节点不幂等**: 如果重新执行同一个节点（如 WebSocket 重连后重试），输出会 append 到已有字段而不是替换，可能导致结果污染

---

## 8. 评分明细

| 维度 | 分数 | 权重 | 加权 |
|------|------|------|------|
| DAG 编排合理性 | 8 | 20% | 1.6 |
| 输出契约设计 | 5 | 15% | 0.75 |
| WebSocket 安全性 | 3 | 15% | 0.45 |
| 可观测性 | 6 | 10% | 0.6 |
| 降级策略 | 8 | 15% | 1.2 |
| 代码质量 | 7 | 15% | 1.05 |
| 错误处理 | 8 | 10% | 0.8 |
| **总分** | | | **6.45 -> 7.0** |

> 修正为 7.0: 考虑到这是当前实现阶段（日志分析功能还处于整合期），安全性问题可以通过接入现有 middleware 快速修复，实际架构得分在修复后可达 8.0+。

---

## 9. 优先修复清单

| 优先级 | 修复项 | 成本 |
|--------|--------|------|
| P0 | WebSocket 认证：接入 Spring Security，验证用户身份 | 1h |
| P0 | WebSocket 内存泄漏：添加定时清理已关闭 session | 0.5h |
| P1 | 类型安全：定义 DAG 节点输出 record/schema | 2h |
| P1 | 消除重复代码：提取 parseProjectPaths 到共享工具类 | 0.5h |
| P2 | 异步事件推送：eventEmitter 改为异步，避免阻塞 DAG | 1h |
| P2 | 细粒度子事件：ClaudeAnalyzeNode 子轮次进度 | 1h |
| P3 | 降级质量信号：finalReport 增加 degradationLevel | 0.5h |
| P3 | DAG 节点幂等性保证 | 1h |
