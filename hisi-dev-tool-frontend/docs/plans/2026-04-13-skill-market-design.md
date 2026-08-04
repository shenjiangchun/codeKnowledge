# Claude 技能配置中心设计方案

> **设计日期**: 2026-04-13
> **目标**: 将 MCP 使用指南页面改造为技能配置中心，支持按项目安装/卸载 CodeAI 的技能、钩子和自我进化机制

---

## 一、背景与需求

### 1.1 问题分析

用户在使用 devtool 时遇到以下痛点：
- **AI 重复犯错** - 同样的错误纠正过，下次又犯
- **技能激活率低** - Claude 经常忘记加载项目规范
- **危险操作风险** - 担心 AI 执行危险命令
- **代码追踪缺失** - 不知道哪些代码是 AI 生成的
- **开发流程不规范** - AI 生成代码风格不一致

### 1.2 解决方案来源

借鉴 CodeAI 项目（`C:\Users\47583\projects\codeai`）的成熟架构：
- 自我进化机制（lesson-learned）
- 安全钩子（pre-tool-use）
- 领域技能库（38个 Skills）
- 生命周期钩子系统

### 1.3 关键约束

- **按项目控制**：不使用全局配置，每个项目独立管理技能
- **一键安装**：每个技能可独立安装/卸载，自动化配置
- **嵌入现有页面**：改造 MCP 使用指南页面为技能配置中心

---

## 二、整体架构

### 2.1 页面布局

```
+------------------------------------------------------------------+
|  头部：技能配置中心                                    [后端状态]  |
+------------------------------------------------------------------+
|  项目选择区域                                                      |
|  +------------------------------------------------------------+  |
|  | 目标项目目录: [D:\projects\my-app        ] [选择文件夹]    |  |
|  | 已安装: MCP ✓ | 安全钩子 ✓ | 自我进化 ✗ | Skills: 3/15    |  |
|  +------------------------------------------------------------+  |
+------------------------------------------------------------------+
|  分类标签                                                          |
|  [全部] [MCP工具] [核心Hooks] [后端Skills] [前端Skills] [通用Skills] |
+------------------------------------------------------------------+
|  技能卡片列表（按分类筛选）                                         |
|  +------------------+  +------------------+  +------------------+  |
|  | 📦 MCP 工具      |  | 🛡️ 安全钩子      |  | 🧠 自我进化      |  |
|  | hisi-dev-tool   |  | pre-tool-use    |  | skill-forced-eval|  |
|  | [已安装 ✓]      |  | [已安装 ✓]      |  | [未安装]        |  |
|  | [详情] [卸载]   |  | [详情] [卸载]   |  | [详情] [安装]   |  |
|  +------------------+  +------------------+  +------------------+  |
+------------------------------------------------------------------+
```

### 2.2 技能分类

| 分类 | 内容 | 示例 |
|------|------|------|
| **MCP 工具** | 原有 MCP Server | hisi-dev-tool |
| **核心 Hooks** | 必要钩子 | pre-tool-use（安全）、skill-forced-eval（自我进化） |
| **后端 Skills** | Java/Python 相关 | crud-development、api-development、database-ops、error-handler |
| **前端 Skills** | Vue/React 相关 | ui-pc、http-client、router-pc、store-pc |
| **通用 Skills** | 跨技术栈 | code-patterns、git-workflow、bug-detective、lesson-learned |

---

## 三、数据结构设计

### 3.1 技能定义

```typescript
interface SkillDefinition {
  id: string              // 唯一标识，如 'crud-development'
  name: string            // 显示名称，如 'CRUD 开发规范'
  category: 'mcp' | 'hook' | 'backend-skill' | 'frontend-skill' | 'general-skill'
  icon: string            // Element Plus 图标名称
  description: string     // 简短描述
  triggerKeywords: string[] // 触发关键词（用于展示）

  // 安装相关
  files: SkillFile[]      // 需要安装的文件列表

  // 详细信息（用于详情弹窗）
  detail: {
    usage: string         // 使用说明
    triggerCondition?: string // Hook 触发条件
    safetyRules?: string[]    // 安全钩子规则
    coreFunctions?: string[]  // 自我进化核心功能
    examples: string[]    // 示例提问
    applicableProjects?: string // 适用项目类型
  }
}

interface SkillFile {
  source: string          // 源文件路径（在 devtool 资源目录）
  target: string          // 目标路径模板，如 '.claude/skills/{id}/SKILL.md'
  type: 'skill' | 'hook' | 'command' | 'memory' | 'settings'
}
```

### 3.2 项目状态

