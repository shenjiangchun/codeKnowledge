# RAM Chat 轮内插入 / 中断 / JSON 截断修复 / 模型配置 实施计划

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** 修复 RAM Chat 的 4 个交互/稳定性问题（A 输入框清空延迟、B 中断生成、C 轮内插入、D JSON 截断异常），并为多模型可插拔（GLM-5.1 等）搭建两层配置骨架。

**Architecture:** REST 命令 + WebSocket 事件（Approach 3）；后端引入 `TurnRegistry` 保存活跃 turn 的 `Disposable`，取消订阅即中断；Prompt 输出改为流式 Markdown（去掉 `{answer,summary}` schema），CHECKPOINT 事件不再依赖 JSON 解析；模型配置分两层：`chat-models.yml` 保存能力元数据（入库），`application-local.yml` 保存 endpoint/apiKey（gitignored）。

**Tech Stack:** Spring Boot 3.2 + Java 17 + Reactor + Lombok + WebSocket / Vue 3.5 + TypeScript + Element Plus + Pinia / JUnit 5 + AssertJ + Mockito

**参考设计文档:** `docs/plans/2026-07-02-ram-chat-in-turn-injection-design.md`

---

## Milestone 概览

- **M1 — Prompt 与配置骨架**（D 修复 + 模型配置）：改造 System Prompt 去掉 JSON schema；将 CHECKPOINT 由 JSON 字段抽取改为读取流式文本缓冲；建立 `ChatModelProperties` 两层配置骨架；调整 `SendOptions` 支持按场景选择 `max_tokens`。
- **M2 — TurnRegistry 与中断**（B 修复）：将 `RamClaudeJsonClient` 中的 `.blockLast()` 改为 `.subscribe()` 并保留 `Disposable`；新增 `TurnRegistry` 管理活跃 turn；新增 `EventType.TURN_INTERRUPTED` + `AgentEvent.turnInterrupted` 工厂；新增 `POST /{sid}/interrupt` 端点。
- **M3 — 轮内插入端点 + 前端 Stop 按钮**（A/C 修复）：新增 `POST /{sid}/inject` 端点，语义 = interrupt + append partial assistant text + append user msg + start new turn；前端 Enter 立即清空输入框、显示 Stop 按钮、把插入语义映射到 `/inject`。
- **M4 — DB 迁移 + 集成测试**：`ram_chat_messages` 增加 `interrupted BOOLEAN`, `turn_id VARCHAR(36)`；对 M1/M2/M3 补集成测试；跑一遍手工回归。

---

## Task 1: 去掉 System Prompt 中的 JSON 输出约束（D 修复第一步）

**Files:**
- Modify: `hisi-dev-tool/src/main/java/com/huawei/hisi/ram/chat/ChatContextBuilder.java:100-110`
- Test: `hisi-dev-tool/src/test/java/com/huawei/hisi/ram/chat/ChatContextBuilderTest.java`（新建）

**Step 1: 写失败的测试**

```java
package com.huawei.hisi.ram.chat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class ChatContextBuilderTest {

    @Test
    @DisplayName("system prompt no longer instructs JSON schema output")
    void systemPrompt_noJsonSchema() {
        ChatContextBuilder builder = new ChatContextBuilder();
        String prompt = builder.buildSystemPrompt();

        assertThat(prompt)
            .doesNotContain("JSON 对象")
            .doesNotContain("{answer")
            .doesNotContain("key_findings");
    }

    @Test
    @DisplayName("system prompt instructs markdown output")
    void systemPrompt_hasMarkdownInstruction() {
        ChatContextBuilder builder = new ChatContextBuilder();
        String prompt = builder.buildSystemPrompt();

        assertThat(prompt).containsIgnoringCase("markdown");
    }
}
```

**Step 2: 运行测试确认失败**

Run: `cd hisi-dev-tool && ./mvnw test -Dtest=ChatContextBuilderTest`
Expected: FAIL — 现 prompt 含 "JSON 对象" 字样。

**Step 3: 修改 `ChatContextBuilder.buildSystemPrompt()`**

将 `ChatContextBuilder.java:102-109` 中 `[输出约束 / Output Constraints]` 一段（包含 `4. 最终输出必须是单一 JSON 对象……`）替换为：

