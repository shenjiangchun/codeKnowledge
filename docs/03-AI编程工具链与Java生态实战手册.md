# AI 编程工具链与 Java 生态实战手册

> 面向 Java 后端工程师（有 Python 经验，已在使用 Claude Code + ECC）
> 编写日期：2026 年 7 月
> 数据来源：GitHub API、Hacker News、Reddit、ArXiv、中文技术社区等 6 份调研报告

---

## 第 1 章：AI 编程助手深度对比 -- 你已经用了 Claude Code，还需要什么？

### 1.1 五大工具能力对比

你已经在用 Claude Code 了，这是 2026 年市场份额排名第一的主力编程工具。但开发者平均使用 **2.4-3.1** 个 AI 工具，单一工具覆盖不了所有场景。下面是五大工具的一页纸对比：

| 维度 | **Claude Code** | **Cursor** | **GitHub Copilot** | **Windsurf** | **Codex CLI** |
|------|:---:|:---:|:---:|:---:|:---:|
| 形态 | 终端 CLI | AI-Native IDE | IDE 插件 | AI-Native IDE | 终端 CLI |
| 主力工具份额 | **28%** (+7pts) | 24% (+2pts) | 17% (-4pts) | 5% (-1pt) | 11% (+3pts) |
| 价格/月 | Pro $20 | Pro $20 | Pro $10 | Pro $15 | 用量定价 |
| SWE-bench | 80%+ | -- | -- | -- | 高 |
| 核心优势 | 项目级推理、Hooks、MCP | Tab补全、多文件编辑 | IDE覆盖面最广 | 跨会话记忆 | OpenAI 模型前沿 |
| 致命短板 | 终端学习曲线、无 JetBrains | 锁死在 Cursor 的 VS Code | Agent 模式弱 | 社区小，被收购后不确定 | MCP 生态不如 Claude Code |

### 1.2 不可替代的优势与致命短板

**Claude Code** -- 你的主力工具。不可替代的优势：项目级自主规划执行、200K-1M token 上下文窗口、最成熟的 Hooks + MCP 生态、Sub-agents 机制。当你需要跨 50 个文件重构一个模块时，这是唯一能先做计划再系统执行的工具。致命短板：终端界面学习曲线、无固定免费版、Team 版 $150/人/月偏贵。

**Cursor** -- 最成熟的 AI-Native IDE。不可替代的优势：代码库索引和理解能力、Composer 多文件编辑、Tab 补全体验行业公认第一。你写 Java 时，Cursor 理解 Spring 的 Bean 依赖关系，补全不只是"猜下一个词"而是"理解上下文"。致命短板：你必须从 IntelliJ IDEA 迁移到 Cursor 的 VS Code 分支，这对习惯 IDEA 快捷键的 Java 开发者是真实成本。

**GitHub Copilot** -- 最广 IDE 覆盖。如果你坚持用 IntelliJ IDEA 写 Java，Copilot 是唯一深度支持 JetBrains 全家桶的选项。致命短板：从 2025 年 67% 垄断份额跌到 2026 年 17%，代码库上下文理解弱于 Cursor 和 Claude Code，Agent 模式起步较晚。

**Windsurf** -- 跨会话记忆独特价值。Cascade Agent 能记住你的编码偏好（命名风格、设计模式选择），但这个优势正被 Cursor 追赶。2025 年多次易主后被 Cognition 收购，路线图不稳定。

**Codex CLI** -- OpenAI 模型前沿优势。如果你更信任 GPT 系列模型，这是自然选择。但 MCP 生态不如 Claude Code 成熟。

### 1.3 为什么专业开发者的标配是"Cursor + Claude Code"

这不是营销话术，而是 2026 年社区自发形成的共识：

- **Cursor 做日常**：写 CRUD、修 bug、补测试、Code Review -- 在 IDE 内完成
- **Claude Code 做大活**：模块重构、跨语言迁移、技术栈升级 -- 在终端完成
- **成本平衡**：两个 Pro 版加起来 $40/月，比 Devin 的不可预测按量收费可控得多

### 1.4 Cursor 年收入 $100M 到 $2B -- 14 个月的信号

Cursor 从 2025 年 1 月的 $100M 年化收入飙升到 2026 年 2 月的 $2B，创造了史上最快 SaaS 增长记录。这背后不是"又一个 AI 泡沫"，而是开发者用脚投票：
- AI-Native IDE 的体验是插件无法替代的（编辑器内核级优化 vs 插件模式有限的 API 访问）
- 专业开发者愿意为生产力工具付费（$20/月 vs 节省的时间远超这个数）

### 1.5 对你的建议：你已经有 Claude Code，加 Cursor 的 ROI 分析

| 方案 | 月成本 | 覆盖场景 | 推荐度 |
|------|--------|---------|:---:|
| 仅 Claude Code（当前） | $20 | 终端大重构、多文件修改 | 基线 |
| Claude Code + Cursor Pro | $40/月 | + IDE 内联补全、Tab 补全、Agent 模式 | **强烈推荐** |
| Claude Code + Copilot（保持 IntelliJ） | $30/月 | + JetBrains 内 AI 补全 | 如果死守 IDEA 则选此方案 |

**明确推荐：Claude Code + Cursor。** 因为你的 Java 项目（如 `hisi-dev-tool`）同时涉及后端 Java 和前端 TypeScript，Cursor 的多语言项目索引比 IntelliJ + Copilot 的组合更自然。迁移成本是一次性的（1-2 天适应快捷键），ROI 是持续性的。

