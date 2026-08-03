# 实施任务 — Phase 4 审计修复 + Phase 5 设计系统重建

---

## Phase 4: 审计修复（12 项 + 2 预存测试）

### Task 4.1: 批量清理审计发现

**依赖**: 无 | **需求**: REQ-LR-001, REQ-LR-003

**文件**:
- `hisi-dev-tool-frontend/src/types/search.ts` — 删除（107 行孤儿死代码）
- `hisi-dev-tool/src/main/resources/application.yml:130-135` — 删除 siliconflow/iflytek 残留配置块
- `hisi-dev-tool/src/main/java/com/huawei/hisi/service/UnifiedEmbeddingService.java:28` — 清理 stale Javadoc
- `hisi-dev-tool/src/main/java/com/huawei/hisi/config/ProxyConfig.java:28` — 清理 stale Javadoc
- `hisi-dev-tool/src/main/java/com/huawei/hisi/config/EmbeddingModelConfig.java:9` — 清理 stale Javadoc

**禁止**: 不碰 application.yml 其他配置块；不改 stale Javadoc 以外的代码逻辑

**第 1 步 — 删除 types/search.ts**:
```bash
rm "hisi-dev-tool-frontend/src/types/search.ts"
```

**第 2 步 — 清理 application.yml 残留配置**:
```bash
# 删除 lines 130-135: siliconflow/iflytek 配置块
```
编辑 `hisi-dev-tool/src/main/resources/application.yml`，删除 zhipu/siliconflow/iflytek 三个 enabled:false 旧配置块（保留前一行注释 "旧版平台配置" 也一并清理）

**第 3 步 — 清理 stale Javadoc**:
- `UnifiedEmbeddingService.java:28`: 删除 "取代原来的 SiliconFlowEmbeddingService / IFlytekEmbeddingService" 注释行
- `ProxyConfig.java:28`: 删除 "ZhipuService、SiliconFlowEmbeddingService 等" 注释中的 SiliconFlowEmbeddingService
- `EmbeddingModelConfig.java:9`: 删除 "ZhipuConfig/SiliconFlowConfig/IFlytekConfig" 注释中的 SiliconFlowConfig/IFlytekConfig

**第 4 步 — 验证前端 build**:
```bash
npm --prefix hisi-dev-tool-frontend run build 2>&1 | tail -3
```
预期: `✓ built in`

**第 5 步 — 验证后端编译**:
```bash
mvn -f hisi-dev-tool/pom.xml compile --batch-mode 2>&1 | tail -5
```
预期: `BUILD SUCCESS`

---

### Task 4.2: 处理 2 个预存测试失败

**依赖**: Task 4.1 | **需求**: REQ-LR-003

**背景**: Agent 审计确认两个失败的根本原因：
- `RemediationIntegrationTest`: 调用 `/api/dialog/health` → 该端点已被 Phase 2 删除（service/intent/ 包清理），测试是孤儿代码
- `ApiRegressionTest$AgentChatControllerContract`: Controller 逻辑正确，失败原因是 `@WebMvcTest` 切片上下文中 `produces = TEXT_EVENT_STREAM_VALUE` 导致 Spring MVC 内容协商失败（无 Accept header 不匹配 SSE produces），加 `Accept: text/event-stream` 即可修复

**第 1 步 — 删除 RemediationIntegrationTest**:
```bash
rm hisi-dev-tool/src/test/java/com/huawei/hisi/config/RemediationIntegrationTest.java
```

**第 2 步 — 修复 ApiRegressionTest Accept header**:
编辑 `hisi-dev-tool/src/test/java/com/huawei/hisi/controller/ApiRegressionTest.java`，在 3 个失败的测试请求中添加 `.accept(MediaType.TEXT_EVENT_STREAM)`：
```java
// unknownAgentType_returns404 (line ~78)
mockMvc.perform(post("/api/chat/UNKNOWN")
    .accept(MediaType.TEXT_EVENT_STREAM)
    .contentType(MediaType.APPLICATION_JSON)
    .content("{\"message\":\"test\"}"))
    .andExpect(status().isNotFound());

// nullMessage_returns400 (line ~102) 
mockMvc.perform(post("/api/chat/apm-diagnose")
    .accept(MediaType.TEXT_EVENT_STREAM)
    .contentType(MediaType.APPLICATION_JSON)
    .content("{\"message\":null}"))
    .andExpect(status().isBadRequest());

// blankMessage_returns400 (line ~90)
mockMvc.perform(post("/api/chat/apm-diagnose")
    .accept(MediaType.TEXT_EVENT_STREAM)
    .contentType(MediaType.APPLICATION_JSON)
    .content("{\"message\":\"   \"}"))
    .andExpect(status().isBadRequest());
```

