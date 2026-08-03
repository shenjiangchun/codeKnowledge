# lightweight-refactoring

## Purpose
系统通过外科手术式删除废弃代码实现轻量化，每步可验证可回滚。

## ADDED Requirements

### REQ-LR-001: 前端死代码删除
前端所有未被路由/组件/Store 引用的视图、组件、API 模块和 Store 文件应被删除。删除范围包括：
- Vue scaffold 残留文件（HomeView, AboutView）
- 确认不可用的页面组件及其子组件
- 零消费者的 API 模块和 Store

**验证**: `npm run build` 零错误

### REQ-LR-002: 僵尸路由清理
仅用于重定向的路由定义应从 router/index.ts 中移除。按需添加 beforeEnter guard 替代 beforeEach 重定向逻辑。

**验证**: 删除后 `npm run build` 零错误，且保留的页面可正常导航

### REQ-LR-003: 后端死代码删除
前端已无引用且后端无内部引用的 Spring Bean、Controller、Service 应被删除。每模块删除后运行 `mvn test` 全量回归。

**验证**: `mvn test` BUILD SUCCESS, 0 failures

### REQ-LR-004: API 示例代码清理
api/index.ts 中的 scaffold 示例代码（health/devices）应被移除，仅保留 request 导出。

**验证**: `npm run build` 零错误

### REQ-LR-005: 侧边栏导航优化
侧边栏菜单项与路由保持同步 —— 已删除的页面应从菜单中移除。MenuKey 类型定义应与实际菜单项一致。

**验证**: 侧边栏所有菜单项可点击，无 404

## REMOVED Requirements

### REQ-LR-006: semantic-search 端点移除
`/search` 路由和 SemanticSearchView 页面已删除。`/api/search/*` 前端 API 模块已删除。后端 search controller 若在其他功能中无引用也应删除。

### REQ-LR-007: mcp-guide 端点移除
`/mcp-guide` 路由和 McpGuide.vue 页面已删除。`/api/mcp/*` 前端 API 模块已删除。

### REQ-LR-008: natural-language-diagnosis 端点移除
`/api/dialog/*` 自然语言诊断 REST 端点已删除。后端 `service/intent/` 包（DialogController 等 9 个类）已删除。

## MODIFIED Requirements

无。所有核心功能（知识图谱、RAM Chat、Fix Chat、日志分析、合入分析、项目管理、技能市场）保持不变。
