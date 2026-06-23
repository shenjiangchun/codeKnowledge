# 需求分析大师 & 日志分析模块 — 交互与设计问题整改计划

> 生成日期: 2026-06-21
> 更新日期: 2026-06-21（合并历史分析 29 项发现 + 本轮分析 16 项发现，去重后共 28 项）
> 验证日期: 2026-06-22（逐项代码验证完成，28 项中 24 项确认存在，4 项不成立/降级）
> 分析范围: RAM（需求分析大师）+ Log Analysis（日志分析）前后端全链路

---

## 一、问题总览

| # | 模块 | 严重度 | 问题概要 | 类型 | 来源 | 验证 |
|---|------|--------|----------|------|------|------|
| L-01 | 日志 | 🔴 CRITICAL | `ReportDetail.vue` 使用 `res.data` 而非 `res`，导致报告详情页数据始终为 undefined | Bug | 本轮 | ✅ 确认 |
| L-02 | 日志 | 🟠 HIGH | `LogQuery.vue` 2404 行，3 Tab + 4 弹窗，严重违反单一职责 | 代码质量 | 本轮 | ✅ 确认 |
| L-03 | 日志 | 🟠 HIGH | `renderMarkdown()` 在 5 个文件中重复实现 | 代码质量 | 本轮 | ✅ 确认 |
| L-04 | 日志 | 🟠 HIGH | `handleAnalyze()` 跳转 ClaudeTerminal，~150 行弹窗代码成死代码 | 交互设计 | 历史+本轮 | ✅ 确认 |
| L-05 | 日志 | 🟠 HIGH | 13 处 `v-html` 无 DOMPurify 消毒，存在 XSS 风险 | 安全 | 历史 | ✅ 确认（实际攻击面低-中） |
| L-06 | 日志 | 🟡 MEDIUM | `log-analysis: true` 硬编码，路由守卫为死代码 | 设计冗余 | 本轮 | ✅ 确认 |
| L-07 | 日志 | 🟡 MEDIUM | `RootCauseAnalysisService` 注入但未使用 | 死代码 | 本轮 | ✅ 确认 |
| L-08 | 日志 | ~~🟡~~ 🟢 | ~~定时拉取间隔未使用配置字段~~ → 实际为 `@Scheduled` 固定间隔 + 配置字段可扩展 | 设计缺陷 | 本轮 | ⚠️ 降级（非紧急） |
| L-09 | 日志 | ~~🟡~~ ❌ | ~~报告详情字段渲染类型问题~~ → `renderMarkdown` 已正确处理 Map 类型 | 数据契约 | 历史 | ❌ 不成立 |
| L-10 | 日志 | 🟡 MEDIUM | DSL 查询界面默认展示 7 条条件，新用户上手困难 | UX | 历史 | ✅ 确认 |
| L-11 | 日志 | 🟡 MEDIUM | 分析进度缺乏反馈，`checkReportStatus` 为死代码 | UX | 历史 | ✅ 确认 |
| L-12 | 日志 | 🟢 LOW | `onMounted` 中对默认 tab 的检查为死代码 | 代码质量 | 本轮 | ✅ 确认 |
| R-01 | RAM | 🟠 HIGH | 会话列表通过 `intent` 字符串匹配过滤，后端无 `sessionType` 字段 | 设计缺陷 | 本轮 | ✅ 确认 |
| R-02 | RAM | 🟠 HIGH | SSE 2 线程 + 500ms 轮询，10 并发时实际间隔拉长到 2.5s | 性能 | 本轮 | ✅ 确认 |
| R-03 | RAM | ~~🟠~~ 🟡 | ~~SSE 缺少标准 id 字段~~ → 已有 `seq + afterSeq` 等效机制，功能不受影响 | SSE 规范 | 历史 | ⚠️ 降级（非 bug） |
| R-04 | RAM | 🟠 HIGH | `/ram/*` 路由无导航守卫，URL 可绕过侧边栏禁用 | 安全 | 历史 | ✅ 确认 |
| R-05 | RAM | ~~🟡~~ 🟢 | ~~sessionIdMap 重启窗口期~~ → 有 DB 回退兜底，影响极低 | 可靠性 | 本轮 | ⚠️ 降级 |
| R-06 | RAM | ~~🟡~~ 🟢 | ~~abortedSessions 内存泄漏~~ → 有 LRU 10k 上限 + DB 持久化兜底 | 可靠性 | 本轮 | ⚠️ 降级 |
| R-07 | RAM | 🟡 MEDIUM | StatusPage/Phase2Page 用 3s 轮询，与 DraftPage 的 SSE 不一致 | 设计不一致 | 本轮 | ✅ 确认 |
| R-08 | RAM | 🟡 MEDIUM | DraftPage `watch(events) + processedSeq` 120 行回调，与 composable `lastSeq` 双重去重 | 代码质量 | 本轮 | ✅ 确认 |
| R-09 | RAM | 🟡 MEDIUM | RamController 8 参数注入、18 端点、1574 行，混合三个业务域 | 代码质量 | 本轮 | ✅ 确认 |
| R-10 | RAM | 🟡 MEDIUM | 前后端状态命名不一致 + 前端两套命名体系并存 | 数据契约 | 历史 | ✅ 确认 |
| R-11 | RAM | ~~🟡~~ ℹ️ | ~~SSE 澄清时断流~~ → 有意设计，节省资源，恢复机制完善 | SSE 设计 | 历史 | ℹ️ 设计如此 |
| R-12 | RAM | 🟡 MEDIUM | 澄清弹窗自动弹出打断用户，已有"继续澄清"按钮但首次打断未解决 | UX | 历史 | ✅ 确认 |
| R-13 | RAM | 🟡 MEDIUM | 重跑按钮无确认对话框、无影响范围提示 | UX | 历史 | ✅ 确认 |
| R-14 | RAM | 🟡 MEDIUM | 项目选择 4 种方式 + 2 种分析模式，新用户认知负担重 | UX | 历史 | ✅ 确认 |
| R-15 | RAM | ~~🟢~~ ❌ | ~~API 响应解耦风格不一致~~ → 两个模块风格一致，都不访问 `.data` | 一致性 | 本轮 | ❌ 不成立 |
| R-16 | RAM | ~~🟢~~ ❌ | ~~菜单键命名不一致~~ → menuKey 和 route name 属于不同命名空间，无需一致 | 一致性 | 历史 | ❌ 不成立 |