**禁止**: 不修改 `AgentChatController.java` 生产代码

**第 3 步 — 验证修复**:
```bash
mvn -f hisi-dev-tool/pom.xml test -Dtest=ApiRegressionTest --batch-mode 2>&1 | grep "Tests run"
```
预期: Failures: 0, Errors: 0

**第 4 步 — 全量回归**:
```bash
mvn -f hisi-dev-tool/pom.xml test --batch-mode 2>&1 | grep -E "Tests run:|BUILD" | tail -5
```
预期: BUILD SUCCESS（预存失败修复后零失败）

---

### Task 4.3: 提交 Phase 4

```bash
git add -A && git commit -m "fix: Phase4 audit cleanup + test repairs" -m "Delete types/search.ts, stale YAML config, stale Javadoc, orphan test. Fix ApiRegressionTest Accept header for SSE content negotiation."
```

---

## Phase 5: 设计系统重建

### Task 5.1: CSS 变量重写 + WCAG 修复

**依赖**: Phase 4 | **需求**: REQ-LR-005

**文件**:
- `hisi-dev-tool-frontend/src/styles/global.css` — 重写
- `hisi-dev-tool-frontend/src/components/layout/AppSidebar.vue` — 引用变量
- `hisi-dev-tool-frontend/src/components/layout/AppLayout.vue` — 引用变量

**第 1 步 — 重写 global.css**:
将当前 17 行替换为完整 CSS 设计 token + WCAG AA 达标配色：

```css
/* 全局样式 — Clean Light Design System */
* { margin: 0; padding: 0; box-sizing: border-box; }

:root {
  --color-accent: #2563eb;
  --color-accent-hover: #1d4ed8;
  --color-accent-light: #eff6ff;
  --color-bg: #f8fafb;
  --color-surface: #ffffff;
  --color-hover: #f3f4f6;
  --color-text-primary: #111827;
  --color-text-secondary: #4b5563;
  --color-text-muted: #6b7280;
  --color-border: #e5e7eb;
  --radius-sm: 6px;
  --radius-md: 8px;
}

html, body, #app {
  width: 100%; height: 100%;
  font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', 'Noto Sans SC', 'Helvetica Neue', Arial, sans-serif;
  -webkit-font-smoothing: antialiased;
  color: var(--color-text-primary);
}

::-webkit-scrollbar { width: 6px; height: 6px; }
::-webkit-scrollbar-track { background: transparent; }
::-webkit-scrollbar-thumb { background: #d1d5db; border-radius: 3px; }
::-webkit-scrollbar-thumb:hover { background: #9ca3af; }
```

**第 2 步 — AppSidebar.vue 改为引用 var()**:
将所有硬编码 hex 值替换为 `var(--color-*)`。例如：
- `background-color: #ffffff` → `background-color: var(--color-surface)`
- `color: #6b7280` → `color: var(--color-text-muted)`
- `color: #111827` → `color: var(--color-text-primary)`
- `background-color: #f3f4f6` → `background-color: var(--color-hover)`
- `color: #2563eb` → `color: var(--color-accent)`
- `background-color: #eff6ff` → `background-color: var(--color-accent-light)`
- `border: 1px solid #e5e7eb` → `border: 1px solid var(--color-border)`

**第 3 步 — AppLayout.vue 背景改为 var()**:
- `background-color: #f8fafb` → `background-color: var(--color-bg)`

**验证**:
```bash
npm --prefix hisi-dev-tool-frontend run build 2>&1 | tail -3
```
预期: `✓ built in`

---

### Task 5.2: 创建 Element Plus 全局主题覆盖

**依赖**: Task 5.1 | **需求**: REQ-LR-005

