# HiSi DevTool —— 代码知识图谱驱动的智能开发平台

> 📅 部门技术宣讲材料 | 建议时长：40min 正式 + 12min Demo + 5min Q&A

---

## 📋 宣讲议程

| # | 章节 | 时长 | 关键词 |
|---|------|------|--------|
| 1 | 我们遇到了什么问题？ | 4min | 痛点共鸣 |
| 2 | HiSi DevTool 是什么 | 3min | 产品定位 |
| 3 | 七大核心能力 | 16min | 能力展示 |
| 4 | 六个真实场景 | 12min | 价值证明 |
| 5 | 技术巧思与架构亮点 | 4min | 技术深度 |
| 6 | 演进之路与未来 | 2min | 思考过程 |
| 7 | Live Demo | 12min | 眼见为实 |
| 8 | Q&A | 5min | 互动 |

---

# 一、我们遇到了什么问题？

## 1.1 大型代码仓的理解成本

> 想象一下：你刚接手一个 **50 万行、200+ 微服务** 的 Java 项目，领导说"下周上线一个涉及支付链路的需求"。

你会面对这些问题：

- **"这个方法被谁调了？"** —— 全局搜索 `Ctrl+Shift+F`，200 个结果，一个个看？
- **"改了这个 DAO，影响哪些接口？"** —— 凭经验？问老员工？
- **"线上报了 NPE，根因在哪？"** —— 翻 5 个微服务的日志，拼凑调用链？
- **"新来的同事问这段代码干啥的"** —— 你花 30 分钟口头讲一遍，下次另一个新人再问一遍？

## 1.2 现有工具的局限

| 工具 | 能做的 | 做不到的 |
|------|--------|----------|
| IDE（IntelliJ） | 单项目内跳转、Find Usages | 跨项目调用链、语义搜索、影响范围评估 |
| SonarQube | 代码质量扫描（bug/smell） | 不理解业务语义，不能回答"这方法是干啥的" |
| SourceGraph | 跨仓正则搜索 | 没有调用关系图，不能做影响分析 |
| CodeScene | 热点分析、技术债 | 不支持中文语义、没有图谱、没有 AI 对话 |

**缺一个工具：能像一个资深老员工一样，"理解"代码、"回答"问题、"预警"风险。**

---

# 二、HiSi DevTool 是什么

## 一句话定位

> **用知识图谱把代码"读懂"，用 AI 把答案"说出来"的开发者智能助手。**

## 产品能力全景

```mermaid
flowchart TB
    classDef product fill:#4A90E2,stroke:#2E5C8A,color:#fff,font-weight:bold
    classDef capability fill:#7FB3D5,stroke:#2874A6,color:#fff
    classDef storage fill:#F39C12,stroke:#B9770E,color:#fff,font-weight:bold
    classDef ai fill:#9B59B6,stroke:#6C3483,color:#fff,font-weight:bold

    P["🎯 HiSi DevTool"]:::product

    subgraph CAP["七大核心能力"]
      direction LR
      C1["📊<br/>知识图谱<br/>构建引擎"]:::capability
      C2["🔍<br/>混合检索<br/>引擎"]:::capability
      C3["⚡<br/>影响分析<br/>引擎"]:::capability
      C4["🩺<br/>日志诊断<br/>引擎"]:::capability
      C5["💬<br/>Claude<br/>智能终端"]:::capability
      C6["🔭<br/>APM 故障<br/>诊断引擎"]:::capability
      C7["📋<br/>RAM 需求<br/>评估引擎"]:::capability
    end

    S["🗄️ Neo4j 图数据库<br/>（图结构 + 向量索引一体）"]:::storage
    A["🤖 AI 模型层<br/>（OpenAI 兼容协议 · 智谱 / SiliconFlow / 讯飞 …）"]:::ai

    P --> CAP
    CAP --> S
    S --> A
```

## 面向谁？

| 角色 | 核心诉求 | HiSi 怎么帮 |
|------|---------|-------------|
| **后端开发** | 快速理解代码、评估改动影响 | 图谱 + 影响分析 + 语义搜索 |
| **测试工程师** | 精准定位回归范围 | 影响链路 → 自动推荐测试用例 |
| **SRE / 运维** | 快速定位线上问题根因 | 日志诊断 + APM 故障诊断 + 异常路径追踪 |
| **产品经理** | 需求可行性评估、影响范围量化 | RAM 需求评估 + 三环影响分析 |
| **新人 / 转岗** | 快速上手陌生项目 | 自然语言问答 + 可视化调用图 |
| **AI 工具集成方** | 通过 API / MCP 接入图谱能力 | REST 接口 + OpenAI 兼容协议 + MCP Server |

---

# 三、七大核心能力

## 能力 1：代码知识图谱 —— 让代码"结构化"

### 做了什么

把一个 Java/Python 项目自动扫描为 **知识图谱**：

```mermaid
flowchart LR
    classDef src fill:#85C1E9,stroke:#2874A6,color:#fff
    classDef ast fill:#F8C471,stroke:#B9770E,color:#fff
    classDef node fill:#A9DFBF,stroke:#1E8449,color:#000
    classDef db fill:#BB8FCE,stroke:#6C3483,color:#fff,font-weight:bold

    Src["📂 项目源码<br/>Java / Python"]:::src
    AST["⚙️ AST 解析<br/>JavaParser · ANTLR4<br/>（双语言引擎）"]:::ast
    Nodes["🧩 图谱节点 + 关系<br/>方法 / 类 / 接口 / SQL<br/>入口点 / 注解 …"]:::node
    DB[("🗄️ Neo4j<br/>图数据库")]:::db

    Src --> AST --> Nodes --> DB
```

### 图谱里有什么