```
[输出约束 / Output Constraints]
1. 使用 Markdown 输出：正文可以包含标题、代码块（```lang）、有序/无序列表、表格。
2. 优先在正文顶部用一句话点明核心结论，然后再展开细节，方便前端做摘要与滚动。
3. 若引用到具体代码位置，使用 `path:line` 形式，便于用户跳转。
4. 图表（如序列图、类关系）使用 mermaid 代码块。
```

**Step 4: 运行测试确认通过**

Run: `cd hisi-dev-tool && ./mvnw test -Dtest=ChatContextBuilderTest`
Expected: PASS

**Step 5: 提交**

```bash
git add hisi-dev-tool/src/main/java/com/huawei/hisi/ram/chat/ChatContextBuilder.java \
        hisi-dev-tool/src/test/java/com/huawei/hisi/ram/chat/ChatContextBuilderTest.java
git commit -m "fix(ram-chat): drop JSON schema clause from system prompt, switch to markdown output"
```

---

## Task 2: CHECKPOINT 不再解析 JSON —— 从流式文本缓冲读取（D 修复第二步）

**Files:**
- Modify: `hisi-dev-tool/src/main/java/com/huawei/hisi/ram/chat/RamChatOrchestrator.java:83-160,187-204`
- Test: `hisi-dev-tool/src/test/java/com/huawei/hisi/ram/chat/RamChatOrchestratorTest.java`（新建或扩展）

**Step 1: 写失败的测试**

关键断言（伪代码，用 Mockito 桩 `RamClaudeJsonClient` 的 streaming 回调）：

```java
@Test
@DisplayName("CHECKPOINT payload uses streamed markdown text, not JSON schema")
void checkpoint_usesStreamedMarkdown() {
    // 桩：让 claudeClient 触发 3 段 assistantDelta，然后 finish
    // 断言：orchestrator 发布的 CHECKPOINT 事件 payload.finalText
    //       == 3 段 delta 拼接结果
    // 且 payload 不再含 "answer"/"summary"/"key_findings" 字段
}
```

**Step 2: 运行测试确认失败**

Run: `./mvnw test -Dtest=RamChatOrchestratorTest#checkpoint_usesStreamedMarkdown`
Expected: FAIL — 当前 CHECKPOINT 通过 `extractFinalText(result.json())` 从 `answer` 字段读取。

**Step 3: 修改 Orchestrator**

- `RamChatOrchestrator.java:83-132`：在 `StreamCallbacks` 里新增 `StringBuilder partialTextBuf = new StringBuilder();`，`onAssistantDelta` 追加。
- `RamChatOrchestrator.java:148-157`：删除 `extractFinalText/extractSummary` 调用，改为使用 `partialTextBuf.toString()` 作为 `finalText`；`summary` 字段暂时留空字符串（M3 后续用旁路 summarizer 生成）。
- `RamChatOrchestrator.java:187-204`：删除 `extractFinalText`/`extractSummary` 两个私有方法（成为孤儿，遵循外科手术式改动准则）。

**Step 4: 运行测试确认通过**

Run: `./mvnw test -Dtest=RamChatOrchestratorTest`
Expected: PASS

**Step 5: 手动烟囱测试**

启动后端 + 前端，问一个会触发长输出的问题（如"总结所有的实体类"），确认：
- 不再抛 `IllegalStateException: Claude response is not valid JSON`
- 前端逐 token 流式渲染 Markdown
- 结束时 CHECKPOINT 事件到达，消息落库

**Step 6: 提交**

```bash
git add hisi-dev-tool/src/main/java/com/huawei/hisi/ram/chat/RamChatOrchestrator.java \
        hisi-dev-tool/src/test/java/com/huawei/hisi/ram/chat/RamChatOrchestratorTest.java
git commit -m "fix(ram-chat): CHECKPOINT reads from streamed markdown buffer, remove JSON extractors"
```

---

## Task 3: 新增 `ChatModelProperties` 两层配置骨架

