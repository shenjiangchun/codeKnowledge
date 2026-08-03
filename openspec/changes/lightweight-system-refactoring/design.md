# 技术设计 — Phase 4 审计修复 + Phase 5 设计系统重建

> 路由: Standard | 风险: Medium | 深度: 紧凑（单模块、无 red-line、文件集限定）

---

## 1. 技术决策记录

### ADR-P4: 审计修复策略 — 单 commit 批量清理
**决策**: 12 项审计发现合并为一个 commit。理由：每一项都是孤立清理（删文件/删注释/删 YAML），互不依赖，无编译风险。

### ADR-P4-rev1: 预存测试修复策略（grill 修正）
**决策**: 
- `RemediationIntegrationTest` — **删除整个测试文件**。`/api/dialog/health` 端点已被 Phase 2 删除（service/intent/ 包清理），测试是孤儿代码。恢复端点会违反"不新增代码"原则。
- `ApiRegressionTest$AgentChatControllerContract` — **修复测试的 Accept header**。Agent 审计确认 Controller 逻辑正确，失败原因为 `@WebMvcTest` 切片上下文中 `produces = TEXT_EVENT_STREAM_VALUE` 导致内容协商失败。添加 `Accept: text/event-stream` header 即可修复，无需动生产代码。

### ADR-P5-1: 自定义侧边栏方案 — 纯 div + router-link 替换 el-menu
**决策**: 用纯 `<div>` + Vue Router `<router-link>` 自定义组件替换 `el-menu`。
- **证据**: `el-menu` 仅在 `AppSidebar.vue` 中被使用（Grep 确认），影响范围精确限定
- **保留**: `el-icon` 组件（轻量无状态，无副作用）
- **原因**: el-menu 的 `:deep()` 选择器只能覆盖表层样式，无法控制 `is-opened`、`focus-visible`、disabled hover 等内部状态
- **迁移项**: router 集成、active 检测、子菜单展开折叠、disabled 状态、分组标签、管理员动态菜单、图标渲染
- **替代方案评估**: ❌ 继续用 el-menu + :deep() 补救 → 只能部分修复，无法彻底解决 is-opened/focus-visible/disabled-hover

### ADR-P5-2: Element Plus 主题覆盖方式 — CSS 自定义属性
**决策**: 创建 `styles/element-overrides.css`，通过覆盖 Element Plus CSS 变量实现全局主题。
- Element Plus 2.x 支持 `--el-color-primary`、`--el-border-radius-base` 等 CSS 变量
- 不需要 SCSS/Less 预编译器或构建配置变更
- 在 `main.ts` 中 Element Plus CSS 之后导入，保证优先级

### ADR-P5-3: AppHeader 改造 — Clean Light 白色顶栏 + UserDropdown 修复
**决策**: 从 `#409eff` 蓝色切换为白色背景 `#ffffff` + `border-bottom: 1px solid var(--color-border)`，标题色 `var(--color-text-primary)`。
- **UserDropdown 修复**: `.user-info { color: #fff }` → `color: var(--color-text-primary)`，登录按钮保留 `type="primary"`（白色背景上蓝色按钮正常可见）
- 理由：与 Clean Light 侧边栏白色统一，减少色彩噪音
- 审计确认: 41 个文件硬编码 `#409eff` 不受此变更影响（scoped 样式 + 独立配色方案），仅影响 Element Plus 组件内部 CSS 变量