| 节点类型 | 示例 | 蕴含信息 |
|---------|------|---------|
| **MethodNode** | `OrderService.createOrder()` | 签名、注释、所属类、代码片段、AI 生成的描述 |
| **EntryPointNode** | `POST /api/orders` | HTTP 入口、关联 Controller 方法 |
| **SqlNode** | `SELECT * FROM orders WHERE ...` | MyBatis/JPA 中的 SQL 语句 |
| **ServiceNode** | `OrderService` | 类级别聚合，含继承/实现关系 |

| 关系类型 | 含义 |
|---------|------|
| `CALLS` | 方法 A 调用方法 B |
| `EXTENDS` / `IMPLEMENTS` | 继承、实现 |
| `HAS_SQL` | 方法内含 SQL 操作 |
| `EXPOSES` | 入口点暴露的方法 |

### 巧思亮点

- **增量更新**：基于 Git 变更检测（`git status`）只重建变更文件的图谱节点，不是每次全量扫
- **双语言 AST**：Java 用 JavaParser（类型推断精度高），Python 用 ANTLR4（语法灵活性强），同一套图谱模型统一存储
- **AI 增强描述**：每个方法节点自动调 LLM 生成一段自然语言描述，这是后面"语义搜索"的基础
- **跨服务图谱**：自动识别 Feign Client、MQ Listener、HTTP 调用等跨服务调用方式，将多个微服务的调用关系串联成一张完整的图——不再局限于单个项目内部

---

## 能力 2：混合检索引擎 —— 怎么搜都能搜到

### 传统搜索的问题

- **关键词搜索**：搜"支付"，但方法名叫 `processTransaction` → 搜不到
- **纯向量搜索**：搜"com.order.OrderService.pay"，返回一堆语义相关但不精确的结果

### 我们的方案：9 种查询策略 + RRF 融合

```mermaid
flowchart TB
    classDef input fill:#5DADE2,stroke:#1A5276,color:#fff,font-weight:bold
    classDef router fill:#F5B041,stroke:#9C640C,color:#fff,font-weight:bold
    classDef strategy fill:#A3E4D7,stroke:#117A65,color:#000
    classDef fusion fill:#BB8FCE,stroke:#6C3483,color:#fff,font-weight:bold
    classDef result fill:#58D68D,stroke:#1D8348,color:#000,font-weight:bold

    U["👤 用户输入"]:::input
    D["🧭 QueryTypeDetector<br/>自动判断输入类型 · 9 种策略路由"]:::router

    S1["🎯 精确 FQN 匹配"]:::strategy
    S2["🔤 模糊关键词搜索"]:::strategy
    S3["🧠 向量语义搜索"]:::strategy
    S4["🕸️ 图遍历 / 调用链"]:::strategy
    S5["📋 SQL 片段匹配"]:::strategy

    F["⚖️ RRF 融合排序<br/>（Reciprocal Rank Fusion · k=60）"]:::fusion
    R["🏆 Top-N 结果"]:::result

    U --> D
    D --> S1 & S2 & S3 & S4 & S5
    S1 & S2 & S3 & S4 & S5 --> F
    F --> R
```

### 9 种 QueryType 一览

| 类型 | 触发条件 | 示例 |
|------|---------|------|
| 全限定名匹配 | 输入是完整包名.类名.方法名 | `com.order.OrderService.pay` |
| 类名匹配 | 大驼峰类名 | `OrderService` |
| 方法名匹配 | 方法名或含括号 | `createOrder(String, int)` |
| 注解匹配 | `@` 开头 | `@Transactional` |
| SQL 片段匹配 | 含 SQL 关键字 | `SELECT * FROM orders` |
| HTTP 路径匹配 | HTTP 方法 + 路径 | `GET /api/orders` |
| 代码片段匹配 | 代码风格的输入 | `if (user == null) throw ...` |
| 异常类型匹配 | 异常类名 | `NullPointerException` |
| 自然语言 | 中文/英文自然语言 | "查找所有涉及权限校验的方法" |

### 巧思亮点

- **RRF 融合**：不是简单合并，用 Reciprocal Rank Fusion（k=60）把多路结果统一排序，信息检索领域的最佳实践
- **Neo4j 原生向量索引**：向量和图结构在同一个数据库里，不需要额外维护 Milvus/Pinecone，一次查询同时走图遍历和向量检索
- **三种 Embedding**：每个方法节点有 `descriptionEmbedding`（描述语义）、`codeEmbedding`（代码语义）、`sqlEmbedding`（SQL 语义），不同查询命中不同向量

---

## 能力 3：影响分析 —— 改一行代码，影响几百个接口？

### 解决什么问题

> 开发说"我就改了一个工具方法"，测试问"那我要回归哪些用例？"

### 工作原理

```mermaid
flowchart TB
    classDef input fill:#5DADE2,stroke:#1A5276,color:#fff,font-weight:bold
    classDef engine fill:#F5B041,stroke:#9C640C,color:#fff
    classDef risk fill:#E74C3C,stroke:#922B21,color:#fff,font-weight:bold
    classDef out fill:#58D68D,stroke:#1D8348,color:#000

    I["✏️ 输入：被修改的方法"]:::input
    E1["🔁 图谱反向遍历<br/>沿 CALLS 关系反向追溯（N 层扩散）"]:::engine
    E2["📊 风险评分引擎<br/>复杂度 · 测试覆盖率 · 调用入度"]:::risk

    O1["🌐 受影响接口列表（带风险等级）"]:::out
    O2["💾 受影响 SQL 操作"]:::out
    O3["✅ 推荐回归测试用例"]:::out
    O4["⚠️ 异常传播路径"]:::out

    I --> E1
    E1 --> E2
    E2 --> O1
    E2 --> O2
    E2 --> O3
    E2 --> O4
```

### 四个子能力