**Files:**
- Create: `hisi-dev-tool/src/main/java/com/huawei/hisi/ram/config/ChatModelProperties.java`
- Create: `hisi-dev-tool/src/main/resources/chat-models.yml`
- Modify: `hisi-dev-tool/src/main/resources/application.yml`（在末尾追加 `spring.config.import: classpath:chat-models.yml`）
- Test: `hisi-dev-tool/src/test/java/com/huawei/hisi/ram/config/ChatModelPropertiesTest.java`

**Step 1: 写失败的测试**

```java
@SpringBootTest
@ActiveProfiles("test")
class ChatModelPropertiesTest {

    @Autowired ChatModelProperties props;

    @Test
    @DisplayName("chat-models.yml loads capability metadata")
    void loadsCapabilityMetadata() {
        var glm = props.getModels().get("glm-5.1");
        assertThat(glm).isNotNull();
        assertThat(glm.getMaxContext()).isEqualTo(202_752);
        assertThat(glm.getScenarioMaxTokens().get("chat")).isEqualTo(4096);
        assertThat(glm.getScenarioMaxTokens().get("summary")).isEqualTo(2048);
        assertThat(glm.getScenarioMaxTokens().get("long-form")).isEqualTo(8192);
    }
}
```

**Step 2: 运行测试确认失败**

Run: `./mvnw test -Dtest=ChatModelPropertiesTest`
Expected: FAIL — 类不存在。

**Step 3: 最小实现**

`ChatModelProperties.java`:

```java
package com.huawei.hisi.ram.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.Map;

@Configuration
@ConfigurationProperties(prefix = "chat")
@Data
public class ChatModelProperties {
    private Map<String, ModelSpec> models;

    @Data
    public static class ModelSpec {
        private String provider;         // "zhipu", "anthropic", ...
        private int maxContext;
        private Map<String, Integer> scenarioMaxTokens;
        private String endpoint;         // 覆盖自 application-local.yml
        private String apiKey;           // 覆盖自 application-local.yml
    }
}
```

`chat-models.yml`（能力元数据，可入库）:

```yaml
chat:
  models:
    glm-5.1:
      provider: zhipu
      max-context: 202752
      scenario-max-tokens:
        chat: 4096
        summary: 2048
        long-form: 8192
    claude-sonnet-4-5:
      provider: anthropic
      max-context: 200000
      scenario-max-tokens:
        chat: 4096
        summary: 2048
        long-form: 8192
```

`application.yml` 末尾追加：

```yaml
spring:
  config:
    import:
      - classpath:chat-models.yml
      - optional:classpath:application-local.yml
```

告诉用户在自己的 `application-local.yml` 里配置：

```yaml
chat:
  models:
    glm-5.1:
      endpoint: <YOUR_ENDPOINT>
      api-key: ${GLM_API_KEY:}
```

**Step 4: 运行测试确认通过**

Run: `./mvnw test -Dtest=ChatModelPropertiesTest`
Expected: PASS

**Step 5: 提交**

```bash
git add hisi-dev-tool/src/main/java/com/huawei/hisi/ram/config/ChatModelProperties.java \
        hisi-dev-tool/src/main/resources/chat-models.yml \
        hisi-dev-tool/src/main/resources/application.yml \
        hisi-dev-tool/src/test/java/com/huawei/hisi/ram/config/ChatModelPropertiesTest.java
git commit -m "feat(ram-chat): add two-layer chat model config skeleton (chat-models.yml + application-local overrides)"
```

---

## Task 4: `SendOptions.forScenario()` 工厂 + Orchestrator 使用

**Files:**
- Modify: `hisi-dev-tool/src/main/java/com/huawei/hisi/ram/sdk/SendOptions.java`
- Modify: `hisi-dev-tool/src/main/java/com/huawei/hisi/ram/chat/RamChatOrchestrator.java:134-146`
- Test: `hisi-dev-tool/src/test/java/com/huawei/hisi/ram/sdk/SendOptionsTest.java`（新建）

**Step 1: 写失败的测试**

```java
class SendOptionsTest {
    @Test
    @DisplayName("forScenario picks maxTokens from ChatModelProperties")
    void forScenario_picksMaxTokens() {
        var props = new ChatModelProperties();
        var glm = new ChatModelProperties.ModelSpec();
        glm.setScenarioMaxTokens(Map.of("chat", 4096, "summary", 2048));
        props.setModels(Map.of("glm-5.1", glm));

        var opts = SendOptions.forScenario(props, "glm-5.1", "chat");

        assertThat(opts.model()).isEqualTo("glm-5.1");
        assertThat(opts.maxTokens()).isEqualTo(4096);
    }
}
```

