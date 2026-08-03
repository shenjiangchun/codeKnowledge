# Agent D: 异常修复对话流 (Exception Fix Flow) 架构审计报告

**审计日期**: 2026-07-17  
**审计范围**: `FixFlowRunner`, `FixAgent`, `FixChatService`, `FixService`, `WorktreeService`, `TestGenService`, `ReproService`, `GitExecutor`, `MavenExecutor`  
**评分**: 4.0 / 10

---

## 1. 总体评估

Agent D 是整个系统中风险最高、复杂度最大、设计问题最集中的模块。它试图端到端自动化"从异常日志到 commit 的修复"这一完整流程——这是 AI 辅助编程领域的前沿问题（SWE-bench 级别）。然而，当前的实现存在严重的架构缺陷：硬编码流程缺乏灵活性和自我纠错能力、单步 LLM 调用而非 Agent 循环、容错策略过于宽松导致假修复可能被 commit、大量代码重复、KG 缺失削弱了修复质量、方法体提取靠脆弱的字符串解析。

**核心矛盾**: 试图用确定性工作流解决一个本质上是探索性、迭代性的问题。

---

## 2. 硬编码 9 步 vs 动态 Agent 循环

### 2.1 评估：7 步适合硬编码，2 步需要 Agent 循环

| 步骤 | 类型 | 判定 |
|------|------|------|
| Step 1: log_recognition | 确定性 | 正确 - 调用 DAG 分析，输入确定，输出确定 |
| Step 2: kg_search | 确定性 | 正确 - 知识图谱查询，无分支 |
| Step 3: create_worktree | 确定性 | 正确 - git 操作，无分支 |
| Step 4: generate_test | **需要 Agent 循环** | 错误 - AI 生成+编译检查是典型的生成-验证循环 |
| Step 5: run_repro | 确定性 | 正确 - 运行测试，检查结果 |
| Step 6: ai_fix | **需要 Agent 循环** | 错误 - 单步 LLM 调用无法验证修复正确性 |
| Step 7: run_pass | 确定性 | 正确 - 运行测试，检查结果 |
| Step 8: commit | 确定性 | 正确 - git 操作 |
| Step 9: done | 确定性 | 正确 - 状态设置 |

### 2.2 详细分析

**Step 4 (generate_test)** 实际上已经有 mini 循环（TestGenService MAX_FIX_ROUNDS=3），但循环只在 testGenAgent.generate() -> compileCheck -> testGenAgent.fixTest() 之间。这个 mini 循环的问题是：

1. 没有利用编译错误的具体位置信息（哪个文件、哪一行）进行定向修复
2. 每次 fixTest 重新生成整个文件，而非增量修改
3. 失败 3 轮后直接"返回原样"（TestGenService.java:45-46），没有降级策略（如回退到纯规则生成的基础测试模板）

**Step 6 (ai_fix)** 是最严重的问题。FixAgent.generateFix() 是一次性 LLM 调用，没有任何验证循环：
- 不编译检查修复后的代码
- 不运行测试验证修复是否通过
- 不对修复结果做任何形式的静态分析

修复后直接写入 worktree 并进入 step 7。如果修复引入新的编译错误，只能在 step 7 的 Maven 测试中发现（且 lenient policy 下即使失败也继续 commit）。

### 2.3 建议

```
正确架构:
  Step 4: generate_test (Agent 循环: 生成 -> 编译 -> 修复编译错误 -> 重试, max 3 rounds)
  Step 6: ai_fix (Agent 循环: 生成修复 -> 编译检查 -> 运行测试 -> 如果失败则反馈错误 -> 重试, max 3 rounds)
         此处 FixAgent 应该升级为带工具循环的 ReAct Agent:
         - Tool 1: read_file (读取相关源码)
         - Tool 2: write_fix (写入修复)
         - Tool 3: compile_check (编译检查)
         - Tool 4: run_test (运行测试)
         Agent 看到编译/测试失败后自主决定如何修复，而非盲目提交
```

对比 Claude Code 在 SWE-bench 上的 80%+ 解决率——其核心就是 Agent 循环 + 工具调用 + 从测试结果中学习。

