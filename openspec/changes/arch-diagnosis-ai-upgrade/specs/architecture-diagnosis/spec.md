# architecture-diagnosis Specification

## ADDED Requirements

### Requirement: 实时报告单项目定位
系统 SHALL 在架构诊断实时报告页以单项目为诊断单元：页面顶部单选项目，报告针对该项目生成；跨项目聚合分析下沉到 AI 深度分析（多项目聚合报告），不在实时报告层做多项目聚合。

#### Scenario: 实时报告针对单项目
- **WHEN** 用户在实时报告页选中单个项目并触发实时生成
- **THEN** 报告四章（项目/模块/类/方法）均为该单项目视角，垂直领域在本项目内识别

#### Scenario: 跨项目交给 AI 深度分析
- **WHEN** 用户需要跨项目聚合的架构分析
- **THEN** 通过 AI 深度分析多选项目生成跨项目报告，实时报告不承担跨项目聚合

## MODIFIED Requirements

### Requirement: 诊断报告导出
系统 SHALL 提供报告导出能力：将诊断报告导出为精简直观的 Markdown 文本（可下载/可归档），供架构师评审会汇报使用。

#### Scenario: 导出精简 Markdown
- **WHEN** 请求导出端点，指定某次历史快照 `snapshotId` 或即时 `projectPaths`
- **THEN** 返回精简 Markdown（健康总分、等级、问题清单、四章核心明细、扣分汇总），响应头含可下载文件名

#### Scenario: 从历史快照导出
- **WHEN** 请求导出端点并提供某次历史快照 `snapshotId`
- **THEN** 从该快照的 `reportJson` 还原并导出精简 Markdown，内容与该次诊断生成时一致

#### Scenario: 单项目快照导出
- **WHEN** 快照 scope 为单项目（复选场景下每个项目各落一条）
- **THEN** 按 `snapshotId` 导出时只还原该项目那份快照的报告