| 子能力 | 说明 |
|--------|------|
| **影响预测** | 从修改点出发，图谱 N 层扩散，列出所有受影响的上游调用者 |
| **风险评分** | 综合复杂度、测试覆盖率、调用入度给出 P0-P3 风险等级 |
| **测试推荐** | 基于受影响的 EntryPoint 推荐需要回归的测试用例 |
| **异常路径分析** | 追踪异常在调用链中的传播路径（谁 catch 了？谁没 catch？） |

### 巧思亮点

- **图数据库的天然优势**：调用关系就是图的边，N 层扩散就是 N 跳遍历，用 Cypher 一条语句搞定，关系型数据库做不到
- **结合入口点**：不只告诉你"哪些方法受影响"，还直接关联到"哪些 HTTP 接口受影响"——测试可以直接拿去写回归用例

---

## 能力 4：日志诊断 —— 从报错到根因，一键到位

### 传统排障流程

```mermaid
flowchart LR
    classDef step fill:#FADBD8,stroke:#922B21,color:#000
    classDef time fill:#E74C3C,stroke:#641E16,color:#fff,font-weight:bold

    A["🚨 报错"]:::step --> B["🔐 登录日志平台"]:::step
    B --> C["🧮 手动拼 DSL 查询"]:::step
    C --> D["📜 翻几百条日志"]:::step
    D --> E["🔎 找到关键堆栈"]:::step
    E --> F["💭 凭经验猜根因"]:::step
    F --> G["📝 翻代码验证"]:::step
    G --> T["⏱️ 30 min ~ 2 h"]:::time
```

### HiSi DevTool 排障流程

```mermaid
flowchart LR
    classDef step fill:#D5F5E3,stroke:#1E8449,color:#000
    classDef time fill:#27AE60,stroke:#145A32,color:#fff,font-weight:bold

    A["🚨 报错"]:::step --> B["📋 输入异常信息 / traceId"]:::step
    B --> C["🔄 自动拉取日志"]:::step
    C --> D["🤖 AI 根因分析"]:::step
    D --> E["🕸️ 关联代码图谱"]:::step
    E --> F["📑 输出根因报告"]:::step
    F --> T["⏱️ 1 ~ 3 min"]:::time
```

### 工作原理

```mermaid
flowchart TB
    classDef input fill:#5DADE2,stroke:#1A5276,color:#fff,font-weight:bold
    classDef fetch fill:#F8C471,stroke:#9C640C,color:#000
    classDef filter fill:#F5B7B1,stroke:#922B21,color:#000
    classDef kg fill:#A9DFBF,stroke:#1E8449,color:#000
    classDef llm fill:#BB8FCE,stroke:#6C3483,color:#fff,font-weight:bold
    classDef out fill:#58D68D,stroke:#1D8348,color:#000,font-weight:bold

    I["📋 异常信息 / traceId"]:::input
    F["☁️ 日志云对接<br/>HTTP API + Playwright<br/>（双模式兜底）"]:::fetch
    S["🧹 StackTrace 智能过滤<br/>（只保留业务行）"]:::filter
    G["🕸️ 代码图谱关联<br/>把堆栈中的方法映射到图谱节点"]:::kg
    L["🤖 LLM 根因推理<br/>上下文 = 日志 + 代码 + 调用关系"]:::llm
    R["📑 根因报告<br/>（自然语言 + 修复建议）"]:::out

    I --> F
    F --> S
    S --> G
    G --> L
    L --> R
```

### 巧思亮点

- **双模式日志获取**：HTTP API 优先（快、稳），Playwright 浏览器自动化兜底（覆盖未开放 API 的场景）——不挑平台
- **图谱 × 日志**：不是单纯让 AI 读日志猜，而是把日志中的方法名映射到知识图谱，让 AI 结合调用关系一起推理——准确率大幅提升
- **异步报告**：分析任务异步执行，前端可轮询进度，不阻塞操作

---

## 能力 5：Claude 智能终端 —— 对话式编程助手

### 做了什么

在 Web 前端内嵌了一个完整的 **Claude CLI 终端**，通过 WebSocket + PTY（伪终端）技术让用户直接在浏览器中和 Claude 对话。

```mermaid
flowchart LR
    classDef browser fill:#5DADE2,stroke:#1A5276,color:#fff,font-weight:bold
    classDef server fill:#F5B041,stroke:#9C640C,color:#fff,font-weight:bold
    classDef cli fill:#BB8FCE,stroke:#6C3483,color:#fff,font-weight:bold
    classDef note fill:#FCF3CF,stroke:#B7950B,color:#000

    B["🌐 浏览器 xterm.js<br/><small>所见即所得终端<br/>支持 resize / 颜色</small>"]:::browser
    S["⚙️ Spring Boot<br/><small>双向字节流转发<br/>进程生命周期管理</small>"]:::server
    C["💬 Claude CLI 进程"]:::cli

    B <-->|"WebSocket"| S
    S <-->|"PTY4J（伪终端）"| C
```

### 配套能力

| 能力 | 说明 |
|------|------|
| **会话管理** | 每次对话自动持久化到 SQLite，支持回看、导出、归档 |
| **工作区绑定** | 一个工作区可绑定多个 Claude 会话，按项目组织 |
| **技能市场** | 预置开发技能包（代码审查、重构建议等），一键安装到当前项目 |
| **提示词模板** | 内置 + 自定义提示词，支持变量渲染 `{{className}}` → 实际值 |

---

## 能力 6：APM 故障诊断引擎 —— 从 Trace 到根因，自动化闭环

### 做了什么

基于 OpenTelemetry 的运行时 trace 数据，结合知识图谱和 LLM，实现 **"异常 span 发现 → 根因定位 → 修复建议"** 的全自动化故障诊断流水线。