**Step 2: 运行测试确认失败**

**Step 3: 加静态工厂**

```java
public static SendOptions forScenario(ChatModelProperties props, String modelId, String scenario) {
    var spec = Objects.requireNonNull(props.getModels().get(modelId),
        () -> "unknown model: " + modelId);
    int max = Objects.requireNonNull(spec.getScenarioMaxTokens().get(scenario),
        () -> "unknown scenario: " + scenario);
    return new SendOptions(modelId, max, 0.7, null);
}
```

`RamChatOrchestrator.java:140` 由 `SendOptions.defaults()` 改为 `SendOptions.forScenario(chatProps, currentModelId, "chat")`。注入 `ChatModelProperties` 与 `RamChatConfig#defaultModelId`（暂时硬编码 `glm-5.1`）。

**Step 4: 运行测试通过**

**Step 5: 提交**

```bash
git commit -m "feat(ram-chat): SendOptions.forScenario reads maxTokens from ChatModelProperties"
```

---

## Task 5: 新增 `EventType.TURN_INTERRUPTED` + `AgentEvent.turnInterrupted` 工厂（B 前置）

**Files:**
- Modify: `hisi-dev-tool/src/main/java/com/huawei/hisi/ram/model/EventType.java`
- Modify: `hisi-dev-tool/src/main/java/com/huawei/hisi/ram/model/AgentEvent.java`
- Test: `hisi-dev-tool/src/test/java/com/huawei/hisi/ram/model/AgentEventTest.java`

**Step 1: 失败的测试**

```java
@Test
@DisplayName("turnInterrupted factory builds event with TURN_INTERRUPTED type")
void turnInterrupted_buildsEvent() {
    var evt = AgentEvent.turnInterrupted("sess-1", 42, Map.of("turnId", "T1", "partialText", "abc"), "idem-1");

    assertThat(evt.getType()).isEqualTo(EventType.TURN_INTERRUPTED);
    assertThat(evt.getSessionId()).isEqualTo("sess-1");
    assertThat(evt.getSeq()).isEqualTo(42);
    assertThat(evt.getPayload()).containsEntry("turnId", "T1");
}
```

**Step 2: 运行确认失败**

**Step 3: 最小实现**

`EventType.java` 在最后一个逗号前加 `TURN_INTERRUPTED`。

`AgentEvent.java` 添加：

```java
public static AgentEvent turnInterrupted(String sessionId, long seq, Map<String, Object> payload, String idempotencyKey) {
    return AgentEvent.builder()
        .sessionId(sessionId)
        .seq(seq)
        .type(EventType.TURN_INTERRUPTED)
        .payload(payload)
        .idempotencyKey(idempotencyKey)
        .createdAt(Instant.now())
        .build();
}
```

**Step 4: 通过**

**Step 5: 提交**

```bash
git commit -m "feat(ram-chat): add TURN_INTERRUPTED event type and factory"
```

---

## Task 6: `RamClaudeJsonClient` 由 `.blockLast()` 改为 `.subscribe()` 并回传 Disposable

**Files:**
- Modify: `hisi-dev-tool/src/main/java/com/huawei/hisi/ram/nodes/impl/RamClaudeJsonClient.java:499-584`
- Test: `hisi-dev-tool/src/test/java/com/huawei/hisi/ram/nodes/impl/RamClaudeJsonClientCancellationTest.java`（新建，重点验证 Disposable.dispose() 能立即打断 SSE 循环）

**Step 1: 失败的测试**

用 `Flux.just(...).delayElements(Duration.ofMillis(100))` 桩 `AnthropicHttpClient.stream()`，调用 `streamAndCollectWithCallbacks` 拿到 `Disposable`，`Thread.sleep(150)` 后 `dispose()`，断言 `onAssistantDelta` 收到 1-2 段而非全部。

**Step 2: 运行确认失败** —— 当前签名返回 `CollectResult` 同步。

**Step 3: 重构签名**