```typescript
interface ProjectSkillStatus {
  projectDir: string      // 项目目录路径

  // 检测结果
  hasClaudeDir: boolean   // 是否存在 .claude/ 目录
  installed: {
    skills: string[]      // 已安装的技能 ID 列表
    hooks: string[]       // 已安装的 Hook ID 列表
    mcp: boolean          // MCP 是否已配置
  }

  // settings.json 状态
  settingsConfigured: boolean  // 是否有 hooks 配置
  settingsValid: boolean       // 配置是否有效
}
```

---

## 四、安装与卸载流程

### 4.1 安装流程

```
用户点击"安装"按钮
        ↓
步骤1：确保 .claude/ 目录存在（不存在则创建）
        ↓
步骤2：复制技能文件到目标目录
        ↓
步骤3：如果是 Hook 类型，更新 settings.json 的 hooks 配置
        ↓
步骤4：如果是 MCP 类型，更新 settings.json 的 mcpServers 配置
        ↓
步骤5：如果是自我进化（skill-forced-eval），创建 memory/lessons.md
        ↓
步骤6：刷新状态显示
        ↓
显示安装成功 ✓
```

### 4.2 settings.json 配置注入

**安装 skill-forced-eval 后**：

```json
{
  "hasCompletedOnboarding": true,
  "hooks": {
    "UserPromptSubmit": [
      {
        "matcher": "",
        "hooks": [{"type": "command", "command": "node .claude/hooks/skill-forced-eval.js"}]
      }
    ]
  }
}
```

**安装 pre-tool-use 后**：

```json
{
  "hasCompletedOnboarding": true,
  "hooks": {
    "UserPromptSubmit": [...],
    "PreToolUse": [
      {
        "matcher": "Bash|Write",
        "hooks": [{"type": "command", "command": "node .claude/hooks/pre-tool-use.js", "timeout": 5000}]
      }
    ]
  }
}
```

### 4.3 卸载流程

```
用户点击"卸载"按钮
        ↓
步骤1：删除技能文件
        ↓
步骤2：如果是 Hook，从 settings.json 移除对应 hooks 配置
        ↓
步骤3：如果是 MCP，从 settings.json 移除 mcpServers 配置
        ↓
步骤4：刷新状态显示
        ↓
显示卸载成功
```

### 4.4 智能配置移除

卸载时只移除该技能相关的配置，保留其他配置不变。如果某个 hook 类型下所有 hook 都被移除，则删除该 hook 类型配置。

---

## 五、状态检测规则

| 检测项 | 判断标准 |
|--------|----------|
| 技能已安装 | `.claude/skills/{id}/SKILL.md` 文件存在 |
| Hook 已安装 | `.claude/hooks/{id}.js` 文件存在 |
| MCP 已配置 | `.claude/settings.json` 中 `mcpServers` 包含对应配置 |
| Hook 已激活 | `.claude/settings.json` 中 `hooks` 包含对应 Hook 命令 |

---

## 六、技能详情弹窗

### 6.1 通用技能弹窗

```
+------------------------------------------+
|  CRUD 开发规范                    [关闭]  |
+------------------------------------------+
|  分类: 后端技能                          |
|  触发词: CRUD, 增删改查, Service, DAO    |
+------------------------------------------+
|  使用说明:                               |
|  开发后端业务模块时自动激活，             |
|  提供三层架构标准代码模板                 |
+------------------------------------------+
|  示例提问:                               |
|  • 帮我开发用户管理模块                  |
|  • 创建订单的 CRUD 功能                  |
+------------------------------------------+
|  包含文件:                               |
|  .claude/skills/crud-development/        |
|  └── SKILL.md                            |
+------------------------------------------+
|  适用项目: Java Spring Boot / Python     |
+------------------------------------------+
|            [安装] [取消]                  |
+------------------------------------------+
```

### 6.2 Hook 弹窗（额外内容）

```
+------------------------------------------+
|  pre-tool-use (安全钩子)          [关闭]  |
+------------------------------------------+
|  触发时机: Bash/Write 工具执行前         |
+------------------------------------------+
|  安全规则:                               |
|  • 阻止 rm -rf / 删除根目录              |
|  • 阻止 git push --force main            |
|  • 阻止 drop database                    |
|  • 提醒写入 .env 等敏感文件              |
+------------------------------------------+
```

### 6.3 自我进化弹窗（额外内容）