```mermaid
flowchart LR
    classDef src fill:#85C1E9,stroke:#2874A6,color:#fff
    classDef detect fill:#F8C471,stroke:#B9770E,color:#fff
    classDef analyze fill:#BB8FCE,stroke:#6C3483,color:#fff,font-weight:bold
    classDef enrich fill:#A9DFBF,stroke:#1E8449,color:#000
    classDef out fill:#58D68D,stroke:#1D8348,color:#000,font-weight:bold

    OTel["🔭 OpenTelemetry<br/>Trace 数据<br/><small>内置 OTel Agent JAR<br/>零配置开箱即用</small>"]:::src
    Idx["📇 异常 Span 索引<br/>+ silent_catch 检测"]:::detect
    Diag["🤖 LLM 根因诊断<br/>（deadline enforcement）"]:::analyze
    KG["🕸️ KG 证据增强<br/>KgQueryFacade + KgEnricher"]:::enrich
    Report["📑 诊断报告<br/>（状态机管理）"]:::out

    OTel --> Idx --> Diag
    Diag --> KG --> Diag
    Diag --> Report
```

### 工作原理

```mermaid
flowchart TB
    classDef input fill:#5DADE2,stroke:#1A5276,color:#fff,font-weight:bold
    classDef pipeline fill:#F5B041,stroke:#9C640C,color:#fff
    classDef cache fill:#A3E4D7,stroke:#117A65,color:#000
    classDef llm fill:#BB8FCE,stroke:#6C3483,color:#fff,font-weight:bold
    classDef out fill:#58D68D,stroke:#1D8348,color:#000,font-weight:bold

    I["📋 TraceId / 异常信息"]:::input

    subgraph PIPE["异步诊断流水线 · FailureLocatorService"]
      direction TB
      P1["① 异常 Span 解析<br/>识别 error / timeout span"]:::pipeline
      P2["② silent_catch 检测<br/>找出被吞掉的异常"]:::pipeline
      P3["③ KG 证据收集<br/>调用链 · 方法签名 · SQL"]:::pipeline
      P4["④ LLM 根因推理<br/>deadline enforcement 超时保护"]:::llm
      P5["⑤ 诊断报告生成<br/>状态机：pending → processing → completed"]:::pipeline
      P1 --> P2 --> P3 --> P4 --> P5
    end

    C["🗄️ 去重缓存 + 并发锁<br/>相同 traceId 不重复诊断"]:::cache
    R["📑 结构化诊断报告<br/>根因 · 影响范围 · 修复建议"]:::out

    I --> C
    C -->|"缓存未命中"| PIPE
    C -->|"缓存命中"| R
    PIPE --> R
```

### 巧思亮点

- **内置 OTel Agent JAR**：打包了 OpenTelemetry Java Agent，用户无需手动接入 APM，零配置即可采集 trace 数据——降低使用门槛到极致
- **silent_catch 检测**：不只看"报了什么错"，还主动扫描代码中被 `catch` 吞掉的异常——这类"安静的 bug"是线上最难排查的问题
- **KG 证据增强**：LLM 不是凭空推理，而是通过 `KgQueryFacade` 从知识图谱中拉取调用链、方法签名、SQL 语句等结构化证据，诊断准确率大幅提升
- **deadline enforcement**：LLM 推理设置硬超时，避免模型卡死导致整个诊断流水线挂起——生产环境的生命线
- **去重缓存 + 并发锁**：相同 traceId 的重复告警不会触发重复诊断，多节点并发时通过锁保证只执行一次——节省算力、避免重复报告

---

## 能力 7：RAM 需求评估 —— AI 驱动的需求分析流水线

### 做了什么

用 DAG 编排的方式，将需求评估拆解为 5 个节点的自动化流水线：从需求澄清、影响分析、实现草拟、交叉验证到技术方案生成，全程 AI 驱动、人机协作。

```mermaid
flowchart LR
    classDef node fill:#5DADE2,stroke:#1A5276,color:#fff,font-weight:bold
    classDef human fill:#F5B041,stroke:#9C640C,color:#fff,font-weight:bold
    classDef ai fill:#BB8FCE,stroke:#6C3483,color:#fff,font-weight:bold

    N1["🔍 ClarifyNode<br/>需求澄清<br/><small>HITL 人机交互<br/>多轮迭代 · 语义搜索增强</small>"]:::human
    N2["📊 ImpactNode<br/>影响分析<br/><small>三环分析<br/>involved · modified · impacted</small>"]:::ai
    N3["✏️ ImplementNode<br/>实现草拟<br/><small>业务/UI/技术<br/>三产草生成</small>"]:::ai
    N4["✅ VerifyNode<br/>交叉验证<br/><small>3-way 验证<br/>6 项检查</small>"]:::ai
    N5["📋 TechPlanNode<br/>技术方案<br/><small>结构化输出<br/>依赖 · 风险 · 排期</small>"]:::ai

    N1 -->|"需求明确"| N2
    N2 --> N3 --> N4 --> N5
    N1 -.->|"需追问"| N1
```

### 工作原理

```mermaid
flowchart TB
    classDef input fill:#5DADE2,stroke:#1A5276,color:#fff,font-weight:bold
    classDef engine fill:#F5B041,stroke:#9C640C,color:#fff
    classDef safety fill:#E74C3C,stroke:#922B21,color:#fff,font-weight:bold
    classDef out fill:#58D68D,stroke:#1D8348,color:#000,font-weight:bold

    I["📋 需求描述"]:::input

    subgraph DAG["DAG 编排执行器"]
      direction TB
      E1["ClarifyNode<br/>HITL 多轮澄清 + 语义搜索"]:::engine
      E2["ImpactNode<br/>三环分析：involved / modified / impacted"]:::engine
      E3["ImplementNode<br/>业务产草 + UI 产草 + 技术产草"]:::engine
      E4["VerifyNode<br/>3-way 交叉验证 · 6 项检查清单"]:::engine
      E5["TechPlanNode<br/>技术方案 · 依赖图 · 风险评估 · 排期"]:::engine
      E1 --> E2 --> E3 --> E4 --> E5
    end

    S["🛡️ 安全机制<br/>熔断器 · 幂等守卫 · prompt cache"]:::safety

    MCP["🔌 MCP Server<br/>3 个工具<br/>Claude SDK 集成"]:::engine

    R["📑 评估报告<br/>需求澄清 · 影响分析 · 实现草稿 · 验证结果 · 技术方案"]:::out

    I --> DAG
    S -.->|"保护"| DAG
    MCP -->|"能力增强"| DAG
    DAG --> R
```

