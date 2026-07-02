# RAM Chat 轮内插入 / 中断 / JSON 截断修复 / 模型配置 设计文档

- 日期：2026-07-02
- 涉及模块：`hisi-dev-tool` 后端 + 前端 RAM Chat
- 覆盖需求：A（输入框即刻清空）/ B（中断生成）/ C（轮内消息插入）/ D（修复 JSON 截断异常）/ 模型配置分层

---

## 1. 背景与问题

当前 RAM Chat 存在四个耦合问题：

- **A**：用户按下 Enter 后输入框要等一次往返才清空，交互卡顿。
- **B**：模型生成中无法主动打断，用户只能干等。
- **C**：生成中用户想追问或补充信息，只能等这一轮结束才能发新消息。
- **D**：`ChatContextBuilder` 强制模型输出 `{answer, summary, key_findings, recommendations}` 单一 JSON 对象；一旦 `max_tokens` 把字符串从中间截断，后端 `JSON.parse` 就抛 `IllegalStateException: Claude response is not valid JSON`。
- 额外：目前 `SendOptions.defaults()` 把 `max_tokens` 写死 4096，未按模型/场景区分。

---

## 2. 目标 & 非目标

### 目标
- 输入框即刻清空（本地状态）
- 用户可主动中断当前生成，被中断的部分文本入库并可在 UI 上看到"（已中断）"标记
- 用户可在生成中直接插入新消息，语义 = 中断 + 追加用户消息 + 开启新一轮
- 彻底消除 JSON 截断异常
- max_tokens 按模型 + 场景可配置；密钥不入库

### 非目标
- 不做多轮并发生成（一个 session 同时只有一个 active turn）
- 不做流式的 tool-use / function-call
- 不改动 KG / 图谱相关模块

---

## 3. 总体方案：REST 命令 + WS 事件（Approach 3）

**命令面（客户端 → 服务端，REST）**

| 方法 | 路径 | 用途 |
|---|---|---|
| POST | `/api/ram/chat/sessions/{sessionId}/send` | 开启新 turn |
| POST | `/api/ram/chat/turns/{turnId}/interrupt` | 中断当前 turn（B） |
| POST | `/api/ram/chat/turns/{turnId}/inject` | 插入消息（C）= interrupt + append + newTurn |
| GET  | `/api/ram/chat/models` | 拉取模型元数据（供前端下拉） |

**状态面（服务端 → 客户端，WS）**

| 事件 | 关键字段 | 触发时机 |
|---|---|---|
| `TURN_STARTED` | `turnId, modelId, startedAt` | 新 turn 落地 |
| `ASSISTANT_DELTA` | `turnId, delta` | 每次 upstream `text_delta` |
| `ASSISTANT_MESSAGE_COMPLETE` | `turnId, messageId` | 正常收尾 |
| `TURN_INTERRUPTED` | `turnId, reason, partialMessageId` | 被中断/上游截断 |
| `USER_MESSAGE_APPENDED` | `messageId, newTurnId` | inject 场景，先广播用户消息落库 |

**turnId 生命周期**：`TURN_STARTED` → 多次 `ASSISTANT_DELTA` → 二选一（`ASSISTANT_MESSAGE_COMPLETE` / `TURN_INTERRUPTED`）→ 结束。

---

## 4. 后端设计

### 4.1 TurnRegistry
```java
record ActiveTurn(
    String turnId,
    String sessionId,
    Disposable disposable,     // Reactor 订阅句柄，用于 dispose 中断上游
    StringBuilder partialBuf,  // 累积 text_delta
    Instant startedAt,
    String modelId
) {}

@Component
class TurnRegistry {
    private final Map<String, ActiveTurn> byTurnId = new ConcurrentHashMap<>();
    private final Map<String, String> sessionToTurn = new ConcurrentHashMap<>();
    // 一个 session 同时最多一个 active turn
}
```

### 4.2 RamChatOrchestrator 改造
- `RamClaudeJsonClient.blockLast()` → 改为 `subscribe(onNext, onError, onComplete)`，保留 `Disposable`
- 每个 `text_delta`：`partialBuf.append(delta)` + 广播 `ASSISTANT_DELTA`
- 正常结束：入库 assistant 消息 + 广播 `ASSISTANT_MESSAGE_COMPLETE` + registry 移除
- 收到 `interrupt(turnId)`：`disposable.dispose()` → 落库 `{role: assistant, content: partialBuf, interrupted: true}` → 广播 `TURN_INTERRUPTED{reason: user_abort}` → registry 移除
- 上游 `finish_reason=length`：按 `truncation-retry-multiplier` 放大 max_tokens 重试，最多 `truncation-max-retries` 次；仍截断 → 走中断路径，`reason: max_tokens_exhausted`

### 4.3 inject 端点（C）
POST `/api/ram/chat/turns/{turnId}/inject`，body `{ text }`。原子三步：
1. `interrupt(turnId)` —— 复用中断路径
2. 落库 user 消息（`role: user, content: text`），广播 `USER_MESSAGE_APPENDED`
3. `startTurn(sessionId)` 开启新 turn，广播 `TURN_STARTED`