---

## 二、问题详细分析与整改方案

### L-01 🔴 `ReportDetail.vue` API 响应解包错误

**现象**: `ReportDetail.vue` 第 123 行使用 `res.data` 取值，而 axios 拦截器已解包响应，导致 `report` 始终为 `undefined`。

**根因**: 前端存在两套 API 响应处理模式：
- 模式 A: axios 拦截器解包 `response.data`，组件直接用 `res`（LogQuery.vue 采用）
- 模式 B: 组件自行访问 `res.data`（ReportDetail.vue 采用）

两种模式混用导致数据取不到。

**整改方案**:
1. 统一为模式 A（拦截器解包），修改 `ReportDetail.vue` 中 `res.data` → `res`
2. 全局排查所有 API 调用，确保一致

**验证**: 访问 `/log-analysis/report/:id` 页面能正确显示报告内容。

---

### L-02 🟠 `LogQuery.vue` 巨型组件拆分

**现状**: 单文件 ~2400 行，包含：
- Tab 1: DSL 查询构建器 + 手动 DSL + 推荐查询 + 查询结果 + 日志详情弹窗
- Tab 2: 定时任务配置管理 + 分组选择
- Tab 3: 报告列表 + 报告详情弹窗
- Claude 分析弹窗（流式对话）

**整改方案**: 拆分为 4 个子组件 + 1 个 composable：

