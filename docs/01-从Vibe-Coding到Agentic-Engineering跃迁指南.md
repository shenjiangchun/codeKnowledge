# 从 Vibe Coding 到 Agentic Engineering 跃迁指南

> 写给已经在用 Claude Code + ECC 的 Java 后端工程师。不解释基础概念，只谈怎么进阶。

---

## 前言：你已经会 Vibe Coding 了，然后呢？

**Vibe Coding** 和 **Agentic Engineering** 的分界线到底是什么？

Reddit 社区给了一个粗暴但精准的定义：**"Vibe coding is when you don't read the code."**——你不读代码，只靠感觉判断对不对，那就是 Vibe Coding。而 Agentic Engineering 的标志是：**你先写规格说明、限定文件范围、跑测试、审查 diff，然后才让 Agent 执行。分界线不是用不用 AI，而是有没有可审查的计划和验证门槛。**

2026 年的数据很能说明问题：

- **92%** 的美国开发者每天使用 AI 编程工具，**41%** 的全球代码已是 AI 生成的（Dev.to 2026 报告）
- 但只有 **3%** 的开发者"高度信任" AI 输出（Stack Overflow 2026 调查）
- 开发者每周花在**审查 AI 代码上的时间（11.4 小时）已经超过写新代码的时间（9.8 小时）**
- HN 上一篇得分 865 的文章标题是："After two years of vibecoding, I'm back to writing by hand"
- 另一篇 616 分的文章干脆说："The cult of vibe coding is dogfooding run amok"

社区在反思。Vibe Coding 在快速原型上的优势毋庸置疑（Vibe Kanban 作者说先拆解任务再让 Agent 执行，效率比直接提需求高 10 倍），但把"不读代码"当成方法论，带来的技术债、安全漏洞和生产事故正在让整个社区警醒——有人的 AI Agent 删了生产数据库，有人一个月烧了 5 亿美元因为没设用量限制，还有印度金融科技公司的程序员因为 AI 代码搞崩生产环境被开除。

**Vibe Coding 是入门方式。Agentic Engineering 是专业玩法。** 这篇指南就是帮你完成这个跃迁。

---

## 第 1 章：Claude Code 深度用法 -- 你只用到了 30%

如果你日常用的是"打开 Claude Code、描述需求、接受建议、偶尔改几行"，恭喜，你已经会用 Claude Code 了。但你离它的完整能力还差 70%。

### 1.1 Hooks 生命周期自动化 -- 不只是便利，是基础设施

Claude Code 的 Hooks 系统是它和其他 Agent 工具拉开差距的核心功能。它有 3 类钩子：**SessionStart**、**PostToolUse**、**Stop**。大多数开发者只用到了 PostToolUse 做自动格式化，这就像买了一台服务器只用来当闹钟。

**你现在怎么做**：手动执行 `mvn test`，如果失败再让 Claude Code 修复。

**进阶后怎么做**：配置 Stop Hook 自动运行测试套件，PostToolUse Hook 在每次文件编辑后跑对应的单元测试，SessionStart Hook 加载项目上下文。你不再"记得"要跑测试，系统自动保底。

**为什么**：Agentic Engineering 的核心不是让 AI 更聪明，而是**用自动化消除人为遗漏**。Claude Code 的 Hooks 本质是一个可编程的生命周期拦截器，你可以把所有"最佳实践"（格式化、lint、测试、安全检查）编码进这个生命周期，让它们从"你应该做"变成"系统替你做了"。

**具体怎么做**：

第一步，在你的项目 `.claude/settings.json` 中配置 Stop Hook：

```json
{
  "hooks": {
    "Stop": [
      {
        "matcher": "",
        "command": "mvn test -pl $(git diff --name-only HEAD | head -1 | cut -d'/' -f1) --no-transfer-progress 2>&1 | tail -20"
      }
    ]
  }
}
```

第二步，配置 PostToolUse Hook 做增量检查：

```json
{
  "PostToolUse": [
    {
      "matcher": "Edit|Write",
      "command": "git diff --name-only HEAD | grep '\\.java$' | head -5 | xargs -r mvn checkstyle:check -Dcheckstyle.includes="
    }
  ]
}
```

**安全提醒**：Reddit 上 2026 年 5 月有个 +3302 upvotes 的帖子专门讨论了 SessionStart Hook 的安全风险——恶意代码可以藏在 hook 里，下次打开项目自动执行。所以：**只使用项目级 `.claude/settings.json`，不要盲目加载来路不明的全局 Hook 配置。**

### 1.2 Sub-agents 的正确用法

**你现在怎么做**：一个 Claude Code 会话从头干到尾，上下文越塞越长，最后几轮的回复质量肉眼可见地下降。

