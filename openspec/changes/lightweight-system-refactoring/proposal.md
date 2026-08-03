# 轻量化系统重构 — 前端清理 + 后端瘦身 + 体验优化

## Why

项目当前 286 个 HTTP 入口点、5051 个方法节点、25 个前端路由页面。经过 Phase 1 审计发现大量可安全移除的废弃代码：
- 前端：HomeView/AboutView（Vue scaffold）、SemanticSearchView（确认不可用）、McpGuide（路由已删除）、naturalLanguage API（自然语言诊断已废弃）
- 后端：`service/intent/` 9 个类无任何外部引用、多个 Embedding 服务未使用、`remote project`/`project group` 模块待确认
- 前端 CLI 工具侧栏有 5 条僵尸重定向路由

目标：在 `release_0731` 分支上通过外科手术式删除，将系统从 25 路由/286 入口瘦身为约 19 路由/270 入口，每步全量回归验证。

## What Changes

- **BREAKING**: 移除 `/search`、`/mcp-guide`、`/call-chain/*` 5 条重定向路由（call-chain 视图组件保留，通过知识图谱页内嵌使用）
- **BREAKING**: 移除 `POST /api/dialog/*` 自然语言诊断 4 个 REST 端点（前后端均无使用者）
- 删除前端 12 个文件（HomeView/AboutView/SemanticSearchView/CodePreviewPanel/SearchResultsPanel/McpGuide + api/search/api/mcp/api/naturalLanguage/naturalLanguageStore）
- 删除后端 `service/intent/` 包（9 个类，~600 行）
- 清理 `api/index.ts` Scaffold 示例代码
- 优化侧边栏导航（MenuKey 类型清理、图标导入精简）
- 前端体验：统一样式 + API 错误处理标准化

## Capabilities

### New Capabilities

- `lightweight-refactoring`: 系统轻量化重构能力 — Phase 1 前端死代码清理 + Phase 2 后端废弃模块删除 + Phase 3 前端体验优化

### Modified Capabilities

- 无现有 spec 变更。所有核心功能（知识图谱/RAM Chat/Fix Chat/日志分析/合入分析/项目管理/技能市场）保留不变。

## Impact

- 前端: 删除 ~12 文件（6 视图 + 2 组件 + 4 API/Store），路由从 25 条减至 19 条
- 后端: 删除 ~9 文件（service/intent/ 包），待确认删除约 ~15 文件
- 依赖: 无新增或删除依赖
- 风险: 低 — 所有删除均为确认无引用链的死代码，每次删除后 `npm run build` / `mvn test` 验证

---

# 需求与代码事实简报

## 意图

### 目标与成功标准
- 目标：通过外科手术式删除废弃代码，将系统从 286 入口点/25 路由瘦身为轻量化版本
- 可观察的成功结果：
  1. `npm run build` 零错误（前端）
  2. `mvn test` 零回归（后端全量测试通过）
  3. 删除 ~20+ 文件，~1000 行废弃代码
  4. 所有核心功能页面可正常访问

### 边界与非目标
- 本次范围：删除确认无引用的死代码 + 优化前端体验
- 非目标：不修改核心 Agent 逻辑；不升级框架版本；不重构模块内部结构；不动 Neo4j / KG 生成 / 搜索 / 配置模块
- 禁止修改路径：`ram/chat/`、`fixengine/`、`knowledgegraph/`、`loganalysis/`、`mergeanalysis/` 核心逻辑

## 代码事实

### 现状摘要

前端清理前状态（Phase 1 前）：
- 25 个路由，其中 6 条为僵尸重定向（/mcp-guide → skill-market、/call-chain/* → knowledge-graph）
- SemanticSearchView.vue + 2 子组件：CLAUDE.md 标注"历史残留，暂不可用"
- naturalLanguage API + Store：零消费者（侧边栏标注"自然语言诊断已移除"）
- api/index.ts：含 health/devices 示例代码
- claude-session 页面：功能链路一部分（非孤立）

后端清理前状态：
- `service/intent/` 9 个类（DialogController/NaturalLanguageDiagnosisCoordinator/InterventionHandler 等）：无外部引用
- IFlytekEmbeddingService/SiliconFlowEmbeddingService：待确认 Unused
- GitController/ExceptionPathController：待确认前端是否使用
- remote project/group/namegroup 模块：待确认

### 可复用 / 需扩展 / 冲突

#### 可直接复用
- 现有测试套件：100+ 测试文件作为回归基线
- JWT 认证体系 + 统一 API 响应格式：保持不变
- 前端布局组件（AppLayout/AppHeader/AppSidebar）：优化后保留

#### 需求与现状冲突
- 无

## 证据表

| 类型 | 结论 | 证据 |
|---|---|---|
| 事实 | HomeView/AboutView 零引用 | Grep: `HomeView\|AboutView` → 0 matches outside self |
| 事实 | SemanticSearchView 确认不可用 | Grep: 仅在 router 中引用；CLAUDE.md 标注"历史残留" |
| 事实 | naturalLanguage API 零消费者 | Grep: `naturalLanguageStore\|useNaturalLanguage` → 仅 store 自身 |
| 事实 | service/intent/ 无外部引用 | Grep: `com.huawei.hisi.service.intent.` → 0 matches |
| 事实 | api/index.ts 仅导出 request | Grep: `from.*api/index` → 0 matches |
| 决策 | claude-session 保留（功能链路） | MethodReferenceGraph→router.push ClaudeSession; workspaceStore.bindClaudeSession |
| 决策 | call-chain 视图保留（KG 页内嵌） | KnowledgeGraph.vue 导入 ChainChart/ContextMenu |

## 风险定级

- 建议风险等级：**Low**
- 命中的风险特征：无（纯删除死代码，不修改核心逻辑）
- 未命中高风险特征：Auth/API 协议/数据库 schema 均不变
- 闸门建议：Phase 1 已通过验证（5 次 build 全部通过），Phase 2 每步 `mvn test` 验证