```
views/log-analysis/
├── LogQuery.vue              (主壳，~200行，Tab 切换 + 数据编排)
├── LogQueryTab.vue           (Tab 1: 查询构建 + 结果表格，~600行)
├── LogConfigTab.vue          (Tab 2: 配置管理，~300行)
├── LogReportsTab.vue         (Tab 3: 报告列表 + 详情弹窗，~400行)
├── LogDetailDialog.vue       (日志详情弹窗，~200行)
├── ReportDetail.vue          (独立路由页面，保持不变)
└── composables/
    └── useLogQuery.ts        (查询状态 + DSL 构建逻辑，~200行)
```

**公共工具提取**:
- `utils/markdown.ts` — `renderMarkdown()` 函数（解决 L-03 重复问题）

**验证**: 三个 Tab 功能正常切换，查询/配置/报告操作不受影响。

---

### L-04 🟠 分析流程入口割裂 + 死代码

**现象**: `handleAnalyze()` 跳转到 `ClaudeTerminal` 页面而非在页面内完成分析，打断用户闭环体验。

**现状代码**:
```typescript
// LogQuery.vue handleAnalyze()
const session = await workspaceStore.createSession('log-analysis', prompt)
router.push({ name: 'ClaudeTerminal', query: { sessionId: session.id } })
```

**问题**:
- 用户在日志查询页点击"分析"后被跳转到完全不同的页面，丢失查询上下文
- 同文件中残留的弹窗代码（`analysisVisible`/`streamOutput`/`sendChat()` 等 ~150 行）成为死代码

**整改方案**:
- 短期: 清理死代码（删除弹窗状态变量 + 模板 + 方法）
- 长期: 在页面内嵌入分析面板（侧边抽屉或底部面板），不跳转页面

**验证**: 点击"分析"后用户仍留在日志查询页，分析结果在面板中展示。

---

### L-05 🟠 Markdown XSS 风险

**现象**: `renderMarkdown()` 直接调用 `marked.parse()` 并通过 `v-html` 注入 DOM，无任何消毒处理。

**现状代码**:
```typescript
// LogQuery.vue 第1069行
const renderMarkdown = (content: any): string => {
  // ...
  return marked.parse(str) as string  // 直接返回 HTML
}
// 模板中
<div v-html="renderMarkdown(selectedReport.errorSummary)"></div>
```

**问题**: 全局搜索 `sanitize`/`DOMPurify`/`dompurify` 返回 0 条匹配。虽然后端数据可信度较高，但报告内容可能包含用户输入（如日志消息），存在存储型 XSS 风险。

**整改方案**:
1. 安装 `dompurify`: `pnpm add dompurify && pnpm add -D @types/dompurify`
2. 在 `utils/markdown.ts`（L-03 提取的公共函数）中封装消毒逻辑：
   ```typescript
   import DOMPurify from 'dompurify'
   export function renderMarkdown(content: any): string {
     const html = marked.parse(str) as string
     return DOMPurify.sanitize(html)
   }
   ```
3. 所有 `v-html` 统一使用消毒后的输出

**验证**: 在报告内容中注入 `<script>alert(1)</script>` 后不执行。

---

### L-09 🟡 报告详情字段渲染类型问题

**现象**: `renderMarkdown()` 接收 `any` 类型参数，但后端返回的 `errorSummary`/`rootCause` 可能是 `Map<String, Object>` 而非 `String`。

**现状**: `LogAnalysisReportEntity` 中 `errorSummary` 声明为 `Map<String, Object>`，但 `renderMarkdown()` 期望 string/array。

**整改方案**: 在 `renderMarkdown()` 中增加 Map 类型处理：
```typescript
if (typeof content === 'object' && !Array.isArray(content)) {
  return Object.entries(content).map(([k, v]) => `**${k}**: ${v}`).join('\n')
}
```

**验证**: 报告详情页所有字段正确渲染，无 `[object Object]` 显示。

---

### L-10 🟡 DSL 查询界面复杂度

**现状**: 日志查询 Tab 默认展示 7 个 should 条件 + 手动 DSL 输入 + 4 个推荐查询卡片，新用户认知负担重。