---

## 3. FixAgent 的单步 LLM 调用 vs SWE-bench Agent

### 3.1 当前设计

FixAgent.generateFix() (FixAgent.java:45-66):
```java
1. 加载 prompt 模板 (fix-prompt.txt)
2. 替换 5 个占位符 ({methodSignature}, {exceptionType}, ...)
3. llm.chat(systemPrompt, userPrompt)  // 单次调用
4. stripCodeFences()  // 去掉 markdown 代码块标记
5. 返回字符串
```

### 3.2 与 SWE-bench 论文的差距

| 能力 | SWE-bench Agent (如 SWE-agent, OpenHands) | FixAgent 当前 |
|------|------------------------------------------|---------------|
| 多轮推理 | 迭代思考-行动-观察循环 | 单轮 LLM 调用 |
| 工具使用 | 文件读写、搜索、git、测试运行 | 无 |
| 自我验证 | 编译运行检查、lint、测试 | 无 |
| 上下文扩展 | 自主搜索相关文件 | 仅限于 prompt 模板中的源码 |
| 错误恢复 | 从编译/测试错误中学习 | 无（一次生成就写入文件） |
| 人类在回路 | 可选中断确认 | 无 |

**差距量化**: SWE-bench Lite 上 Claude 3.5 Sonnet + Agent 循环可达到 ~50% 解决率，而 FixAgent 在当前设计下预计解决率 < 10%（因为无法从错误中恢复）。

### 3.3 核心问题

1. **Prompt 模板过于简单** (fix-prompt.txt): 只有 26 行，缺少上下文（调用链、相关类、项目结构）。对比 Claude Code 的系统 prompt（数千行，含大量编码规范和工具使用指导）。
2. **无上下文扩展**: 只传入一个方法的源码。实际修复可能需要理解调用方、被调用方、配置类、数据模型。
3. **system prompt 硬编码**: `"You are a senior Java engineer..."` (FixAgent.java:59) 一行。有效的 Agent system prompt 通常包含编码规范、常见陷阱、输出格式约束等。

### 3.4 改进建议

1. **升级为 ReAct Agent**: 实现 think-act-observe 循环，工具包括：读取文件、写入修复、编译、运行单测
2. **扩展 prompt**: 包含项目 package 前缀、编码规范、已有测试的格式参考
3. **从编译错误中学习**: 修复失败后，将编译器错误消息作为第二轮 prompt 的上下文，让 LLM 针对性修改
4. **添加安全检查**: 不要让 Agent 随意修改文件系统——限制在 worktree 目录内，禁止执行任意 shell 命令

---

## 4. 容错策略评估: "Lenient Policy"

### 4.1 当前策略

FixFlowRunner.java:96-99:
```java
// Lenient policy: build/repro/pass failures don't abort the flow.
// Collected here, surfaced in the final checkpoint so the model/user
// can decide whether to trust the unverified fix.
List<String> buildNotes = new ArrayList<>();
```

当 repro (step 5) 或 pass (step 7) 失败时，流程**继续执行到 commit (step 8)**，只是在最终 checkpoint 消息中标注 `UNVERIFIED`。会话状态设为 `SUCCESS_UNVERIFIED`。

### 4.2 评估：策略是合理的，但实现有缺陷

**合理之处**:
- 在很多 Maven 项目环境中，编译失败是因为依赖解析问题（私有仓库不可达、依赖版本不兼容），而非代码本身有问题
- 如果因为环境问题而拒绝 commit，会造成误杀——修复本身可能正确
- `buildNotes` 记录了失败原因，`SUCCESS_UNVERIFIED` 状态明确告知用户"我们没验证成功"

**缺陷**:

1. **没有区分"环境失败"和"代码失败"**:

   当前代码在 buildNotes 里只能写一条模糊的消息:
   ```
   "Pass: fix did not pass the test (build/env failure likely — see maven output)"
   ```
   但无法区分:
   - 情况 A: 编译通过但测试逻辑错误（修复可能有效，但测试写得不对）
   - 情况 B: 编译失败（修复引入了语法错误）
   - 情况 C: 依赖下载失败（修复正确，环境问题）

   MavenExecutor.runTest() 返回 `TestRunResult`，包含 exit code 和 output。但 `reproService.runAndCheckPass()` 只返回 boolean——丢弃了区分 A/B/C 所需的具体错误信息。

2. **SPECIAL_EXCEPTIONS 只跳过了 Maven 全量编译**:

   从 MavenExecutor 的代码推测（`runTest` 调用时传 `null` 作为 testClass 参数），可能会触发全量项目编译而非仅编译测试类。正确的做法应该使用 `mvn test -pl <module> -Dtest=<TestClass> -DfailIfNoTests=false` 进行隔离测试。

3. **SUCCESS_UNVERIFIED 状态在前端的呈现**:

   如果前端只显示"修复成功 (UNVERIFIED)"，用户可能不注意或理解不了其含义。需要明确的前端 UI 提示（黄色警告框 + 建议手动 review diff）。

### 4.3 改进建议

1. TestRunResult 应该携带更结构化的信息（编译失败 vs 测试失败 vs 依赖下载失败），buildNotes 据此生成更精准的消息
2. 考虑添加"环境嗅探"步骤：在 flow 开始前运行一次 `mvn compile` 验证基础编译环境
3. `SUCCESS_UNVERIFIED` 应该阻止自动 merge——建议分支命名加 `unverified-` 前缀

---

## 5. 代码重复: FixChatService vs RamChatOrchestrator

### 5.1 重复分析

FixChatService 和 RamChatOrchestrator 共享了几乎相同的事件构建逻辑:

| 代码段 | FixChatService | RamChatOrchestrator |
|--------|---------------|-------------------|
| wsEvent() (静态方法) | FixChatService.java:166-177 | RamChatOrchestrator.java:366-377 |
| wsPush() | FixChatService.java:160-164 | RamChatOrchestrator.java:118-125 (内联) |
| toJson() / appendEvent() | FixChatService.java:179-186 | RamChatOrchestrator.java:341-358 |
| idemKey() 生成 | FixChatService.java:188-190 | (FixFlowRunner 中也有) |
| user_msg 构建 | FixChatService.java:78-91 | RamChatOrchestrator.java:137-146 |
| assistant_delta 构建 | FixChatService.java:96-108 | RamChatOrchestrator.java:169-178 |
| checkpoint 构建 | FixChatService.java:110-130 | RamChatOrchestrator.java:288-300 |
| error 构建 | FixChatService.java:134-151 | RamChatOrchestrator.java:315-324 |

这是**近乎 1:1 的复制**，违反了 DRY 原则。如果事件格式变更，需要同时修改 3 个地方（FixFlowRunner, FixChatService, RamChatOrchestrator）。

### 5.2 根本原因

FixChatService 和 FixFlowRunner 复用 RamChatWebSocketHandler 推送事件，但没有复用事件构建逻辑。三者都有自己的一套 `pushToolStart/pushToolResult/pushCheckpoint/wsEvent` 方法。

### 5.3 改进建议

1. **提取共享事件服务**: 创建 `ChatEventService` 或 `AgentEventPublisher`，封装：
   - `publishUserMsg(sessionId, turnId, text)` -> 持久化 + WS 推送
   - `publishAssistantDelta(sessionId, turnId, delta)` -> 持久化 + WS 推送
   - `publishCheckpoint(sessionId, turnId, summary, finalText)` -> 持久化 + WS 推送
   - `publishError(sessionId, turnId, error)` -> 持久化 + WS 推送
   - `publishToolUse(sessionId, turnId, toolName, input)` -> 持久化 + WS 推送
   - `publishToolResult(sessionId, turnId, toolName, result)` -> 持久化 + WS 推送

2. FixFlowRunner, FixChatService, RamChatOrchestrator 全部依赖此服务，只传递业务数据
3. 这将消除 **~200 行重复代码**，降低维护风险

---

## 6. 安全性: Worktree 上的任意代码修改 + Commit