---

## 第 2 章：Claude Code + ECC 工具包深度挖掘

### 2.1 ECC 230K stars -- 你装了的这个工具包到底有多强？

ECC (Everything Claude Code) 不是框架，是"拿来即用"的增强装备。它包含四个模块：

| 模块 | 做什么 | 对你（Java 后端）的价值 |
|------|--------|------------------------|
| **Skills** | 预定义的 Agent 技能模板 | 代码审查 Skill、测试生成 Skill、重构 Skill |
| **Instincts** | 自动学习你的编码习惯 | 学会你的命名风格、异常处理模式、架构偏好 |
| **Memory** | 跨会话持久化上下文 | 记住你的项目结构、技术栈决策、常用配置 |
| **Security** | 自动安全检查 | 检测 AI 生成的代码中是否有 SQL 注入、XSS、密钥泄露 |

ECC 的核心思路是：**让 AI Agent 从"每次被召唤的临时工"变成"了解你项目的老员工"**。如果你只是 `claude` 然后提需求，你只用了 30% 的潜力；配好 ECC 后才是满血状态。

### 2.2 superpowers 255K stars -- Skills 体系的工业化标准

obra/superpowers 是当前 GitHub 上最火的 AI 编码项目，定义了一套 Skills 系统的标准。"Skill"是一个自包含的、可复用的 Agent 行为定义文件，告诉 Claude Code 在特定场景下应该怎么工作。比如：
- `superpowers:test-driven-development` -- 自动执行 TDD 流程
- `superpowers:systematic-debugging` -- 系统化排查 Bug
- `superpowers:subagent-driven-development` -- 使用子 Agent 并行开发

你不需要自己写这些 Skill，直接引用社区贡献的 Skill 即可。这和 Java 生态的 Maven 依赖管理是一样的逻辑：**不重复造轮子，引用经过验证的组件**。

### 2.3 Claude Code Hooks 实战

Hooks 是 Claude Code 最被低估的能力。它们是生命周期回调函数，让你在 Agent 的工作流中插入自动化逻辑。

**SessionStart Hook -- 自动注入项目上下文**

```yaml
hooks:
  SessionStart:
    - command: "cat CLAUDE.md"
    - command: "git diff --stat HEAD~1"
    - command: "mvn dependency:tree -q 2>/dev/null | head -30"
```

每次启动 Claude Code 会话时自动执行：加载项目上下文、展示最近改动、检查依赖树。省去你每次手动 `请先阅读 CLAUDE.md` 的重复指令。

**PostToolUse Hook -- 自动格式化与验证**

```yaml
hooks:
  PostToolUse:
    - on: "Edit"
      run: "mvn spotless:apply -pl ${file.dir}"
    - on: "Write"
      run: "mvn test -pl ${file.dir} -Dtest=${file.name}"
```

每当你用 Claude Code 编辑或写入 Java 文件后，自动跑格式化、自动跑相关单元测试。这是 Loop Engineering 的具体实践 -- 不止写代码，还要自动验证代码。

**Stop Hook -- 自动生成 commit message**

```yaml
hooks:
  Stop:
    - command: "git diff --staged | head -100"
    - prompt: "基于以上 diff，生成一条 conventional commit 消息"
```

会话结束时自动生成规范的 commit message。对于你的 `hisi-dev-tool` 项目，这意味着提交信息格式一致（`feat:` / `fix:` / `refactor:` 前缀），不再出现"update code"这种无效提交。

### 2.4 Sub-agents 的正确打开方式

Sub-agents 机制让你 spawn 子 Agent 去执行独立任务，然后用主 Agent 整合结果。

**什么时候 spawn 子 Agent：**

1. **独立任务**：一个 Agent 改后端 Java 代码，一个 Agent 同步更新前端 Vue 组件。它们之间没有顺序依赖。
2. **多视角审查**：spawn 3 个子 Agent 分别审查代码安全性、性能、可读性，主 Agent 合并审查意见。
3. **批量测试生成**：给 5 个 Service 类各 spawn 一个 Agent 生成单元测试。

**什么时候不要 spawn：**

1. **共享状态**：改动依赖前一个 Agent 的输出结果。
2. **顺序依赖**：先改接口定义、再改实现类、再改调用方 -- 这是串行任务，子 Agent 并行会导致冲突。
3. **简单修改**：改一个方法的参数名，spawn Agent 的开销比直接改还大。

**实战示例**（针对你的 hisi-dev-tool）：

```text
你: "为 KnowledgeGraphService 和 IncrementalRefreshService 生成单元测试"

Claude Code spawns 2 sub-agents:
  Agent A: 写 KnowledgeGraphService 的单元测试
  Agent B: 写 IncrementalRefreshService 的单元测试

主 Agent 合并两个输出，检查一致性，跑 mvn test 验证
```

### 2.5 MCP 集成：给 Claude Code 装上"手"

MCP (Model Context Protocol) 已从 Anthropic 的实验品变成 Linux 基金会标准。2026 年 7 月数据：每月 SDK 下载量 9700 万，公开 MCP 服务器 60000+。

对于 Java 后端工程师，最有价值的 MCP 服务器：

