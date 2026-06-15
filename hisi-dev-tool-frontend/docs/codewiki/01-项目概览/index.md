# 01-项目概览

## 使命与定位

HiSi DevTool Frontend 是面向开发者的**代码理解与运维平台**前端应用，为后端 Spring Boot 服务提供现代化的 Web 用户界面。核心使命是将复杂的代码分析、知识图谱、影响评估等能力以直观的可视化方式呈现给开发者。

### 核心价值

1. **需求分析智能化**：通过 RAM（Requirement Analysis Master）三页向导，实现需求 → 影响分析 → 技术方案的自动化流程
2. **代码理解可视化**：知识图谱浏览器支持方法搜索、调用链导航、入口类型浏览
3. **APM 调试便捷化**：DTO schema body skeleton、entryNodeId launch、API search autocomplete
4. **合并影响分析**：向导 UI + SSE streaming，实时展示代码变更影响范围

---

## 技术栈

| 类别 | 技术 | 版本 | 用途 |
|------|------|------|------|
| **核心框架** | Vue 3 | ^3.5.30 | 响应式 UI 框架 |
| **类型系统** | TypeScript | ~5.9.3 | 静态类型检查 |
| **UI 组件库** | Element Plus | ^2.13.5 | 企业级 UI 组件 |
| **状态管理** | Pinia | ^3.0.4 | 集中式状态管理 |
| **路由** | Vue Router | ^4.6.4 | SPA 路由 |
| **HTTP 客户端** | Axios | ^1.13.6 | API 请求 |
| **图表可视化** | ECharts | ^6.0.0 | 数据可视化 |
| **流程图** | Mermaid | ^11.15.0 | 流程图、时序图 |
| **图布局** | dagre | ^1.1.8 | DAG 自动布局 |
| **流程图引擎** | Vue Flow | ^1.48.2 | 交互式流程图 |
| **图数据库可视化** | Cytoscape | ^3.33.4 | 图数据可视化 |
| **终端模拟** | xterm.js | ^6.0.0 | Web 终端 |
| **Markdown** | marked | ^18.0.4 | Markdown 渲染 |
| **工具库** | lodash-es | ^4.18.1 | 工具函数 |
| **构建工具** | Vite | ^8.0.0 | 开发服务器、构建 |
| **单元测试** | Vitest | ^4.1.7 | 单元测试框架 |
| **E2E 测试** | Playwright | ^1.58.2 | 端到端测试 |
| **DOM 模拟** | happy-dom | ^20.9.0 | 测试环境 |

---

## 快速启动

### 环境要求

- Node.js 18+
- npm 9+ 或 pnpm 8+
- 后端服务：`hisi-dev-tool`（默认 `http://localhost:8080`）

### 安装与运行

```bash
# 进入前端目录
cd hisi-dev-tool-frontend

# 安装依赖
npm install

# 启动开发服务器（默认端口 5173）
npm run dev

# 构建生产版本
npm run build

# 预览生产构建
npm run preview
```

### 测试

```bash
# 单元测试
npm run test:unit

# 单元测试（监听模式）
npm run test:unit:watch

# 单元测试（覆盖率）
npm run test:unit:coverage

# E2E 测试
npm run test:e2e

# E2E 测试（UI 模式）
npm run test:e2e:ui
```

---

## 核心概念

### 1. RAM（Requirement Analysis Master）

需求分析大师，三页向导流程：
- **InputPage**：输入需求原文 + 选择目标项目
- **DraftPage**：SSE 实时接收分析进度，DAG 可视化展示 5 个分析节点
- **GraphPreviewPage**：影响图谱可视化（ThreeRingGraph + DagGraph）

### 2. APM 调试

应用性能监控调试工具：
- DTO schema body skeleton：基于后端 schema 自动生成请求体
- entryNodeId launch：通过入口节点 ID 快速启动调试
- API search autocomplete：API 搜索自动补全

### 3. 知识图谱浏览器

代码知识图谱的可视化探索工具：
- 方法搜索：按方法名、类名搜索
- 调用链导航：上下游调用关系可视化
- 入口类型浏览：按 Controller/Schedule/MQ 等类型浏览

### 4. 合并分析

代码合并影响分析工具：
- 向导 UI：引导用户输入分支信息
- SSE streaming：实时接收分析进度
- Diff 预览：代码差异可视化

---

## 目录结构

```
hisi-dev-tool-frontend/
├── src/
│   ├── api/                    # Axios API 模块（每模块一个文件）
│   ├── components/             # 复用组件
│   │   ├── layout/             # AppLayout / AppHeader / AppSidebar
│   │   ├── ram/                # RAM 相关组件
│   │   ├── dialog/             # 对话相关组件
│   │   └── diagnosis/          # 诊断相关组件
│   ├── composables/            # Vue 3 组合式函数
│   ├── router/                 # Vue Router 配置
│   ├── stores/                 # Pinia 状态管理
│   ├── types/                  # TypeScript 类型定义
│   ├── views/                  # 页面组件
│   │   ├── ram/                # RAM 需求分析
│   │   ├── apm-debug/          # APM 调试
│   │   ├── knowledge-graph/    # 知识图谱浏览器
│   │   ├── merge-analysis/     # 合并分析
│   │   ├── call-chain/         # 调用链可视化
│   │   ├── claude-terminal/    # Claude 终端
│   │   ├── claude-session/     # Claude 会话
│   │   ├── log-analysis/       # 日志分析
│   │   ├── search/             # 语义搜索
│   │   ├── project/            # 项目管理
│   │   ├── skill-market/       # 技能市场
│   │   ├── glossary/           # 术语管理
│   │   ├── prompt-config/      # 提示词配置
│   │   ├── settings/           # 系统设置
│   │   └── mcp/                # MCP 配置
│   ├── utils/                  # 工具函数
│   ├── themes/                 # 主题配置
│   ├── App.vue                 # 根组件
│   └── main.ts                 # 入口文件
├── public/                     # 静态资源
├── package.json                # 项目配置
├── vite.config.ts              # Vite 配置
├── tsconfig.json               # TypeScript 配置
└── docs/                       # 文档
    └── codewiki/               # 本手册
```

---

## 与后端的关系

| 后端事实 | 前端影响 |
|---------|---------|
| Spring Boot 3.2.0 + Java 17 | 前端通过 REST API 与后端交互 |
| Neo4j 5.11+ 图数据库 | 前端不直接连接，通过后端代理 |
| 智谱 AI（embedding-3 / glm-4-flash） | 前端不直接调用，通过后端代理 |
| WebSocket 终端会话 | 前端使用 xterm.js 连接 WebSocket |
| SSE 事件流 | 前端使用 EventSource 接收实时事件 |

---

## 下一步

- [架构设计](../02-架构设计/index.md) - 深入了解系统架构
- [术语表](../09-术语表/index.md) - 了解项目专有名词
- [模块说明](../03-模块说明/) - 各功能模块详细说明
