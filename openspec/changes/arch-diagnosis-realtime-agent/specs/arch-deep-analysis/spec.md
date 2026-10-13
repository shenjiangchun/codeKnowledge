# arch-deep-analysis Specification

## ADDED Requirements

### Requirement: arch-deep-analysis 工作流注册
系统 SHALL 在通用工作流引擎中注册 `arch-deep-analysis` 工作流类型，由三个线性 DAG 节点组成：证据收集（确定性）→ 问题根因分析（LLM）→ 架构现状报告（LLM）。注册方式与既有工作流一致（节点 bean + 注册配置），不引入新的编排框架。

#### Scenario: 工作流可被启动
- **WHEN** 前端调用工作流启动端点并传入 `type=arch-deep-analysis` 与项目参数
- **THEN** 系统创建 agent 会话并按 证据收集 → 根因分析 → 现状报告 的顺序执行节点

#### Scenario: 未知类型拒绝
- **WHEN** 启动端点收到未注册的工作流类型
- **THEN** 返回 400 错误，不创建会话

### Requirement: 证据收集节点
系统 SHALL 实现确定性证据收集节点（不调 LLM）：调用诊断报告逻辑（persist=false）获取四章数据与问题清单，并通过知识图谱只读查询补充证据（参与环的关键类 ego-net、违规依赖的代码位置），产出证据包并写入节点 CHECKPOINT。

#### Scenario: 产出完整证据包
- **WHEN** 证据收集节点执行且报告与 KG 查询均成功
- **THEN** 节点输出证据包（chapters/issues/KPI/KG 摘录），CHECKPOINT 落库，后续节点可从缓存复用

#### Scenario: 底层数据缺失降级
- **WHEN** 项目未构建 KG 导致报告或 KG 查询失败/为空
- **THEN** 节点以可得数据继续（空集也算合法证据包）或以明确错误终止工作流，前端能看到具体失败原因

### Requirement: 根因分析 LLM 节点与 JSON 契约
系统 SHALL 实现问题根因分析节点：以证据包为输入调用 LLM（现有 deepseek JSON 客户端），按 JSON 契约 schema（`classpath:schemas/arch/` 下）输出结构化根因清单（每项含问题引用、标题、严重度、根因、证据引用、修复建议），经 schema 校验。校验失败 SHALL 重试一次，仍失败则降级：保留证据包并在报告中标注根因分析失败，不阻塞报告节点。

#### Scenario: 根因输出通过契约校验
- **WHEN** 根因分析节点执行且 LLM 返回符合 schema 的 JSON
- **THEN** 结构化根因清单写入会话输出，报告节点可引用每条根因及其证据引用

#### Scenario: 契约校验失败重试与降级
- **WHEN** LLM 返回不符合 schema 的 JSON（两次均失败）
- **THEN** 节点标记根因分析失败，工作流继续执行报告节点，报告中根因部分显示失败说明与重试入口

### Requirement: 架构现状报告 LLM 节点
系统 SHALL 实现架构现状报告节点：以证据包与根因结果为输入调用 LLM，生成项目级「架构现状及问题分析」Markdown 报告，内容 MUST 覆盖四段：架构现状总览、核心问题及根因分析、改进建议与优先级、证据附录。报告 SHALL 持久化（复用 agent 会话存储或新增最小表），支持前端查询与导出。

#### Scenario: 生成四段式报告
- **WHEN** 报告节点执行成功
- **THEN** 产出含现状总览/根因分析/改进优先级/证据附录四段的 Markdown 报告并持久化，前端可渲染

#### Scenario: 报告随项目切换失效
- **WHEN** 用户切换所选项目
- **THEN** 前端展示的深度分析结果被重置，不展示其他项目的报告

### Requirement: 深度分析 SSE 进度与恢复
系统 SHALL 通过现有 agent 事件 + SSE 端点推送深度分析进度（节点开始/完成、LLM 输出增量），前端以进度卡展示三节点状态。用户离开页面 MUST NOT 中断执行；回到页面 SHALL 按 lastSeq 水位增量恢复进度，已完成工作流 SHALL 能直接查看历史报告（输入指纹命中时复用 CHECKPOINT，不重复执行已完成节点）。

#### Scenario: 进度实时展示
- **WHEN** 深度分析运行中且用户停留在架构诊断页
- **THEN** 进度卡实时反映三节点状态（待执行/运行中/完成），可见当前节点的流式输出摘要

#### Scenario: 离开页面后恢复
- **WHEN** 用户在深度分析运行中离开架构诊断页后又返回
- **THEN** 进度卡从上次事件水位增量恢复，不重复渲染历史事件，不重启工作流

#### Scenario: 输入指纹命中复用
- **WHEN** 对相同输入（同项目、同报告数据指纹）再次发起深度分析且已有完成会话
- **THEN** 已完成节点直接复用 CHECKPOINT 结果，仅未完成节点重新执行

### Requirement: 通用工作流启动端点
系统 SHALL 实现 `POST /api/workflow/start`（当前仅有定义无实现）：接受 `{type, input}`，经工作流注册表校验类型、创建 agent 会话、异步启动执行并返回会话标识。该端点为通用能力（任意已注册工作流类型可用），架构诊断为首个消费方。

#### Scenario: 启动已注册工作流
- **WHEN** POST `/api/workflow/start` 携带已注册类型与合法 input
- **THEN** 返回会话标识，工作流异步开始执行，前端可经既有 SSE 端点订阅进度

#### Scenario: 非法入参校验
- **WHEN** POST 请求缺少 type、类型未注册或 input 缺少必填字段（如 projectPaths）
- **THEN** 返回 400 与明确错误信息，不创建会话
