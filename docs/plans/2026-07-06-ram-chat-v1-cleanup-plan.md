# RAM Chat V1 Streaming Cleanup Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** 删除 `RamClaudeJsonClient` 中已无生产调用方的 V1 单轮 streaming API（2 重载 + 1 测试文件），仅保留 V2 multi-turn streaming。

**Architecture:** 纯死代码清理。V1 streaming 在 2026-07-02 in-turn injection V2 重构后已无生产调用方，本次清理删除两个重载方法 + 唯一依赖该方法的测试文件，并修复 V2 Javadoc 中一处 `{@link}` 引用。

**Tech Stack:** Java 17 + Spring Boot 3.2.0 + JUnit 5 + Maven（多模块：`hisi-dev-tool` 子模块）

---

## 任务前置

- **工作目录**：`C:\Users\47583\projects\hisi_dev_tool v5.0`（仓库根）
- **设计文档**：`docs/plans/2026-07-06-ram-chat-v1-cleanup-design.md`（已批准并提交）
- **提交基线**：`main` @ `8dc55bfa`（docs commit 已落地）

---

## Task 1: 删除 V1 单轮 streaming 方法（2 个重载）

**Files:**
- Modify: `hisi-dev-tool/src/main/java/com/huawei/hisi/ram/nodes/impl/RamClaudeJsonClient.java` (line 281-401)

**Step 1: 打开文件，定位 V1 区段**

```bash
# 查看 line 281 附近的 section 注释 + 两个重载
sed -n '280,310p' hisi-dev-tool/src/main/java/com/huawei/hisi/ram/nodes/impl/RamClaudeJsonClient.java
```

确认起点是 `// ──────────────── Streaming variant with callbacks ────────────────` 注释（line 281）。

**Step 2: 用 Edit 精确删除 line 281-401**

old_string（整个 section，必须包含前后唯一锚点避免歧义）：

