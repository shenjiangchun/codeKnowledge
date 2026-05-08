# Claude 会话功能实施计划

## 概述

本计划实现 Claude 会话功能，包括新增会话页签、提示词配置页签、后端接口通用化改造。

## 实施步骤

### Phase 1: 后端基础设施 (预计工作量: 中)

#### Step 1.1 创建数据库表结构 Repository

**文件**: `hisi-dev-tool/src/main/java/com/huawei/hisi/repository/ClaudeSessionRepository.java`

任务：
- [ ] 创建 `ClaudeSessionRepository` 类
- [ ] 实现 OpenGauss/PostgreSQL 兼容的表初始化逻辑
- [ ] 实现 `claude_session` 表的 CRUD 操作
- [ ] 实现 `claude_message` 表的 CRUD 操作
- [ ] 实现 `prompt_template` 表的 CRUD 操作

**表结构**:
```sql
-- claude_session 表
CREATE TABLE claude_session (
  id VARCHAR(36) PRIMARY KEY,
  title VARCHAR(200),
  scene VARCHAR(50) NOT NULL,
  status VARCHAR(20) DEFAULT 'active',
  metadata JSONB,
  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- claude_message 表
CREATE TABLE claude_message (
  id BIGSERIAL PRIMARY KEY,
  session_id VARCHAR(36) NOT NULL,
  role VARCHAR(20) NOT NULL,
  content TEXT NOT NULL,
  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  FOREIGN KEY (session_id) REFERENCES claude_session(id)
);

-- prompt_template 表
CREATE TABLE prompt_template (
  template_key VARCHAR(50) PRIMARY KEY,
  name VARCHAR(100) NOT NULL,
  content TEXT NOT NULL,
  variables JSONB,
  description VARCHAR(500),
  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
```

#### Step 1.2 改造现有 Repository 类的表初始化逻辑

**文件**:
- `hisi-dev-tool/src/main/java/com/huawei/hisi/utils/TableInitializer.java`
- `hisi-dev-tool/src/main/java/com/huawei/hisi/repository/LogAnalysisRepository.java`
- 其他包含表初始化逻辑的 Repository 类

任务：
- [ ] 创建通用的 `DatabaseHelper` 工具类，实现 OpenGauss/PostgreSQL 兼容逻辑
- [ ] 改造 `TableInitializer` 使用新的兼容逻辑
- [ ] 改造其他 Repository 类的表初始化方法

#### Step 1.3 创建 Model 类

**文件**:
- `hisi-dev-tool/src/main/java/com/huawei/hisi/model/ClaudeSession.java`
- `hisi-dev-tool/src/main/java/com/huawei/hisi/model/ClaudeMessage.java`
- `hisi-dev-tool/src/main/java/com/huawei/hisi/model/PromptTemplate.java`

任务：
- [ ] 创建 `ClaudeSession` 实体类
- [ ] 创建 `ClaudeMessage` 实体类
- [ ] 创建 `PromptTemplate` 实体类
- [ ] 创建相关 DTO 类（SessionListRequest, ChatRequest 等）

---

### Phase 2: 后端接口开发 (预计工作量: 高)

#### Step 2.1 创建 SessionController

**文件**: `hisi-dev-tool/src/main/java/com/huawei/hisi/controller/SessionController.java`

任务：
- [ ] 实现会话列表接口 `GET /api/sessions`
- [ ] 实现会话详情接口 `GET /api/sessions/{id}`
- [ ] 实现会话更新接口 `PATCH /api/sessions/{id}`
- [ ] 实现会话删除接口 `DELETE /api/sessions/{id}`
- [ ] 实现会话归档接口 `POST /api/sessions/{id}/archive`
- [ ] 实现会话导出接口 `GET /api/sessions/{id}/export`
- [ ] 实现清除历史接口 `DELETE /api/sessions/{id}/messages`

#### Step 2.2 创建 SessionService

**文件**:
- `hisi-dev-tool/src/main/java/com/huawei/hisi/service/SessionService.java`
- `hisi-dev-tool/src/main/java/com/huawei/hisi/service/SessionServiceImpl.java`

任务：
- [ ] 创建 `SessionService` 接口
- [ ] 实现会话管理业务逻辑
- [ ] 实现会话导出逻辑（Markdown/JSON 格式）

#### Step 2.3 创建 PromptController