**整改方案**:
- 默认折叠 DSL 构建器，仅展示"快速查询"输入框 + 推荐查询卡片
- 高级模式展开后显示完整 DSL 构建器
- 保留"一键查询错误日志"快捷按钮

**验证**: 新用户能在 3 步内完成一次日志查询。

---

### L-11 🟡 分析进度缺乏反馈

**现状**: 提交分析后仅返回 `pending` 状态，用户无法感知分析进度。虽然后端 `GET /api/log/report/:id/status` 返回 `progress`/`stage`/`etaSeconds`，但前端未使用。

**整改方案**: 在报告列表中增加进度条组件，轮询 `getStatus` API 直到 `completed`/`failed`。

**验证**: 提交分析后能看到进度条从 0% 到 100%。

---

### L-06 🟡 定时拉取间隔未使用配置字段

**现状**:
- `AppLogConfig.pullIntervalMinutes` 字段存在且前端可配置
- `LogPullScheduler.pullLogsForAllApps()` 使用 `@Scheduled(fixedRate = 600000)` 硬编码 10 分钟
- 所有应用共用同一拉取间隔

**整改方案**:
- 方案 A（推荐）: 使用 `ScheduledExecutorService` 替代 `@Scheduled`，按每个配置的 `pullIntervalMinutes` 独立调度
- 方案 B（最小改动）: 在 `@Scheduled` 方法内检查 `lastPullAt` + `pullIntervalMinutes` 决定是否跳过

**验证**: 修改 `pullIntervalMinutes` 后，实际拉取间隔随之变化。

---

### R-01 🟠 会话列表过滤方式脆弱

**现状**: `SessionListPage` 和 `StatusSessionListPage` 通过字符串匹配过滤：
```typescript
// SessionListPage: 排除现状分析
const demandSessions = computed(() =>
  sessions.value.filter(s =>
    !s.intent?.includes('现状分析') &&
    !s.intent?.includes('project_overview') &&
    !s.intent?.includes('项目概览') &&
    !s.intent?.includes('Phase2')
  )
)
```

**问题**:
- 依赖 `intent` 字符串内容，新增会话类型时需同步修改过滤逻辑
- 两个页面的过滤条件互为补集，但没有强制约束

**整改方案**:
- 后端 `AgentSession` 增加 `sessionType` 枚举字段（`demand` / `status` / `phase2`）
- 会话列表 API 支持按 `sessionType` 过滤
- 前端直接使用 `sessionType` 过滤，不再依赖 `intent` 字符串

**验证**: 两个列表页正确展示各自类型的会话，新增会话类型无需修改前端过滤逻辑。

---

### R-02 🟠 SSE 轮询性能问题

**现状**: `RamController` 中 SSE 实现：
```java
streamScheduler = Executors.newScheduledThreadPool(2);
// 每 500ms 轮询一次数据库
streamScheduler.scheduleAtFixedRate(() -> {
    // 查询 agent_event 表
}, 0, 500, TimeUnit.MILLISECONDS);
```

**问题**:
- 每个 SSE 连接每 500ms 查询一次 DB，10 个并发连接 = 每秒 20 次 DB 查询
- 2 线程池无法支撑更多并发
- 心跳和事件查询耦合在同一任务中

**整改方案**:
- 方案 A（推荐）: 改用数据库 WAL 监听或 Spring `ApplicationEvent` 推送模式，事件写入时主动通知 SSE 线程
- 方案 B: 增大轮询间隔至 1-2 秒，心跳间隔独立为 15 秒
- 无论哪种方案，线程池大小应改为可配置

**验证**: 10 个并发 SSE 连接时 DB 查询频率下降 50%+，事件延迟不超过 1 秒。

---

### R-05 🟡 状态分析轮询 vs SSE 不一致

**现状**:
- RAM 需求分析: SSE 实时推送（`useRamSession` composable）
- RAM 现状分析/Phase2: 3 秒轮询（`setInterval` + `getStatusReport()`）

**问题**: 同一模块两种实时通信模式，增加维护成本和用户困惑。