将 `streamAndCollectWithCallbacks` 拆分为：
- `streamAndCollectAsync(...)` 返回 `Mono<CollectResult>` + 暴露 `Disposable`（通过 `Sinks.One` + 订阅句柄配对返回）
- `callJsonWithToolsAndStreaming` 内部保存最后一轮的 `Disposable` 到入参 `Consumer<Disposable> disposableSink`

调用方（Orchestrator Task 7）通过 `disposableSink.accept(...)` 拿到句柄注册进 `TurnRegistry`。

**Step 4: 通过**

**Step 5: 提交**

```bash
git commit -m "refactor(ram-chat): expose Disposable from streamAndCollect to enable turn cancellation"
```

---

## Task 7: `TurnRegistry` 组件 + `POST /{sid}/interrupt` 端点

**Files:**
- Create: `hisi-dev-tool/src/main/java/com/huawei/hisi/ram/chat/TurnRegistry.java`
- Modify: `hisi-dev-tool/src/main/java/com/huawei/hisi/ram/chat/RamChatOrchestrator.java`（注入 TurnRegistry, 提交 Disposable）
- Modify: `hisi-dev-tool/src/main/java/com/huawei/hisi/ram/chat/RamChatController.java`（新增 `POST /{sid}/interrupt`）
- Test: `hisi-dev-tool/src/test/java/com/huawei/hisi/ram/chat/TurnRegistryTest.java`
- Test: `hisi-dev-tool/src/test/java/com/huawei/hisi/ram/chat/RamChatControllerInterruptTest.java`

**Step 1: 失败的测试（TurnRegistry）**

```java
@Test
@DisplayName("interrupt disposes active turn and returns partial text")
void interrupt_disposesAndReturnsPartial() {
    var reg = new TurnRegistry();
    var disp = mock(Disposable.class);
    var buf = new StringBuilder("hello ");

    reg.register("sess-1", new ActiveTurn("T1", "sess-1", disp, buf, Instant.now(), "glm-5.1"));

    var result = reg.interrupt("sess-1");

    assertThat(result).isPresent();
    assertThat(result.get().turnId()).isEqualTo("T1");
    assertThat(result.get().partialText()).isEqualTo("hello ");
    verify(disp).dispose();
    assertThat(reg.get("sess-1")).isEmpty();
}
```

**Step 2: 失败**

**Step 3: 实现**

`TurnRegistry.java`:

```java
@Component
public class TurnRegistry {
    private final ConcurrentMap<String, ActiveTurn> active = new ConcurrentHashMap<>();

    public void register(String sessionId, ActiveTurn turn) { active.put(sessionId, turn); }
    public Optional<ActiveTurn> get(String sessionId) { return Optional.ofNullable(active.get(sessionId)); }
    public Optional<InterruptResult> interrupt(String sessionId) {
        var t = active.remove(sessionId);
        if (t == null) return Optional.empty();
        t.disposable().dispose();
        return Optional.of(new InterruptResult(t.turnId(), t.partialBuf().toString()));
    }
    public void complete(String sessionId, String turnId) {
        active.computeIfPresent(sessionId, (k, v) -> v.turnId().equals(turnId) ? null : v);
    }

    public record ActiveTurn(String turnId, String sessionId, Disposable disposable,
                             StringBuilder partialBuf, Instant startedAt, String modelId) {}
    public record InterruptResult(String turnId, String partialText) {}
}
```

Orchestrator 在开始 turn 时 `register`，`onAssistantDelta` 时 `partialBuf.append(delta)`，正常完成时 `complete`。

Controller：

```java
@PostMapping("/{sid}/interrupt")
public ResponseEntity<Void> interrupt(@PathVariable String sid) {
    turnRegistry.interrupt(sid).ifPresent(r -> {
        chatEventBus.publish(AgentEvent.turnInterrupted(sid, seqGen.next(sid),
            Map.of("turnId", r.turnId(), "partialText", r.partialText()), UUID.randomUUID().toString()));
        // 持久化 partial + interrupted=true 由 M4 完成
    });
    return ResponseEntity.accepted().build();
}
```

**Step 4: 通过**

**Step 5: 提交**