**文件**:
- `hisi-dev-tool-frontend/src/styles/element-overrides.css` — 新建
- `hisi-dev-tool-frontend/src/main.ts` — 导入

**第 1 步 — 创建 styles/element-overrides.css**:
```css
/* Element Plus 全局主题覆盖 — Clean Light */
:root {
  --el-color-primary: #2563eb;
  --el-color-primary-light-3: #60a5fa;
  --el-color-primary-light-5: #93bbfd;
  --el-color-primary-light-7: #bfdbfe;
  --el-color-primary-light-8: #dbeafe;
  --el-color-primary-light-9: #eff6ff;
  --el-color-primary-dark-2: #1d4ed8;
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

**第 2 步 — main.ts 导入顺序**:
```typescript
import 'element-plus/dist/index.css'
import '@/styles/element-overrides.css'   // ← 在 Element Plus CSS 之后
import '@/styles/global.css'
```

**验证**:
```bash
npm --prefix hisi-dev-tool-frontend run build 2>&1 | tail -3
```
预期: `✓ built in`

---

### Task 5.3: 重建自定义侧边栏（替换 el-menu）

**依赖**: Task 5.2 | **需求**: REQ-LR-005

**文件**:
- `hisi-dev-tool-frontend/src/components/layout/AppSidebar.vue` — 完全重写

**Design contract（从 design.md 提取）**:

必须保留的功能:
- `useRoute()` active 检测: `route.path.startsWith(item.index)`
- `menuItems` computed: 12 基础项 + admin 用户管理
- `defaultOpeneds` → `openedSubmenus: Set<string>`（reactive）
- 子菜单展开/折叠: `toggleSubmenu(index)`
- 图标: `<el-icon><component :is="item.icon"/></el-icon>` 保留
- Router 导航: `<router-link :to="item.index">`
- 分组标签: `nav-group-label`
- Badge: 数字角标
- Admin 用户管理: splice 插入逻辑不变

新的需求（替换 el-menu 后解锁）:
- `:focus-visible` outline
- `is-opened` 状态可视化
- disabled 项无 hover 反馈 + `pointer-events: none`
- 无需 `:nth-child()` 分组间距（用 margin-top on first-of-group）

删除的旧依赖:
- 所有 `:deep(.el-menu-item)` / `:deep(.el-sub-menu)` / `:deep(.el-menu)` 选择器
- `el-aside` → 普通 `aside`
- `el-menu` / `el-sub-menu` / `el-menu-item` 组件

**第 1 步 — 更新 MenuItem 接口 + groupLabel**:
在 `<script setup>` 中将 `MenuItem` 接口扩展：

```typescript
interface MenuItem {
  index: string
  title: string
  icon: Component
  menuKey: MenuKey
  groupLabel?: string               // ← 新增: 分组标签（仅第一个分组项设置）
  children?: { index: string; title: string }[]
}
```

在 `baseMenuItems` 中为三个分组的首项添加 `groupLabel`:
```typescript
const baseMenuItems: MenuItem[] = [
  { index: '/project', title: '项目管理', icon: Folder, menuKey: 'project-management', groupLabel: '分析工具' },
  // ... knowledge-graph, log-analysis, apm-debug（不加 groupLabel）
  { index: '/ram', title: '需求分析大师', icon: MagicStick, menuKey: 'ram-demand', groupLabel: 'AI Agent' },
  // ... ram-chat, fix-chat, merge-analysis（不加 groupLabel）
  { index: '/claude-terminal', title: 'Claude 终端', icon: Monitor, menuKey: 'claude-terminal', groupLabel: '工具 & 市场' },
  // ... skill-market, kg-skills-kit（不加 groupLabel）
  { index: '/settings', title: '系统设置', icon: Setting, menuKey: 'settings' },
]
```

**第 2 步 — 重写 <script setup>**:
保留所有现有逻辑，仅将 `defaultOpeneds` computed 改为 `openedSubmenus` reactive Set：

```typescript
const route = useRoute()
const openedSubmenus = reactive(new Set<string>())

// 初始化: 路由匹配的子菜单默认展开
watch(() => route.path, () => {
  menuItems.value.forEach(item => {
    if (item.children?.some(c => route.path.startsWith(c.index))) {
      openedSubmenus.add(item.index)
    }
  })
}, { immediate: true })