### 4.4 修复 D（JSON 截断）
- 删除 `ChatContextBuilder.java:102` 的 `4. 最终输出必须是单一 JSON 对象…` 约束
- `RamChatOrchestrator` 中所有 `json.get("answer")` / `json.get("summary")` / `Map.of("summary", ...)` 全部删除
- 主对话产出改为纯 markdown，通过 `ASSISTANT_DELTA` 流式推送
- 需要结构化产物（summary / key_findings / recommendations）的下游需求 → 独立 **side-call**：对话结束后异步跑一次 summarizer 提示词，用 `scenario-defaults.structured = 1024`，专用 max_tokens，且带 `finish_reason` 重试兜底

---

## 5. 模型配置分层

### 5.1 `chat-models.yml`（提交到 git，能力元数据）
```yaml
chat:
  models:
    default-id: glm-5.1
    specs:
      glm-5.1:
        display-name: "GLM-5.1"
        api-protocol: openai
        context-window: 202752
        max-output-tokens: 128000
        scenario-defaults:
          chat: 4096
          generation: 4096
          long-doc: 16384
          extreme: 65536
          structured: 1024
        input-headroom: 2048
        thinking-overhead-ratio: 0.6
        truncation-retry-multiplier: 1.5
        truncation-max-retries: 2
```

### 5.2 `application-local.yml`（gitignore，机密）
```yaml
chat:
  models:
    specs:
      glm-5.1:
        endpoint: https://...
        api-key: sk-...
```

### 5.3 绑定与使用
- `@ConfigurationProperties(prefix = "chat")` → `ChatModelProperties`
- `SendOptions.recommendedChatMaxTokens(modelId, scenario)`：查 spec，未命中 fallback `chat=4096`
- `RamChatOrchestrator` 构造 `SendOptions` 用上面这个方法
- HTTP Client（Anthropic / OpenAI 兼容）从同一 spec 取 endpoint/apiKey
- `application.yml` 加 `spring.config.import: classpath:chat-models.yml`
- 新增 `GET /api/ram/chat/models` 只暴露 `id / displayName / scenarioDefaults`，不返回 endpoint/apiKey

---

## 6. 前端设计

### 6.1 A（立即清空）
```
ChatInput.onEnter():
  text = input.value
  input.value = ''              // 立即清
  await store.dispatchSend(text)
```

### 6.2 B（Stop 按钮）
`ChatStore.state.activeTurnId` 非空 → 发送按钮变 Stop 图标：
```
Stop.click → POST /api/ram/chat/turns/{activeTurnId}/interrupt
WS TURN_INTERRUPTED → activeTurnId=null → 按钮恢复 Send
```

### 6.3 C（inject）
```
onEnter():
  if (state.activeTurnId) {
    POST /api/ram/chat/turns/{activeTurnId}/inject { text }
  } else {
    POST /api/ram/chat/sessions/{sessionId}/send { text }
  }
```

### 6.4 消息渲染
- `ASSISTANT_DELTA` → 追加到当前 assistant 消息 `content`，Markdown 组件流式重渲染
- `interrupted: true` 的消息尾部渲染"（已中断）"角标

### 6.5 WS sender helper
- 客户端封装 `useChatSocket()`：收到事件按 `turnId` 分派到 store mutation
- 断连自动重连 + 事件重放（服务端保留最近 N 条按 turnId 索引，重连后补发）

---

## 7. 数据模型变更

`chat_message` 表新增：
- `interrupted BOOLEAN DEFAULT FALSE`
- `turn_id VARCHAR(36)`（既有 turn 概念的话直接扩，没有则新增）

无 schema 破坏，向下兼容。

---

## 8. 测试策略

- 单测：`TurnRegistry` 注册/中断/清理；`ChatModelProperties` YAML 绑定；`SendOptions.recommendedChatMaxTokens` fallback
- 集成：`interrupt` 端点在生成中被调用，验证 partial 入库 + WS `TURN_INTERRUPTED`
- 集成：`inject` 端点原子三步全部生效（partial 入库 → user 消息入库 → 新 turn 开启）
- 集成：模拟上游 `finish_reason=length` → 触发重试 → 仍截断 → 走中断路径
- E2E：前端 A/B/C 三条主路径

---

## 9. 风险 & 回滚

- **风险 1**：删除 JSON schema 后，历史依赖 `summary` 字段的 UI/接口会拿到 null → 缓解：先跑 side-call 兜底填充；灰度期保留旧字段读路径
- **风险 2**：`Disposable.dispose()` 在极端情况下与上游 IO 竞态 → 缓解：`dispose` 后仍监听 onNext，把到达的残余 delta 丢弃（不落 partialBuf）
- **回滚**：模型配置和 orchestrator 改造可通过 feature flag `ram.chat.streaming.enabled` 一键切回旧路径

---

## 10. 里程碑

1. **M1**：模型配置层 + `chat-models.yml` + `ChatModelProperties`（1 天）
2. **M2**：后端流式 + TurnRegistry + interrupt/inject 端点 + D 修复（2-3 天）
3. **M3**：前端 A/B/C + WS 事件消费（2 天）
4. **M4**：side-call summarizer + 测试补齐（1 天）

---

## 11. 未决事项

- side-call summarizer 的触发时机：assistant 消息完成后立即跑 vs. 用户显式请求"总结这一轮"—— 待 M4 前定
- WS 断连重放窗口大小（默认打算 50 条 per turn，可调）