| MCP 服务器 | 用途 | 你为什么需要 |
|-----------|------|-------------|
| **GitHub MCP** | 读 PR、管理 Issue、搜索代码 | 不用切换浏览器看 PR，直接在 Claude Code 里审查 |
| **PostgreSQL / MySQL MCP** | 查询数据库、检查 schema | 排查 bug 时直接查数据，不用另开 DataGrip |
| **Playwright MCP** | 浏览器自动化 | 自动测试前端页面，截图留证 |
| **参考实现 (文件系统/Git)** | 文件操作、Git 操作 | Anthropic 官方维护，稳定可靠 |
| **Spring Boot Actuator MCP** | 健康检查、metrics、env | 在 Claude Code 里查看应用运行状态 |

**注意**：MCP 安全不能忽视。2026 年社区发现 SessionStart hook 可以被用作跨项目持久化攻击面。建议只安装来源明确（有 GitHub 仓库、有维护者、有合理 stars 数）的 MCP 服务器，不要用野外发现的 MCP 配置。

### 2.6 Claude Code 的争议（你必须知道）

你既然已经在用 Claude Code，这些争议不该回避，了解清楚才能保护自己。

**争议 1：隐写标记请求（HN 2445 分，750 评论）**
2026 年 6 月被发现 Claude Code 在 HTTP 请求中用隐写术标记来源。Anthropic 尚未给出明确回应。应对策略：如果你在处理敏感商业代码，考虑在你的 CI/CD 管道中加入网络请求审计（检查是否有异常 header 或 payload 模式）。

**争议 2：源码泄露（HN 2095 分，1022 评论）**
2026 年 3 月 Claude Code 源码通过 npm map 文件泄露。如果你使用 npm 安装的 Claude Code CLI，需要关注版本更新。应对策略：用 `npm audit` 定期检查依赖安全，关注 Anthropic 的安全公告。

**争议 3：封锁竞争对手 OpenClaw（HN 1349 分，720 评论）**
Claude Code 被发现在检测到 commit message 中提到 "OpenClaw" 时拒绝服务。这说明 Anthropic 在工具层面内置了竞争对手检测逻辑。应对策略：如果你在比较或评估不同工具，不要在 Claude Code 的会话上下文中留下竞争对手相关信息。

**争议 4：用户反映"变笨了"（HN 1085 分，701 评论）**
2026 年社区有持续的声音认为 Claude Code 推理能力下降。可能是模型迭代中的质量波动，也可能是 Anthropic 为了控制成本降低了推理深度。应对策略：关注 Anthropic 的变更日志和版本号，如果发现质量下降，可以考虑在敏感任务上切换到特定版本或换用 Codex CLI 作为备选。

**安全使用 Claude Code 的 Checklist：**

- [ ] 定期审查 `.claude/settings.json` 中的 hooks 配置（有没有恶意 hook？）
- [ ] 只用来源明确的 MCP 服务器
- [ ] 不在包含商业机密的仓库中开启外部遥测
- [ ] 审查 AI 生成的代码，尤其是安全相关代码（认证、加密、SQL）
- [ ] 不要让 Agent 在无人值守下操作生产数据库
- [ ] 定期更新 Claude Code 到最新版本
- [ ] 备选方案就绪：至少有一个非 Claude Code 的工具可用（Codex CLI 或 Cursor）

---

## 第 3 章：Java 生态的 AI 工具全景

### 3.1 通义灵码（阿里，2000 万+下载）

通义灵码是目前国内下载量最大的 AI 编程工具，对 Java/Spring Boot 的适配号称超越 Copilot。从实际社区反馈看：

**优势（对 Java 后端）：**
- **Spring Bean 依赖注入理解**：能正确理解 `@Autowired`、`@Resource`、构造器注入的关系链，不会生成不存在的 Bean
- **MyBatis XML 生成**：根据 Mapper 接口自动生成对应的 XML 映射文件和 SQL，比 Copilot 更懂中国式数据库设计（字段命名、分页习惯）
- **Maven/Gradle 依赖解析**：理解 `pom.xml` 的依赖传递关系，生成代码时不会引用不存在的依赖
- **中文注释和文档**：中文注释质量远好于 Copilot（后者常有翻译腔）

**劣势：**
- Agent 模式不如 Claude Code 成熟（长任务易中断）
- 深度重构能力弱（跨 10+ 文件的重构容易丢失上下文）
- 对非 Spring 的 Java 框架（如 Quarkus、Micronaut）支持差

**最适合你的场景：** 日常 Spring Boot CRUD 开发、MyBatis Mapper 编写、Maven 配置修改。

### 3.2 Trae（字节，600 万+注册）

Trae 的核心卖点是**中文体验第一**，SOLO 模式是杀手锏。

**SOLO 模式是什么：** 你描述一个需求（中文即可），Trae 自动拆解为子任务、编写代码、自测、提交 -- 整个过程一个对话搞定。对前端/全栈原型效率极高，但对 Java 后端的支持目前不如通义灵码。

**对你的价值：** 你的 `hisi-dev-tool` 项目有前端部分（Vue 3），Trae 在做前端交互逻辑和 UI 调整时有明显优势。后端 Java 部分用通义灵码更好。

### 3.3 CodeBuddy（腾讯）

核心壁垒是**微信生态**。小程序开发效率提升 125-290%，公众号后台开发也有专门优化。如果你是做微信相关业务，无脑选 CodeBuddy。如果你不碰微信生态，它对你的价值不如通义灵码和 Trae。

### 3.4 文心快码 Comate（百度）

Multi-Agent 架构 + SPEC 模式，C++ 生态最强。对 Java 后端不是最优选择，但它首创的 SPEC 模式值得关注：先生成规格说明（spec），审核通过后再生成代码，最后验证代码是否符合 spec -- 这是"先设计再实现"的 AI 化实践。

