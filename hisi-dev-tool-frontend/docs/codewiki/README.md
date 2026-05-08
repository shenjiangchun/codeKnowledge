# HiSi DevTool Frontend CodeWiki

> 自动生成于 2026-05-08，基于 `main` 分支
> 项目路径：`hisi-dev-tool-frontend/`

## 项目速览

| 指标 | 值 |
|------|-----|
| 项目名 | hisi-dev-tool-frontend |
| 技术栈 | Vue 3.5 + TypeScript 5.9 + Vite 8 + Pinia 3 + Element Plus 2.13 + ECharts 6 + Axios 1.13 |
| 测试栈 | Vitest 4 (单元) + Playwright 1.58 (E2E) + happy-dom |
| 版本 | 0.0.0（开发中） |
| 源文件数 | 100 个（`src/**/*.{ts,vue}`） |
| 模块数 | 8 个核心模块 |
| 入口 | `index.html` → `src/main.ts` → `App.vue` → `AppLayout` |
| 后端 | `../hisi-dev-tool`（Spring Boot 3.2 + Java 17 + Neo4j + 智谱 AI） |
| 开发端口 | `5173` |
| 后端代理 | `/api` → `http://localhost:8080`，`/ws` → `ws://localhost:8080` |

## 目录

### 理解项目

| 章节 | 说明 |
|------|------|
| [项目概览](01-项目概览/index.md) | 使命、技术栈、快速启动、核心概念 |
| [术语表](09-术语表/index.md) | 项目专有名词解释 |

### 理解设计

| 章节 | 说明 |
|------|------|
| [架构设计](02-架构设计/index.md) | 分层设计、C4 图、技术选型、质量属性 |
| [数据流程](04-数据流程/index.md) | 端到端数据流、流式 SSE、WebSocket 状态机 |
| [技术决策](08-技术决策/index.md) | 关键技术选型的原因和权衡（ADR） |

### 开发参考

| 章节 | 说明 |
|------|------|
| [模块说明](03-模块说明/) | 各功能模块的职责、接口、内部结构 |
| [接口文档](05-接口文档/index.md) | REST / SSE / WebSocket 接口清单 |
| [数据模型](06-数据模型/index.md) | TypeScript 类型、Store 状态、枚举 |

### 运维部署

| 章节 | 说明 |
|------|------|
| [部署运维](07-部署运维/index.md) | 环境配置、构建、部署、E2E 测试 |

## 模块列表

| 模块 | 层 | 文件 | 关键词 |
|------|-----|------|--------|
| 应用入口与路由 | 表现层 | [03-模块说明/应用入口与路由.md](03-模块说明/应用入口与路由.md) | main.ts、Vue Router、路由守卫 |
| 布局与通用组件 | 表现层 | [03-模块说明/布局与通用组件.md](03-模块说明/布局与通用组件.md) | AppLayout、AppSidebar、ProjectDirConfig |
| 业务视图 | 表现层 | [03-模块说明/业务视图.md](03-模块说明/业务视图.md) | views、KnowledgeGraph、ClaudeTerminal |
| Pinia 状态管理 | 应用层 | [03-模块说明/Pinia状态管理.md](03-模块说明/Pinia状态管理.md) | app、session、skill、theme、workspace |
| API 服务层 | 服务层 | [03-模块说明/API服务层.md](03-模块说明/API服务层.md) | axios、拦截器、21 个 API 模块 |
| 类型与数据契约 | 服务层 | [03-模块说明/类型与数据契约.md](03-模块说明/类型与数据契约.md) | types/、ApiResponse、Intent、Agent |
| Composables 与工具 | 应用层 | [03-模块说明/Composables与工具.md](03-模块说明/Composables与工具.md) | useDiagnosis、useDialogWebSocket、request |
| 主题系统 | 基础设施层 | [03-模块说明/主题系统.md](03-模块说明/主题系统.md) | themes/、CSS 变量、xterm.js 主题 |

## 推荐阅读路径

| 角色 | 路径 |
|------|------|
| 新成员入职 | [项目概览](01-项目概览/index.md) → [架构设计](02-架构设计/index.md) → [应用入口与路由](03-模块说明/应用入口与路由.md) → [术语表](09-术语表/index.md) |
| 功能开发 | [API 服务层](03-模块说明/API服务层.md) → [Pinia 状态管理](03-模块说明/Pinia状态管理.md) → [接口文档](05-接口文档/index.md) → [数据模型](06-数据模型/index.md) |
| 流式调试 | [数据流程](04-数据流程/index.md) → [Composables 与工具](03-模块说明/Composables与工具.md) → [接口文档](05-接口文档/index.md) |
| 技术选型 | [技术决策](08-技术决策/index.md) → [架构设计](02-架构设计/index.md) |
| 部署上线 | [部署运维](07-部署运维/index.md) → [架构设计](02-架构设计/index.md) |