```bash
git commit -m "feat(ram-chat): TurnRegistry + POST /interrupt to cancel active turn"
```

---

## Task 8: 前端 Stop 按钮 + Enter 立即清空输入框（A + B 前端）

**Files:**
- Modify: `hisi-dev-tool-frontend/src/views/RamChatView.vue`（或对应组件路径，实施时用 Glob 定位）
- Modify: `hisi-dev-tool-frontend/src/stores/ramChat.ts`
- Modify: `hisi-dev-tool-frontend/src/api/ramChat.ts`
- Test: `hisi-dev-tool-frontend/src/stores/__tests__/ramChat.spec.ts`

**Step 1: 失败的测试**

- store 单测：`sendMessage` 应在调用 HTTP 之前 emit `input:cleared`（或直接返回 `pendingUserMsg`，让组件立即清空）。
- store 单测：`interrupt()` 调用 `POST /{sid}/interrupt` 并处理 `TURN_INTERRUPTED` 事件。

**Step 2: 失败**

**Step 3: 实现**

- 组件：绑定 `@keydown.enter.exact.prevent="onEnter"`，`onEnter` 先 `const text = input.value; input.value = '';` 再 `store.sendMessage(text)`。
- 组件：`v-if="store.isStreaming"` 显示红色 Stop 按钮，点击调用 `store.interrupt()`。
- store：`isStreaming` 状态由 WS 事件切换（收到第一个 `ASSISTANT_DELTA` → true；收到 `CHECKPOINT` 或 `TURN_INTERRUPTED` → false）。
- api：新增 `interruptTurn(sid) => POST /api/ram/chat/${sid}/interrupt`。

**Step 4: 通过**

**Step 5: 提交**

```bash
git commit -m "feat(ram-chat/ui): clear input on Enter + Stop button wired to /interrupt"
```

---

## Task 9: `POST /{sid}/inject` 三步原子端点（C）

**Files:**
- Modify: `hisi-dev-tool/src/main/java/com/huawei/hisi/ram/chat/RamChatController.java`
- Modify: `hisi-dev-tool/src/main/java/com/huawei/hisi/ram/chat/RamChatOrchestrator.java`（新增 `injectAndContinue` 方法）
- Test: `hisi-dev-tool/src/test/java/com/huawei/hisi/ram/chat/RamChatInjectTest.java`

**Step 1: 失败的测试**

```java
@Test
@DisplayName("inject interrupts active turn, appends partial assistant, appends user, starts new turn")
void inject_threeStepAtomic() {
    // 1) 先启动一个 turn 并让它 emit 部分 delta
    // 2) 调 POST /{sid}/inject with {content:"其实我想问的是 X"}
    // 3) 断言事件序列: TURN_INTERRUPTED -> USER_MSG(新) -> ASSISTANT_DELTA(新turn)
    // 4) 断言历史消息里 partial assistant 已持久化（interrupted=true）
}
```

**Step 2: 失败**

**Step 3: 实现 `injectAndContinue`**

```java
public void injectAndContinue(String sessionId, String userContent) {
    var interrupted = turnRegistry.interrupt(sessionId);
    interrupted.ifPresent(r -> {
        historyRepo.savePartialAssistant(sessionId, r.turnId(), r.partialText(), true);
        eventBus.publish(AgentEvent.turnInterrupted(sessionId, seqGen.next(sessionId),
            Map.of("turnId", r.turnId(), "partialText", r.partialText()), UUID.randomUUID().toString()));
    });
    historyRepo.saveUserMessage(sessionId, userContent);
    eventBus.publish(AgentEvent.userMsg(sessionId, seqGen.next(sessionId), userContent));
    startNewTurn(sessionId, userContent);  // 复用现有的 turn 启动逻辑
}
```

Controller 增加：

```java
@PostMapping("/{sid}/inject")
public ResponseEntity<Void> inject(@PathVariable String sid, @RequestBody InjectRequest req) {
    orchestrator.injectAndContinue(sid, req.content());
    return ResponseEntity.accepted().build();
}
public record InjectRequest(String content) {}
```

**Step 4: 通过**

**Step 5: 提交**

```bash
git commit -m "feat(ram-chat): POST /inject — interrupt + persist partial + new user msg + new turn"
```