### 6.1 安全边界分析

```
用户触发修复
  -> FixFlowRunner.run()
    -> Step 3: 在外部路径 (~/.hisi-devtool/worktrees/<branch>) 创建 git worktree
    -> Step 4: AI 生成测试代码 -> 写入 src/test/java/
    -> Step 6: AI 生成修复代码 -> 写入 src/main/java/
    -> Step 8: git commit + 推送到远程
```

### 6.2 风险点

| 风险 | 严重程度 | 细节 |
|------|---------|------|
| **AI 生成的代码未经人工审核即提交** | HIGH | FixAgent 返回的代码直接写入文件系统 (WorktreeService.java:74-83)，无 diff review 环节 |
| **worktree 路径可预测** | MEDIUM | `~/.hisi-devtool/worktrees/<branchName>` 是固定模式，可猜测 |
| **无权限校验** | HIGH | 任何能创建 FixSession 的用户都可以触发任意仓库的修复和 commit |
| **git commit 可以推送到远程** | HIGH | commit 方法 (WorktreeService.java:90-93) 只做了本地 commit，但分支存在于 worktree 中，后续如果 push 会直接推送到远程 |
| **AI 注入恶意代码** | LOW | LLM prompt 注入理论上可能生成恶意代码，但实际风险低（生成的是方法级修复） |

### 6.3 改进建议

1. **强制人工审核**: Step 6 和 Step 8 之间插入"等待用户确认"步骤（HITL - Human-in-the-Loop），展示 diff 并等待用户 approve
2. **worktree 隔离**: 使用随机目录名而非 `branchName` 作为 worktree 路径
3. **commit 签名**: 使用不同的 git author（如 `fix-bot <bot@hisi.local>`）区分 AI 提交和人工提交
4. **限制 push**: 在 worktree 中禁止 push（`git config receive.denyCurrentBranch` 或使用 bare repo）
5. **审计日志**: 记录每次 AI 修复的 diff、prompt、模型版本，用于追溯

---

## 7. KG 未集成: Step 2 的缺失影响

### 7.1 当前状态

FixFlowRunner.java:123-125:
```java
// TODO: integrate with KG hybrid_search to find method location
log.info("[FixFlowRunner] step2: KG not yet integrated, using throwPointSig={}", throwPointSig);
return Map.of("throwPointSig", String.valueOf(throwPointSig));
```

Step 2 是一个**占位符**——只是把 Step 1 得到的 throwPointSig 原样传出，没有做任何 KG 查询。

### 7.2 缺失影响评估

KG 搜索缺失的影响取决于 throwPointSig 的质量：

| throwPointSig 来源 | 可靠性 | KG 缺失影响 |
|-------------------|--------|------------|
| DAG 分析 `parsedError.errorType` | 高 | 低 - 如果异常类型已知，sigToFilePath 可工作 |
| 堆栈帧 `keyFrames[0].fullSignature` | 中 | 中 - 堆栈首帧可能不是业务代码 |
| 基础规则分析 | 低 | 高 - 正则提取可能遗漏复杂场景 |

**但实际影响比上述表格更大**，因为 KG 可以提供：
1. **精确的方法位置** (文件路径+行号): 当前 sigToFilePath() (FixFlowRunner.java:821-829) 只是把 FQN 转为 `src/main/java/<fqn>.java`，这在多模块项目中会失败
2. **调用链上下文**: FixAgent 目前只看到单个方法的源码，KG 可以提供上游调用者和下游被调用者
3. **参数来源追踪**: 如果参数来自 HTTP 请求体，KG 可以追踪到 Controller -> Service -> this method
4. **类似修复的历史模式**: 如果 KG 包含历史修复记录，可以提供参考

### 7.3 当前 workaround 的可靠性

sigToFilePath() 假设所有源码都在标准 Maven `src/main/java/` 路径下。但：
- **多模块项目**: com.foo.bar.MyClass 可能在 `module-a/src/main/java/com/foo/bar/MyClass.java`，当前逻辑只产生 `src/main/java/com/foo/bar/MyClass.java`，缺少模块前缀
- **多仓库**: FixFlowRunner 有 `resolveMultiRepoPath()` 方法处理多仓库场景，但如果 throwPointSig 在多个仓库中同时存在，会选择错误的仓库