```java
    // ──────────────── Streaming variant with callbacks ────────────────

    /**
     * Same as {@link #callJsonWithToolsAndReasoning} but invokes
     * {@link StreamCallbacks} during streaming so callers can push
     * real-time deltas / tool events to the frontend.
     */
    public JsonCallResult callJsonWithToolsAndStreaming(
            String systemPrompt,
            String userPrompt,
            List<ToolDefinition> tools,
            Map<String, Function<Map<String, Object>, Object>> handlers,
            SendOptions opts,
            StreamCallbacks callbacks) {
        return callJsonWithToolsAndStreaming(systemPrompt, userPrompt, tools, handlers, opts, callbacks, d -> {});
    }

    /**
     * Overload of {@link #callJsonWithToolsAndStreaming} that exposes the
     * per-round reactive {@link Disposable} via {@code disposableSink}, so
     * callers (e.g. a turn registry) can cancel the ongoing SSE stream from
     * another thread mid-turn.
     *
     * <p>The sink is invoked once per streamed round with the {@code Disposable}
     * of that round's subscription. Disposing it aborts the current round's
     * SSE consumption; the caller should treat any partially-parsed JSON as
     * best-effort and expect {@link IllegalStateException} on malformed output.
     */
    public JsonCallResult callJsonWithToolsAndStreaming(
            String systemPrompt,
            String userPrompt,
            List<ToolDefinition> tools,
            Map<String, Function<Map<String, Object>, Object>> handlers,
            SendOptions opts,
            StreamCallbacks callbacks,
            Consumer<Disposable> disposableSink) {
        if (tools == null || tools.isEmpty()) {
            Map<String, Object> json = callJson(systemPrompt, userPrompt, opts);
            callbacks.onRoundComplete(0, "end_turn");
            return new JsonCallResult(json, List.of("单轮调用，无工具使用"));
        }

        SendOptions effective = new SendOptions(
                opts.model(), opts.maxTokens(), opts.temperature(), systemPrompt);

        List<Map<String, Object>> messages = new ArrayList<>();
        messages.add(Map.of("role", "user", "content", userPrompt));

        List<String> reasoningSteps = new ArrayList<>();
        reasoningSteps.add("初始查询: " + truncateForReasoning(userPrompt));

        for (int round = 0; round < MAX_TOOL_ROUNDS; round++) {
            if (round == MAX_TOOL_ROUNDS - 2) {
                messages.add(Map.of("role", "user", "content",
                        "[SYSTEM] You have used most of your tool budget. " +
                        "You MUST output your final JSON response in the next turn. " +
                        "Stop calling tools and produce the complete JSON output now."));
            }

            StreamResult result = streamAndCollectWithCallbacks(messages, tools, effective, callbacks, disposableSink);

            log.info("[RamClaudeJsonClient] streaming round={} stop_reason={} text.len={} tool_use_blocks={}",
                    round, result.stopReason, result.textContent.length(), result.toolUseBlocks.size());

            if (!"tool_use".equals(result.stopReason) || result.toolUseBlocks.isEmpty()) {
                reasoningSteps.add("LLM返回最终结果");
                callbacks.onRoundComplete(round, result.stopReason);
                return new JsonCallResult(parseJsonResponse(result.textContent.toString()), List.copyOf(reasoningSteps));
            }

            List<Map<String, Object>> assistantContent = new ArrayList<>();
            if (!result.textContent.isEmpty()) {
                assistantContent.add(Map.of("type", "text", "text", result.textContent.toString()));
            }

            List<Map<String, Object>> toolResults = new ArrayList<>();
            for (ToolUseBlock block : result.toolUseBlocks) {
                assistantContent.add(Map.of(
                        "type", "tool_use",
                        "id", block.id,
                        "name", block.name,
                        "input", block.input
                ));

                callbacks.onToolUseStart(block.name, block.input);

                String toolResultContent = executeToolHandler(handlers, block);
                reasoningSteps.add(String.format("Round %d: %s(%s) -> %s",
                        round, block.name, summarizeInput(block.name, block.input),
                        truncateForReasoning(toolResultContent)));

                callbacks.onToolResult(block.name, toolResultContent);

                toolResults.add(Map.of(
                        "type", "tool_result",
                        "tool_use_id", block.id,
                        "content", toolResultContent
                ));
            }

            messages.add(Map.of("role", "assistant", "content", assistantContent));
            messages.add(Map.of("role", "user", "content", toolResults));
            callbacks.onRoundComplete(round, result.stopReason);
        }

        log.warn("[RamClaudeJsonClient] Streaming exceeded {} tool rounds — forcing termination", MAX_TOOL_ROUNDS);
        reasoningSteps.add("超过最大工具轮次，强制终止");
        messages.add(Map.of("role", "user", "content",
                "[SYSTEM] Tool budget exhausted. You MUST now output your final answer " +
                "as a single valid JSON object. Do NOT call any more tools. " +
                "Do NOT include any prose before or after the JSON. " +
                "Output ONLY the JSON object starting with { and ending with }."));
        StreamResult finalResult = streamAndCollectWithCallbacks(messages, List.of(), effective, callbacks, disposableSink);
        String finalText = finalResult.textContent.toString().trim();
        if (finalText.isEmpty()) {
            log.error("[RamClaudeJsonClient] Final forced streaming response is empty");
            throw new IllegalStateException("Claude response is not valid JSON");
        }
        callbacks.onRoundComplete(MAX_TOOL_ROUNDS, finalResult.stopReason);
        return new JsonCallResult(parseJsonResponse(finalText), List.copyOf(reasoningSteps));
    }

    /**
     * Multi-turn variant of {@link #callJsonWithToolsAndStreaming}.
```

new_string（删干净后 V2 的开头直接接在 V1 结束位置）：

```java
    /**
     * Multi-turn variant of the streaming API.
```

**Step 3: 验证删除成功**

```bash
# V1 方法名（不含 MultiTurn 后缀）在源码中应消失
grep -n 'callJsonWithToolsAndStreaming\b' hisi-dev-tool/src/main/java/com/huawei/hisi/ram/nodes/impl/RamClaudeJsonClient.java
# 期望：无输出
```

Expected: 无输出（V1 重载已全部删除；V2 用的是 `callJsonWithToolsAndStreamingMultiTurn`，正则 `\b` 不会匹配它）。

**Step 4: 暂不 commit，等 Task 2 一起**

---

## Task 2: 删除唯一测试文件 `RamClaudeJsonClientCancellationTest`

**Files:**
- Delete: `hisi-dev-tool/src/test/java/com/huawei/hisi/ram/nodes/impl/RamClaudeJsonClientCancellationTest.java`

**Step 1: 删除文件**

```bash
rm hisi-dev-tool/src/test/java/com/huawei/hisi/ram/nodes/impl/RamClaudeJsonClientCancellationTest.java
```

**Step 2: 验证 V1 引用彻底清零**

```bash
grep -rn 'callJsonWithToolsAndStreaming\b' hisi-dev-tool/src --include='*.java'
# 期望：无输出（仅 docs/plans/ 历史文档里仍可能提及，不影响编译）
```

Expected: 无输出。

**Step 3: 验证 V2 仍被使用**