### ADR-P5-4: 设计范围限定 — 仅布局壳（grill 修正）
**决策**: Phase 5 仅覆盖布局壳文件（AppSidebar / AppHeader / AppLayout / global.css / element-overrides.css / main.ts），**不动任何页面组件**。
- **理由**: 41 个文件硬编码 `#409eff`，逐一修改范围过大且与"轻量化重构"目标无关。Element Plus 全局覆盖自动适配 el-button/el-table/el-card 内置颜色，页面级 scoped 样式保持现状。
- **被排除**: project/ProjectList.vue、knowledge-graph/*、ram/*、call-chain/*、claude-terminal/*、skill-market/*、log-analysis/* 等 49 个视图文件
- **后续**: 若有需要，在独立 change 中渐进迁移

### ADR-P5-5: 侧边栏 groupLabel 数据模型 + Transition 动画（审计修正）
**决策**: 
- **MenuItem 接口**新增 `groupLabel?: string` 字段。`baseMenuItems` 的第一个元素前插入 `{ index: '', title: '', icon: Folder, menuKey, groupLabel: '分析工具' }` 占位项，为 `分析工具` / `AI Agent` / `工具 & 市场` 三个分组添加标签
- **子菜单动画**: 使用 Vue `<Transition name="slide">` 包裹子导航，不追求 el-menu 原有动画的完全复刻
- **验证**: Task 5.3 完成后，追加 `npm run dev` 手动验证步骤：切 10 个页面、展开折叠子菜单、管理员登录验证、键盘 Tab/Enter 导航

---

## 2. 文件影响地图

```
Phase 4 — 审计修复（仅删/仅改）
  hisi-dev-tool-frontend/src/
    ❌ types/search.ts                          (107行孤儿死代码)
  hisi-dev-tool/src/main/resources/
    ✏️ application.yml:130-135                  (删除 siliconflow/iflytek 残留配置块)
  hisi-dev-tool/src/main/java/.../
    ✏️ service/UnifiedEmbeddingService.java:28   (stale Javadoc)
    ✏️ config/ProxyConfig.java:28                (stale Javadoc)
    ✏️ config/EmbeddingModelConfig.java:9        (stale Javadoc)

Phase 5 — 设计系统重建
  hisi-dev-tool-frontend/src/
    ✏️ styles/global.css                        (CSS 变量重写 → WCAG AA 达标)
    ➕ styles/element-overrides.css              (Element Plus 全局主题覆盖)
    ✏️ components/layout/AppSidebar.vue          (重建为自定义组件)
    ✏️ components/layout/AppHeader.vue           (Clean Light 白色顶栏)
    ✏️ main.ts                                  (导入 element-overrides.css)
  hisi-dev-tool-frontend/
    ❌ prototype/                                (删除 3 个 demo HTML)
```

---

## 3. 自定义侧边栏组件设计

### Props / 数据流

```
AppSidebar (组件)
├── route: RouteLocationNormalized   ← useRoute()
├── menuItems: MenuItem[]            ← baseMenuItems + admin 动态项
├── defaultOpeneds: string[]         ← computed from route
│
├── NavGroup (无组件, v-for + label)
│   └── NavItem (无组件, router-link)
│       ├── icon: Component          ← Element Plus el-icon 保留
│       ├── title: string
│       ├── active: boolean          ← route.path startsWith
│       ├── disabled: boolean
│       ├── badge?: string
│       └── children?: {index, title}[]
│           └── SubNavItem (router-link)
```

### 状态逻辑（全部保留现有逻辑）

| 现有逻辑 | 迁移方式 |
|----------|---------|
| `useRoute()` active 检测 | `route.path.startsWith(item.index)` |
| `defaultOpeneds` computed | 同级 state `openedSubmenus: Set<string>` |
| 管理员动态菜单 (splice) | 已有 `menuItems` computed → 直接复用 |
| `router` 导航 | `<router-link :to="item.index">` 或 `@click="router.push"` |
| 图标渲染 | `<el-icon><component :is="item.icon"/></el-icon>` — 保留 |
| disabled 状态 | `pointer-events: none` + `opacity: 0.4` |
| 子菜单展开/折叠 | v-show / v-if 绑定 `openedSubmenus.has(item.index)` |

### 子菜单展开逻辑

```typescript
// 初始状态：路由匹配的子菜单默认展开
const openedSubmenus = reactive(new Set<string>(
  menuItems.value
    .filter(item =>
      item.children?.some(child => route.path.startsWith(child.index))
    )
    .map(item => item.index)
))

function toggleSubmenu(index: string) {
  if (openedSubmenus.has(index)) {
    openedSubmenus.delete(index)
  } else {
    openedSubmenus.add(index)
  }
}
```

### 键盘导航

```css
.nav-item:focus-visible {
  outline: 2px solid var(--color-accent);
  outline-offset: -2px;
}
```

---

## 4. CSS 设计 Token（完整变量体系）

```css
:root {
  /* 主色调 */
  --color-accent: #2563eb;
  --color-accent-hover: #1d4ed8;
  --color-accent-light: #eff6ff;

  /* 背景 */
  --color-bg: #f8fafb;
  --color-surface: #ffffff;
  --color-hover: #f3f4f6;

  /* 文字 — WCAG AA 全达标 */
  --color-text-primary: #111827;   /* 17.74:1 on #fff */
  --color-text-secondary: #4b5563; /* 7.86:1 on #fff  (原 #6b7280=4.83:1 保留可选) */
  --color-text-muted: #6b7280;     /* 4.83:1 on #fff  (原 #9ca3af=2.54:1 → 已修复) */

  /* 边框 */
  --color-border: #e5e7eb;

  /* 圆角 */
  --radius-sm: 6px;
  --radius-md: 8px;
  --radius-lg: 10px;
}
```

**WCAG AA 修复说明**: `--color-text-muted` 从 `#9ca3af`(2.54:1) 改为 `#6b7280`(4.83:1)。`--color-text-secondary` 从 `#6b7280`(4.83:1) 改为 `#4b5563`(7.86:1) 以区分层次。

---

## 5. Element Plus 全局覆盖

`styles/element-overrides.css` 将覆盖以下 Element Plus CSS 变量：

```css
:root {
  --el-color-primary: #2563eb;
  --el-color-primary-light-3: #60a5fa;
  --el-color-primary-light-5: #93bbfd;
  --el-color-primary-light-7: #bfdbfe;
  --el-color-primary-light-8: #dbeafe;
  --el-color-primary-light-9: #eff6ff;
  --el-border-radius-base: 6px;
  --el-border-radius-small: 4px;
  --el-bg-color: #ffffff;
  --el-bg-color-page: #f8fafb;
  --el-text-color-primary: #111827;
  --el-text-color-regular: #4b5563;
  --el-text-color-secondary: #6b7280;
  --el-text-color-placeholder: #9ca3af;
  --el-border-color: #e5e7eb;
  --el-border-color-light: #f3f4f6;
  --el-fill-color-light: #f3f4f6;
  --el-box-shadow-light: 0 1px 3px rgba(0,0,0,0.06);
}
```

---

## 6. 验证矩阵

| 需求 | 验证层 | 命令/检查点 | 成功标准 |
|------|--------|------------|---------|
| REQ-LR-001 前端死代码 | TypeScript 编译 | `npm run build` | 零 TS 错误 |
| REQ-LR-003 后端死代码 | Maven 测试 | `mvn -f hisi-dev-tool/pom.xml test` | BUILD SUCCESS |
| REQ-LR-005 侧边栏导航 | 自定义组件 | 路由切 10 个页面, 展开/折叠子菜单 | 全部可点击, 无 404 |
| WCAG AA 对比度 | 色彩审计 | `agent-browser snapshot` + 目视 | 所有文字满足 4.5:1 |
| Element Plus 全局主题 | 所有页面组件 | `npm run dev` 后访问核心页面 | el-button/el-table/el-card 颜色匹配 |
| 预存测试修复 | JUnit | `mvn test -Dtest=RemediationIntegrationTest,ApiRegressionTest` | 2 个测试类全部通过 |