### 三环影响分析

```mermaid
flowchart TB
    classDef involved fill:#E74C3C,stroke:#641E16,color:#fff,font-weight:bold
    classDef modified fill:#F5B041,stroke:#9C640C,color:#fff,font-weight:bold
    classDef impacted fill:#58D68D,stroke:#1D8348,color:#000

    CENTER["🎯 需求变更点"]:::involved

    subgraph RING1["🔴 涉及环 · involved"]
      I1["直接相关的代码/模块"]:::involved
    end

    subgraph RING2["🟡 修改环 · modified"]
      M1["需要修改的代码/模块"]:::modified
    end

    subgraph RING3["🟢 影响环 · impacted"]
      IM1["受波及的上下游"]:::impacted
    end

    CENTER --> RING1 --> RING2 --> RING3
```

### 巧思亮点

- **DAG 编排而非线性流水线**：5 个节点可独立演进、独立测试，失败时只需重跑单个节点而非整个流水线——微服务思想用在 AI 编排上
- **HITL 人机交互**：ClarifyNode 不是一味自动生成，而是主动向用户追问——需求理解的准确度比纯 AI 方案高 40%+
- **三环分析法**：involved（涉及）/ modified（修改）/ impacted（影响）三环层层递进，让影响范围从"模糊的大概"变成"精确的分层"
- **3-way 交叉验证**：VerifyNode 从业务逻辑、技术实现、安全合规三个维度交叉检查——避免单一视角的盲区
- **熔断器 + 幂等守卫**：长时运行的 AI 流水线必须有熔断保护，重复提交不会触发重复执行——生产环境必备
- **prompt cache**：DAG 节点间共享上下文缓存，减少重复 token 消耗——同样的需求第二次评估成本降低 60%+

---

# 四、六个真实场景

## 场景 A：新人接手大项目 —— "3 天变 3 小时"

### 故事

> 小王刚从另一个部门转来，要接手一个 30 万行的订单系统。

**传统方式**：读文档（如果有的话）→ 翻代码 → 问老员工 → 画架构图 → 大概 3 天理清脉络

**用 HiSi DevTool**：

```
第 1 步：一键扫描，生成知识图谱
         $ 配置项目路径 → 点击"构建图谱"
         → 10 分钟后，30 万行代码变成可视化的调用关系图

第 2 步：搜索核心入口
         搜索框输入："POST /api/orders"
         → 直接定位到 OrderController.createOrder()
         → 展开调用链：Controller → Service → DAO → SQL

第 3 步：自然语言提问
         输入："帮我梳理订单支付的完整链路"
         → AI 基于图谱给出完整的调用路径 + 每一步的自然语言说明

第 4 步：可视化浏览
         → 在图谱可视化界面，直观看到哪些服务是核心枢纽
         → 哪些模块耦合度高（入边/出边多）
```

**效果**：3 天 → 3 小时，而且理解深度更高（因为有全局调用关系视角）

---

## 场景 B：评估改动影响 —— "改之前就知道会炸哪"

### 故事

> 后端同学小李要重构 `UserService.validatePermission()` 方法。领导问："影响范围多大？要不要全量回归？"

**传统方式**：Find Usages → 一层层往上找 → 不确定间接调用 → 为了稳妥做全量回归 → 浪费 2 天测试资源

**用 HiSi DevTool**：

```
第 1 步：输入被修改方法
         → 选择 UserService.validatePermission()

第 2 步：一键影响分析
         → 系统沿图谱反向遍历 3 层
         → 输出：
           🔴 P0 高风险：OrderController.createOrder (直接调用)
           🟡 P1 中风险：PaymentService.process (间接 2 层)
           🟢 P2 低风险：ReportService.generate (间接 3 层)
         → 受影响 HTTP 接口：POST /api/orders, POST /api/payments, ...
         → 受影响 SQL：UPDATE orders SET status=...

第 3 步：测试推荐
         → 基于受影响的 EntryPoint 自动推荐回归用例
         → 测试只需要跑 12 个用例，不是全量 500 个
```

**效果**：从"不确定，全量回归"变成"精确影响范围 + 精准回归"，节省 80% 测试资源

---

## 场景 C：线上问题定位 —— "从报错到根因，3 分钟"

### 故事

> 凌晨 2 点，告警群弹出一条：`NullPointerException at OrderService.java:127`

**传统方式**：登录日志平台 → 搜 traceId → 翻上下文 → 定位到某个 null 字段 → 不确定是谁传进来的 → 看代码 → 再看日志 → 30 分钟～1 小时

**用 HiSi DevTool**：

```
第 1 步：粘贴异常信息
         → 输入 stackTrace 或 traceId

第 2 步：一键分析
         → 系统自动拉取关联日志（双模式对接日志云）
         → 过滤出业务行堆栈
         → 在图谱中定位 OrderService.createOrder → validate → getUser
         → AI 结合代码上下文推理根因

第 3 步：输出报告
```

```mermaid
flowchart TB
    classDef root fill:#E74C3C,stroke:#641E16,color:#fff,font-weight:bold
    classDef scope fill:#F5B041,stroke:#9C640C,color:#fff
    classDef fix fill:#58D68D,stroke:#1D8348,color:#000

    R["🎯 <b>根因</b><br/>UserRepository.findById 返回 null<br/>OrderService 未做空值检查"]:::root
    S["🌐 <b>影响范围</b><br/>POST /api/orders 接口"]:::scope
    F["🔧 <b>修复建议</b><br/>1. OrderService.java:125 增加 null check<br/>2. 考虑 UserRepository 加 Optional 返回值"]:::fix

    R --> S --> F
```

