# V1 Cleanup Test Fix Patch（2026-07-07）

> 状态：本地验证完成，等用户在远端代码仓修改后回拉比对。
> 约束：不在本地提交。改完两份测试文件后 git diff 应仅含本文档与两份测试文件。

## 背景

远端 commit 785d5bd refactor(ram-chat): remove V1 single-turn streaming API 删除了 RamClaudeJsonClient.callJsonWithToolsAndStreaming（V1，2 个重载，124 行），但遗漏了两份仍引用 V1 方法的测试文件：

- src/test/java/com/huawei/hisi/ram/chat/RamChatOrchestratorTest.java
- src/test/java/com/huawei/hisi/ram/chat/RamChatInTurnInjectionIT.java

导致 mvn test-compile 编译失败，commit message 中"17/17 PASS"声明与实际不符。

## 本地验证结果

环境 JAVA_HOME=C:/Program Files/Java/jdk-17.0.3.1，Maven 3.3.9。

### 修复前（HEAD = 785d5bd）

```
mvn test-compile
[ERROR] RamChatInTurnInjectionIT.java:[125,26] 找不到符号: 方法 callJsonWithToolsAndStreaming(...)
[ERROR] RamChatInTurnInjectionIT.java:[193,30] 找不到符号: 方法 callJsonWithToolsAndStreaming(...)
[ERROR] RamChatOrchestratorTest.java:[112,26] 找不到符号: 方法 callJsonWithToolsAndStreaming(...)
[ERROR] RamChatOrchestratorTest.java:[189,26] 找不到符号: 方法 callJsonWithToolsAndStreaming(...)
```

（另有 2 个 pre-existing 失败 ParseNodeTest / LogAnalysisDagOrchestratorTest 的 ParseNode 构造函数签名不匹配，与本次 V1 cleanup 无关，已在 docs/plans/2026-07-06-test-failures-backlog.md 第 4 节登记。）

### 修复后

```
mvn surefire:test -Dtest='RamChatOrchestratorTest,RamChatInjectTest,RamChatControllerInterruptTest,TurnRegistryTest,ChatContextBuilderTest'
[INFO] Tests run: 15, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS

mvn surefire:test -Dtest='RamChatInTurnInjectionIT'
[INFO] Tests run: 2, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
```

## 修改清单（2 个文件，4 处改动）

### 文件 1：hisi-dev-tool/src/test/java/com/huawei/hisi/ram/chat/RamChatOrchestratorTest.java

#### 改动 1.1：补 import（第 30 行附近）

**Before**：

```java
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
```

**After**：

```java
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
```

#### 改动 1.2：第一个 stub（约 112 行，checkpoint_usesStreamedMarkdown 测试方法内）

**Before**：

```java
        when(claudeClient.callJsonWithToolsAndStreaming(
                anyString(), anyString(), any(), anyMap(), any(), any(StreamCallbacks.class), any()))
                .thenAnswer(inv -> {
                    StreamCallbacks cb = inv.getArgument(5);
                    cb.onAssistantDelta("段1");
```

**After**：

```java
        when(claudeClient.callJsonWithToolsAndStreamingMultiTurn(
                anyString(), anyList(), any(), anyMap(), any(), any(StreamCallbacks.class), any()))
                .thenAnswer(inv -> {
                    StreamCallbacks cb = inv.getArgument(5);
                    cb.onAssistantDelta("段1");
```

#### 改动 1.3：第二个 stub（约 189 行，orchestrator_usesConfigDrivenDefaultModelId 测试方法内）

**Before**：

```java
        when(claudeClient.callJsonWithToolsAndStreaming(
                anyString(), anyString(), any(), anyMap(), any(), any(StreamCallbacks.class), any()))
                .thenAnswer(inv -> {
                    StreamCallbacks cb = inv.getArgument(5);
                    cb.onRoundComplete(0, "end_turn");
```

**After**：

```java
        when(claudeClient.callJsonWithToolsAndStreamingMultiTurn(
                anyString(), anyList(), any(), anyMap(), any(), any(StreamCallbacks.class), any()))
                .thenAnswer(inv -> {
                    StreamCallbacks cb = inv.getArgument(5);
                    cb.onRoundComplete(0, "end_turn");
```

### 文件 2：hisi-dev-tool/src/test/java/com/huawei/hisi/ram/chat/RamChatInTurnInjectionIT.java

> 该文件已 import anyList，无需补 import。