### 7.4 改进建议

1. 实现 KG 搜索集成（调用 `kg_mcp_client.hybridSearch`）：已知 DAG 的 KgSearchNode 已经有了完整的实现（KgSearchNode.java），FixFlowRunner 可以直接复用
2. 在 KG 搜索失败时使用当前 sigToFilePath() 作为 fallback
3. KG 搜索结果应作为 FixAgent prompt 的额外上下文（方法描述、调用链、参数来源）

---

## 8. 测试生成质量: extractTestClassName 硬编码

### 8.1 问题

FixFlowRunner.java:590-595:
```java
private static String extractTestClassName(TestGenInput input) {
    String name = input.getTestMethodName();
    // e.g. "testDoStuffNullPointerException" -> "DoStuffNullPointerExceptionTest"
    // simpler: just use "ReproTest"
    return "ReproTest";
}
```

方法接收 `TestGenInput` 参数但完全忽略，硬编码返回 "ReproTest"。这意味着**所有修复会话生成的测试类都叫 `ReproTest`**。

### 8.2 影响

1. **测试类命名冲突**: 如果同一个模块曾被修复过，旧的 ReproTest.java 会被覆盖
2. **失去语义信息**: 测试名称不能反映被测试的方法或异常类型
3. **测试发现困难**: 用户无法从测试类名判断它测试什么
4. **并行修复冲突**: 同时运行两个修复会话可能写入同一个文件

### 8.3 改进建议

使用有意义的命名：
```java
private static String extractTestClassName(TestGenInput input) {
    // testDoStuffNullPointerException -> DoStuffTest (simple) 
    // 或使用 fix session ID 确保唯一性
    String name = input.getTestMethodName();
    if (name != null && name.startsWith("test")) {
        String base = name.substring(4); // strip "test" prefix
        return base + "Test";
    }
    return "FixTest_" + System.currentTimeMillis();
}
```

至少应包含 session ID 以保证唯一性。

---

## 9. 源码读取: extractMethodBody 的可靠性

### 9.1 当前实现

FixFlowRunner.java:781-815:
```
算法: 逐行扫描 -> 找包含 methodName + "(" + 修饰符的行 -> 花括号计数
```

### 9.2 失效场景

| 场景 | 是否失效 | 说明 |
|------|---------|------|
| 简单方法 | 正常 | 单行签名 + {} 块 |
| 方法含字符串常量 `"foo("` | 可能失效 | `trimmed.contains(methodName + "(")` 不区分代码和字符串 |
| 方法签名跨多行 | **失效** | 当前只检查单行是否包含 `methodName + "("` |
| 匿名内部类 | 可能失效 | 匿名类的 {} 会使 braceCount 增加，但如果匿名类在目标方法内，最终 braceCount 仍会归零，应正常 |
| Lambda 表达式 | 可能失效 | `() -> { ... }` 中的 {} 会增加 braceCount，但如果 Lambda 在方法内，应正常 |
| 方法有注释 `// methodName(` | **失效** | 会把注释行误认为方法签名 |
| 非 public/private/protected 修饰符 | 可能失效 | 检查要求 `contains("public ")` 或 `contains("private ")` 等，package-private 方法（无修饰符）不会被匹配 |
| static 方法无其他修饰符 | **失效** | `static void foo()` 只有 static，如果修饰符检查逻辑要求 public/private/protected，则匹配不到 |
| 构造函数 | 可能失效 | 构造函数无返回类型，但签名可能被误匹配 |

### 9.3 严重性

在实际使用中（Java 后端项目），大部分方法都有明确修饰符，且方法签名一般在单行。但对于大型项目的复杂方法，这个方法提取是**不可靠的**。如果提取失败（返回 `// method not found: xxx`），AI 修复将毫无上下文，生成的修复质量极低。

### 9.4 改进建议