```bash
grep -rn 'callJsonWithToolsAndStreamingMultiTurn' hisi-dev-tool/src --include='*.java'
# 期望：
#   .../ram/nodes/impl/RamClaudeJsonClient.java:413:    public JsonCallResult callJsonWithToolsAndStreamingMultiTurn(
#   .../ram/nodes/impl/RamClaudeJsonClient.java:420:        return callJsonWithToolsAndStreamingMultiTurn(
#   .../ram/chat/RamChatOrchestrator.java:XXX:        ... callJsonWithToolsAndStreamingMultiTurn(...) ...   （具体行号以实际为准）
```

---

## Task 3: 编译验证

**Step 1: 编译主模块**

```bash
cd "C:/Users/47583/projects/hisi_dev_tool v5.0"
mvn -pl hisi-dev-tool -am compile -DskipTests -q
```

Expected: `BUILD SUCCESS`（无 error）。warning 可忽略。

如失败：
- `cannot find symbol callJsonWithToolsAndStreaming` → Task 1 删除不完整，回查 grep
- `unresolved { @link #callJsonWithToolsAndStreaming }` → Task 1 的 Javadoc 修复未生效

**Step 2: 编译测试模块**

```bash
mvn -pl hisi-dev-tool test-compile -q
```

Expected: `BUILD SUCCESS`。

---

## Task 4: 单元测试 + 集成测试

**Step 1: 跑 RAM/Claude 相关测试**

```bash
mvn -pl hisi-dev-tool test -Dtest='RamChat*Test,RamClaude*Test,Claude*Test' -q
```

Expected: `BUILD SUCCESS`，全部测试通过。

**Step 2: 跑 RAM Chat 集成测试**

```bash
mvn -pl hisi-dev-tool test -Dtest='RamChatInTurnInjectionIT' -q
```

Expected: `BUILD SUCCESS`。

**Step 3: 跑全量测试防回归**

```bash
mvn -pl hisi-dev-tool test -q
```

Expected: `BUILD SUCCESS`。如出现测试失败，需要分析是否与本次清理相关；如果是无关 flaky 测试，记录到 `docs/known-flaky-tests.md`（如果存在）后重跑确认。

---

## Task 5: Commit + Push

**Step 1: 查看变更**

```bash
cd "C:/Users/47583/projects/hisi_dev_tool v5.0"
git status --short
git diff --stat
```

Expected:
- `M hisi-dev-tool/src/main/java/com/huawei/hisi/ram/nodes/impl/RamClaudeJsonClient.java` (-121 行)
- `D hisi-dev-tool/src/test/java/com/huawei/hisi/ram/nodes/impl/RamClaudeJsonClientCancellationTest.java` (-146 行)
- 项目 pre-commit hook 会自动 stage `ai-sessions/47583-ai-session-laptop-c2kj599j.json`（已知行为）

**Step 2: 提交**

```bash
git add hisi-dev-tool/src/main/java/com/huawei/hisi/ram/nodes/impl/RamClaudeJsonClient.java
git add hisi-dev-tool/src/test/java/com/huawei/hisi/ram/nodes/impl/RamClaudeJsonClientCancellationTest.java
git commit -m "$(cat <<'EOF'
refactor(ram-chat): remove V1 single-turn streaming API

V1 callJsonWithToolsAndStreaming (2 overloads, 121 lines) has had
zero production callers since the 2026-07-02 in-turn injection V2
refactor. Remove both overloads + the only test file that exercises
its disposableSink cancellation mechanism. V2
callJsonWithToolsAndStreamingMultiTurn is the sole production path
used by RamChatOrchestrator.runTurnInternal.

Verification:
- mvn -pl hisi-dev-tool compile + test-compile: SUCCESS
- mvn -pl hisi-dev-tool test: SUCCESS
- grep callJsonWithToolsAndStreaming\b: 0 hits in src/

Refs design: docs/plans/2026-07-06-ram-chat-v1-cleanup-design.md
EOF
)"
```

Expected: commit 落地，pre-commit hook 自动加的 ai-sessions 文件按项目惯例保留在 commit 内。

**Step 3: 推送**

```bash
# 本机环境 SSH 22 端口不通，用 HTTPS
git push https://github.com/shenjiangchun/codeKnowledge.git main
```

Expected: 推送成功（如前几次 commit 一样）。

如果 HTTPS 推送需要认证，使用项目 git credentials 或手动输入 token。

---

## Follow-up（不在本次 scope，但建议作为下一轮 backlog）

1. **补 V2 版 cancellation test**：V2 同样有 `disposableSink` 重载，需要补一个对等测试覆盖取消行为
2. **监控 production 日志**：清理后跑 1 周，确认没有任何残留的 V1 异常堆栈
3. **`parseJsonResponse` 容错强化**：考虑对 Markdown 包裹的 JSON 做更宽容的解析（V2 也仍会触发 "Claude response is not valid JSON"）