# 技术决策(ADR)

> 关键技术选型的背景、备选、决定与权衡。日期为现状记录。

---

## ADR-001:前端框架选 Vue 3

| 项 | 内容 |
|----|------|
| 状态 | Accepted |
| 上下文 | 团队偏后端 Java 背景,需要低门槛、文档完善的前端框架 |
| 选择 | **Vue 3.5 + Composition API + `<script setup lang="ts">`** |
| 备选 | React 18(TSX 灵活)、Svelte(轻量) |
| 后果(+) | 模板/响应式直观;Element Plus 表单/表格生态完善;`<script setup>` 简洁 |
| 后果(-) | TSX 灵活度不如 React;部分库 TS 支持滞后 |

---

## ADR-002:状态管理选 Pinia

| 项 | 内容 |
|----|------|
| 状态 | Accepted |
| 上下文 | 多页面共享 PROJECT_DIR / 选中项目 / Claude 会话 / 主题等 |
| 选择 | **Pinia 3 + Composition API 写法** |
| 备选 | Vuex 4 |
| 理由 | Vue 3 官方推荐;TS 一等支持;Composition store 与组件写法一致 |
| 后果 | 全部 store 用 `defineStore('xxx', () => { ... })`,无 mutations 概念 |

---

## ADR-003:UI 组件库选 Element Plus

| 项 | 内容 |
|----|------|
| 状态 | Accepted |
| 上下文 | 大量表单/表格/弹窗/通知场景,需要中文友好生态 |
| 选择 | **Element Plus 2.13** + `@element-plus/icons-vue` 全量注册 |
| 备选 | Naive UI、Ant Design Vue |
| 后果(+) | 表单校验/分页/弹窗/Message 一应俱全;中文文档 |
| 后果(-) | 包体较大;主题深度定制需额外工作 |

---

## ADR-004:HTTP 用 axios + 拦截器统一解包

| 项 | 内容 |
|----|------|
| 状态 | Accepted |
| 上下文 | 后端统一返回 `ApiResponse<T>{code,message,data}`,需要业务层无感解包;部分接口为 `?k=a&k=b` 风格 |
| 选择 | **axios 1.13** + 自定义响应拦截器 + `paramsSerializer: { indexes: null }` |
| 备选 | 原生 `fetch` 自包装 |
| 后果(+) | 业务层 `await api.foo()` 直拿 data;400 校验错误自动解析弹提示 |
| 后果(-) | 与流式协议(SSE/fetch 流)分离两套实现 |

---

## ADR-005:流式协议三栈共存

| 项 | 内容 |
|----|------|
| 状态 | Accepted |
| 上下文 | 不同后端端点协议不同:终端必须双向(WS);Claude SSE 即可;POST 携 body 流式需 fetch ReadableStream |
| 选择 | **EventSource(GET SSE)+ fetch ReadableStream(POST SSE)+ 原生 WebSocket(双向)** |
| 备选 | 全部统一为 WebSocket |
| 后果(+) | 各场景最合适协议,实现简洁 |
| 后果(-) | 三套客户端代码,需要分别封装(claudeApi/naturalLanguageApi/terminal.ts) |

---

## ADR-006:E2E 选 Playwright

| 项 | 内容 |
|----|------|
| 状态 | Accepted |
| 上下文 | 需覆盖 Chrome/Firefox/Safari + 移动端 |
| 选择 | **Playwright 1.58**,5 项目矩阵(Chromium/Firefox/WebKit/Mobile Chrome/Mobile Safari) |
| 备选 | Cypress(仅 Chromium 系)、WebDriverIO |
| 后果(+) | 跨浏览器一次配置;并发执行;自动截屏/视频 |
| 后果(-) | 首次安装浏览器二进制较大 |

---

## ADR-007:`/call-chain*` 路由全部 redirect 到 `/knowledge-graph`

| 项 | 内容 |
|----|------|
| 状态 | Accepted |
| 上下文 | 调用链能力已并入知识图谱页 Tab,旧路由仍被外链/书签使用 |
| 选择 | `router/index.ts` 中 `/call-chain*` 全部 `redirect: '/knowledge-graph?tab=methodRef'` |
| 后果(+) | 旧链接不 404;能力集中维护 |
| 后果(-) | `views/call-chain/` 目录保留但逐步退役 |

---

## ADR-008:残留 `views/search/SemanticSearchView.vue` 暂保留

| 项 | 内容 |
|----|------|
| 状态 | Accepted(待清理) |
| 上下文 | 该页调用 `/api/search/semantic`,后端无对应路由;真正的语义检索已迁至 `KnowledgeGraphView` 内嵌 `SemanticSearchPanel` 调用 `/api/vector-search` |
| 选择 | 暂保留页面文件,在 README 与术语表标注"历史残留" |
| 计划 | 公共图谱(`scope` 字段)上线时一并清理 |

---

## ADR-009:主题用 CSS 变量 + xterm.js theme 同源

| 项 | 内容 |
|----|------|
| 状态 | Accepted |
| 上下文 | UI 与 Claude 终端需视觉一致 |
| 选择 | `themes/` 提供 6 预设 `ThemeDefinition`,`themeStore.applyCSSVariables` 注入 `document.documentElement.style.setProperty`,xterm.js 同时按 theme 渲染 |
| 后果(+) | 单一主题源驱动两套渲染;切换无刷新 |
| 后果(-) | 新增 CSS 变量时需 6 预设全部补齐 |

---

> **延伸阅读**:[架构设计](../02-架构设计/index.md)