1. **使用 KG 获取方法体**: KgMcpClient 已经有 `loadMethodBodies` 方法（KgSearchNode -> CodeContextNode 中使用），FixFlowRunner 应通过 KG 获取精确的方法体，而非自己解析
2. **使用 JavaParser 库**: 添加 `com.github.javaparser:javaparser-core` 依赖，使用 AST 解析精确提取方法体
3. **至少修复已知问题**:
   - 支持跨行签名检测
   - 支持 package-private 方法
   - 跳过注释行

---

## 10. 架构评分

| 维度 | 分数 | 权重 | 加权 |
|------|------|------|------|
| 流程编排合理性 | 4 | 15% | 0.60 |
| Agent 智能程度 | 2 | 20% | 0.40 |
| 容错策略 | 5 | 10% | 0.50 |
| 代码质量/DRY | 3 | 10% | 0.30 |
| 安全性 | 3 | 15% | 0.45 |
| KG 集成完整性 | 2 | 10% | 0.20 |
| 测试生成质量 | 3 | 10% | 0.30 |
| 源码提取可靠性 | 3 | 10% | 0.30 |
| **总分** | | | **3.05 -> 4.0** |

> 修正为 4.0: 考虑到这是实验性功能的第一版实现，已有基础工作流和 WS 事件推送机制。核心缺陷（无 Agent 循环、无 KG 集成、无安全性审计）需要架构级重构才能达到生产级别。

---

## 11. 优先修复路线图

### 第一阶段：安全底线 (P0, 2-3 天)

| 项目 | 描述 |
|------|------|
| HITL 确认 | Step 6 和 Step 8 之间插入人工确认步骤，展示 diff |
| 认证鉴权 | 验证 FixSession 创建者权限，禁止操作无权限仓库 |
| 测试类隔离 | 修复 `extractTestClassName` 使用唯一命名 |
| Git 安全 | worktree 内禁止 push，使用独立 git author（bot 标识） |

### 第二阶段：Agent 能力升级 (P0, 3-5 天)

| 项目 | 描述 |
|------|------|
| KG 集成 | 实现 FixFlowRunner Step 2 的 KG 搜索 |
| KG 方法体获取 | 替换 extractMethodBody 的字符串解析为 KG 方法体查询 |
| FixAgent 工具循环 | 实现 think-act-observe 循环（编译 -> 测试 -> 修复） |
| Prompt 工程 | 扩展 fix-prompt.txt 至 100+ 行，包含项目上下文和编码规范 |

### 第三阶段：代码质量 (P1, 2-3 天)

| 项目 | 描述 |
|------|------|
| 提取 ChatEventService | 消除 FixChatService / RamChatOrchestrator / FixFlowRunner 的事件构建重复 |
| 容错策略精确化 | 区分"环境失败"和"代码失败"，针对性处理 |
| 降级策略 | 添加环境嗅探步骤，支持"仅分析不修复"模式 |

### 第四阶段：遥测与监控 (P2, 1-2 天)

| 项目 | 描述 |
|------|------|
| 修复成功率追踪 | 记录每次修复的最终状态（SUCCESS/UNVERIFIED/FAILED） |
| Diff 审计日志 | 记录每次 AI 修复的完整 diff |
| 回归检测 | 修复后的测试运行结果持久化存储，支持回溯分析 |

---

## 12. 总结：能否安全用于生产？

**当前状态: 不能。** 以下红线尚未解决：

1. AI 生成的代码未经任何人工审核就 commit 到仓库
2. FixAgent 是单步 LLM 调用，修复成功率极低（预计 < 10%）
3. KG 未集成，方法定位和源码提取不可靠
4. 测试类命名冲突可能导致现有测试被覆盖

**如果要渐进式上线**，建议先从"分析 + 建议"模式开始：
- 只运行 Step 1-3（日志分析 + KG 搜索 + worktree 创建）
- 生成修复建议但不应用（输出到前端，用户手动应用）
- HITL 是必须的，不能跳过

一旦"分析 + 建议"模式验证了分析准确性，再逐步启用自动修复（严格人工审核控制）。