### 3.5 Qoder CN（阿里新发布）

阿里最新发布的 AI IDE，Quest 全流程模式（从需求到上线的一个完整"任务流"），Qwen3-Max 驱动。目前太新，生态不够成熟，建议关注但不主力使用。

### 3.6 国产工具 vs Claude Code：战略决策

| 场景 | 推荐工具 | 理由 |
|------|---------|------|
| 写 Spring Boot CRUD | 通义灵码 | 中文理解好，Spring 生态适配精准 |
| 写 MyBatis Mapper | 通义灵码 | XML 自动生成最靠谱 |
| 写前端 Vue 组件 | Trae | SOLO 模式快速出原型 |
| 写安全敏感代码（认证、加密） | Claude Code + 人工 Review | 深度推理最强，但你还是要自己审 |
| 大重构（30+ 文件） | Claude Code | 国产工具的 Agent 模式做这种活还不稳 |
| 排查 bug（跨模块依赖） | Claude Code | 代码库级理解能力无可替代 |
| 微信小程序开发 | CodeBuddy | 生态壁垒，其他工具做不了 |
| 自动化 CI/CD 脚本 | Claude Code | Hooks + MCP 可以让 AI 自动跑流水线 |

**核心原则：** 中文需求、Java 框架标准场景用国产工具（免费且更懂你的技术栈）；复杂推理、深度重构、Agent 编排用 Claude Code（能力天花板更高）。

### 3.7 Spring AI 框架：是否值得投入？

Spring AI 是 Spring 官方推出的 AI 集成框架，目标是让 Java 开发者用熟悉的 Spring 范式调用 LLM、构建 Agent、管理向量数据库。和 LangChain4j 是直接竞争对手。

**投入建议：** 如果你已经在用 Spring 生态，可以关注但不建议现在就全面投入。理由：
1. 框架尚在快速迭代中（API 不稳定）
2. 社区案例和最佳实践还不够丰富
3. 对于你已经熟悉 Python AI 工具链的情况（你提到有 Python 经验），用 Python 写 Agent 比你用 Java 写方便得多

**务实策略：** Java 后端通过 Spring AI 的轻量级集成（如 `ChatClient` 做简单的 AI 文本处理），复杂的 Agent 工作流用 Python + LangGraph/CrewAI 写微服务，通过 API 调用集成。

---

## 第 4 章：Prompt 工程 2.0 -- 从写 Prompt 到设计系统

### 4.1 传统 Prompt 工程的局限

传统 Prompt 工程是"你写一个 prompt，AI 执行一次"。这种方式有三个致命问题：
1. AI 执行完你不会验证结果（除非手动检查）
2. AI 出错你不会知道（除非结果明显不对）
3. 每次都要重写 prompt（没有复用和迭代机制）

Loop Engineering 是 2026 年 Claude Code 负责人 Boris Cherny 和 Google Cloud 工程总监 Addy Osmani 在同一周内提出的新范式。

### 4.2 Loop Engineering：设计闭环系统

Loop Engineering 的核心思路：**不写 prompt，设计流程。** 流程 = 发现任务 -> 分配执行 -> 检查质量 -> 记录进度 -> 循环。

**实战 1：为你的 Java 项目设计一个"PR 审查 Loop"**

```
Loop: PR Review for hisi-dev-tool
触发条件: git diff main...feature-branch

Step 1: 检查代码风格
  - mvn spotless:check
  - 如果不通过，自动修复并重新检查

Step 2: 安全检查
  - 扫描是否有硬编码密钥
  - 是否有 SQL 注入风险点（字符串拼接 SQL）
  - 是否新增了不安全的依赖

Step 3: 测试覆盖
  - 统计新增/修改代码的测试覆盖率
  - 如果覆盖率低于 80%，标记并要求补充

Step 4: 架构一致性
  - 新增类是否符合包结构约定
  - 是否引入了新的循环依赖

Step 5: 生成审查意见
  - 汇总以上检查结果
  - 输出一条结构化的审查意见
```

这个 Loop 可以用 Claude Code 的 PostToolUse Hook + Shell 脚本实现，每次 commit 前自动触发。

**实战 2：为数据库 Migration 设计一个"安全检查 Loop"**

```
Loop: Migration Safety Check
触发条件: 检测到 src/main/resources/db/migration/ 下的新 SQL 文件

Step 1: 解析 SQL
  - 提取所有 DDL 语句
  - 分类：CREATE / ALTER / DROP

Step 2: 安全检查
  - DROP TABLE / DROP COLUMN -> 严重警告，要求人工确认
  - ALTER TABLE ... ADD COLUMN -> 检查是否有 DEFAULT 值（防止锁表）
  - 新增外键 -> 检查目标表是否存在

Step 3: 性能评估
  - 大表 ALTER -> 警告可能的锁表时间
  - 新建索引 -> 如果是生产库，建议 CONCURRENTLY（PostgreSQL）

Step 4: 输出
  - 安全通过: 自动提交
  - 有风险: 生成风险报告，等待人工确认
```

### 4.3 自然语言 vs JSON Function Calling：2026 年的发现

2026 年 7 月发表在 ArXiv 的论文揭示了一个反直觉的发现：**用自然语言描述工具比用结构化 JSON function calling 准确率高 14.9 个百分点，关键错误减少 93%**。

这对 Java 后端程序员的启示：
- 如果你在构建 Agent 需要调用内部 API，不需要把每个接口都改造成 OpenAPI/JSON Schema。直接用自然语言描述接口的输入输出即可，对小模型效果更好。
- Token 消耗还降低了 25.2%。

