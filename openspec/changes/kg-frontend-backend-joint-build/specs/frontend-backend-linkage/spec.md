# frontend-backend-linkage Specification (Delta)

## Purpose
明确跨层 INVOKES_API 链接的触发方式：由「构建前后端完整图谱」绑定端点显式触发，而非后端全量建图收尾自动触发。

## ADDED Requirements

### Requirement: 跨层链接由绑定构建端点显式触发
系统 SHALL 通过「构建前后端完整图谱」绑定端点显式触发跨层链接（前端实体化完成后执行静态 URL 匹配构建 INVOKES_API 边）；系统 SHALL NOT 在后端全量建图收尾阶段自动触发前端实体化或跨层链接。

#### Scenario: 绑定构建端点触发跨层链接
- **WHEN** 用户通过绑定构建端点触发完整图谱构建，且前端实体化已生成 ApiClient 节点
- **THEN** 系统执行静态 URL 匹配，构建 `ApiClient -[:INVOKES_API]-> EntryPoint` 跨层边，并返回命中边数

#### Scenario: 后端全量建图不触发跨层链接
- **WHEN** 用户仅执行后端全量建图（未通过绑定构建端点）
- **THEN** 系统 SHALL NOT 自动执行前端实体化或跨层链接，不产生新的 INVOKES_API 边

#### Scenario: 跨层链接仍可独立重跑
- **WHEN** 前端图与后端图均已就绪，用户再次通过绑定构建端点触发（或后端图重建后）
- **THEN** 跨层链接 SHALL 可独立重跑，先清理旧 INVOKES_API 边再重建，不触发前后端图的全量重建