**文件**: `hisi-dev-tool/src/main/java/com/huawei/hisi/controller/PromptController.java`

任务：
- [ ] 实现模板列表接口 `GET /api/prompts`
- [ ] 实现模板详情接口 `GET /api/prompts/{key}`
- [ ] 实现模板更新接口 `PUT /api/prompts/{key}`
- [ ] 初始化默认模板数据（log-analysis, code-analysis 等）

#### Step 2.4 创建 PromptService

**文件**:
- `hisi-dev-tool/src/main/java/com/huawei/hisi/service/PromptService.java`
- `hisi-dev-tool/src/main/java/com/huawei/hisi/service/PromptServiceImpl.java`

任务：
- [ ] 创建 `PromptService` 接口
- [ ] 实现模板管理业务逻辑
- [ ] 实现变量提取和渲染功能

#### Step 2.5 重构 ClaudeAnalysisController

**文件**: `hisi-dev-tool/src/main/java/com/huawei/hisi/controller/ClaudeAnalysisController.java`

任务：
- [ ] 新增通用对话接口 `POST /api/claude/chat`（接收通用参数）
- [ ] 保留现有接口向后兼容
- [ ] 集成会话持久化逻辑

#### Step 2.6 改造 ClaudeSdkService

**文件**: `hisi-dev-tool/src/main/java/com/huawei/hisi/service/ClaudeSdkService.java`

任务：
- [ ] 新增方法支持持久化会话消息
- [ ] 改造 `streamQuery` 方法支持会话持久化

---

### Phase 3: 前端基础设施 (预计工作量: 中)

#### Step 3.1 创建类型定义

**文件**: `hisi-dev-tool-frontend/src/types/session.ts`

任务：
- [ ] 定义 `Session` 接口
- [ ] 定义 `Message` 接口
- [ ] 定义 `PromptTemplate` 接口
- [ ] 定义相关请求/响应类型

#### Step 3.2 创建 API 模块

**文件**:
- `hisi-dev-tool-frontend/src/api/session.ts`
- `hisi-dev-tool-frontend/src/api/prompt.ts`

任务：
- [ ] 实现 `sessionApi` 模块（list, get, update, delete, archive, export）
- [ ] 实现 `promptApi` 模块（list, get, update）
- [ ] 改造 `claudeApi` 支持新的通用接口

#### Step 3.3 创建 Pinia Store

**文件**:
- `hisi-dev-tool-frontend/src/stores/sessionStore.ts`
- `hisi-dev-tool-frontend/src/stores/promptStore.ts`

任务：
- [ ] 实现 `sessionStore` 管理会话列表和当前会话
- [ ] 实现 `promptStore` 管理提示词模板

---

### Phase 4: 前端页面开发 (预计工作量: 高)

#### Step 4.1 添加路由配置

**文件**: `hisi-dev-tool-frontend/src/router/index.ts`

任务：
- [ ] 添加 `/claude-session` 路由（keepAlive: true）
- [ ] 添加 `/prompt-config` 路由
- [ ] 更新菜单可用性检查逻辑

#### Step 4.2 创建 Claude 会话页面

**文件**:
- `hisi-dev-tool-frontend/src/views/claude-session/ClaudeSession.vue`
- `hisi-dev-tool-frontend/src/views/claude-session/components/SessionList.vue`
- `hisi-dev-tool-frontend/src/views/claude-session/components/ChatPanel.vue`
- `hisi-dev-tool-frontend/src/views/claude-session/components/MessageItem.vue`

任务：
- [ ] 创建主页面布局（左侧列表 + 右侧对话）
- [ ] 实现会话列表组件（搜索、分组、新建）
- [ ] 实现对话面板组件（消息显示、输入框、发送）
- [ ] 实现消息项组件（支持 Markdown 渲染）
- [ ] 实现会话管理功能（重命名、归档、导出、删除）

#### Step 4.3 创建提示词配置页面

**文件**:
- `hisi-dev-tool-frontend/src/views/prompt-config/PromptConfig.vue`
- `hisi-dev-tool-frontend/src/views/prompt-config/components/TemplateList.vue`
- `hisi-dev-tool-frontend/src/views/prompt-config/components/TemplateEditor.vue`

任务：
- [ ] 创建主页面布局
- [ ] 实现模板列表组件
- [ ] 实现模板编辑器组件（支持 `#{变量名}` 语法高亮）
- [ ] 实现变量预览功能