**进阶后怎么做**：识别可并行任务，spawn 多个 sub-agent 同时处理；识别需要独立上下文的任务，spawn 一个干净的子进程来做，避免主会话上下文污染。

**Sub-agents 什么时候用**（不是用来炫技的）：

| 场景 | 用 sub-agent | 不用 |
|------|------------|------|
| 3 个独立模块的单元测试可以同时写 | 用，并行 | -- |
| 代码审查（需要独立视角） | 用，干净上下文 | -- |
| 一个功能的端到端实现（前后步骤依赖） | -- | 主会话 |
| 简单查一个文件 | -- | 没必要 |

**关键原则**：sub-agent 的核心价值是**上下文隔离 + 并行执行**，不是为了多 Agent 而多 Agent。如果你 spawn 了 sub-agent 但里面的任务依赖主会话的上下文，那就完全用错了。

2026 年学术界的研究也在验证这个方向：MACA 论文证明用结构引导的多 Agent 协调同时提升了 8.42% 性能并减少了 43.19% 的 token 消耗。Sakana AI 用 7B 小模型作 Conductor 调度 GPT-5、Gemini、Claude 等大模型，在编程难题上达到 83.9%。关键是**聪明地调度**，不是堆 Agent 数量。

### 1.3 Skills 体系 -- 可复用的能力单元

**你现在怎么做**：每次开始新功能，都在 Claude Code 里手动描述一遍"我们要用 Spring Boot 3.x + MyBatis + Redis，测试用 JUnit 5 + Mockito"。

**进阶后怎么做**：把这些约定抽象成 Skills，一次定义，永久复用。

参考 superpowers（255K stars）和 ECC（230K stars）的架构思想：Skills 不是 Prompt 模板，而是**带有 decision boundary 的可执行单元**。一个 Skill 定义了：

1. **触发条件**（什么时候激活）
2. **上下文需求**（需要读取哪些文件/配置）
3. **执行约束**（工具 API、检查点、退化策略）
4. **输出格式**（diff、报告、状态码）

对于 Java 项目，你可以设计这些 Skill：

- `java-tdd`：触发后自动走 RED-GREEN-REFACTOR 流程，每个阶段都跑 `mvn test`
- `java-code-review`：检查 NPE、事务边界、SQL 注入、资源泄露
- `java-migration`：数据库迁移 Skill，强制要求生成回滚脚本 + 在测试环境验证

### 1.4 实战：用 Claude Code 管理 Java 项目的完整工作流

以下是一个中等复杂度 Java 后端功能（"给订单服务加一个批量导出到 Excel 的功能"）的完整流程，对比 Vibe Coding 和 Agentic Engineering 的区别：

**Vibe Coding 方式**（你现在可能这么做）：
1. 打开 Claude Code："帮我给 OrderService 加一个导出 Excel 的功能"
2. AI 哗哗写了很多代码
3. 你大概看了一下，跑了一下，好像能工作
4. 提交后，code review 发现忘了处理大量数据时的内存溢出、忘了权限校验、Excel 文件没有清理...

**Agentic Engineering 方式**：

```
1. 定义完成标准（5分钟）
   - 将 criteria 写入 specs/order-export.md
   - 功能需求：按时间范围导出订单到 Excel
   - 非功能需求：支持最多 100K 行数据，内存峰值 <512MB
   - 安全需求：校验当前用户有该订单的查看权限
   - 验证方法：单元测试覆盖核心逻辑 + 集成测试验证 100K 行场景

2. 拆解任务（3分钟）
   Task A: OrderExportService 核心导出逻辑（独立可测试）
   Task B: OrderExportController REST 接口（依赖 A）
   Task C: SXSSFWorkbook 流式写入配置（独立可测试）

3. 分配执行（paralel A + C，然后 B）
   Sub-agent 1 → Task A（TDD 模式）
   Sub-agent 2 → Task C（研究 SXSSFWorkbook 最佳实践）
   主会话 → 等 A 和 C 完成后，执行 Task B

4. 验证（Stop Hook 自动触发）
   - mvn test 全量通过
   - Checkstyle 无新增违规
   - 集成测试验证 100K 行场景内存 <512MB
```

**为什么差这么远**：Vibe Coding 模式下，你把"理解需求"和"写出代码"都委托给了 AI，但 AI 不知道你们的权限模型、不知道你们的性能要求、不知道你们的代码规范。Agentic Engineering 模式下，你负责**定义完成标准**和**设计验证方法**，AI 负责在约束下执行——各做各擅长的事。

### 1.5 常见误区

**误区 1：一次塞太多上下文**。Claude 200K token 的上下文窗口不是让你一口气塞满的。ICLR 2026 的研究表明，仓库级上下文文件（AGENTS.md）对任务成功率没有显著提升，反而增加 20%+ 推理成本。关键信息精准投放比"全塞进去"有效得多。