#### 改动 2.1：第一个 stub（约 125 行，injectMidStream_persistsInterruptAndStartsNewTurn 测试方法内，first-turn stub）

**Before**：

```java
        // First-turn stub: emit one ASSISTANT_DELTA, then block until released.
        when(claudeClient.callJsonWithToolsAndStreaming(
                anyString(), anyString(), anyList(), anyMap(),
                any(SendOptions.class), any(StreamCallbacks.class), any()))
                .thenAnswer(inv -> {
```

**After**：

```java
        // First-turn stub: emit one ASSISTANT_DELTA, then block until released.
        when(claudeClient.callJsonWithToolsAndStreamingMultiTurn(
                anyString(), anyList(), anyList(), anyMap(),
                any(SendOptions.class), any(StreamCallbacks.class), any()))
                .thenAnswer(inv -> {
```

#### 改动 2.2：第二个 stub（约 193 行，同测试方法内，second-turn stub，reset(claudeClient) 之后）

**Before**：

```java
            // 3. Reset the stub so the SECOND (injected) turn returns immediately.
            reset(claudeClient);
            when(claudeClient.callJsonWithToolsAndStreaming(
                    anyString(), anyString(), anyList(), anyMap(),
                    any(SendOptions.class), any(StreamCallbacks.class), any()))
                    .thenAnswer(inv -> {
```

**After**：

```java
            // 3. Reset the stub so the SECOND (injected) turn returns immediately.
            reset(claudeClient);
            when(claudeClient.callJsonWithToolsAndStreamingMultiTurn(
                    anyString(), anyList(), anyList(), anyMap(),
                    any(SendOptions.class), any(StreamCallbacks.class), any()))
                    .thenAnswer(inv -> {
```

## 改动原理

V1 签名（已删）：

```java
JsonCallResult callJsonWithToolsAndStreaming(
    String systemPrompt,
    String userPrompt,                          // ← 单轮：String
    List<ToolDefinition> tools,
    Map<String, Function<Map<String, Object>, Object>> handlers,
    SendOptions opts,
    StreamCallbacks callbacks,
    Consumer<Disposable> disposableSink);
```

V2 签名（保留）：

```java
JsonCallResult callJsonWithToolsAndStreamingMultiTurn(
    String systemPrompt,
    List<Map<String, Object>> messages,         // ← 多轮：List<Map>
    List<ToolDefinition> tools,
    Map<String, Function<Map<String, Object>, Object>> handlers,
    SendOptions opts,
    StreamCallbacks callbacks,
    Consumer<Disposable> disposableSink);
```

唯一变化是第二参从 String userPrompt → List<Map<String, Object>> messages。

Mockito stub 中的 anyString() 必须改成 anyList()，否则 Java 方法解析在编译期找不到匹配的 V2 重载。
inv.getArgument(5) / inv.getArgument(6) 等参数位置不变（V1、V2 都是 7 个参数，StreamCallbacks 在第 6 位，Consumer<Disposable> 在第 7 位）。

## 完整 diff（参考用）