**整改方案**:
- 短期: 保持现状，在 UI 上对轮询状态加 loading 指示器
- 长期: 现状分析和 Phase2 也走 SSE 通道，复用 `useRamSession` composable

**验证**: 现状分析完成后 UI 能及时响应（不超过 3 秒延迟）。

---

### R-08 🟡 DraftPage 事件处理复杂度

**现状**: `DraftPage` 通过 `watch(session.events)` 逐事件处理：
```typescript
watch(() => session.events.value, (events) => {
  for (const ev of events) {
    if (ev.seq <= processedSeq.value) continue;
    // 按 type 分发到不同处理逻辑
    // 更新 markdown 变量
    // 写入 Pinia store
    processedSeq.value = ev.seq;
  }
}, { deep: true });
```

**问题**:
- watch 回调中累积了大量副作用（更新 5 个节点的 markdown、提取 ImpactPayload、管理 clearedNodeKeys）
- `processedSeq` 作为可变状态在多处被修改，调试困难

**整改方案**:
- 将事件处理逻辑提取为 `useDagEventHandler(session, store)` composable
- 每个节点的事件处理独立为纯函数
- `processedSeq` 由 composable 内部管理，外部只读

**验证**: DAG 状态正确更新，节点详情正确展示，重跑功能正常。

---

### R-07 🟡 RamController 职责过重

**现状**: `RamController` 注入 8 个依赖，处理 18 个 API 端点，涵盖：
- 需求分析会话 CRUD（8 个端点）
- 项目现状分析（2 个端点）
- Phase2 精确分析（2 个端点）
- 健康检查（1 个端点）
- SSE 流管理（1 个端点）

**整改方案**: 拆分为 3 个 Controller：
```
RamController              — 需求分析会话 CRUD + SSE (10 端点)
RamStatusController        — 项目现状分析 + Phase2 (4 端点)
RamHealthController        — 健康检查 (1 端点)
```

**验证**: 所有 API 端点行为不变，URL 路径不变。

---

### R-03 🟠 SSE 事件缺少标准 id 字段

**现状**: `RamController.sendEvent()` 使用 `SseEmitter.event().data(payload)` 发送事件，未调用 `.id()` 设置标准 SSE 字段。

**问题**:
- SSE 规范的 `id` 字段用于断线重连时的 `Last-Event-ID` 机制
- 当前使用自定义 `afterSeq` 查询参数实现类似功能，不符合标准协议
- 浏览器原生 `EventSource` 的自动重连无法利用 `Last-Event-ID`

**整改方案**:
```java
// 修改 sendEvent()
emitter.send(SseEmitter.event()
    .id(String.valueOf(event.getSeq()))  // 添加标准 id
    .name(event.getType())               // 添加事件名
    .data(payload));
```

**验证**: 浏览器断线重连后能从上次断点继续接收事件。

---

### R-04 🟠 RAM 路由缺乏导航守卫

**现状**: `router/index.ts` 中所有 `/ram/*` 路由无任何前置条件校验。导航守卫仅处理 `/knowledge-graph`、`/search`、`/log-analysis` 和 `/admin/users`。

**问题**: 用户可直接输入 URL 访问 `/ram/draft/xxx` 绕过侧边栏禁用状态。

**整改方案**: 在 `beforeEach` 中增加 RAM 路由检查：
```typescript
if (to.path.startsWith('/ram') && !menuAvailability['ram']) {
  ElMessage.warning('请先在项目管理页面选择项目')
  return next('/project')
}
```

**验证**: 未选择项目时访问 `/ram` 被重定向到 `/project`。

---

### R-10 🟡 前后端会话状态命名不一致

**现状**:

| 后端 `SessionStatus` | 前端 `RamStatus` | 说明 |
|---|---|---|
| `RUNNING` | `running` | 一致 |
| `WAITING_CLARIFY` | `clarify` | 命名不同 |
| `WAITING_HITL` | `confirm` | 命名不同 |
| `PAUSED` | (无对应) | 前端缺失 |
| `DONE` | `completed` | 命名不同 |
| `FAILED` | `error` | 命名不同 |
| `ABORTED` | `aborted` | 一致 |