**误区 2：不设 stopping condition**。Agent 无限循环是教程里反复强调的 Top 1 问题。不管你用不用 sub-agent，总要设 `max_iterations`（一般 20 足够），总要在 prompt 里明确"什么时候认为任务完成"。

**误区 3：把 Claude Code 当万能锤子**。不是所有任务都适合终端 Agent。开发者平均使用 2.4-3.1 个 AI 工具，Cursor 做日常编码 + Claude Code 做大重构已经成为 2026 年最主流的配置。一个任务用对工具比用一个工具硬刚重要。

---

## 第 2 章：Context Engineering -- 让 AI 真正理解你的代码库

### 2.1 为什么 coleam00/context-engineering-intro 能拿到 13K stars？

因为 2026 年的开发者正在经历同一个痛苦：**AI 写代码很快，但经常不贴合项目实际。** 你让它加一个 REST 接口，它给你写了一个用 `@RestController` 的，但你们项目实际上用的是继承 `BaseController` 的另一种方式。问题不出在 AI 能力，出在 AI 不知道你们的约定。

Context Engineering 解决的就是这个问题。它不是"写更好的 prompt"，而是**系统化地决定什么信息在什么时机以什么格式进入 AI 的视野**。

### 2.2 AGENTS.md / CLAUDE.md 怎么写才有用

首先，必须直面 ICLR 2026 的一项刺眼发现：一篇获 Runner-up Best Paper 的研究发现，**给 Agent 提供仓库级上下文文件（如 AGENTS.md）对任务成功率没有提升，反而增加了 20% 以上的推理成本**。

这不是说你别写 CLAUDE.md。这说明：**乱写的 CLAUDE.md 不如不写。**

什么叫乱写？把你项目的 README 复制一份、把技术栈列表写进去、把 pom.xml 的内容抄一遍——这些信息 Claude Code 自己读代码库就知道了。你写的是噪音，不是信号。

**什么该写进 CLAUDE.md**：

1. **强约定**（代码里看不出来但大家都得遵守的）：
   - "所有 Controller 必须继承 `BaseController`，不要直接用 `@RestController`"
   - "数据库查询必须通过 `Mapper` 接口，禁止在 Service 里拼 SQL 字符串"
   - "异常处理统一用 `BusinessException(code, message)`，不要 throw `RuntimeException`"

2. **否定式约束**（AI 容易犯但你们禁止的）：
   - "不要在 controller 层写业务逻辑"
   - "不要使用 Spring Data JPA 的 `@Query` 注解，SQL 必须写在 XML mapper 里"
   - "不要引入新的依赖而不在 plan 中说明理由"

3. **项目特有的模式**（不是通用技术栈，是你项目独有的）：
   - "权限校验用 `@RequirePermission(resource = "...", action = "...")` 注解"
   - "日志用 `log.info("op={}, result={}", op, result)` 格式，不要用字符串拼接"
   - "我们的 Response 格式是 `Result<T>(code, message, data)`，不要自创"

**什么不该写**：

- 技术栈列表（AI 自己看 pom.xml/build.gradle）
- 数据库表结构（AI 自己看 migration 文件或实体类）
- 项目目录结构（AI 自己看文件树）
- 通用最佳实践（"请写单元测试"——这应该放在 Hook 里自动强制，不是放在文档里祈求 AI 照做）

**核心原则**：CLAUDE.md 只放"AI 自己读代码读不出来的东西"。每加一条都问自己：Claude Code 通读这个代码库后，能不能自己推断出来？能推断出来的就不要写。

### 2.3 代码库索引策略：什么该放 context、什么不该放

Coleam00 的方法论核心是分层上下文：

| 层级 | 内容 | 加载时机 | Java 项目示例 |
|------|------|---------|--------------|
| **L0 常驻** | 项目强约定 | 每次会话 | CLAUDE.md |
| **L1 任务相关** | 当前任务涉及的模块 | 任务开始时 | 订单模块的 Service/Controller/Mapper 路径 |
| **L2 按需检索** | 关联模块、公共组件 | 分析到需要时 | 公共的 `BaseController`、`UserContext` |
| **L3 不放入** | 无关模块、历史代码 | 从不 | 支付模块的代码（当前任务只涉及订单） |

对于 Java 项目有一个天然优势：**强类型 + 明确接口 = 天然的上下文边界**。你的 Service 接口定义了什么方法、Mapper 定义了什么查询，AI 读接口就能理解模块能力，不需要把整个实现类 2000 行都塞进去。

**实践建议**：