### 4.4 Agent-First Tool APIs：六动词协议

传统 CRUD API 是为"人"设计的，对 Agent 不友好。2026 年的研究提出六动词协议：

| 动词 | 含义 | 对应你的 Java 后端 |
|------|------|-------------------|
| **Search** | 语义搜索候选资源 | Elasticsearch / 向量检索 |
| **Lock** | 锁定要操作的资源 | 分布式锁 (Redis/DB) |
| **Preview** | 预览操作结果 | dry-run / 模拟执行 |
| **Execute** | 执行操作 | 实际数据库操作 |
| **Verify** | 验证操作结果 | 查询确认 + 断言 |
| **Recover** | 回滚到操作前状态 | 事务回滚 / 补偿操作 |

在企业生产中，六动词协议的任务成功率达 88%，而传统 CRUD+ReAct 只有 64%。ID 幻觉错误减少 86%。

### 4.5 给 Java 后端程序员的 Prompt 模板库

**模板 1：代码审查**

```text
审查以下 Java 代码变更。按以下维度评估：
1. 安全性：是否有 SQL 注入、XSS、认证绕过风险？
2. 性能：是否有 N+1 查询、不必要的对象创建、大事务？
3. 可读性：命名是否清晰、方法是否过长、嵌套是否过深？
4. Spring 最佳实践：Bean 注入方式是否正确、事务边界是否合理？

对于每个问题，给出具体代码位置和修改建议。
如果代码没有问题，直接说"通过"。
```

**模板 2：重构**

```text
重构 com.huawei.hisi.knowledgegraph.service.IncrementalRefreshService.refresh 方法。
目标：
1. 将方法拆分为不超过 50 行的子方法
2. 提取可复用的逻辑到私有方法
3. 改善异常处理（不要吞掉异常，要有明确的错误传播）
4. 不改变外部行为（所有现有测试必须通过）

步骤：
1. 先分析当前代码结构，列出重构计划
2. 逐步执行重构，每步完成后跑 mvn test
3. 如果测试失败，回滚并告诉我原因
```

**模板 3：写测试**

```text
为 KnowledgeGraphBuilder 类生成单元测试：
- 使用 JUnit 5 + Mockito
- 覆盖所有 public 方法
- mock 所有外部依赖（Neo4j、KgClient）
- 包含边界情况（null 输入、空集合、异常路径）
- 测试方法名使用 given_when_then 模式

先列出测试计划（要测哪些方法、哪些场景），确认后再写代码。
```

**模板 4：排查 Bug**

```text
我在 hisi-dev-tool 项目中遇到一个问题：
现象：调用 /api/diagnosis 接口时，偶尔返回 500，错误日志中有 NullPointerException。
相关类：DiagnosisController、DiagnosisService、KgToolRegistry

请帮我排查：
1. 分析可能产生 NullPointerException 的代码路径
2. 检查 KgToolRegistry 中的工具注册逻辑是否有竞态条件（该项目是多线程环境）
3. 给出修复建议 + 对应的测试用例
```

---

## 第 5 章：2026 年前沿动态 -- 你该关注什么

### 5.1 学术突破

**Agent 安全范式转变 -- "拒答"思路是错的**

2026 年 6 月发表的论文《Agent Safety Is Action Alignment》提出了颠覆性观点：基于"拒答"的安全训练会让 Agent 更不安全。一个在提示注入测试中得分 90% 的"安全"模型，在真实攻击下执行危险操作的概率高达 78%。核心理由：Agent 的伤害不在于它"说了什么"，而在于它"做了什么"。

**对你的影响：** 不要信任"AI 会说'不'"的安全策略。正确的做法是：在行动层面设置最小权限（Agent 只能访问它需要的文件、只能连它需要的数据库、不能执行 `DROP TABLE` 除非你显式批准）。这是你需要在 Claude Code 配置和 CI/CD 管道中实现的。

**形式化验证进入 Agent 主流 -- AxDafny 92.7% 验证成功率**

AxDafny 展示了用 Dafny（一种可数学证明代码正确性的语言）配合 Agent 循环生成可验证代码。直接生成只有 11.6% 的验证成功率，Agent 循环下达到 92.7%。

**对你的影响：** 短期内你的 Java 代码不会改成 Dafny 去写。但趋势表明：未来的 AI 代码生成不会是"写得快就行"，编译器级验证、静态分析、形式化证明会被整合到 Agent 工作流中。你团队中的 SonarQube、SpotBugs、Checkstyle 等静态分析工具的价值只会更高（它们是"便宜的形式化验证"）。

**AGENTS.md 可能没用 -- ICLR 2026 研究**

研究发现给 Agent 提供 AGENTS.md 这样的仓库级上下文文件**对任务成功率没有提升**，反而增加了 20% 以上的推理成本。

**对你的影响：** 不用花大量时间维护 AGENTS.md 试图面面俱到。更好的精力投入是：确保代码结构清晰、类名和方法名准确描述行为、注释解释"为什么"而非"是什么"。一个可读的代码库本身就是最好的上下文。

### 5.2 社区声音

**"两年 Vibe Coding 后我回归手写"（HN 865 分）**

一个 Vibe Coding 两年老玩家宣布回归手写。核心原因：AI 生成的代码短期快但长期是技术债，debug 时间远超手写时间。这呼应了 2026 年的数据：开发者花在审查 AI 代码上的时间（11.4 小时/周）超过了写新代码的时间（9.8 小时/周）。