**问题**: 前端通过 SSE 事件类型字符串（`CLARIFY_REQUIRED`/`RUN_COMPLETED`）推导状态，而非消费后端枚举值，增加维护认知负担。

**整改方案**:
- 短期: 在 `types/ram.ts` 中添加注释标注前后端映射关系
- 长期: 后端 SSE 事件中携带 `sessionStatus` 字段，前端直接使用

**验证**: 无运行时影响，仅改善可维护性。

---

### R-11 🟡 SSE 澄清时断流设计

**现状**: 收到 `CLARIFY_REQ` 后后端 `emitter.complete()` 关闭 SSE，前端 `tearDown()` 关闭 EventSource。

**问题**: 用户长时间不回答澄清问题期间，无法接收后续事件（如其他节点完成）。

**整改方案**:
- 方案 A（推荐）: 澄清期间保持 SSE 连接但暂停事件推送，仅发送心跳
- 方案 B: 澄清期间降低轮询间隔至 5 秒，保持轻量连接

**验证**: 澄清弹窗打开期间，若后端有新事件产生，关闭弹窗后能立即看到。

---

### R-12 🟡 澄清弹窗自动弹出打断用户

**现状**: 收到 `CLARIFY_REQUIRED` 事件后，`ClarifyModal` 自动弹出。

**问题**: 用户可能正在阅读当前节点的输出，弹窗打断思考。

**整改方案**: 改为页面内通知条 + 手动打开弹窗：
- 顶部显示"需要澄清"通知条，点击展开澄清面板
- 30 秒无操作后自动展开

**验证**: 用户能按自己的节奏回答澄清问题。

---

### R-13 🟡 重跑流程引导不足

**现状**: `onRerunFromNode(key)` 和 `onRerunFromRound(roundNo)` 功能存在，但 UI 上缺乏引导。

**问题**: 用户不清楚从哪个节点重跑，也不清楚重跑会影响哪些下游节点。

**整改方案**:
- 重跑确认弹窗中展示影响范围："将重跑 impact → implement → verify → tech_plan 共 4 个节点"
- DAG 图中高亮将被清除的节点

**验证**: 用户在重跑前能清楚了解影响范围。

---

### R-14 🟡 项目选择流程复杂

**现状**: `InputPage` 支持 4 种项目选择方式：本地扫描、远端项目、手动输入路径、按分组选择。

**问题**: 新用户面对多种选择方式感到困惑，不清楚哪种方式适合自己的场景。

**整改方案**:
- 默认展示"推荐"方式（本地扫描 + 分组选择），折叠其他方式
- 增加引导文案："如果您已建过知识图谱，请选择对应的项目分组"

**验证**: 新用户能在 2 步内完成项目选择。

---

### R-16 🟢 菜单键命名不一致

**现状**: 侧边栏菜单使用 `ram` 作为 key，路由使用 `RamSessions`/`RamInput` 等命名。

**整改方案**: 统一为 `ram-*` 前缀命名风格。

**验证**: 菜单高亮与路由切换一致。

---

### R-08 🟢 API 响应解耦风格不一致

**现状**:
- RAM 模块: `const res = await api.get(...); return res.data;`（自行解包）
- 日志模块: axios 拦截器解包 `response.data`，组件直接用 `res`

**整改方案**: 统一为拦截器解包模式，所有 API 封装层不再自行访问 `.data`。

**验证**: 全模块 API 调用正常。

---

## 三、整改优先级排序（验证后修订）

> ✅ = 代码验证确认存在 | ⚠️ = 降级（风险低于预期） | ❌ = 不成立 | ℹ️ = 设计如此

### P0 — 立即修复（影响功能正确性/安全）
1. **L-01** ✅ ReportDetail.vue API 响应解包 Bug → 1 行修改 `res.data` → `res`
2. **L-05** ✅ Markdown XSS 风险 → 安装 DOMPurify + 封装消毒函数（13 处 v-html）