- 在 CLAUDE.md 里写清楚模块边界："订单模块在 `order/` 下，对外只暴露 `OrderService` 接口；支付模块在 `payment/` 下，对外只暴露 `PaymentService` 接口。两个模块通过 `OrderPaymentFacade` 解耦。"
- 当你需要 AI 修改订单模块时，只加载：`OrderService.java`（接口）+ `OrderController.java`（入口）+ 相关 `Mapper.xml`（数据访问）+ 实体类（数据结构）。不要加载整个模块。
- 当你发现 AI 反复误解某个约定时，把那一条加进 CLAUDE.md 的否定式约束里。

### 2.4 Java 项目的上下文工程实践

**模块边界**：Maven/Gradle 多模块项目是天然的上下文隔离器。Claude Code 在一个模块内工作时，只需要知道"其他模块提供什么接口（返回什么、抛什么异常）"，不需要知道实现细节。在 CLAUDE.md 里用模块依赖图代替代码堆砌。

**命名规范**：如果你的项目里 `*Service` 通过 `*ServiceImpl` 实现，`*Mapper` 通过 XML 映射，这些 AI 自己能发现。但如果你有非标准约定（如 `*BizService` 表示有事务、`*QueryService` 表示只读），一定要写进 CLAUDE.md。

**依赖关系如何影响 AI 理解**：ICLR 2026 的"马太效应"研究发现，AI 对主流框架（Spring Boot、MyBatis、Redis）的成功率显著高于小众技术。这意味着：如果你的项目用了某些小众的内部框架或自研组件，**必须**在 CLAUDE.md 里描述其行为和约束，否则 AI 会把它当通用组件瞎猜。

**实践案例**：假设你的项目有一个自研的 `@EventDriven` 注解做领域事件发布，CDD（Context-Driven Development）方法要求在 CLAUDE.md 里写：

```
## 领域事件约定
- 事件发布使用 @EventDriven(topic = "...") 注解，不是 Spring 的 ApplicationEventPublisher
- 事件消费实现 EventHandler<T> 接口，框架会自动扫描并注册
- 事件处理默认异步，重试 3 次，每次间隔 exponential backoff
- 不要在 @Transactional 方法内发布事件（框架会自动在事务提交后发布）
```

这比扔给 AI 一份 EventDriven 框架的源码文档有效得多——AI 需要的是"使用约束"，不是"实现原理"。

---

## 第 3 章：Loop Engineering -- 下一代"Prompt 工程"

### 3.1 两个大厂技术领导人同一周提出的概念

2026 年 7 月，钛媒体和 InfoQ 同时报道了一个现象：Claude Code 负责人 **Boris Cherny** 和 Google Cloud 工程总监 **Addy Osmani** 在同一周内各自独立提出了"Loop Engineering"（循环工程）的概念。

核心思想一句话：**不要手写 Prompt，要设计一个自动发现任务 -> 分配执行 -> 检查质量 -> 记录进度的闭环系统。**

这和学术界的方向高度吻合。2026 年的 Agent 架构研究正在从 ReAct 单循环升级到更精细的控制流：

- **ReflAct**（目标状态反射）：Agent 不再只管"下一步做什么"，而是持续问自己"我离目标还有多远"。在 ALFWorld 上成功率 93.3%，比原始 ReAct 提升 27.7%。
- **SR-SAM**（自我调节规划）：用 8B 小模型达到 120-355B 大模型的效果，同时推理 token 减少 25-95%，核心是让 Agent 知道"什么时候值得深度思考、什么时候快速决策就行"。
- **APEX**（自主策略探索）：构建带里程碑的 DAG，当某个方向走不通时自动开拓新方向。

这三篇论文的共同结论是：**Agent 不是越大越好，而是越知道什么时候该深度思考越好。** Loop Engineering 就是在应用层面实现这个理念。

### 3.2 从"写一个 prompt"到"设计一个闭环系统"

**你现在怎么做**："帮我 review 这段代码" -> AI 给你一堆建议 -> 你挑几处改一下 -> 完事。

**进阶后怎么做**：设计一个代码审查 Loop，每次 PR 自动触发，流程固化。

回顾学术研究中的发现——`Iterative Critique-and-Routing Controller` 将多 Agent 协调建模为 MDP：每轮评估草稿、决定是否继续、选择下一个 Agent。这正是 Loop Engineering 的思想内核：不是一次性的 prompt-response，而是一个状态机。

### 3.3 实战：为 Java 后端项目设计一个代码审查 Loop