#### Step 4.4 改造日志分析页面

**文件**: `hisi-dev-tool-frontend/src/views/log-analysis/LogQuery.vue`

任务：
- [ ] 改造分析按钮逻辑，调用通用对话接口
- [ ] 实现分析后跳转到 Claude 会话页签
- [ ] 传递会话 ID 参数

#### Step 4.5 更新侧边栏菜单

**文件**: `hisi-dev-tool-frontend/src/components/layout/AppSidebar.vue`

任务：
- [ ] 添加"Claude 会话"菜单项
- [ ] 添加"提示词配置"菜单项

#### Step 4.6 配置 keep-alive

**文件**: `hisi-dev-tool-frontend/src/App.vue` 或布局组件

任务：
- [ ] 配置 `keep-alive` 包含 `ClaudeSession` 组件

---

### Phase 5: 集成测试 (预计工作量: 中)

#### Step 5.1 后端单元测试

任务：
- [ ] 测试 SessionRepository 的 CRUD 操作
- [ ] 测试 PromptRepository 的 CRUD 操作
- [ ] 测试 SessionService 业务逻辑
- [ ] 测试 PromptService 业务逻辑

#### Step 5.2 前端组件测试

任务：
- [ ] 测试会话列表组件
- [ ] 测试对话面板组件
- [ ] 测试模板编辑器组件

#### Step 5.3 端到端测试

任务：
- [ ] 测试日志分析 → 跳转会话流程
- [ ] 测试多会话并行
- [ ] 测试会话管理功能
- [ ] 测试提示词配置功能

---

## 文件清单

### 后端新增文件

```
hisi-dev-tool/src/main/java/com/huawei/hisi/
├── controller/
│   ├── SessionController.java
│   └── PromptController.java
├── model/
│   ├── ClaudeSession.java
│   ├── ClaudeMessage.java
│   ├── PromptTemplate.java
│   ├── ChatRequest.java (通用)
│   └── SessionDto.java
├── repository/
│   ├── ClaudeSessionRepository.java
│   └── PromptRepository.java
├── service/
│   ├── SessionService.java
│   ├── SessionServiceImpl.java
│   ├── PromptService.java
│   └── PromptServiceImpl.java
└── utils/
    └── DatabaseHelper.java (OpenGauss/PostgreSQL 兼容)
```

### 后端修改文件

```
hisi-dev-tool/src/main/java/com/huawei/hisi/
├── controller/
│   └── ClaudeAnalysisController.java (重构)
├── service/
│   └── ClaudeSdkService.java (改造)
└── utils/
    └── TableInitializer.java (改造)
```

### 前端新增文件

```
hisi-dev-tool-frontend/src/
├── api/
│   ├── session.ts
│   └── prompt.ts
├── stores/
│   ├── sessionStore.ts
│   └── promptStore.ts
├── types/
│   └── session.ts
└── views/
    ├── claude-session/
    │   ├── ClaudeSession.vue
    │   └── components/
    │       ├── SessionList.vue
    │       ├── ChatPanel.vue
    │       └── MessageItem.vue
    └── prompt-config/
        ├── PromptConfig.vue
        └── components/
            ├── TemplateList.vue
            └── TemplateEditor.vue
```

### 前端修改文件

```
hisi-dev-tool-frontend/src/
├── api/
│   └── claude.ts (改造)
├── router/
│   └── index.ts (添加路由)
├── components/
│   └── layout/
│       └── AppSidebar.vue (添加菜单)
└── views/
    └── log-analysis/
        └── LogQuery.vue (改造分析逻辑)
```

---

## 执行顺序

1. **Phase 1** → 后端数据库和 Model 层
2. **Phase 2** → 后端接口和服务层
3. **Phase 3** → 前端基础设施（类型、API、Store）
4. **Phase 4** → 前端页面开发
5. **Phase 5** → 集成测试

---

## 风险和注意事项

1. **数据库兼容性**: OpenGauss 和 PostgreSQL 在某些语法上有差异，需要充分测试
2. **会话状态同步**: 前端 keep-alive 与后端会话状态需要保持一致
3. **SSE 连接管理**: 多会话并行时需要正确管理多个 SSE 连接
4. **提示词变量解析**: `#{变量名}` 格式的解析需要处理边界情况（未定义变量、特殊字符等）