**对你的启示：** Vibe Coding 适合原型和探索，不适合生产核心逻辑。你当前的"Claude Code 辅助 + 自己审查"模式是正确的。保持 70/30 法则：70% AI 辅助（样板代码、测试、文档），30% 纯人工（架构设计、核心逻辑、安全审查）。

**AI 代码出问题谁背锅？印度程序员被开除事件**

一个印度金融科技公司的程序员，公司鼓励用 AI 写代码，AI 生成的代码搞崩了生产数据库，然后程序员被开除了。关键是：合并代码的经理也是用 AI 做 Code Review 的。

**对你的启示：** AI 生成的代码，出了问题是你背锅，不是 AI。这不是法律意见，而是现实中的组织惯例。所以你必须审代码，不能把 AI 当"免责声明"。在你的团队中，如果引入 AI 工具，明确规定"AI 是工具，人是责任人"。

**成本爆炸：一个月烧了 5 亿美元**

r/LLMDevs 上的真实案例：某客户忘了设 Claude 用量限制，一个月烧了 5 亿美元。

**对你的启示：** API 按量付费不是闹着玩的。如果你用 Claude API（而不是 Pro 订阅），务必设置硬性用量上限。如果你的团队使用，在 API 网关层面设置每日/每月预算上限。GCP/AWS 也都提供类似功能。

### 5.3 工具趋势

**MCP 从实验品变成 Linux 基金会标准**

2025 年 12 月 Anthropic 将 MCP 捐赠给 Linux 基金会。2026 年 7 月，每月 SDK 下载量 9700 万，公开 MCP 服务器 60000+。MCP 正在成为"AI 应用的 USB-C 接口"。

**对你的影响：** 学习 MCP 不算投入过度。未来你团队中的工具（数据库管理、CI/CD、监控、日志分析）都有可能通过 MCP 暴露给 AI Agent。

**Copilot 的陨落和 Claude Code 的崛起**

Copilot 从 67% 垄断份额跌到 17%。不是因为竞争对手做得更好，而是因为 Copilot 的 Agent 模式起步太晚。第一波 AI 编程工具的赢家是"写代码最快的"（Copilot 内联补全），第二波是"理解项目最好的"（Claude Code 代码库级推理）。

**Vibe Coding 正在被 Agentic Engineering 取代**

2025 年的 "Vibe Coding"（随口说需求让 AI 写代码）在 2026 年被 "Agentic Engineering" 取代。区别：Agentic Engineering = 先写 spec + 限定文件范围 + 跑测试 + review diff，然后才让 Agent 执行。

### 5.4 哪些趋势会直接影响你的日常工作？

| 趋势 | 影响等级 | 你应该做什么 |
|------|:---:|------|
| Agent 安全范式转变 | **高** | 检查 Claude Code hooks 和 MCP 配置，确保最小权限 |
| MCP 标准化 | **高** | 开始用 MCP 连接数据库和 CI/CD，节省日常切换工具的时间 |
| Loop Engineering | **中** | 设计 2-3 个自动化 Loop（PR 审查、测试生成、安全扫描） |
| Agentic Engineering | **中** | 从"直接改代码"习惯升级为"先写变更计划，再执行" |
| 形式化验证 | **低** | 关注但无需行动，先在现有静态分析工具上做好 |
| AGENTS.md 无效 | **中** | 不要花时间维护冗长的 AGENTS.md，保持代码自身易读 |

---

## 第 6 章：构建你的个人 AI 开发工作流

### 6.1 当前状态审计

基于你的画像（Java 后端 + Python 经验 + Claude Code + ECC），审计结果：

**做对的地方：**
- 选择了能力天花板最高的终端 Agent（Claude Code）
- 安装了 ECC 工具包（Skills + Memory + Security 是正确方向）
- 用 Claude Code 做项目级操作而非简单补全

**可以优化的地方：**
- ECC 的 Instincts 模块你很可能没有充分使用（让它学会你的 Java 编码风格）
- 你可能缺少一个 AI-Native IDE 做日常开发（建议补 Cursor）
- 你可能没有设定自动化的 Hooks（PreToolUse / PostToolUse / Stop）
- 你可能缺少一个备选方案（万一 Claude Code 出问题或服务不可用）

### 6.2 三层工作流设计

**L1 日常开发：Cursor + Claude Code 内联补全**

```
日常工作流（以写一个新 Service 为例）:
1. 在 Cursor 中打开项目
2. 用中文描述需求："创建一个 DeviceDiagnosisService，支持按 deviceId 查询诊断记录和按时间范围导出 CSV"
3. Cursor Composer 生成 Service 骨架、Controller 接口、单元测试
4. 你审查生成的代码，调整不符合项目风格的部分
5. 用 Claude Code 的 "code-review" Skill 做一次预审查
6. git add + commit（用 Stop Hook 自动生成的 commit message）
```

**L2 代码审查 Loop：自动化质量门**

创建一个 Claude Code Hook 或在 CI/CD 中集成：

```
审查 Loop（每次 git commit 前自动触发）:
1. 安全扫描: grep 硬编码密钥、SQL 注入风险点、不安全依赖
2. 静态分析: mvn spotless:check + mvn pmd:check
3. 测试: mvn test（如果新增代码覆盖率低于 80%，阻止提交）
4. 架构检查: 是否引入了新的类循环依赖（用 jdepend 等工具）
```

**L3 架构决策：Sub-agents 多视角评估**