```
Loop: java-code-review
触发条件：git push 或 /code-review 命令
输入：本次 PR 的 diff

┌─────────────────────────────────────────────┐
│ Step 1: 分类变更                            │
│   新增功能 / Bug修复 / 重构 / 配置变更       │
│   验证：分类结果与 commit message 一致        │
│   → 如果不一致，标记为需要澄清               │
├─────────────────────────────────────────────┤
│ Step 2: 按类别分配检查清单                   │
│   新增功能 → 检查清单 A（NPE、事务、权限）   │
│   Bug修复 → 检查清单 B（是否有回归测试）      │
│   重构 → 检查清单 C（行为是否等价）           │
│   验证：每条检查项有通过/不通过/不适用判断    │
├─────────────────────────────────────────────┤
│ Step 3: 执行检查（并行 sub-agent）            │
│   Agent 1: NPE 风险扫描                      │
│   Agent 2: 事务边界检查                      │
│   Agent 3: 权限校验检查                      │
│   Agent 4: SQL 注入检查                      │
│   验证：每个 Agent 输出结构化结果             │
├─────────────────────────────────────────────┤
│ Step 4: 汇总并生成审查报告                    │
│   CRITICAL: 必须修改                         │
│   HIGH: 强烈建议修改                         │
│   MEDIUM: 建议修改                            │
│   LOW: 可选优化                              │
│   验证：每条建议都有代码位置引用              │
├─────────────────────────────────────────────┤
│ Step 5: 回写结果                             │
│   - 将报告写入 PR comment                    │
│   - 将新的模式记录到项目知识库                │
│   - 更新 CLAUDE.md（如果发现新的约定违规）    │
│   验证：PR comment 可见、格式可读             │
└─────────────────────────────────────────────┘
```

这个 Loop 的关键设计决策：

1. **每个 Step 都有验证标准**，不是"建议"而是"检查点"。Agent 在每步必须确认上一步输出达标，否则停在原地修正。
2. **Step 5 的"更新 CLAUDE.md"尤其重要**——它让 Loop 有记忆。第一次发现有人用 `@RestController` 而不是 `BaseController`，只标记为 CRITICAL。同样的错误出现 3 次，Loop 自动建议在 CLAUDE.md 里加一条否定约束。
3. **token 成本可控**：每个 sub-agent 只看 diff 而不是全量代码，上下文控制在 5000-10000 token 内。

### 3.4 实战：为数据库迁移设计一个安全检查 Loop

Java 后端工程师最怕的是什么？数据库迁移搞崩生产环境。Flyway 或 Liquibase 写个 SQL 就能改表结构，但谁来检查这个 SQL 是安全的？

```
Loop: sql-migration-safety
触发条件：检测到新的 migration 文件（Flyway: V*__.sql）
输入：新建的 SQL migration 文件

┌──────────────────────────────────────────────┐
│ Step 1: SQL 解析与分类                        │
│   - 是 DDL 还是 DML？                         │
│   - 涉及的表是否有大量数据？                   │
│   验证：正确识别了所有操作的 SQL 类型          │
├──────────────────────────────────────────────┤
│ Step 2: 安全检查清单                           │
│   DDL 检查项：                                │
│   - ALTER TABLE 是否会导致表锁定（MySQL 5.x）  │
│   - ADD COLUMN 是否加了默认值（避免长时间锁）   │
│   - DROP COLUMN 是否有备份计划？               │
│   - 新索引是否会与现有索引冗余？               │
│   DML 检查项：                                 │
│   - UPDATE/DELETE 是否有 WHERE 子句？          │
│   - 是否限制了影响行数？                        │
│   - 大表操作是否分批次执行？                    │
│   验证：每条检查项有判定结果                    │
├──────────────────────────────────────────────┤
│ Step 3: 回滚检查                               │
│   - 是否提供了对应的回滚 SQL？                 │
│   - 回滚 SQL 是否真的能回到原状态？            │
│   验证：回滚 SQL 语法正确且逻辑可逆            │
├──────────────────────────────────────────────┤
│ Step 4: 生成迁移检查报告                       │
│   - PASS: 所有检查通过                        │
│   - WARN: 有风险但可接受（附建议）             │
│   - BLOCK: 存在安全隐患，阻止合并              │
│   验证：所有 BLOCK 项都有明确的修改建议         │
└──────────────────────────────────────────────┘
```

这个 Loop 强制要求**回滚脚本**——这是很多人（包括 AI）容易遗漏的。更重要的是，它把"DBA 脑子里记着的注意事项"外化为自动化的检查流程，不会因为某天 DBA 休假就出事故。

### 3.5 Loop Engineering 的设计原则

从 2026 年的研究中可以提炼出 4 条设计原则：

1. **每个 Step 都要有退出条件**（否则 Agent 会无限循环——这是教程里排第一的避坑项）
2. **验证标准必须可自动判定**（"代码看起来没问题"不是验证标准；"`mvn test` 全量通过"是）
3. **Loop 要有记忆**（用文件或项目知识库持久化关键决策，防止跨会话丢失）
4. **成本与深度成正比**（简单任务用小模型/少步骤，复杂任务才启用完整 Loop——SR-SAM 的核心洞察）

