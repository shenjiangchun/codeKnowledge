# HiSi DevTool Frontend

## Project Overview

**Version**: 0.x (Development)
**Tech Stack**: Vue 3.5+ + TypeScript 5.x + Element Plus + Pinia + ECharts + Axios
**Purpose**: HiSi DevTool 前端 —— 知识图谱管理/语义检索/调用链可视化/日志诊断/项目管理/Skill 市场/MCP/Claude 终端。

## Quick Start

```bash
cd hisi-dev-tool-frontend
npm install
npm run dev        # Dev server → http://localhost:5173
npm run build      # Production build
npm run preview    # Preview production build
```

**Default Dev Port**: 5173
**API Proxy**: `vite.config.ts` → `http://localhost:8080`

## Architecture

```
src/
├── api/                    # Axios API 模块（每模块一个文件）
│   ├── index.ts            # Axios 实例 + 全局拦截器
│   ├── knowledgeGraph.ts   # /api/knowledge-graph/*
│   ├── vectorSearch.ts     # /api/vector-search
│   ├── vectorGeneration.ts # /api/vector-generation/*
│   ├── search.ts           # /api/search/*
│   ├── callChain.ts        # /api/callchain/*
│   ├── logAnalysis.ts      # /api/log/*
│   ├── project.ts          # /api/projects/*
│   ├── ops.ts              # /api/ops/*
│   ├── git.ts              # /api/git/*
│   ├── mcp.ts              # /api/mcp/*
│   ├── skillMarket.ts      # /api/skill/*
│   ├── claude.ts           # Claude 相关
│   ├── session.ts          # /api/session/*
│   ├── workspaceSession.ts # /api/workspace-session/*
│   └── ...
├── components/             # 复用组件
│   └── layout/             # AppLayout / AppHeader / AppSidebar
├── router/                 # Vue Router
├── stores/                 # Pinia stores
├── types/                  # TypeScript 类型定义
├── views/                  # 页面组件
│   ├── knowledge-graph/    # ★ 知识图谱 & 语义检索
│   ├── search/             # 全局搜索
│   ├── call-chain/         # 调用链可视化
│   ├── log-analysis/       # 日志查询与分析
│   ├── project/            # 项目管理
│   ├── mcp/                # MCP 配置
│   ├── claude-session/     # Claude 会话
│   ├── claude-terminal/    # Claude 终端
│   ├── skill-market/       # Skill 市场
│   ├── prompt-config/      # Prompt 配置
│   ├── settings/           # 设置
│   └── HomeView.vue
├── App.vue
└── main.ts
```

## 核心页面与对应后端端点

### 主战场

| 页面 | 路由 | 后端 API | 备注 |
|---|---|---|---|
| 知识图谱管理 | `/knowledge-graph` | `/api/knowledge-graph/*` | 项目扫描 → KG 生成 → 向量生成 |
| 语义检索（面板） | 知识图谱页内嵌 `SemanticSearchPanel` | `POST /api/vector-search` | 发 `{query, projectPath, limit}` ；**目前不传 scope/language**（后端默认回退单项目模式） |
| 向量生成 | 知识图谱页内嵌 | `/api/vector-generation/*` | 触发智谱 embedding 生成 |

> ⚠️ `views/search/SemanticSearchView.vue` 使用 `/api/search/semantic`（后端无此路由），是历史残留，暂不可用。

### 通用

| 页面 | 路由 | 后端 API |
|---|---|---|
| 调用链 | `/call-chain` | `/api/callchain/*` |
| 日志分析 | `/log-analysis` | `/api/log/*` |
| 项目管理 | `/project` | `/api/projects/*` |
| MCP | `/mcp` | `/api/mcp/*` |
| Skill 市场 | `/skill-market` | `/api/skill/*` |

## 与后端架构对齐要点

| 后端事实 | 前端影响 |
|---|---|
| Neo4j + 智谱 AI 为主存储/AI 引擎 | 前端不直接连 Neo4j/智谱，通过后端 REST 代理 |
| `VectorSearchController` 新增 `scope` / `language` 可选字段 | `api/vectorSearch.ts` 的 `VectorSearchRequest` 目前只有 `query/projectPath/limit/graphDepth`；**公共图谱检索功能上线后需追加 `scope?` / `language?` 字段** |
| 公共知识图谱端点（进行中）：`/public/scan` / `/public/generate` / `/public/refresh` | 知识图谱页需新增"公共图谱生成"按钮 + 引导弹窗（plan P3 Task 29–30） |
| ChromaDB / `hisi-vector-service` 已废弃 | 前端如有残留调用或提示文案应清理 |

## Key Dependencies

| Package | Version | Purpose |
|---|---|---|
| vue | ^3.5.30 | Core framework |
| vue-router | ^4.6.4 | Routing |
| pinia | ^3.0.4 | State management |
| element-plus | ^2.13.5 | UI components |
| axios | ^1.13.6 | HTTP client |
| echarts | ^6.0.0 | Charts / visualization |

## Code Conventions

1. **Composition API**: `<script setup lang="ts">` for all components
2. **TypeScript**: 强类型，接口定义放 `types/`
3. **Naming**: PascalCase（组件），camelCase（函数/变量）
4. **Styling**: `<style scoped>` 块作用域 CSS
5. **Error Handling**: `ElMessage` 向用户反馈；`try/catch` 包裹 async 调用
6. **API Response**: 后端统一返回 `ApiResponse<T>`，前端在拦截器中解包

## Related Projects

- **Backend**: `../hisi-dev-tool` — Spring Boot 3.2.0 + Java 17 + Neo4j 5.11+ + 智谱 AI

## Common Tasks

### 新增页面

1. `views/` 下创建 Vue 组件
2. `router/index.ts` 加路由
3. `components/layout/AppSidebar.vue` 加导航项
4. `api/` 下创建 API 模块
5. `types/` 下定义类型

### 新增 API 调用

1. `types/` 定义请求/响应类型
2. `api/xxx.ts` 创建或更新函数
3. 组件中 `async/await` + `try/catch` + `ElMessage` 错误提示