```
当面对重要技术决策时（如"要不要从 Neo4j 迁移到 PostgreSQL 做知识图谱存储"）:

1. spawn 3 个子 Agent，分别从三个视角评估：
   Agent A（数据工程师视角）：数据模型适配性、查询性能预估、迁移成本
   Agent B（运维视角）：维护成本、备份恢复、监控方案
   Agent C（业务视角）：对现有功能的影响、未来扩展性

2. 主 Agent 汇总三个视角的评估，输出决策矩阵
3. 你基于决策矩阵做最终判断（AI 提供分析，你做决策）
```

### 6.3 成本优化

**当前基础成本：** Claude Pro $20/月 （工具本身）

**扩展方案：**

| 方案 | 新增月成本 | 收益 |
|------|-----------|------|
| + Cursor Pro | $20 | IDE 级 AI 辅助，日常效率提升 2-3x |
| + API 用量（按需） | $0-50 | 复杂任务用更大模型，简单任务用小模型 |
| + GitHub Copilot（如保留 IntelliJ） | $10 | IntelliJ 内 AI 补全 |

**省钱技巧：**

1. **Prompt Caching**：如果你的团队用 Claude API，长 system prompt 和项目上下文会被自动缓存（命中后成本降低 90%）。你不需要做任何事，Anthropic 自动处理。
2. **动态模型路由**：简单任务（生成 getter/setter、写 JavaDoc、格式化）用 Haiku 模型（便宜 10x），复杂任务（重构、代码审查、架构建议）用 Sonnet/Opus。
3. **Token 用量监控**：在 Anthropic Console 中设置月度预算上限，防止意外超支。
4. **MCP 服务器用量**：本地 MCP 服务器不需要 API 费用（如文件系统、本地 Git），尽量优先用本地服务器。

### 6.4 知识管理

**建立你的个人 Skills 库：**

```
~/.claude/skills/
├── java-spring-review.md      # Spring 代码审查 Skill
├── java-test-generate.md      # Java 单元测试生成 Skill
├── maven-dependency-check.md  # Maven 依赖安全检查 Skill
├── git-conventional-commit.md # Conventional Commit 生成 Skill
└── postman-api-test.md        # API 测试用例生成 Skill
```

每个 Skill 文件包含：触发条件、执行步骤、输出格式、成功标准。

**Prompt 模板库：** 把第 4 章的 Prompt 模板保存为 Markdown 文件，放在你的笔记系统或项目文档中。每次用到就复制微调，而不是重写。

**最佳实践文档：** 每当你碰到一个 AI 工具用法的坑或发现一个高效技巧，记录下来。三个月后你会有一份完全个性化的"工具使用手册"，比任何网上教程都适合你自己。

### 6.5 30 天进阶计划

| 天数 | 做什么 | 验证标准 |
|------|--------|---------|
| Day 1 | 安装 Cursor，导入你的 hisi-dev-tool 项目 | Cursor 可以正常索引和补全 Java 代码 |
| Day 2 | 适应 Cursor 快捷键（Ctrl+K 内联编辑、Ctrl+I Composer） | 不用鼠标完成一次方法重构 |
| Day 3 | 配置 Claude Code SessionStart Hook | 启动 Claude Code 时自动加载项目上下文 |
| Day 4 | 配置 PostToolUse Hook（自动格式化 + 自动跑测试） | 每次 Edit 后自动跑 spotless:apply |
| Day 5 | 配置 Stop Hook（自动生成 commit message） | 每次会话结束自动生成 conventional commit |
| Day 6 | 用 Cursor Composer 开发一个新功能 | 完整走完"描述-生成-审查-提交"流程 |
| Day 7 | 用 Claude Code 做一个跨文件重构 | 改动 10+ 文件，验证所有测试通过 |
| Day 8 | 配置 ECC 的 Instincts 模块 | Claude Code 开始学习你的代码风格 |
| Day 9 | 安装一个 MCP 服务器（GitHub MCP） | 在 Claude Code 中查看和评论 PR |
| Day 10 | 安装 PostgreSQL MCP 服务器 | 在 Claude Code 中直接查询数据库 |
| Day 11 | 实现"PR 审查 Loop" | 每次 commit 前自动检查安全、测试、风格 |
| Day 12 | 实现"Migration 安全检查 Loop" | 新建 SQL 迁移文件时自动审查 |
| Day 13 | 写 3 个自定义 Skill | 分别用于 Spring 代码审查、测试生成、Bug 排查 |
| Day 14 | 用 sub-agents 完成一个并行任务 | spawn 2+ 子 Agent 分别处理独立任务 |
| Day 15 | 安装通义灵码（IntelliJ 插件），试用一天 | 对比通义灵码和 Cursor 的 Java 补全体验 |
| Day 16 | 用 Trae 开发一个前端组件 | 体验 SOLO 模式从描述到完成的过程 |
| Day 17 | 对比国产工具 vs Claude Code 的代码质量 | 同一个需求用两种工具完成，评判差异 |
| Day 18 | 设计"数据库 Migration 安全 Loop" | 实现并跑一遍 |
| Day 19 | 建立个人 Skills 库目录 | 保存至少 3 个可复用的 Skill |
| Day 20 | 建立 Prompt 模板库 | 保存至少 5 个日常 Prompt 模板 |
| Day 21 | 设置 Anthropic API 月度预算上限 | 如果用到 API，确保不会超支 |
| Day 22 | 用 sub-agents 做一次多视角技术方案评估 | 至少 3 个子 Agent 从不同角度分析 |
| Day 23 | 审查 Claude Code 的安全配置 | 检查 hooks、MCP 服务器、权限设置 |
| Day 24 | 整合：用 Cursor + Claude Code + 通义灵码完成一个完整功能 | 选择最合适的工具做每个环节 |
| Day 25 | 写一份个人 AI 工具使用 SOP | 记录什么场景用什么工具 |
| Day 26 | 补完 Skill 库中的 Java 测试生成 Skill | 用 TDD 模式：先写测试，再生成实现 |
| Day 27 | 尝试 Spring AI ChatClient 做简单集成 | 在 Java 代码中调用 LLM 完成文本处理 |
| Day 28 | 建立备选方案：测试 Codex CLI | 确保 Claude Code 不可用时你有备选 |
| Day 29 | 全面复盘：哪些工具真正提升了效率，哪些只是新鲜感 | 做出取舍，精简工具链 |
| Day 30 | 整理 30 天的经验，更新团队最佳实践文档 | 产出可分享的文档 |