function toggleSubmenu(index: string) {
  openedSubmenus.has(index) ? openedSubmenus.delete(index) : openedSubmenus.add(index)
}
```

**第 2 步 — 重写 <template>**:
用纯 div 结构替换 el-menu/el-sub-menu/el-menu-item:

```html
<aside class="app-sidebar">
  <div class="sidebar-brand">HiSi DevTool</div>
  <nav class="sidebar-nav">
    <template v-for="item in menuItems" :key="item.index">
      <!-- 分组标签 -->
      <div v-if="item.groupLabel" class="nav-group-label">{{ item.groupLabel }}</div>

      <!-- 有子菜单 -->
      <template v-if="item.children && !item.disabled">
        <div class="nav-item" :class="{ opened: openedSubmenus.has(item.index) }"
             @click="toggleSubmenu(item.index)" tabindex="0"
             @keydown.enter="toggleSubmenu(item.index)">
          <el-icon><component :is="item.icon"/></el-icon>
          <span>{{ item.title }}</span>
          <span class="nav-chevron" :class="{ rotated: openedSubmenus.has(item.index) }">▾</span>
        </div>
        <div v-show="openedSubmenus.has(item.index)" class="sub-nav">
          <router-link v-for="child in item.children" :key="child.index"
            :to="child.index" class="nav-item sub-item"
            :class="{ active: route.path === child.index }">
            {{ child.title }}
          </router-link>
        </div>
      </template>

      <!-- 无子菜单 -->
      <router-link v-else-if="!item.children" :to="item.index"
        class="nav-item" :class="{
          active: route.path.startsWith(item.index),
          disabled: item.disabled
        }" :tabindex="item.disabled ? -1 : 0">
        <el-icon><component :is="item.icon"/></el-icon>
        <span>{{ item.title }}</span>
        <span v-if="item.badge" class="nav-badge">{{ item.badge }}</span>
      </router-link>
    </template>
  </nav>
  <div class="sidebar-footer">
    <router-link to="/settings" class="footer-item">⚙ 系统设置</router-link>
  </div>