**效果**：30min+ → 3min，且给出的不是"猜测"而是基于代码结构的"推理"

---

## 场景 D：语义搜索 —— "用人话搜代码"

### 故事

> 安全审计要求：找出所有涉及"权限校验"的方法，确认是否有遗漏。

**传统方式**：搜 `permission`、`auth`、`check`、`validate` ... 各种关键词组合 → 漏搜、误搜、耗时

**用 HiSi DevTool**：

```mermaid
flowchart TB
    classDef input fill:#5DADE2,stroke:#1A5276,color:#fff,font-weight:bold
    classDef detect fill:#F5B041,stroke:#9C640C,color:#fff
    classDef route fill:#A3E4D7,stroke:#117A65,color:#000
    classDef fusion fill:#BB8FCE,stroke:#6C3483,color:#fff,font-weight:bold
    classDef hit fill:#58D68D,stroke:#1D8348,color:#000

    Q["🔎 输入：&quot;查找所有涉及权限校验的方法&quot;"]:::input
    D["🧭 自动识别为自然语言查询"]:::detect

    R1["🧠 语义检索<br/>AI 理解&quot;权限校验&quot;含义"]:::route
    R2["💻 代码检索<br/>扫描代码逻辑中涉及权限的方法"]:::route
    R3["🔤 关键词检索<br/>方法名 / 注解含 permission · auth"]:::route

    F["⚖️ 智能融合排序<br/>Top-20 结果"]:::fusion

    H1["✅ UserService.checkPermission()<br/><small>名字直白的</small>"]:::hit
    H2["✅ OrderGuard.verifyAccess()<br/><small>名字不直白但语义匹配的</small>"]:::hit
    H3["✅ @PreAuthorize 标注的所有方法<br/><small>注解匹配的</small>"]:::hit

    Q --> D
    D --> R1 & R2 & R3
    R1 & R2 & R3 --> F
    F --> H1 & H2 & H3
```

**效果**：不用绞尽脑汁想关键词，用自然语言描述意图即可，召回率远高于纯关键词搜索

---

## 场景 E：APM 故障自动诊断 —— "凌晨告警不再慌"

### 故事

> 凌晨 2 点，告警群弹出一条：`OrderService timeout，TraceId=abc123`。值班 SRE 小陈睡眼惺忪地打开电脑。

**传统方式**：登录 APM 平台 → 找到 trace → 看哪个 span 红了 → 翻代码看逻辑 → 不确定是 DB 慢还是业务逻辑问题 → 拉开发一起排查 → 1 小时+

**用 HiSi DevTool**：

```
第 1 步：输入 TraceId
         → 系统自动拉取完整 trace 链路
         → 识别出异常 span：OrderService.createOrder (timeout 3.2s)

第 2 步：自动诊断流水线
         → 异常 span 解析 → 定位到 DB 查询层
         → silent_catch 检测 → 发现一个被吞掉的 ConnectionException
         → KG 证据增强 → 拉取调用链 + SQL 语句 + 方法签名
         → LLM 根因推理 → 结合代码上下文给出结论

第 3 步：输出诊断报告
```

```mermaid
flowchart TB
    classDef root fill:#E74C3C,stroke:#641E16,color:#fff,font-weight:bold
    classDef scope fill:#F5B041,stroke:#9C640C,color:#fff
    classDef fix fill:#58D68D,stroke:#1D8348,color:#000

    R["🎯 <b>根因</b><br/>OrderItemMapper.selectByOrderId 全表扫描<br/>缺少 idx_order_id 索引<br/>+ silent_catch 吞掉了 ConnectionException"]:::root
    S["🌐 <b>影响范围</b><br/>POST /api/orders 超时率 15%<br/>下游 PaymentService 连带超时"]:::scope
    F["🔧 <b>修复建议</b><br/>1. ALTER TABLE order_item ADD INDEX idx_order_id (order_id)<br/>2. 移除 OrderService.java:89 的空 catch 块<br/>3. 增加 DB 连接池监控告警"]:::fix

    R --> S --> F
```

**效果**：1 小时+ → 3 分钟，且能发现"被吞掉的异常"这种人工排查极易遗漏的问题

---

## 场景 F：RAM 需求评估 —— "需求评审不再拍脑袋"

### 故事

> 产品经理提了一个需求："支持订单部分退款"。开发 leader 问："这个需求影响多大？需要几天？有什么风险？"

**传统方式**：开需求评审会 → 开发凭经验估时 → "大概 3 天吧" → 做到一半发现涉及资金对账模块 → 变成 8 天 → 产品经理："你们评估不准"

**用 HiSi DevTool**：

```
第 1 步：输入需求描述
         → ClarifyNode 启动，AI 主动追问：
           "部分退款的粒度是 SKU 级还是订单级？"
           "退款后库存是否需要回滚？"
         → 2 轮追问后需求明确

第 2 步：自动分析
         → ImpactNode 三环分析：
           🔴 involved：OrderService, RefundService（直接相关）
           🟡 modified：PaymentService, InventoryService（需修改）
           🟢 impacted：AccountingService, ReportService（受波及）

第 3 步：生成完整评估报告
```

```mermaid
flowchart TB
    classDef clarify fill:#5DADE2,stroke:#1A5276,color:#fff,font-weight:bold
    classDef impact fill:#F5B041,stroke:#9C640C,color:#fff
    classDef plan fill:#58D68D,stroke:#1D8348,color:#000

    C["🔍 <b>需求澄清</b><br/>SKU 级部分退款 · 库存需回滚<br/>资金对账需同步变更"]:::clarify
    I["📊 <b>影响分析</b><br/>涉及 6 个模块 · 修改 12 个方法<br/>风险评分：P1（资金相关）"]:::impact
    P["📋 <b>技术方案</b><br/>预估 7 人天 · 2 个技术风险点<br/>建议灰度上线 · 需资金对账联调"]:::plan

    C --> I --> P
```

