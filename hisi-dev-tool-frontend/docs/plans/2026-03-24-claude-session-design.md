# Claude 会话功能设计文档（前端）

## 一、需求概述

1. **新增 Claude 会话页签**：用户点击日志分析后自动跳转到此页签，会话在后台持续运行
2. **多会话并行支持**：支持 2-3 个报错同时分析处理
3. **提示词前端配置**：支持在线编辑，使用 `#{变量名}` 格式

## 二、新增页面

### 2.1 Claude 会话页签

**路径**：`/claude-session`

**布局**：
```
┌─────────────────────────────────────────────────────┐
│  左侧会话列表 (可折叠)    │    右侧会话详情           │
│  ┌─────────────────┐    │  ┌─────────────────────┐  │
│  │ 🔍 搜索会话      │    │  │ 会话标题 (可编辑)    │  │
│  │ ─────────────── │    │  │ 场景标签            │  │
│  │ 📋 进行中 (3)    │    │  │ ─────────────────── │  │
│  │   ├ 会话1       │    │  │                     │  │
│  │   ├ 会话2       │    │  │   对话消息区域       │  │
│  │   └ 会话3       │    │  │   (流式输出)         │  │
│  │ ─────────────── │    │  │                     │  │
│  │ 📦 已归档        │    │  │                     │  │
│  │   └ 会话4       │    │  │ ─────────────────── │  │
│  └─────────────────┘    │  │ 💬 输入框 + 发送     │  │
│  ────────────────────   │  │ ⚙️ 导出/归档/删除    │  │
│  │  ➕ 新建会话      │    │  └─────────────────────┘  │
└─────────────────────────────────────────────────────┘
```

### 2.2 提示词配置页签

**路径**：`/prompt-config`

**功能**：
- 展示所有场景的提示词模板
- 支持在线编辑，使用 `#{变量名}` 格式定义变量
- 提供变量预览和测试功能
- 配置持久化到后端

## 三、路由配置

```typescript
// router/index.ts
{
  path: '/claude-session',
  name: 'ClaudeSession',
  component: () => import('@/views/claude-session/ClaudeSession.vue'),
  meta: { title: 'Claude会话', keepAlive: true }
},
{
  path: '/prompt-config',
  name: 'PromptConfig',
  component: () => import('@/views/prompt-config/PromptConfig.vue'),
  meta: { title: '提示词配置' }
}
```

## 四、keep-alive 配置

```vue
<!-- 布局组件 -->
<router-view v-slot="{ Component }">
  <keep-alive :include="['ClaudeSession']">
    <component :is="Component" />
  </keep-alive>
</router-view>
```

## 五、状态管理

### 5.1 sessionStore

```typescript
// stores/sessionStore.ts
export const useSessionStore = defineStore('session', () => {
  const sessions = ref<Session[]>([])
  const activeSessionId = ref<string | null>(null)
  const currentSession = computed(() =>
    sessions.value.find(s => s.id === activeSessionId.value)
  )

  // 加载会话列表
  async function loadSessions() { ... }

  // 添加会话
  function addSession(session: Session) { ... }

  // 更新会话消息
  function appendMessage(sessionId: string, message: Message) { ... }

  // 删除会话
  async function deleteSession(sessionId: string) { ... }

  // 页面刷新恢复
  async function restoreSessions() { ... }
})
```

### 5.2 promptStore

```typescript
// stores/promptStore.ts
export const usePromptStore = defineStore('prompt', () => {
  const templates = ref<PromptTemplate[]>([])

  // 加载模板列表
  async function loadTemplates() { ... }

  // 获取单个模板
  async function getTemplate(key: string) { ... }

  // 更新模板
  async function updateTemplate(key: string, content: string) { ... }

  // 渲染模板（替换变量）
  function render(key: string, variables: Record<string, string>) { ... }
})
```

## 六、日志分析页面改造

```typescript
// LogQuery.vue
async function handleAnalyze(errorInfo: ErrorInfo) {
  // 1. 获取提示词模板
  const template = await promptStore.getTemplate('log-analysis')

  // 2. 渲染提示词
  const prompt = promptStore.render('log-analysis', {
    errorMessage: errorInfo.message,
    stackTrace: errorInfo.stackTrace,
    logContext: errorInfo.context
  })

  // 3. 调用通用对话接口
  const sessionId = await claudeApi.streamChat({
    prompt,
    scene: 'log-analysis',
    metadata: { sourceId: errorInfo.id }
  })

  // 4. 跳转到 Claude 会话页签
  router.push({ name: 'ClaudeSession', query: { sessionId } })
}
```

## 七、API 接口

### 7.1 claude.ts 改造

```typescript
// api/claude.ts
export function streamChat(
  data: ChatRequest,
  callbacks: StreamCallbacks
): Promise<string> {
  // 返回 sessionId
}
```

### 7.2 session.ts（新增）

```typescript
// api/session.ts
export const sessionApi = {
  list(params: { status?: string }): Promise<Session[]>,
  get(id: string): Promise<SessionDetail>,
  update(id: string, data: { title?: string }): Promise<void>,
  delete(id: string): Promise<void>,
  archive(id: string): Promise<void>,
  export(id: string, format: 'markdown' | 'json'): Promise<Blob>,
  clearMessages(id: string): Promise<void>
}
```

### 7.3 prompt.ts（新增）

```typescript
// api/prompt.ts
export const promptApi = {
  list(): Promise<PromptTemplate[]>,
  get(key: string): Promise<PromptTemplate>,
  update(key: string, data: { content: string }): Promise<void>
}
```

## 八、组件结构

```
src/views/
├── claude-session/
│   ├── ClaudeSession.vue          # 主页面
│   ├── components/
│   │   ├── SessionList.vue        # 会话列表
│   │   ├── ChatPanel.vue          # 对话面板
│   │   └── MessageItem.vue        # 消息项
│   └── composables/
│       └── useSession.ts          # 会话逻辑
├── prompt-config/
│   ├── PromptConfig.vue           # 主页面
│   └── components/
│       ├── TemplateList.vue       # 模板列表
│       └── TemplateEditor.vue     # 模板编辑器
```