---

## 附录 A：Java 项目 CLAUDE.md 模板

```markdown
# CLAUDE.md

## 项目概述
- 项目名：hisi-dev-tool
- 技术栈：Java 17, Spring Boot 3.x, MyBatis, Neo4j, Vue 3
- 构建工具：Maven 3.9+

## 代码风格
- 类名：PascalCase
- 方法名：camelCase
- 常量：UPPER_SNAKE_CASE
- 包名：com.huawei.hisi.{module}
- 每行最多 120 字符

## 架构约定
- Controller -> Service -> Repository 三层架构
- Service 层不允许直接操作数据库，必须通过 Repository
- 异常统一使用 BusinessException，由 GlobalExceptionHandler 处理
- DTO 用于 Controller 层，DO 用于 Service 和 Repository 层

## 测试约定
- JUnit 5 + Mockito
- 测试类命名：{ClassName}Test
- 测试方法命名：given{条件}_when{操作}_then{预期}
- 目标覆盖率：80%+

## 禁止事项
- 禁止在 Controller 中写业务逻辑
- 禁止用 System.out.println 打日志（用 SLF4J）
- 禁止硬编码配置项（用 application.properties 或环境变量）
- 禁止吞掉异常不处理

## 常用命令
- 构建: mvn clean package -DskipTests
- 测试: mvn test
- 格式化: mvn spotless:apply
- 启动: mvn spring-boot:run
```

---

## 附录 B：Claude Code Hooks 配置示例（Java/Spring Boot）

```json
{
  "hooks": {
    "SessionStart": [
      {
        "command": "cat CLAUDE.md"
      },
      {
        "command": "git diff --stat $(git merge-base HEAD main) HEAD"
      },
      {
        "command": "mvn dependency:tree -q -Dincludes=org.springframework.boot 2>/dev/null | head -20"
      }
    ],
    "PostToolUse": [
      {
        "matcher": "Edit",
        "hooks": [
          {
            "command": "mvn spotless:apply -q 2>/dev/null || true"
          }
        ]
      },
      {
        "matcher": "Write",
        "hooks": [
          {
            "command": "mvn spotless:apply -q 2>/dev/null || true"
          }
        ]
      }
    ],
    "Stop": [
      {
        "prompt": "基于本次会话的所有变更，生成一条 conventional commit 消息。格式：<type>: <description>。Type 可选：feat, fix, refactor, test, docs, chore。"
      }
    ]
  }
}
```

---

## 附录 C：推荐 MCP 服务器清单（适合 Java 后端）

| MCP 服务器 | GitHub Stars | 用途 | 安装优先级 |
|-----------|:-----------:|------|:---:|
| **Reference Servers** | 87.7K | 文件系统、Git、数据库、搜索等官方参考实现 | 必装 |
| **GitHub MCP** | 31K | 管理 PR、Issue、代码搜索 | 高 |
| **PostgreSQL MCP** | -- | 查询数据库、检查 schema | 高 |
| **Playwright MCP** | 34.3K | 浏览器自动化、前端测试 | 中 |
| **Docker MCP** | -- | 容器管理、部署检查 | 中 |
| **Spring Boot Actuator MCP** | -- | 应用健康检查、metrics 查询 | 中 |

---

## 附录 D：Prompt 模板速查卡

**代码审查：** "审查以下代码变更。按安全性、性能、可读性、Spring 最佳实践四个维度评估。每个问题给出代码位置和修改建议。通过则直接说通过。"

**重构：** "重构 {类名}.{方法名}。拆分为不超过 50 行的子方法，不改变外部行为。先列出重构计划，逐步执行，每步跑测试。"

**写测试：** "为 {类名} 生成 JUnit 5 + Mockito 单元测试。覆盖所有 public 方法，含边界情况和异常路径。方法名用 given_when_then 模式。先列测试计划。"

**排查 Bug：** "问题：{现象}。相关类：{类列表}。请分析可能的根因，给出修复建议和测试用例。"

**生成 SQL：** "根据以下 MyBatis Mapper 接口生成对应的 XML 映射文件。注意：使用参数化查询防 SQL 注入，字段名与数据库列名对应。"

---

> 本手册基于 2026 年 7 月的 6 份调研报告编写，涵盖 GitHub 热门项目、Hacker News/Reddit 社区、前沿学术论文、工具深度评测、中文开发者生态和教程资源。所有数据、案例、争议均来自调研报告，建议定期（每季度）重新审视工具选择和配置。