---

## 第 4 章：Skills 与 Harness -- 建立你自己的 AI 工具链

### 4.1 superpowers (255K stars) 和 ECC (230K stars) 的架构思想

这两个项目合计近 50 万 star，它们不是在比谁的 prompt 更好，而是在解决同一个问题：**如何让 AI 编程助手的表现可预测、可复用、可组合。**

**superpowers 的核心理念**：将开发活动分解为标准化的 Skills。每个 Skill 对应一个开发阶段（规划、实现、测试、审查、部署），AI 在不同阶段切换不同的 Skill。这不是"写 prompt"，而是**定义 AI 的行为协议**——输入什么、输出什么、在什么条件下暂停等人类确认。

**ECC 的核心理念**：Harness（装备/增强）是在 Claude Code 等 Agent 之上的一套可插拔增强层。它包含：
- **Skills**：领域能力（代码审查、TDD、重构）
- **Instincts**：自动学习的行为模式（从你的项目中学习偏好）
- **Memory**：跨会话的记忆系统（解决 Agent "忘记之前决定" 的问题）
- **Security**：安全检查门禁（阻止危险操作自动执行）

两者共通的设计哲学：**Skills 不是 prompt 集合，是带有决策逻辑的可执行单元。**

### 4.2 如何设计可复用的 Skills

如果你要给自己的 Java 项目设计 Skills，按以下结构组织：

```
skills/
├── java-tdd/
│   ├── skill.md          # 触发条件、执行流程（人类读）
│   ├── prompts/
│   │   ├── red-phase.md  # "先写一个会失败的测试"
│   │   ├── green-phase.md # "用最小代码让测试通过"
│   │   └── refactor-phase.md # "消除重复，改善结构"
│   └── checks/
│       └── coverage.sh   # 跑 jacoco 检查覆盖率 >=80%
├── java-code-review/
│   ├── skill.md
│   └── prompts/
│       ├── npe-scan.md
│       ├── transaction-check.md
│       ├── sqli-check.md
│       └── resource-leak.md
└── java-migration-safety/
    ├── skill.md
    └── prompts/
        ├── ddl-check.md
        ├── dml-check.md
        └── rollback-check.md
```

**设计要点**：

1. **单一职责**：一个 Skill 只做一件事。`java-code-review` 不是一个 Skill，它下面有 4 个子 Skill 分别检查不同维度。
2. **明确的激活条件**：Skill 不在不需要的时候激活。`java-migration-safety` 只在检测到 Flyway/Liquibase migration 文件时激活。
3. **可组合**：`java-tdd` 可以和 `java-code-review` 组合使用——TDD 流程完成后自动触发代码审查。
4. **有退化策略**：如果某项检查无法自动完成（比如需要人类判断权限模型是否正确），Skill 应该明确标记为"需要人工审核"，而不是瞎猜一个结果。

### 4.3 Skills 的"依赖注入"：如何让 Skill 适配不同项目

一个设计良好的 Skill 不应该硬编码项目特定信息。参考 ECC 的做法：

**项目特定配置放入 settings.json**：

```json
{
  "skills": {
    "java-code-review.npe-scan": {
      "nullAnnotations": ["@NonNull", "javax.annotation.Nonnull"],
      "frameworkNullSafety": "spring_null_safety",
      "excludePatterns": ["*Test.java", "*DTO.java"]
    }
  }
}
```

**Skill 本身保持通用**：

```
你是 NPE 风险扫描器。
扫描规则：识别所有可能产生 NullPointerException 的代码模式。
项目特定的 @NonNull 注解列表从配置中读取：{{npe-scan.nullAnnotations}}
```

这样你的 `java-code-review` Skill 在项目 A（用 lombok `@NonNull`）和项目 B（用 `javax.annotation.Nonnull`）都能工作，只改配置文件。

### 4.4 从社区借鉴的 5 个最佳 Skill 设计模式

**模式 1：检查清单模式（来自医疗/航空领域的启示）**

不要问 AI"这段代码有什么问题？"——太开放，结果不稳定。给 AI 一份具体的检查清单，逐项打勾/打叉。

```
检查清单：Java Service 层 NPE 风险
[ ] 所有方法参数是否都有 @NonNull/@Nullable 标注？
[ ] Optional 返回值是否正确处理了 orElseThrow？
[ ] Stream API 中的 filter().findFirst() 是否有空值处理？
[ ] 从 Map 中 get() 的结果是否判空？
```

**模式 2：双人审查模式（来自 crewAI 的角色驱动思想）**

同一个 diff，用两个 agent 各审一遍，然后合并结果：
- Agent A（"悲观的"）：假设所有边缘情况都会发生
- Agent B（"务实的"）：只关注生产环境中实际可能发生的问题
- 两者结果取并集 -> 人类决定哪些是真实问题

