# Spec: lightweight-refactoring

## ADDED Requirements

### Requirement: 前端死代码删除

前端所有未被路由/组件/Store 引用的视图、组件、API 模块和 Store 文件 SHALL 被删除。删除范围包括：Vue scaffold 残留文件（HomeView、AboutView）、确认不可用的页面组件及其子组件、零消费者的 API 模块和 Store。

#### Scenario: 删除前端死代码
- **WHEN** 存在未被路由/组件/Store 引用的视图、组件、API 模块或 Store 文件
- **THEN** 系统删除这些文件，`npm run build` 零错误

### Requirement: 僵尸路由清理

仅用于重定向的路由定义 SHALL 从 router/index.ts 中移除。按需添加 beforeEnter guard 替代 beforeEach 重定向逻辑。

#### Scenario: 清理僵尸路由
- **WHEN** 存在仅用于重定向的路由定义
- **THEN** 系统移除该路由定义，删除后 `npm run build` 零错误且保留的页面可正常导航

### Requirement: 后端死代码删除

前端已无引用且后端无内部引用的 Spring Bean、Controller、Service SHALL 被删除。每模块删除后运行 `mvn test` 全量回归。

#### Scenario: 删除后端死代码
- **WHEN** 存在前端无引用且后端无内部引用的 Spring Bean/Controller/Service
- **THEN** 系统删除这些类，`mvn test` BUILD SUCCESS 且 0 failures

### Requirement: API 示例代码清理

api/index.ts 中的 scaffold 示例代码（health/devices）SHALL 被移除，仅保留 request 导出。

#### Scenario: 清理 API 示例代码
- **WHEN** api/index.ts 含 scaffold 示例代码（health/devices）
- **THEN** 系统移除示例代码，`npm run build` 零错误

### Requirement: 侧边栏导航优化

侧边栏菜单项 SHALL 与路由保持同步 —— 已删除的页面 SHALL 从菜单中移除。MenuKey 类型定义 SHALL 与实际菜单项一致。

#### Scenario: 侧边栏与路由同步
- **WHEN** 某页面路由已删除
- **THEN** 系统从侧边栏菜单移除对应项，所有菜单项可点击且无 404

## REMOVED Requirements

- **semantic-search 端点移除**：`/search` 路由和 SemanticSearchView 页面已删除，`/api/search/*` 前端 API 模块已删除，后端 search controller 若无其他引用也删除。
- **mcp-guide 端点移除**：`/mcp-guide` 路由和 McpGuide.vue 页面已删除，`/api/mcp/*` 前端 API 模块已删除。
- **natural-language-diagnosis 端点移除**：`/api/dialog/*` 自然语言诊断 REST 端点已删除，后端 `service/intent/` 包（DialogController 等 9 个类）已删除。