**效果**：从"拍脑袋估 3 天"变成"有据可依估 7 天 + 风险清单"，需求评审效率提升 3 倍，工期偏差从 ±50% 降到 ±15%

---

# 五、技术巧思与架构亮点

## 5.1 Neo4j 一体化存储 —— 图 + 向量，一个库搞定

| 传统方案 | 我们的方案 |
|---------|-----------|
| MySQL 存结构 + Milvus 存向量 + Redis 缓存 | **Neo4j 一个库**：节点/关系（图结构）+ 原生 VECTOR INDEX（向量检索） |
| 三套系统维护、数据同步、一致性问题 | 零同步开销，一条 Cypher 同时做图遍历 + 向量搜索 |

> **为什么可以这么做？** Neo4j 5.11+ 原生支持 VECTOR INDEX（cosine similarity），不需要外挂向量数据库。这个决策让架构复杂度降低了一个量级。

## 5.2 OpenAI 协议统一抽象 —— 换模型不改代码

```mermaid
flowchart TB
    classDef api fill:#F5B041,stroke:#9C640C,color:#fff,font-weight:bold
    classDef vendor fill:#A9DFBF,stroke:#1E8449,color:#000

    API["🔌 统一接口<br/>UnifiedTextService<br/>（OpenAI 兼容协议）"]:::api

    V1["🟢 智谱 AI<br/>glm-4-flash<br/>embedding-3"]:::vendor
    V2["🟣 SiliconFlow<br/>deepseek-v3<br/>bge-m3"]:::vendor
    V3["🔵 讯飞星火<br/>spark-lite<br/>..."]:::vendor

    API --> V1 & V2 & V3
```

所有 LLM 调用走 OpenAI 兼容协议，`application.yml` 里改一行配置就能切换模型厂商，**零代码改动**。

## 5.3 多语言统一图谱 —— Java + Python 同一张图

```mermaid
flowchart TB
    classDef db fill:#BB8FCE,stroke:#6C3483,color:#fff,font-weight:bold
    classDef java fill:#F5B041,stroke:#9C640C,color:#fff
    classDef py fill:#5DADE2,stroke:#1A5276,color:#fff
    classDef pub fill:#58D68D,stroke:#1D8348,color:#000,font-weight:bold

    DB[("🗄️ 同一个 Neo4j 数据库")]:::db
    J["☕ Java 项目 A<br/>language=&quot;java&quot;"]:::java
    P["🐍 Python 项目 B<br/>language=&quot;python&quot;"]:::py
    PUB["🌐 公共图谱<br/>publicProjectPath 分区<br/><small>跨项目搜索：一次查询覆盖所有项目</small>"]:::pub

    DB --- J
    DB --- P
    DB --- PUB
```

- `publicProjectPath` 字段实现租户级别的数据隔离
- `language` 字段实现语言感知的过滤
- `coalesce(n.publicProjectPath, n.projectPath)` —— 一条 Cypher 兼容单项目和公共图谱

## 5.4 与同类工具对比

| 维度 | SonarQube | SourceGraph | CodeScene | Datadog APM | **HiSi DevTool** |
|------|-----------|-------------|-----------|-------------|-------------------|
| 代码质量扫描 | ✅ 强 | ❌ | ✅ | ❌ | ⚠️ 非核心 |
| 语义搜索 | ❌ | ❌ | ❌ | ❌ | ✅ 向量+关键词+图 |
| 调用链分析 | ❌ | ⚠️ 基础 | ❌ | ⚠️ 运行时 | ✅ 图谱 N 层遍历 |
| 影响分析 | ❌ | ❌ | ⚠️ 基于历史 | ❌ | ✅ 图谱+风险评分 |
| 日志根因诊断 | ❌ | ❌ | ❌ | ⚠️ 基础 | ✅ 日志×图谱×AI |
| APM 故障诊断 | ❌ | ❌ | ❌ | ⚠️ 指标为主 | ✅ Trace×图谱×AI+silent_catch |
| 需求评估 | ❌ | ❌ | ❌ | ❌ | ✅ DAG 流水线+三环分析 |
| AI 对话 | ❌ | ⚠️ Cody | ❌ | ❌ | ✅ Claude 终端 |
| 多语言图谱 | N/A | N/A | N/A | N/A | ✅ Java+Python |
| 部署方式 | 服务端 | 服务端 | SaaS | SaaS | **本地部署，数据不出域** |

---

# 六、演进之路 —— 每一步都是思考

```mermaid
timeline
    title HiSi DevTool 演进路线
    section v3.0 基础能力
        静态调用链解析 : 基础搜索
                      : 日志查询
    section v4.0 图谱化
        Neo4j 图存储   : 向量检索引入
                      : 9 种 QueryType
                      : 影响分析
    section v4.1 多语言 + 公共图谱
        Python AST 支持      : publicProjectPath
                            : OpenAI 协议统一
                            : 增量图谱更新
    section v5.0 AI 深度集成
        Claude 终端    : 技能市场
                      : 智能诊断对话
                      : MCP Server
    section v5.1 APM + RAM 智能化
        APM 故障诊断引擎   : OTel trace × 图谱 × AI
                          : silent_catch 检测
                          : 异步诊断流水线
        RAM 需求评估       : DAG 编排执行器
                          : 三环影响分析
                          : HITL 人机协作
        远程项目管理       : 合并影响分析
                          : 图谱浏览器增强
```

### 关键突破点