**模式 3：快慢道模式（来自 SR-SAM 的研究发现）**

不是所有代码都需要深度审查。文件变更超过 200 行才启用完整的 4-agent 并行审查；50 行以下只做 NPE + SQL 注入检查。

**模式 4：反馈学习模式（来自 ECC 的 Instincts 概念）**

每次代码审查的结果不是用完就扔。如果同一个模式被标记为 CRITICAL 超过 3 次，自动建议在 CLAUDE.md 里加一条约束规则。这是 Agent 越用越聪明的关键——不是模型变聪明了，是你的上下文在积累。

**模式 5：门禁模式（来自 CI/CD 的思想）**

某些检查必须通过才能继续：
- 数据库迁移 -> sql-migration-safety 必须 PASS 才能合并
- 对外 API 变更 -> api-compatibility-check 必须 PASS 才能发布
- 不通过 -> Agent 自动生成修复建议 -> 人工确认 -> 重跑

---

## 第 5 章：从 Vibe Coder 到 Agentic Engineer 的 4 个思维转变

### 转变 1：从"我想要 X"到"我定义 X 的完成标准和验证方法"

**Vibe Coder 的提问方式**："帮我加一个导出订单到 Excel 的功能。"

**Agentic Engineer 的提问方式**：
```
任务：订单导出到 Excel
完成标准：
  - 支持按时间范围过滤
  - 支持最多 100K 行数据
  - 内存峰值 < 512MB（用 SXSSFWorkbook 流式写入）
  - 包含用户权限校验
验证方法：
  - OrderExportServiceTest 覆盖上述场景
  - 集成测试验证 100K 行内存占用
  - mvn test 全量通过
```

**为什么**：Vibe Coding 模式下，你赌 AI 能猜到你的需求。Agentic Engineering 模式下，你花 30% 的时间定义"什么算完成"，然后让 AI 花 70% 的时间在明确的边界内高效执行。

学术界的多个评测研究也在支持这一点：**Position: Coding Benchmarks Are Misaligned with Agentic Software Engineering**（2026.06）尖锐批评现有评测（SWE-bench、HumanEval）是为"前 Agent 时代"设计的——它们只测单次代码补全，而真实 Agentic Engineering 是多轮交互的软件工程过程。你定义完成标准的能力，比 AI 写代码的速度重要得多。

### 转变 2：从"AI 写代码我检查"到"我设计流程，AI 执行流程中的每一步"

**Vibe Coder 的工作模式**：
```
我 → AI（帮我写 X） → 我（看一遍，改几处） → 提交
```

**Agentic Engineer 的工作模式**：
```
我 → 设计流程（5 个步骤 + 每个步骤的验证标准 + 退出条件）
AI → 执行步骤 1 → 自检 → 通过 → 执行步骤 2 → 自检 → 失败（修正）→ 重试 → 通过 → 步骤 3...
我 → 只在 AI 自检失败 3 次以上时介入
```

**为什么**：2026 年 7 月的论文 `The Remarkable Effectiveness of Providing AI Agents with Natural Language Tools` 发现，用自然语言描述工具比结构化 JSON function calling 准确率高 14.9 个百分点，关键错误减少 93%。这验证了一个洞察：**AI 更需要清晰的人类意图，而不是复杂的工程适配。** 设计流程就是最清晰的意图表达。

### 转变 3：从"一个 prompt 搞定"到"拆解 -> 分配 -> 验证 -> 合并"

**Vibe Coder**：一个巨大的 prompt 塞给 AI，期望它一次搞定。

**Agentic Engineer**：
1. **拆解**：把一个复杂功能拆成 3-5 个独立可测试的子任务
2. **分配**：独立任务 spawn sub-agent 并行执行，有依赖的任务串行
3. **验证**：每个子任务有自己的测试，独立通过后才进入下一步
4. **合并**：所有子任务完成后，跑集成测试确保拼起来也能跑

**为什么要拆**：Vibe Kanban（27K stars）的核心理念就是"先拆解任务，再让 AI Agent 执行，效率比直接提需求高得多"。拆解之后，每个子任务的上下文是独立的、可控的，AI 不会在跨模块的复杂场景中迷失方向。

### 转变 4：从"工具使用者"到"工具链设计者"

**Vibe Coder**：选一个工具（Claude Code/Cursor/Windsurf），尽量用好它。

**Agentic Engineer**：设计一套工具链，让不同工具在不同环节各司其职。

2026 年开发者平均使用 2.4-3.1 个 AI 工具，"Cursor + Claude Code"是公认的黄金组合。但这还不够，Agentic Engineer 的工具链应该包含：

