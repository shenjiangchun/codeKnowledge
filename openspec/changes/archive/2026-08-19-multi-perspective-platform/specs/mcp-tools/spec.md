# MCP 工具扩展：需求规格

## 目的
新增 6 个 MCP 工具（kg_dashboard, kg_dsm, kg_hotspots, kg_domains, kg_service_topology, kg_blast_radius），暴露给 AI 助手（Claude Code / Cursor）使用，利用 KG 数据辅助架构分析和代码理解。

## 范围
### 本次范围
- 6 个新 MCP 工具定义 + handler
- MCP Server 的 KG_TOOLS 数组从 15 扩展到 21
- 工具参数遵循已有模式（projectPaths 必填，language 可选）

### 非目标
- 修改已有 15 个 kg_* 工具的接口签名

## ADDED Requirements
### Requirement: kg_dashboard 工具
系统 MUST 提供 kg_dashboard 工具，返回架构仪表盘聚合数据。

#### Scenario: AI 助手查询项目架构概况
- 前提：用户对 AI 说"这个项目的架构概况是什么？"
- 当：AI 调用 kg_dashboard(projectPaths=["hisi-dev-tool"], language="java")
- 则：工具返回 JSON 含 modules 数组（模块名/方法数/复杂度/稳定性）
- 并且：kpis（总方法数/模块数/循环依赖数/分层违规数）
- 并且：risks（Top 风险列表，每条含严重度和建议）

### Requirement: kg_blast_radius 工具
系统 MUST 提供 kg_blast_radius 工具，返回指定方法的爆炸半径分析。

#### Scenario: AI 助手分析变更影响面
- 前提：开发者问 AI"改 OrderService.placeOrder 会影响什么？"
- 当：AI 调用 kg_blast_radius(nodeId="...OrderService.placeOrder", projectPaths=["hisi-dev-tool"], maxDepth=5)
- 则：工具返回 downstream 影响面 + upstream 入口点 + affectedServices列表

### Requirement: kg_hotspots 工具
系统 MUST 提供 kg_hotspots 工具，返回热点文件排名。

#### Scenario: AI 助手识别代码库中的高风险文件
- 前提：用户对 AI 说"我的项目里风险最高的模块是哪些？"
- 当：AI 调用 kg_hotspots(projectPaths=["hisi-dev-tool"], limit=10)
- 则：工具返回 Top 10 文件，每条含 className/filePath/complexity/commitCount90d/riskScore/riskBreakdown

### Requirement: kg_dsm 工具
系统 MUST 提供 kg_dsm 工具，返回模块间的依赖结构矩阵。

#### Scenario: AI 助手查询模块依赖矩阵
- 前提：AI 助手需要分析模块间依赖
- 当：AI 调用 kg_dsm(projectPaths=["hisi-dev-tool"], level="package")
- 则：工具返回 N×N 依赖矩阵 + 循环依赖/分层违规标记

### Requirement: kg_domains 工具
系统 MUST 提供 kg_domains 工具，返回自动检测的领域边界和跨域交互。

#### Scenario: AI 助手查询领域边界
- 前提：聚合管道的领域检测已完成
- 当：AI 调用 kg_domains(projectPaths=["hisi-dev-tool"])
- 则：工具返回领域列表（业务名词命名）+ 领域间 INTERACTS_WITH 关系

### Requirement: kg_service_topology 工具
系统 MUST 提供 kg_service_topology 工具，返回微服务之间的拓扑依赖关系。

#### Scenario: AI 助手查询微服务拓扑
- 前提：项目已聚合，跨服务调用边已识别
- 当：AI 调用 kg_service_topology(projectPaths=["hisi-dev-tool"])
- 则：工具返回服务节点列表 + 跨服务依赖边（Feign/MQ/HTTP 类型着色）

## 兼容性与外部契约
- 所有新工具遵循已有参数模式（projectPaths: string[] 必填）
- 工具调用路由到已有 REST 端点（/api/v2/knowledge-graph/*）
- MCP Server 的 ListTools 响应自动包含新工具（在 KG_TOOLS 数组中注册）

## 验收矩阵
| 需求/场景 | 验证方法 | 可证伪的失败表现 |
|-----------|---------|----------------|
| kg_dashboard 返回架构数据 | MCP 集成测试：调用工具 → 断言 modules 数组非空 | 返回错误或空数据 |
| kg_blast_radius 返回影响面 | MCP 集成测试：传入已知 nodeId → 断言 downstream.totalAffectedMethods > 0 | 返回 0 条影响 |
| ListTools 包含 21 个工具 | 单元测试：assert KG_TOOLS.length === 21 | 工具数 ≠ 21 |
| 未知工具返回错误 | 单元测试：传入未知 toolName → 断言 error 响应 | 静默成功 |

## 已确认决策
| 决策项 | 选择 | 批准人/日期 | 影响的需求 |
|--------|------|------------|-----------|
| 爆炸半径也对 AI 暴露 | 新增 kg_blast_radius MCP 工具 | 用户 / 2026-08-11 | kg_blast_radius |
| MCP 工具数量 | 15 → 21（新增 6 个，不改已有） | Agent 设计决策 | 所有新工具 |

## 显式未知项
- 无