### P1 — 短期整改（1-2 周）
3. **L-04** ✅ 死代码清理 → 删除弹窗状态变量 10 个 + 模板 ~80 行 + 方法 3 个 + CSS ~110 行
4. **L-03** ✅ renderMarkdown 公共函数提取 → 新建 `utils/markdown.ts`（含 DOMPurify 消毒）
5. **L-06** ✅ 路由守卫死代码清理 → 删除 `/log-analysis` 守卫（4 行）
6. **R-04** ✅ RAM 路由增加导航守卫 → `beforeEach` 增加 `/ram` 检查
7. **L-07** ✅ RootCauseAnalysisService 死注入清理 → 删除字段和 import
8. **L-12** ✅ onMounted 死代码清理 → 删除 2 个无意义的 if 检查

### P2 — 中期整改（2-4 周）
9. **L-02** ✅ LogQuery.vue 巨型组件拆分 → 3 个 Tab 子组件 + 1 个 composable
10. **R-01** ✅ 会话列表过滤改为 sessionType 枚举 → 前后端联动（需 DB 迁移）
11. **R-02** ✅ SSE 线程池优化 → 增大线程池 + 可配置化轮询间隔
12. **L-10** ✅ DSL 查询界面简化 → 折叠高级模式，默认展示快速查询
13. **L-11** ✅ 分析进度反馈 → 激活 `checkReportStatus` + 轮询 + 进度条
14. **R-10** ✅ 前后端状态命名映射文档化 → 添加注释标注映射关系
15. **R-03** ⚠️ SSE 添加标准 id 字段 → `sendEvent()` 增加 `.id(String.valueOf(seq))`（低优先级，已有等效机制）

### P3 — 长期优化（1-2 月）
16. **R-08** ✅ DraftPage 事件处理提取 composable → 从 ~880 行缩减到 ~500 行
17. **R-09** ✅ RamController 拆分 → 3 个 Controller（Session/Status/Phase2）
18. **R-07** ✅ 现状分析统一为 SSE 模式 → 复用 useRamSession
19. **R-12** ✅ 澄清弹窗改为通知条 + 手动展开 → 解决首次打断问题
20. **R-13** ✅ 重跑流程增加确认弹窗 + 影响范围提示
21. **R-14** ✅ 项目选择流程简化 → 默认折叠高级选项
22. **L-08** ⚠️ 定时拉取间隔使用配置字段 → Scheduler 改造（非紧急）

### 已排除（验证不成立）
- ~~L-09~~ ❌ `renderMarkdown` 已正确处理 Map 类型，不存在 `[object Object]` 问题
- ~~R-11~~ ℹ️ SSE 澄清断流是有意设计，节省资源，恢复机制完善
- ~~R-15~~ ❌ API 响应解耦风格一致，两个模块都不访问 `.data`
- ~~R-16~~ ❌ menuKey 和 route name 属于不同命名空间，无需一致
- ~~R-05~~ ⚠️ sessionIdMap 有 DB 回退兜底，重启窗口期影响极低
- ~~R-06~~ ⚠️ abortedSessions 有 LRU 10k 上限 + DB 持久化兜底，无泄漏风险

---

## 四、架构优化建议

### 4.1 前端 API 层统一

```
建议创建统一的 API 响应拦截器规范:
api/
├── request.ts          — axios 实例 + 拦截器（统一解包 response.data）
├── logAnalysis.ts      — 所有方法直接返回 data，不再访问 .data
├── ram.ts              — 同上
└── ...
```

### 4.2 日志分析模块分层

```
当前: LogQuery.vue (2400行) = UI + 逻辑 + 状态

建议:
LogQuery.vue (壳) → useLogQuery (composable) → logAnalysisApi (API)
    ↓                       ↓
LogQueryTab.vue         DSL 构建逻辑
LogConfigTab.vue        配置管理逻辑
LogReportsTab.vue       报告管理逻辑
```

### 4.3 RAM 模块会话类型化