```diff
diff --git a/hisi-dev-tool/src/test/java/com/huawei/hisi/ram/chat/RamChatOrchestratorTest.java b/hisi-dev-tool/src/test/java/com/huawei/hisi/ram/chat/RamChatOrchestratorTest.java
index 9a832cd..5a0a373 100644
--- a/hisi-dev-tool/src/test/java/com/huawei/hisi/ram/chat/RamChatOrchestratorTest.java
+++ b/hisi-dev-tool/src/test/java/com/huawei/hisi/ram/chat/RamChatOrchestratorTest.java
@@ -29,6 +29,7 @@ import java.util.concurrent.atomic.AtomicReference;

 import static org.assertj.core.api.Assertions.assertThat;
 import static org.mockito.ArgumentMatchers.any;
+import static org.mockito.ArgumentMatchers.anyList;
 import static org.mockito.ArgumentMatchers.anyLong;
 import static org.mockito.ArgumentMatchers.anyMap;
 import static org.mockito.ArgumentMatchers.anyString;
@@ -109,8 +110,8 @@ class RamChatOrchestratorTest {
         // Stub the streaming Claude call: fire 3 assistant deltas, then return
         // a JsonCallResult whose json map contains an answer field that
         // MUST NOT leak into the CHECKPOINT payload.
-        when(claudeClient.callJsonWithToolsAndStreaming(
-                anyString(), anyString(), any(), anyMap(), any(), any(StreamCallbacks.class), any()))
+        when(claudeClient.callJsonWithToolsAndStreamingMultiTurn(
+                anyString(), anyList(), any(), anyMap(), any(), any(StreamCallbacks.class), any()))
                 .thenAnswer(inv -> {
                     StreamCallbacks cb = inv.getArgument(5);
                     cb.onAssistantDelta("段1");
@@ -186,8 +187,8 @@ class RamChatOrchestratorTest {
         when(kgToolRegistry.buildToolHandlers(any(List.class))).thenReturn(Map.of());
         when(projectOverviewTool.buildDefinition()).thenReturn(mock(ToolDefinition.class));
         when(projectOverviewTool.buildHandler(any(List.class))).thenReturn(map -> null);
-        when(claudeClient.callJsonWithToolsAndStreaming(
-                anyString(), anyString(), any(), anyMap(), any(), any(StreamCallbacks.class), any()))
+        when(claudeClient.callJsonWithToolsAndStreamingMultiTurn(
+                anyString(), anyList(), any(), anyMap(), any(), any(StreamCallbacks.class), any()))
                 .thenAnswer(inv -> {
                     StreamCallbacks cb = inv.getArgument(5);
                     cb.onRoundComplete(0, "end_turn");
diff --git a/hisi-dev-tool/src/test/java/com/huawei/hisi/ram/chat/RamChatInTurnInjectionIT.java b/hisi-dev-tool/src/test/java/com/huawei/hisi/ram/chat/RamChatInTurnInjectionIT.java
index fda1af9..5f9c59a 100644
--- a/hisi-dev-tool/src/test/java/com/huawei/hisi/ram/chat/RamChatInTurnInjectionIT.java
+++ b/hisi-dev-tool/src/test/java/com/huawei/hisi/ram/chat/RamChatInTurnInjectionIT.java
@@ -122,8 +122,8 @@ class RamChatInTurnInjectionIT {
         AtomicReference<String> firstTurnDelta = new AtomicReference<>("first partial ");

         // First-turn stub: emit one ASSISTANT_DELTA, then block until released.
-        when(claudeClient.callJsonWithToolsAndStreaming(
-                anyString(), anyString(), anyList(), anyMap(),
+        when(claudeClient.callJsonWithToolsAndStreamingMultiTurn(
+                anyString(), anyList(), anyList(), anyMap(),
                 any(SendOptions.class), any(StreamCallbacks.class), any()))
                 .thenAnswer(inv -> {
                     StreamCallbacks cb = inv.getArgument(5, StreamCallbacks.class);
@@ -190,8 +190,8 @@ class RamChatInTurnInjectionIT {

             // 3. Reset the stub so the SECOND (injected) turn returns immediately.
             reset(claudeClient);
-            when(claudeClient.callJsonWithToolsAndStreaming(
-                    anyString(), anyString(), anyList(), anyMap(),
+            when(claudeClient.callJsonWithToolsAndStreamingMultiTurn(
+                    anyString(), anyList(), anyList(), anyMap(),
                     any(SendOptions.class), any(StreamCallbacks.class), any()))
                     .thenAnswer(inv -> {
                         StreamCallbacks cb = inv.getArgument(5, StreamCallbacks.class);
```

## 验证清单（远端改完后跑）

```bash
# 1. test-compile 全量（含 2 个 pre-existing broken 测试仍会失败，与本次无关）
mvn test-compile
# 期望：仅 ParseNodeTest / LogAnalysisDagOrchestratorTest 编译失败

# 2. 跑 RAM chat 测试
mvn surefire:test -Dtest='RamChatOrchestratorTest,RamChatInjectTest,RamChatControllerInterruptTest,TurnRegistryTest,ChatContextBuilderTest' -DfailIfNoTests=false
# 期望：Tests run: 15, Failures: 0, Errors: 0

# 3. 跑 IT
mvn surefire:test -Dtest='RamChatInTurnInjectionIT' -DfailIfNoTests=false
# 期望：Tests run: 2, Failures: 0, Errors: 0
```

## 不在本次 scope 内

- ParseNodeTest / LogAnalysisDagOrchestratorTest 的 ParseNode 构造函数签名不匹配 —— pre-existing，见 docs/plans/2026-07-06-test-failures-backlog.md。
- 17 个 pre-existing 失败的处理决策。
- V2 cancellation test 的 follow-up（V1 删了，V2 同样有 disposableSink 但没单测）。