---

## Task 10: 前端在流式过程中把 Enter 语义映射到 `/inject`（C 前端）

**Files:**
- Modify: `hisi-dev-tool-frontend/src/stores/ramChat.ts`
- Test: `hisi-dev-tool-frontend/src/stores/__tests__/ramChat.spec.ts`

**Step 1: 失败的测试**

`sendMessage` 分支：
- `isStreaming === false` → `POST /messages`（普通）
- `isStreaming === true` → `POST /inject`

**Step 2/3/4/5:** 实现 + 通过 + 提交

```bash
git commit -m "feat(ram-chat/ui): route in-turn Enter to /inject; idle Enter to /messages"
```

---

## Task 11: DB 迁移 `interrupted` + `turn_id`（M4-1）

**Files:**
- Create: `hisi-dev-tool/src/main/resources/db/migration/V<timestamp>__ram_chat_add_interrupted_and_turn_id.sql`
- Modify: `hisi-dev-tool/src/main/java/com/huawei/hisi/ram/entity/RamChatMessage.java`

**Step 1: 写迁移 SQL**

```sql
ALTER TABLE ram_chat_messages
    ADD COLUMN interrupted BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN turn_id VARCHAR(36) NULL;
CREATE INDEX idx_ram_chat_messages_turn_id ON ram_chat_messages(turn_id);
```

**Step 2: 更新实体**

`@Column private boolean interrupted;` `@Column(name = "turn_id") private String turnId;` +getter/setter 或 Lombok。

**Step 3: 运行 `./mvnw test` 验证 Flyway/JPA 兼容**

**Step 4: 提交**

```bash
git commit -m "feat(ram-chat/db): add interrupted + turn_id columns to ram_chat_messages"
```

---

## Task 12: 集成测试 —— 完整轮内插入回归

**Files:**
- Create: `hisi-dev-tool/src/test/java/com/huawei/hisi/ram/chat/RamChatInTurnInjectionIT.java`

**Step 1: 写 IT**

- 桩 Anthropic/GLM SDK；启动 `@SpringBootTest`；打开 WebSocket；
- 发一条问题、等到 `ASSISTANT_DELTA`；调 `/inject`；
- 断言事件序列 & 数据库落地行的 `interrupted` / `turn_id`。

**Step 2/3/4/5:** 让它跑起来，通过，提交

```bash
git commit -m "test(ram-chat): integration test for in-turn injection end-to-end"
```

---

## Task 13: 手动回归 checklist（不算提交，收尾用）

- [ ] Enter 立即清空
- [ ] 生成中 Stop 立即停止（后端日志能看到 dispose）
- [ ] 生成中再次 Enter → 上一条 partial 保留、新问题从新 turn 开始
- [ ] 长输出（>4k tokens）不再抛 JSON 解析异常
- [ ] Mermaid 依然渲染正常（回归 E）
- [ ] 换 `chat.models.glm-5.1` 到自己的 `application-local.yml` 后可用
- [ ] `chat-models.yml` 中不含任何 endpoint/apiKey

---

## Risks & Mitigations

- **`.subscribe()` 换 `.blockLast()` 会改并发模型**：orchestrator 的 turn 结束时机由回调驱动，注意 `TurnRegistry.complete` 一定要在最终 `onComplete`/`onError` 时被调用，用 try/finally 兜底。
- **inject 三步中的持久化失败**：至少保证 "interrupt + broadcast" 已经完成；partial 落库失败时 fallback 只在事件里保留 `partialText`，避免用户以为消息丢了。
- **前端 `isStreaming` 状态漂移**：用 `turnId` 而非 boolean 追踪，`ASSISTANT_DELTA.turnId` 变化即视为新 turn；`CHECKPOINT/TURN_INTERRUPTED` 到达且 `turnId` 匹配才清空。

---

## References

- Design doc: `docs/plans/2026-07-02-ram-chat-in-turn-injection-design.md`
- Java rules: `.claude/rules/java/coding-style.md`, `.claude/rules/java/testing.md`, `.claude/rules/java/patterns.md`
- 编码行为准则: `CLAUDE.md` §1–4（先思考，再编码 / 极简优先 / 外科手术式改动 / 目标驱动执行）