```
当前: sessionType 隐含在 intent 字符串中

建议:
AgentSession {
  sessionType: 'demand' | 'status' | 'phase2'  // 显式枚举
  intent: string                                 // 保留用于展示
}

前端: listRamSessions({ type: 'demand' })
后端: SELECT ... WHERE session_type = ?
```

### 4.4 实时通信统一

```
当前: RAM 需求分析 = SSE, RAM 现状分析 = 轮询, 日志分析 = 无实时

建议: 所有长时间运行任务统一走 SSE
- 需求分析: SSE (已有)
- 现状分析: SSE (改为)
- 日志分析: 轮询状态 API (保持，因为任务由后台调度器触发)
```

---

## 五、影响范围评估（验证后修订）

| 整改项 | 验证 | 涉及文件 | 风险 | 整改要点 |
|--------|------|----------|------|----------|
| L-01 | ✅ | 1 (ReportDetail.vue:123) | 极低 | `res.data` → `res`，1 行修改 |
| L-02 | ✅ | 6-8 | 中 | 拆分需回归测试，Tab 间通过 activeTab 协调 |
| L-03 | ✅ | 5+ (5个文件有重复实现) | 低 | 提取到 utils/markdown.ts，统一 DOMPurify |
| L-04 | ✅ | 1 (LogQuery.vue) | 低 | 删除 ~150 行死代码（状态+模板+方法+CSS） |
| L-05 | ✅ | 13 处 v-html | 低 | 新增 DOMPurify 依赖，封装消毒函数 |
| L-06 | ✅ | 1 (router/index.ts:252-255) | 极低 | 删除 4 行死代码守卫 |
| L-07 | ✅ | 1 (LogAnalysisExecutor.java:33) | 极低 | 删除 1 个字段 + import |
| L-08 | ⚠️ | 2 | 低 | 非紧急，可与 L-07 合并处理 |
| L-10 | ✅ | 1 (LogQuery.vue) | 低 | 折叠 DSL 构建器，展示快速查询入口 |
| L-11 | ✅ | 2-3 | 低 | 激活已有 getStatus API，增加轮询+进度条 |
| L-12 | ✅ | 1 (LogQuery.vue:1101-1108) | 极低 | 删除 6 行死代码 |
| R-01 | ✅ | 4-5 (前后端) | 中 | 需 DB 迁移增加 sessionType 字段 |
| R-02 | ✅ | 1 (RamController.java) | 中 | 线程池大小改为可配置，需压测验证 |
| R-03 | ⚠️ | 1 (RamController.java:480) | 低 | 1 行修改，已有等效机制兜底 |
| R-04 | ✅ | 1 (router/index.ts) | 极低 | 增加 3 行守卫检查 |
| R-07 | ✅ | 3-4 (StatusPage/Phase2Page) | 中 | 改用 SSE 需后端支持 |
| R-08 | ✅ | 2 (DraftPage.vue) | 中 | 提取 composable，逻辑与 UI 状态耦合 |
| R-09 | ✅ | 1 (RamController.java) | 低 | 纯重构，URL 路径不变 |
| R-10 | ✅ | 2 (前后端) | 低 | 添加注释/映射文档 |
| R-12 | ✅ | 2 (DraftPage/ClarifyModal) | 低 | 首次弹出改为通知条 |
| R-13 | ✅ | 1 (DraftPage.vue) | 低 | 增加 ElMessageBox.confirm |
| R-14 | ✅ | 1 (InputPage.vue) | 低 | 折叠高级选项 |

### 已排除项

| 整改项 | 验证 | 原因 |
|--------|------|------|
| L-09 | ❌ | renderMarkdown 已处理 Map，不存在 [object Object] |
| R-05 | ⚠️ | sessionIdMap 有 DB 回退兜底，重启影响极低 |
| R-06 | ⚠️ | abortedSessions 有 LRU 10k 上限，无泄漏 |
| R-11 | ℹ️ | 有意设计，节省 SSE 轮询资源 |
| R-15 | ❌ | API 风格一致，都不访问 .data |
| R-16 | ❌ | menuKey 和 route name 属不同命名空间 |