</aside>
```

**第 3 步 — 重写 <style scoped>**:
完全基于 CSS 变量，消除所有 :deep() 和 :nth-child():

```css
.app-sidebar {
  width: 220px; background: var(--color-surface);
  border-right: 1px solid var(--color-border);
  display: flex; flex-direction: column; flex-shrink: 0; overflow: hidden;
}
.sidebar-brand {
  height: 48px; display: flex; align-items: center; padding: 0 18px;
  font-size: 15px; font-weight: 700; color: var(--color-text-primary);
  border-bottom: 1px solid var(--color-border);
}
.sidebar-nav { flex: 1; overflow-y: auto; padding: 8px 10px; }
.nav-group-label {
  padding: 14px 10px 6px; font-size: 10px; font-weight: 700;
  text-transform: uppercase; letter-spacing: 1px; color: var(--color-text-muted);
}
.nav-item {
  display: flex; align-items: center; gap: 10px; padding: 9px 12px;
  border-radius: var(--radius-md); font-size: 13.5px; color: var(--color-text-secondary);
  cursor: pointer; transition: all 0.12s; text-decoration: none; font-weight: 450;
}
.nav-item:hover:not(.disabled) { background: var(--color-hover); color: var(--color-text-primary); }
.nav-item.active { background: var(--color-accent-light); color: var(--color-accent); font-weight: 550; }
.nav-item.disabled { opacity: 0.4; pointer-events: none; cursor: not-allowed; }
.nav-item:focus-visible { outline: 2px solid var(--color-accent); outline-offset: -2px; }
.nav-item .el-icon { color: inherit; }
.nav-chevron { margin-left: auto; font-size: 12px; transition: transform 0.15s; }
.nav-chevron.rotated { transform: rotate(180deg); }
.sub-nav { padding-left: 20px; }
.sub-item { font-size: 13px; padding-left: 14px; margin: 2px 0; }
.nav-badge {
  margin-left: auto; font-size: 10px; padding: 1px 7px;
  border-radius: 10px; background: var(--color-accent-light); color: var(--color-accent);
  font-weight: 600;
}
.sidebar-footer { border-top: 1px solid var(--color-border); padding: 8px 10px; }
.footer-item {
  display: flex; align-items: center; gap: 8px; padding: 8px 12px;
  border-radius: var(--radius-md); font-size: 12.5px; color: var(--color-text-muted);
  text-decoration: none; cursor: pointer;
}
.footer-item:hover { background: var(--color-hover); color: var(--color-text-secondary); }
```

**验证 — 第 1 轮：编译**:
```bash
npm --prefix hisi-dev-tool-frontend run build 2>&1 | tail -5
```
预期: `✓ built in`, 零 TS 错误

**验证 — 第 2 轮：运行时手动验证（必须执行）**:
```bash
cd hisi-dev-tool-frontend && npm run dev &
```
打开 http://localhost:5173 并验证：
1. 侧边栏 12 个菜单项全部可见
2. 点击 `知识图谱` → 子菜单展开（chevron 旋转）, 子项 `图谱总览`/`提示词配置`/`术语管理` 可见
3. 点击 `项目管理` → 路由导航到 /project, 菜单项高亮
4. 刷新页面 → active 状态保持
5. 分组标签 `分析工具` / `AI Agent` / `工具 & 市场` 显示在对应组顶部
6. 管理员登录后 → `用户管理` 菜单项出现在 `系统设置` 之前
7. Tab 键 → :focus-visible outline 正常工作
8. 缩小窗口 → 侧边栏不溢出
9. 控制台无 `[Vue warn]` 或路由警告
10. disabled 菜单项不可点击, 无 hover 反馈

---

### Task 5.4: AppHeader Clean Light 适配 + UserDropdown 修复

**依赖**: Task 5.3 | **需求**: REQ-LR-005

**文件**:
- `hisi-dev-tool-frontend/src/components/layout/AppHeader.vue` — 白色背景 + 深色文字
- `hisi-dev-tool-frontend/src/components/auth/UserDropdown.vue` — `.user-info` 文字色修复

**第 1 步 — AppHeader 样式变更**:
```css
.app-header {
  background-color: var(--color-surface);
  border-bottom: 1px solid var(--color-border);
  color: var(--color-text-primary);
  padding: 0 20px;
  height: 48px;
  display: flex;
  align-items: center;
}
.app-title {
  font-size: 15px;
  font-weight: 700;
  margin: 0;
  color: var(--color-text-primary);
}
```

**第 2 步 — UserDropdown 文字色修复**:
编辑 `hisi-dev-tool-frontend/src/components/auth/UserDropdown.vue`：
- `.user-info { color: #fff }` → `.user-info { color: var(--color-text-primary) }`
- 登录按钮 `type="primary" plain` 在白色背景上保持蓝色可见（无需改）

**验证 — 编译**:
```bash
npm --prefix hisi-dev-tool-frontend run build 2>&1 | tail -3
```
预期: `✓ built in`

**验证 — 运行时**:
- Header 背景白色, 标题 "HiSi DevTool" 深色可见
- UserDropdown 用户名文字深色可见
- 登录按钮蓝色, 与白色背景对比清晰

---

### Task 5.5: 清理 prototypes + 最终验证

**依赖**: Task 5.4 | **需求**: REQ-LR-001

**第 1 步 — 删除 prototype/ 目录**:
```bash
rm -rf hisi-dev-tool-frontend/prototype/
```

**第 2 步 — 全量前端 build**:
```bash
npm --prefix hisi-dev-tool-frontend run build 2>&1 | grep -E "✓ built|error"
```
预期: `✓ built in`

**第 3 步 — 全量后端测试**:
```bash
mvn -f hisi-dev-tool/pom.xml test --batch-mode 2>&1 | grep -E "Tests run:|BUILD" | tail -5
```
预期: BUILD SUCCESS

---

### Task 5.6: 提交 Phase 5

```bash
git add -A && git commit -m "refactor(frontend): Phase5 Clean Light design system" -m "CSS variables replace hardcoded hex; Element Plus global theme override; custom sidebar replacing el-menu; WCAG AA compliance; AppHeader Clean Light adaptation."
```