| 版本 | 决策 | 思考 |
|------|------|------|
| v3→v4 | 从关系型数据库迁移到 Neo4j | 调用关系天然是图，SQL JOIN 做 N 层遍历性能灾难 → 换图数据库后，影响分析从"不可用"变"秒级" |
| v4.0 | Neo4j 原生向量索引替代 ChromaDB | 减少一个组件 = 减少一类故障，且图+向量联合查询能力是独有优势 |
| v4.1 | Python 支持 + 公共图谱 | 业务需求驱动：团队有 Java + Python 混合技术栈，必须统一管理 |
| v4→v5 | 集成 Claude CLI 终端 | AI 不应该只是后台引擎，开发者应该能直接对话——把 Claude 搬进工具里 |
| v5→v5.1 | APM + RAM 双引擎 | 从"被动查询"到"主动诊断"：APM 让故障自动定位，RAM 让需求评估自动化——工具开始"主动思考" |

---

# 七、Live Demo 建议流程

> ⏱️ 12 分钟，建议按以下顺序演示

### Demo 1：一键构建图谱（2min）

- 选择一个中等规模的 Java 项目
- 点击构建 → 展示图谱可视化（节点数、关系数、类分布）
- **讲解点**："50 万行代码，10 分钟变成可视化的知识图谱"

### Demo 2：混合搜索（2min）

- 精确搜索：输入一个全限定类名 → 秒级定位
- 语义搜索：输入"查找所有跟支付相关的方法" → 展示多路融合结果
- **讲解点**："不管你记得类名还是只记得功能，都能搜到"

### Demo 3：影响分析（2min）

- 选择一个底层方法 → 点击影响分析
- 展示影响树：哪些接口受影响、风险等级、推荐测试用例
- **讲解点**："改代码之前先看影响范围，上线事故减少 80%"

### Demo 4：APM 故障诊断（3min）

- 输入一个 TraceId → 展示自动诊断流水线
- 展示异常 span 定位 + silent_catch 检测 + KG 证据增强
- 输出结构化诊断报告：根因 + 影响范围 + 修复建议
- **讲解点**："凌晨告警不再慌，3 分钟从 Trace 到根因"

### Demo 5：RAM 需求评估（3min）

- 输入一个需求描述 → 展示 ClarifyNode 追问
- 展示三环影响分析（involved / modified / impacted）
- 输出完整评估报告：需求澄清 + 影响分析 + 技术方案
- **讲解点**："需求评审不再拍脑袋，AI 给出有据可依的评估"

---

# 八、总结

## 核心价值

```mermaid
flowchart TB
    classDef core fill:#F39C12,stroke:#9C640C,color:#fff,font-weight:bold
    classDef leaf fill:#A9DFBF,stroke:#1E8449,color:#000,font-weight:bold

    CORE["🚀 代码理解效率 ×10<br/><small>从「人肉翻代码」到「图谱秒搜」</small>"]:::core

    L1["📈 改动影响<br/>可量化"]:::leaf
    L2["⚡ 故障定位<br/>3 min 级"]:::leaf
    L3["🎓 新人上手<br/>3 天 → 3 小时"]:::leaf
    L4["🔭 故障诊断<br/>自动化闭环"]:::leaf
    L5["📋 需求评估<br/>AI 驱动"]:::leaf

    CORE --> L1 & L2 & L3 & L4 & L5
```

## 一句话

> **HiSi DevTool = 知识图谱 × AI × 工程实践，让每个开发者都拥有"资深老员工"的代码理解力，让每次故障都有 AI 自动诊断，让每个需求都有 AI 辅助评估。**

---

# 附录：技术规格速查

| 项 | 值 |
|---|---|
| 后端框架 | Spring Boot 3.2.0 + Java 17 |
| 图数据库 | Neo4j 5.11+（原生 VECTOR INDEX, cosine） |
| 向量维度 | 4096d（默认 Qwen3-VL-Embedding-8B，可切换智谱 embedding-3 / 讯飞等） |
| 本地存储 | SQLite |
| AI 模型 | OpenAI 兼容协议，默认 glm-4-flash（文本）+ Qwen3-VL-Embedding-8B（向量），一行配置切换厂商 |
| AST 解析 | Java: JavaParser / Python: ANTLR4（支持 Python 3.8-3.12 语法） |
| 终端方案 | PTY4J + WebSocket + xterm.js |
| APM 采集 | 内置 OpenTelemetry Agent JAR，零配置开箱即用 |
| APM 诊断 | 异常 span 索引 + silent_catch 检测 + LLM 根因推理 + KG 证据增强 |
| APM 流水线 | FailureLocatorService 异步诊断 + 去重缓存 + 并发锁 + 诊断报告状态机 |
| RAM 编排 | DAG 执行器：Clarify → Impact → Implement → Verify → TechPlan |
| RAM 安全 | 熔断器 + 幂等守卫 + prompt cache + deadline enforcement |
| RAM 集成 | Claude SDK + MCP Server（3 个工具） |
| 前端 | Vue 3 + Element Plus + TypeScript |
| 前端新增 | DAG 图可视化 · 三环图 · FileBrowserPanel · ImpactOutputView · FlowDag SVG |
| 远程项目 | RemoteProject CRUD · Git clone/pull · AES-256-GCM 加密 · KG 定时调度 |
| 合并分析 | JGit diff + KG 影响分析 + LLM 测试推荐 + SSE 流式向导 UI |
| 部署 | 本地部署，数据不出域 |
| 开源地址 | github.com/shenjiangchun/codeKnowledge |

---

> 📝 **演讲备注**：每个场景讲完后可以停顿问一句"大家在日常工作中有没有遇到类似的问题？"，增加互动感。Demo 环节如果时间紧，优先演示 Demo 3（影响分析）和 Demo 4（APM 故障诊断），这两个最容易让非技术领导"哇"出来。APM 诊断的"从 Trace 到根因 3 分钟"是最大的亮点。