```
开发阶段         工具               用途
────────────────────────────────────────────
需求分析         你                 定义完成标准和验证方法
任务拆解         Vibe Kanban        可视化任务依赖
日常编码         Cursor             Tab 补全 + 多文件编辑
复杂重构         Claude Code        终端 Agent 模式
代码审查 Loop    Sub-agents         并行检查（NPE/事务/SQL/安全）
CI/CD 门禁       Stop Hook          mvn test + checkstyle + jacoco
知识积累         CLAUDE.md          新发现的约定、常见陷阱
```

**关键**：工具链不是你一次性设计好就完事了。MACA 论文的核心洞察——"自动学习不同任务需要什么样的协作结构"——同样适用于工具链：随着你对项目理解的加深，工具链也应该不断进化。

---

## 附录 A：Claude Code Hooks 配置模板（Java 项目用）

```json
{
  "hooks": {
    "SessionStart": [
      {
        "matcher": "",
        "command": "echo '=== Build Status ===' && mvn compile -q --no-transfer-progress 2>&1 | tail -5 && echo '=== Recent Changes ===' && git log --oneline -5"
      }
    ],
    "PostToolUse": [
      {
        "matcher": "Edit|Write",
        "command": "CHANGED=$(git diff --name-only HEAD | grep '\\.java$' | head -5); if [ -n \"$CHANGED\" ]; then echo \"$CHANGED\" | xargs -r mvn checkstyle:check -Dcheckstyle.includes= 2>&1 | tail -10; fi",
        "onError": "warn"
      }
    ],
    "Stop": [
      {
        "matcher": "",
        "command": "echo '=== Running Tests ===' && mvn test --no-transfer-progress 2>&1 | grep -E '(Tests run|BUILD|FAILURE|ERROR)' | tail -20"
      }
    ]
  },
  "skills": {
    "java-code-review.npe-scan": {
      "nullAnnotations": ["@NonNull", "@Nullable", "javax.annotation.Nonnull", "javax.annotation.Nullable"],
      "frameworkNullSafety": "spring_null_safety",
      "excludePatterns": ["*Test.java", "*DTO.java", "*Config.java"]
    },
    "java-migration-safety": {
      "lockDetection": "enabled",
      "requireRollbackScript": true,
      "maxAffectedRowsWarning": 100000
    }
  }
}
```

## 附录 B：推荐 Skills 清单及用途

| Skill | 用途 | 来源/参考 |
|-------|------|----------|
| **java-tdd** | RED-GREEN-REFACTOR 流程自动化 | superpowers/subagent-driven-development |
| **java-code-review** | 并行审查 NPE、事务边界、SQL 注入、资源泄露 | ECC/code-review |
| **java-migration-safety** | Flyway/Liquibase 迁移安全检查 + 回滚验证 | Loop Engineering 实践 |
| **java-api-compat** | 对外 API 变更兼容性检查 | CI/CD 门禁模式 |
| **context-optimizer** | 自动判断当前任务需要加载哪些文件，排除无关模块 | coleam00/context-engineering-intro |
| **learn-from-review** | 从代码审查结果中提取模式，自动建议更新 CLAUDE.md | ECC/Instincts |
| **cost-guard** | 跟踪 token 用量，简单任务自动路由到小模型 | SR-SAM 快慢道思想 |
| **multi-model-verify** | 同一 diff 用不同模型/角色审 2 遍，合并结果 | 双人审查模式 |

## 附录 C：核心参考资源

- **superpowers** (255K stars): Agentic Skills 框架与方法论 - https://github.com/obra/superpowers
- **ECC** (230K stars): Agent Harness 增强系统 - https://github.com/affaan-m/ECC
- **context-engineering-intro** (13K stars): 上下文工程入门 - https://github.com/coleam00/context-engineering-intro
- **claude-code-best-practice** (62K stars): Vibe Coding 到 Agentic Engineering 最佳实践 - https://github.com/shanraisshan/claude-code-best-practice
- **vibe-kanban** (27K stars): Agent 任务管理工具 - https://github.com/BloopAI/vibe-kanban
- **awesome-agentic-patterns** (4.8K stars): Agentic 设计模式大全 - https://github.com/nibzard/awesome-agentic-patterns
- **awesome-agentic-ai-zh** (4.5K stars): 中文 Agentic AI 学习路线图 - https://github.com/WenyuChiou/awesome-agentic-ai-zh
- 微软《AI Agents for Beginners》: 15 节免费课程 - https://github.com/microsoft/ai-agents-for-beginners

---

> **最后**：这篇指南的核心只有一句话——Vibe Coding 和 Agentic Engineering 的分界线不是用不用 AI，而是**你有没有在 AI 之前定义"完成"的标准**。当你开始花 30% 的时间设计流程、定义验证方法、配置自动检查，你就已经开始跃迁了。
