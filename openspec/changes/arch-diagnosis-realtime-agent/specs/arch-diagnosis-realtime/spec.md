# arch-diagnosis-realtime Specification

## ADDED Requirements

### Requirement: 实时报告自动生成
系统 SHALL 在用户进入架构诊断页（且已选项目）或切换所选项目后，自动调用诊断报告端点（`persist=false`，不落快照）并渲染报告，无需用户点击任何生成按钮。报告生成期间 SHALL 显示加载态。

#### Scenario: 进入页面自动生成
- **WHEN** 用户进入架构诊断页且 store 中已有选中项目
- **THEN** 页面自动发起报告请求（persist=false），秒级渲染诊断总览与四章内容，无空态步骤

#### Scenario: 切换项目自动重新生成
- **WHEN** 用户在架构诊断页或其它页面（如项目管理）切换了所选项目后回到架构诊断页
- **THEN** 旧报告被清除并自动重新生成新项目报告，不残留上一项目内容

#### Scenario: 未选项目空态
- **WHEN** 用户进入架构诊断页但未选择任何项目
- **THEN** 显示项目选择引导空态，不发起报告请求

### Requirement: 显式保存快照
系统 SHALL 将快照持久化改为显式动作：页面提供「保存快照」按钮，点击后以 `persist=true` 重新请求报告端点（或保存当前实时报告），成功后刷新快照历史列表。自动实时生成 MUST NOT 产生快照记录。

#### Scenario: 点击保存快照
- **WHEN** 用户点击「保存快照」按钮
- **THEN** 当前报告以 persist=true 保存，快照历史列表新增一条记录并刷新展示

#### Scenario: 实时生成不落快照
- **WHEN** 报告由进页/切项目自动生成（persist=false）
- **THEN** 快照表不新增任何记录，快照历史列表不变

### Requirement: 项目切换状态重置
前端 SHALL 监听项目选择状态（`appStore` 选中项目集合）变化，统一重置报告、快照列表与深度分析状态后自动重新生成实时报告；项目选择控件本身不再单独维护重置逻辑（单一重置入口）。

#### Scenario: 跨页面切换项目无残留
- **WHEN** 用户在项目管理页修改选中项目后返回架构诊断页
- **THEN** 上一项目的报告、快照列表、深度分析结果均被清除，并自动加载新项目实时报告

### Requirement: 模块级可视化渲染
前端模块级 tab SHALL 渲染 DSM 依赖矩阵（复用 DSM 组件）与包级循环依赖列表：环条目按 `CROSS_LAYER`（danger）/`SAME_LAYER`（warning）区分着色，展示环的参与包路径；分层违规列表来源为章节内的违规数据。

#### Scenario: 模块级展示 DSM 与环
- **WHEN** 报告的 module 章节含 cycles 与 DSM 数据且用户切换到模块级 tab
- **THEN** 页面渲染 DSM 矩阵与环列表（含 CROSS_LAYER/SAME_LAYER 着色），而非空表格

#### Scenario: 无环项目
- **WHEN** 模块级章节 cycles 为空
- **THEN** 环区域显示「未检测到循环依赖」空态，DSM 矩阵仍正常渲染

### Requirement: 类级与方法级可视化增强
类级 tab SHALL 以横向条形图展示上帝类（WMC/方法数）与枢纽类（扇入/扇出）度量对比，表格降级为折叠明细；方法级 tab SHALL 以 treemap（复用现有热点 treemap 组件，按风险分着色）展示方法热点，明细表格折叠可展开。

#### Scenario: 方法级 treemap
- **WHEN** 用户切换到方法级 tab 且热点数据非空
- **THEN** 渲染方法热点 treemap（面积/颜色对应风险分），点击可查看明细，纯表格不再是默认视图

#### Scenario: 类级度量条形图
- **WHEN** 用户切换到类级 tab 且存在上帝类/枢纽类数据
- **THEN** 展示度量条形图（WMC 排行、扇入扇出对比），明细数据可展开查看