```
+------------------------------------------+
|  skill-forced-eval (自我进化)     [关闭]  |
+------------------------------------------+
|  触发时机: 用户每次提交问题时            |
+------------------------------------------+
|  核心功能:                               |
|  • 自动检测纠正关键词（不对/错了/应该是）|
|  • 激活 lesson-learned Skill 记录错误   |
|  • 下次对话自动注入经验库规则            |
+------------------------------------------+
|  经验存储位置:                            |
|  .claude/memory/lessons.md               |
+------------------------------------------+
```

---

## 七、后端 API 设计

### 7.1 接口列表

| 接口 | 方法 | 功能 |
|------|------|------|
| `/api/skills/list` | GET | 获取所有可用技能定义列表 |
| `/api/skills/install` | POST | 安装技能到指定项目 |
| `/api/skills/uninstall` | POST | 从指定项目卸载技能 |
| `/api/skills/status` | GET | 检测项目已安装技能状态 |
| `/api/skills/detail/{id}` | GET | 获取技能详细信息 |

### 7.2 接口详细定义

**获取技能列表**：
```typescript
// GET /api/skills/list
// Response
{
  skills: SkillDefinition[],
  categories: string[]
}
```

**安装技能**：
```typescript
// POST /api/skills/install
// Request
{
  skillId: string,
  projectDir: string
}
// Response
{
  success: boolean,
  message: string,
  installedFiles: string[]
}
```

**卸载技能**：
```typescript
// POST /api/skills/uninstall
// Request
{
  skillId: string,
  projectDir: string
}
// Response
{
  success: boolean,
  message: string,
  removedFiles: string[]
}
```

**检测项目状态**：
```typescript
// GET /api/skills/status?projectDir=xxx
// Response
{
  projectDir: string,
  hasClaudeDir: boolean,
  installed: {
    skills: string[],
    hooks: string[],
    mcp: boolean
  },
  settingsValid: boolean
}
```

---

## 八、资源文件结构

### 8.1 后端资源目录

```
hisi-dev-tool/
└── src/main/resources/
    └── codeai-skills/
        ├── skills/
        │   ├── crud-development/SKILL.md
        │   ├── api-development/SKILL.md
        │   ├── database-ops/SKILL.md
        │   ├── error-handler/SKILL.md
        │   ├── ui-pc/SKILL.md
        │   ├── http-client/SKILL.md
        │   ├── code-patterns/SKILL.md
        │   ├── git-workflow/SKILL.md
        │   ├── bug-detective/SKILL.md
        │   ├── lesson-learned/SKILL.md
        │   └── ...
        ├── hooks/
        │   ├── pre-tool-use.js
        │   ├── skill-forced-eval.js
        │   └── stop.js
        ├── commands/
        │   └── remember.md
        ├── memory/
        │   └── lessons.md.template
        └── skill-definitions.json
```

### 8.2 目标项目安装后结构

```
D:\projects\my-app\
└── .claude/
    ├── hooks/
    │   ├── skill-forced-eval.js
    │   └── pre-tool-use.js
    ├── skills/
    │   ├── crud-development/SKILL.md
    │   ├── lesson-learned/SKILL.md
    │   └── ...
    ├── memory/
    │   └── lessons.md
    └── settings.json
```

---

## 九、前端改造要点

### 9.1 McpGuide.vue 改造

1. **页面标题**：从"MCP 安装指南"改为"技能配置中心"
2. **新增项目选择区**：输入框 + 文件夹选择 + 状态显示
3. **新增分类标签**：筛选技能卡片
4. **改造卡片区域**：改为技能市场样式
5. **新增详情弹窗**：技能详细信息展示
6. **保留原有内容**：MCP 工具列表作为其中一个分类

### 9.2 新增组件

- `SkillCard.vue`：技能卡片组件
- `SkillDetailDialog.vue`：技能详情弹窗
- `ProjectStatusPanel.vue`：项目状态面板

---

## 十、实现优先级

### Phase 1：核心功能
1. 后端 API（skills/list、skills/install、skills/status）
2. 技能资源文件（hooks、核心 skills）
3. 前端项目选择区 + 状态显示
4. 前端技能卡片列表

### Phase 2：完善功能
1. 卸载功能（skills/uninstall）
2. 详情弹窗
3. 分类筛选
4. settings.json 配置管理

### Phase 3：扩展
1. 更多技能模板
2. 经验库查看功能
3. 技能更新机制

---

## 十一、预期收益

1. **自我进化**：AI 从纠正中学习，不再重复犯错
2. **安全防护**：阻止危险命令，提醒敏感操作
3. **规范统一**：技能模板确保代码风格一致
4. **激活率提升**：Hook 强制评估，技能激活率从 ~25% 提升到 ~90%
5. **项目隔离**：每个项目独立配置，适应不同技术栈