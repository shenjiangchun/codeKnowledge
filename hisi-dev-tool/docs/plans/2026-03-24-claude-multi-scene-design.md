# Claude 多场景入口集成设计文档

## 一、需求概述

1. **菜单可用性改造**：Claude 会话和提示词配置菜单正确配置可用性
2. **项目管理页面改造**：支持 Git 提交选择、提交代码分析、影响分析、一键更新所有仓库
3. **方法引用关系页面改造**：支持多方法入口、分析方向选择、有向无环图、AI 影响分析
4. **接口调用链查询页面改造**：支持 Claude 分析，结果持久化

## 二、功能入口汇总

| 场景 | 入口位置 | 触发方式 | Claude 场景标识 |
|------|----------|----------|-----------------|
| 日志分析 | 日志分析页面 | 点击日志"分析"按钮 | `log-analysis` |
| 提交代码分析 | 项目管理页面 | 选择 Git 提交 → 点击"提交代码分析" | `code-analysis` |
| 调用链分析 | 接口调用链查询页面 | 点击 URI 的"Claude分析"按钮 | `trace-analysis` |
| 影响分析 | 方法引用关系页面 | 选择方法 → 生成图 → 点击"AI影响分析" | `impact-analysis` |
| 自由对话 | Claude 会话页面 | 点击"新建会话" | `free-chat` |

## 三、数据库表设计

### 3.1 调用链分析结果表

```sql
CREATE TABLE call_chain_analysis (
  id VARCHAR(36) PRIMARY KEY,
  project_name VARCHAR(100) NOT NULL,
  uri VARCHAR(500) NOT NULL,
  analysis_result TEXT NOT NULL,
  session_id VARCHAR(36),
  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
```

## 四、前端改动

### 4.1 菜单可用性改造

**文件**: `src/stores/app.ts`

```typescript
const availableMenus = computed(() => ({
  'project-management': true,
  'mcp-guide': true,
  'call-chain': projectDirConfigured.value && projectSelected.value,
  'log-analysis': projectDirConfigured.value && projectSelected.value,
  'claude-session': projectDirConfigured.value && projectSelected.value,
  'prompt-config': true,
  'ops': false
}))
```

### 4.2 项目管理页面改造

**文件**: `src/views/project/ProjectList.vue`

新增功能：
- Git 提交列表（支持多选）
- 提交代码分析按钮
- 影响分析按钮
- 一键更新所有仓库按钮

### 4.3 方法引用关系页面改造

**文件**: `src/views/call-chain/MethodReferenceGraph.vue`

新增功能：
- 方法输入框（支持多个方法）
- 分析方向选择（向上/向下）
- 生成有向无环图
- AI 影响分析按钮

### 4.4 接口调用链查询页面改造

**文件**: `src/views/call-chain/CallChainGraph.vue`

新增功能：
- Claude 分析按钮
- 分析结果持久化

### 4.5 Claude 会话页面改造

**文件**: `src/views/claude-session/ClaudeSession.vue`

新增功能：
- 新建会话按钮（自由对话）
- 项目上下文显示

## 五、后端改动

### 5.1 新增 Repository

**文件**: `CallChainAnalysisRepository.java`

- 创建调用链分析结果表
- CRUD 操作
- OpenGauss/PostgreSQL 兼容

### 5.2 改造 Controller

**文件**: `GitController.java`

新增接口：
- `GET /api/git/commits` - 获取 Git 提交列表
- `POST /api/git/update-all` - 一键更新所有仓库

**文件**: `CallChainController.java`

新增接口：
- `POST /api/call-chain/analyze` - 调用链分析
- `DELETE /api/call-chain/analysis/{project}` - 清理项目分析结果
- `POST /api/method-reference/graph` - 生成方法依赖图
- `POST /api/impact/methods` - 获取 Git 提交影响的方法列表

## 六、执行顺序

1. 后端：菜单可用性配置（app.ts）
2. 后端：新增 CallChainAnalysisRepository
3. 后端：改造 GitController
4. 后端：改造 CallChainController
5. 前端：菜单可用性改造
6. 前端：项目管理页面改造
7. 前端：方法引用关系页面改造
8. 前端：接口调用链查询页面改造
9. 前端：Claude 会话页面改造
