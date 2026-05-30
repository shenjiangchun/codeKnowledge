# Claude 会话功能设计文档

## 一、需求概述

1. **新增 Claude 会话页签**：用户点击日志分析后自动跳转到此页签，会话在后台持续运行
2. **多会话并行支持**：支持 2-3 个报错同时分析处理
3. **通用化后端接口**：入参改为通用参数，支持扩展更多场景
4. **提示词前端配置**：支持在线编辑，使用 `#{变量名}` 格式

## 二、整体架构

```
┌─────────────────────────────────────────────────────────────────┐
│                          前端 (Vue 3)                            │
├─────────────────────────────────────────────────────────────────┤
│  页签结构 (keep-alive 保持状态)                                   │
│  ├── MCP使用指南                                                 │
│  ├── 日志分析 ──点击分析──► 自动跳转到 Claude会话                  │
│  ├── 调用链分析                                                  │
│  ├── 项目管理                                                    │
│  ├── Claude会话 (新增) ◄── 会话列表 + 会话详情                    │
│  └── 提示词配置 (新增) ◄── 提示词模板管理                         │
├─────────────────────────────────────────────────────────────────┤
│  stores/                                                         │
│  ├── sessionStore.ts (新增) 会话状态管理                         │
│  └── promptStore.ts (新增) 提示词配置管理                         │
└───────────────────────┬─────────────────────────────────────────┘
                        │
┌───────────────────────▼─────────────────────────────────────────┐
│                    后端 (Spring Boot)                            │
├─────────────────────────────────────────────────────────────────┤
│  ClaudeController (重构)                                         │
│  └── POST /api/claude/chat  ◄── 通用对话接口                     │
├─────────────────────────────────────────────────────────────────┤
│  SessionController (新增)                                        │
│  ├── GET    /api/sessions        获取会话列表                    │
│  ├── GET    /api/sessions/{id}   获取会话详情                    │
│  ├── PATCH  /api/sessions/{id}   更新会话(重命名)                │
│  ├── DELETE /api/sessions/{id}   删除会话                        │
│  ├── POST   /api/sessions/{id}/archive  归档会话                 │
│  └── GET    /api/sessions/{id}/export   导出会话                 │
├─────────────────────────────────────────────────────────────────┤
│  PromptController (新增)                                         │
│  ├── GET    /api/prompts         获取提示词模板列表              │
│  ├── GET    /api/prompts/{key}   获取提示词详情                  │
│  └── PUT    /api/prompts/{key}   更新提示词模板                  │
└─────────────────────────────────────────────────────────────────┘
```

## 三、后端接口设计

### 3.1 通用对话接口

**接口**：`POST /api/claude/chat`

**请求参数**：
```json
{
  "sessionId": "uuid-xxx",        // 可选，已有会话ID则传入
  "prompt": "完整提示词...",       // 前端已拼接变量
  "scene": "log-analysis",        // 场景标识
  "metadata": {                   // 可选元数据
    "sourceId": "report-123",
    "projectPath": "/path/to/project"
  }
}
```

**响应**：SSE 流式输出
- `session` - 会话ID
- `output` - 输出内容
- `done` - 完成状态
- `error` - 错误信息

### 3.2 会话管理接口

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | /api/sessions | 获取会话列表 |
| GET | /api/sessions/{id} | 获取会话详情 |
| PATCH | /api/sessions/{id} | 更新会话 |
| DELETE | /api/sessions/{id} | 删除会话 |
| POST | /api/sessions/{id}/archive | 归档会话 |
| GET | /api/sessions/{id}/export | 导出会话 |
| DELETE | /api/sessions/{id}/messages | 清除历史 |

### 3.3 提示词模板接口

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | /api/prompts | 获取模板列表 |
| GET | /api/prompts/{key} | 获取模板详情 |
| PUT | /api/prompts/{key} | 更新模板 |

## 四、数据库设计

### 4.1 会话表

```sql
CREATE TABLE claude_session (
  id VARCHAR(36) PRIMARY KEY,
  title VARCHAR(200),
  scene VARCHAR(50) NOT NULL,
  status VARCHAR(20) DEFAULT 'active',
  metadata JSON,
  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
```

### 4.2 会话消息表

```sql
CREATE TABLE claude_message (
  id BIGSERIAL PRIMARY KEY,
  session_id VARCHAR(36) NOT NULL,
  role VARCHAR(20) NOT NULL,
  content TEXT NOT NULL,
  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  FOREIGN KEY (session_id) REFERENCES claude_session(id)
);
```

### 4.3 提示词模板表

```sql
CREATE TABLE prompt_template (
  template_key VARCHAR(50) PRIMARY KEY,
  name VARCHAR(100) NOT NULL,
  content TEXT NOT NULL,
  variables JSON,
  description VARCHAR(500),
  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
```

### 4.4 表初始化策略

Repository 类中判断表是否存在，不存在则自动创建：
1. 优先使用 OpenGauss 语法执行
2. 如果失败，使用 PostgreSQL 语法重试
3. 现有 Repository 类也按此方式改造

## 五、支持场景

| 场景标识 | 说明 |
|----------|------|
| log-analysis | 日志分析 |
| code-analysis | 代码分析 |
| trace-analysis | 调用链分析 |
| impact-analysis | 影响分析 |
| free-chat | 自由对话 |

## 六、会话管理功能

- 创建、查看、删除会话
- 会话重命名、归档、清除历史
- 会话导出（Markdown/JSON）
- 页面刷新后恢复未完成会话

## 七、前端改动要点

1. **新增路由**：`/claude-session`、`/prompt-config`
2. **keep-alive**：ClaudeSession 组件保持状态
3. **sessionStore**：管理会话列表和当前会话
4. **promptStore**：管理提示词配置
5. **LogQuery.vue 改造**：点击分析后跳转到 Claude 会话页签
