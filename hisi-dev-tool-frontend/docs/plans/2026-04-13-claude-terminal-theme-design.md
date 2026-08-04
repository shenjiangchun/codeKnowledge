# Claude Terminal 主题系统与体验优化设计

## 一、概述

### 目标
1. 为 Claude Terminal 实现多主题切换功能，支持用户自定义配色
2. 优化用户体验，添加实用功能增强

### 设计原则
- 主题设置存储在 localStorage，浏览器刷新后自动恢复
- 借鉴 codeai 项目的主题架构和 Dashboard 视觉元素
- 复用项目已有的 Git API 实现状态显示

---

## 二、主题系统设计

### 2.1 预设主题列表

| 主题 ID | 名称 | 背景色 | 主色调 | 适用场景 |
|--------|------|--------|--------|---------|
| `dark-tech` | 深色科技 | #1a1a1a | #409eff (蓝) | 开发者首选 |
| `dark-monokai` | Monokai 经典 | #1e1e1e | #f92672 (粉红) | 终端爱好者 |
| `dark-dracula` | Dracula | #282a36 | #bd93f9 (紫) | 夜间编码 |
| `light-minimal` | 浅色简约 | #f5f5f5 | #409eff (蓝) | 日间使用 |
| `light-sepia` | 护眼暖色 | #f8f4e8 | #d48806 (橙) | 长时间阅读 |
| `eye-care` | 护眼绿色 | #e8f5e9 | #67c23a (绿) | 护眼模式 |

### 2.2 主题变量结构

```typescript
interface TerminalTheme {
  id: string
  name: string
  isDark: boolean

  // 背景层级
  backgroundLevel1: string  // 页面背景
  backgroundLevel2: string  // 卡片/面板背景
  backgroundLevel3: string  // 子区域背景
  backgroundLevel4: string  // 边框/分隔线

  // 文字颜色
  textPrimary: string       // 主要文字
  textSecondary: string     // 次级文字
  textMuted: string         // 描述文字

  // 强调色
  accentPrimary: string     // 主色（按钮、选中）
  accentSuccess: string     // 成功状态
  accentWarning: string     // 警告状态
  accentDanger: string      // 错误状态

  // xterm.js 终端主题
  terminal: {
    foreground: string
    background: string
    cursor: string
    cursorAccent: string
    selectionBackground: string
    black: string
    red: string
    green: string
    yellow: string
    blue: string
    magenta: string
    cyan: string
    white: string
    brightBlack: string
    brightRed: string
    brightGreen: string
    brightYellow: string
    brightBlue: string
    brightMagenta: string
    brightCyan: string
    brightWhite: string
  }
}
```

### 2.3 存储方案

- **localStorage 键名**: `claude-terminal-theme`
- **存储内容**: `{ themeId: string, customAccent?: string }`
- **加载时机**: 组件 mounted 时读取并应用

---

## 三、功能增强设计

### 3.1 Git 状态显示（复用现有 API）

**接口复用**: `gitApi.getStatus(workingDirectory)`

**显示内容**:
- 当前分支名称
- 未提交修改数 (modified + untracked)
- 工作区状态指示 (干净/有修改)

**组件位置**: TerminalSidebar 的会话信息区

**实现方案**:
```typescript
// 在 TerminalSidebar.vue 中添加
import { gitApi } from '@/api/git'

const gitStatus = ref<{
  branch: string
  clean: boolean
  modifiedCount: number
} | null>(null)

async function loadGitStatus() {
  if (!workingDirectory.value) return
  try {
    const status = await gitApi.getStatus(workingDirectory.value)
    gitStatus.value = {
      branch: status.branch,
      clean: status.clean,
      modifiedCount: (status.modified?.length || 0) + (status.untracked?.length || 0)
    }
  } catch {
    gitStatus.value = null
  }
}
```

### 3.2 主题选择器组件

**组件位置**: TerminalSidebar 底部新增 ThemeSelector 区块

**功能**:
- 预设主题列表（带预览色块）
- 自定义主色调拾取器（ColorPicker）
- 保存/重置按钮

### 3.3 键盘快捷键

| 快捷键 | 功能 |
|--------|------|
| `Ctrl+N` | 新建会话 |
| `Ctrl+L` | 清屏 |
| `Ctrl+R` | 重连终端 |
| `Ctrl+F` | 搜索会话列表 |
| `Escape` | 关闭设置面板 |

---

## 四、文件变更清单

### 4.1 新建文件

| 文件路径 | 说明 |
|---------|------|
| `src/stores/themeStore.ts` | 主题状态管理 (Pinia) |
| `src/themes/presets.ts` | 6种预设主题配置 |
| `src/themes/types.ts` | 主题类型定义 |
| `src/views/claude-terminal/components/ThemeSelector.vue` | 主题选择器组件 |

### 4.2 修改文件

| 文件路径 | 改动内容 |
|---------|---------|
| `ClaudeTerminal.vue` | 应用主题 CSS 变量，传递主题到 xterm.js |
| `SessionList.vue` | 替换硬编码颜色为 CSS 变量 |
| `TerminalSidebar.vue` | 替换颜色 + 添加 Git 状态 + 主题选择器 |
| `src/types/terminal.ts` | 添加主题相关类型 |

---

## 五、CSS 变量映射

主题通过 CSS 变量注入，组件使用变量而非硬编码颜色：

```css
/* 主题变量定义 */
:root {
  --ct-bg-level-1: #1a1a1a;
  --ct-bg-level-2: #1e1e1e;
  --ct-bg-level-3: #252526;
  --ct-bg-level-4: #404040;

  --ct-text-primary: #e0e0e0;
  --ct-text-secondary: #909399;
  --ct-text-muted: #666666;

  --ct-accent-primary: #409eff;
  --ct-accent-success: #67c23a;
  --ct-accent-warning: #e6a23c;
  --ct-accent-danger: #f56c6c;
}

/* 组件使用变量 */
.session-list-panel {
  background-color: var(--ct-bg-level-2);
  color: var(--ct-text-primary);
}

.session-item.active {
  background-color: var(--ct-accent-primary);
}
```

---

## 六、实现步骤

### Phase 1: 主题基础设施
1. 创建 `themes/types.ts` 和 `themes/presets.ts`
2. 创建 `stores/themeStore.ts`
3. 实现 CSS 变量动态注入函数

### Phase 2: 组件改造
1. 改造 `ClaudeTerminal.vue` 使用 CSS 变量
2. 改造 `SessionList.vue` 使用 CSS 变量
3. 改造 `TerminalSidebar.vue` 使用 CSS 变量
4. 实现 xterm.js 主题同步

### Phase 3: 功能增强
1. 创建 `ThemeSelector.vue` 组件
2. 添加 Git 状态显示功能
3. 实现键盘快捷键
4. 添加 localStorage 持久化

### Phase 4: 测试验证
1. 验证主题切换效果
2. 验证 localStorage 恢复
3. 验证 Git 状态显示
4. 验证快捷键功能

---

## 七、参考资源

- codeai 主题系统: `@vueuse/core` 的 `useDark/useToggle`
- codeai Dashboard: Hero section 渐变背景、KPI 卡片样式
- Element Plus 主题色动态修改: `handleThemeStyle()` 函数
- 项目 Git API: `src/api/git.ts` 的 `getStatus()` 方法