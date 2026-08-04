# RAM Chat V1 Streaming 历史代码清理 — Design

> **状态**：已批准（2026-07-06）
> **范围**：仅清理 RAM Chat V1 单轮 streaming API，V2 multi-turn API 保留
> **作者**：Claude Code + 用户协作

## 背景

`RamClaudeJsonClient` 当前同时保留两套 streaming API：

| 版本 | 方法签名 | 用途 | 当前是否被生产代码调用 |
|---|---|---|---|
| **V1**（单轮） | `callJsonWithToolsAndStreaming(systemPrompt, userPrompt, tools, handlers, opts, callbacks[, disposableSink])` | 旧版单轮对话工具调用循环 | ❌ 否（生产链路已切到 V2） |
| **V2**（多轮） | `callJsonWithToolsAndStreamingMultiTurn(systemPrompt, messages, tools, handlers, opts, callbacks[, disposableSink])` | 新版支持 `messages` 历史的多轮对话 | ✅ 是（`RamChatOrchestrator.runTurnInternal`） |

V1 的两个重载在生产链路上没有任何调用方，全部调用已迁移到 V2。V1 仍然存在的唯一原因只剩两个：

1. **历史包袱**：从未清理
2. **测试覆盖**：`RamClaudeJsonClientCancellationTest` 验证 `disposableSink` 取消行为，但该机制在 V2 中以同样形式存在（V2 的 `disposableSink` 重载）

V1 还会带来实际危害 —— 它会调用 `parseJsonResponse`，而 Claude 真实返回常常是 Markdown 包裹的 JSON 文本，触发 `"Claude response is not valid JSON"` 错误（这正是 2026-07-06 修复 503 + JSON 解析 + 120s 超时三项 bug 的根因之一）。

## 目标

- **删除** V1 单轮 streaming API 及其唯一测试，**保留** V2 multi-turn API
- 不影响任何生产路径
- 通过编译 + 单元测试 + 集成测试三重验证

## 范围

### 2.1 删除文件

| 文件 | 原因 |
|---|---|
| `hisi-dev-tool/src/test/java/com/huawei/hisi/ram/nodes/impl/RamClaudeJsonClientCancellationTest.java` | 唯一测试 V1 `disposableSink` 行为；V2 有等价测试覆盖相同机制 |

### 2.2 修改文件

`hisi-dev-tool/src/main/java/com/huawei/hisi/ram/nodes/impl/RamClaudeJsonClient.java`：

| 位置 | 改动 |
|---|---|
| Line 281（section 注释 `// ──────────────── Streaming variant with callbacks ────────────────`） | 删除 |
| Line 283–296（V1 第一个重载 `callJsonWithToolsAndStreaming(systemPrompt, userPrompt, tools, handlers, opts, callbacks)`） | 删除 |
| Line 298–401（V1 第二个重载 `callJsonWithToolsAndStreaming(systemPrompt, userPrompt, tools, handlers, opts, callbacks, disposableSink)`） | 删除 |
| Line 404（V2 Javadoc 中 `{@link #callJsonWithToolsAndStreaming}` 引用） | 改为描述性文本"Streaming variant" |

预计删除约 **121 行**代码 + 修复 1 处 Javadoc 引用。

### 2.3 保持不变

- `parseJsonResponse` / `recoverTruncatedJson` —— 仍被 V2 使用
- `streamAndCollect*` —— V2 复用同一套 SSE 流式采集
- `callJson` / `callText` / `callJsonWithTools*` 非 streaming 版本 —— 生产链路可能仍使用
- V2 multi-turn 全部两个重载 —— 当前生产调用方
- `RamChatOrchestrator.java` —— 已经在用 V2，不动
- 任何前端代码 —— 前端只通过 `/api/ram-chat/*` HTTP + WebSocket 与后端交互，不直接调用 Java 方法

## 验证步骤

1. **死代码全清**
   ```bash
   # V1 方法名（不含 MultiTurn）应只在历史 doc 中出现，源码 0 处
   grep -rn 'callJsonWithToolsAndStreaming\b' hisi-dev-tool/src --include='*.java'
   # 期望：无输出（除非 Javadoc 引用修复不当）
   ```

2. **V2 路径无副作用**
   ```bash
   # V2 调用方必须在，验证 V2 仍被使用
   grep -rn 'callJsonWithToolsAndStreamingMultiTurn' hisi-dev-tool/src --include='*.java'
   # 期望：RamClaudeJsonClient.java（声明）+ RamChatOrchestrator.java（调用）+ 至少一个测试
   ```

3. **编译通过**
   ```bash
   mvn -pl hisi-dev-tool -am compile -DskipTests
   ```

4. **单元 + 集成测试全绿**
   ```bash
   mvn -pl hisi-dev-tool test -Dtest='RamChat*Test,RamClaude*Test,Claude*Test'
   mvn -pl hisi-dev-tool test -Dtest='RamChatInTurnInjectionIT'
   ```

5. **取消测试专项验证**（可选，更稳）
   ```bash
   # 删除旧 V1 测试后，需要确认 V2 路径的取消行为仍被某个测试覆盖
   grep -rn 'disposableSink\|Disposable.*dispose' hisi-dev-tool/src/test --include='*.java'
   # 若无：作为 follow-up 补一个 V2 版 cancellation test（不在本次清理 scope）
   ```

## 风险评估

| 风险 | 等级 | 缓解 |
|---|---|---|
| 误删被某个未知调用方依赖的 V1 | 🟡 中 | 编译 + 现有测试覆盖了 `RamChatOrchestrator.runTurnInternal` 这条唯一生产链路；如真有遗漏会在 `mvn test` 阶段暴露 |
| V2 路径的 cancellation 行为失去测试覆盖 | 🟢 低 | V2 是新代码，调用方目前是 orchestrator，concurrent cancellation 在真实链路中天然被覆盖；可作为 follow-up 补 V2 版 cancellation test |
| Javadoc 引用残留导致 javadoc 编译失败 | 🟢 低 | 同步修改 line 404 的 `{@link}`，从根源消除 |

## 不在本次范围

- **不重写 / 优化 V2**：V2 是当前生产链路，本次只清理 V1
- **不调整 `RamChatOrchestrator` 调用顺序**：orchestrator 已经在用 V2
- **不补 V2 版 cancellation test**：作为 follow-up；本次清理目标是"删除无用代码"
- **不重构 `RamClaudeJsonClient` 其他方法**（`parseJsonResponse` 等）

## 落地清单

1. 修改 `RamClaudeJsonClient.java`：删除 line 281–401（121 行）+ 修复 line 404 Javadoc 引用
2. 删除 `RamClaudeJsonClientCancellationTest.java`（146 行）
3. 执行"验证步骤"4 条
4. 跑全量 `mvn -pl hisi-dev-tool test` 确认无回归
5. `git commit -m "refactor(ram-chat): remove V1 single-turn streaming API"` + push

预计净减少约 **267 行**代码（121 主代码 + 146 测试）。