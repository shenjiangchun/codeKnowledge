# 方案1: 多Agent协作诊断系统

## 依赖层级声明

```
依赖层级图：
┌─────────────────────────────────────────────────────────┐
│                    协作层（本方案）                       │
│         Orchestrator + 专业Agent + Consensus            │
└─────────────────────────────────────────────────────────┘
                          │
                          ▼
┌─────────────────────────────────────────────────────────┐
│                    通信层                                │
│  ┌─────────────────┐  ┌─────────────────┐              │
│  │ WebSocket       │  │ Agent消息队列   │              │
│  │ (已有)          │  │ (新增)          │              │
│  └─────────────────┘  └─────────────────┘              │
└─────────────────────────────────────────────────────────┘
                          │
                          ▼
┌─────────────────────────────────────────────────────────┐
│                    能力层                                │
│  ┌─────────────────┐  ┌─────────────────┐              │
│  │ ClaudeSdkService │  │ LLMService      │              │
│  │ (已有)          │  │ (已有)          │              │
│  └─────────────────┘  └─────────────────┘              │
└─────────────────────────────────────────────────────────┘

前置依赖：
- ClaudeSdkService（Claude CLI集成，已有）
- LLMService（远程LLM调用，已有）
- WebSocket实时通信（已有）
- JavaParser代码解析（已有）

可独立开发：
- Orchestrator Agent核心逻辑
- StackTrace Agent实现
- CodeContext Agent实现
- GitHistory Agent实现
- Consensus Agent验证框架

解耦点：
- 通过Agent接口标准与其他方案解耦
- 通过WebSocket协议与前端解耦
- 各Agent可独立部署、独立测试
```

---

## 一、目标与价值

### 1.1 核心目标

**将日志分析从"单点分析"升级为"多Agent协作诊断"**

| 当前状态 | 目标状态 |
|---------|---------|
| 单一LLM分析 | 多专业Agent分工协作 |
| 黑盒分析过程 | 透明协作过程展示 |
| 固定分析流程 | 灵活任务分解与调度 |
| 单一结论输出 | 多角度验证+置信度评分 |

### 1.2 价值主张

```
诊断能力提升：
├── 专业分工：StackTrace/CodeContext/GitHistory各司其职
├── 交叉验证：Consensus Agent确保结论可靠性
├── 实时反馈：用户可查看各Agent工作进度
└── 用户干预：支持中途调整分析方向

效率提升：
├── 并行执行：多个Agent可并行处理独立任务
├── 精准定位：专业Agent深入分析特定维度
└── 可信结论：置信度评分帮助用户判断
```

### 1.3 成功指标

| 指标 | 基线 | 目标 |
|------|------|------|
| 根因定位准确率 | 70% | ≥80% |
| 多Agent协作成功率 | N/A | ≥90% |
| 分析效率提升 | 基准线 | ≥10倍 |
| 用户干预有效性 | N/A | 提升≥10% |

---

## 二、技术方案

### 2.1 Agent协作架构

```
┌─────────────────────────────────────────────────────────────┐
│                    WebSocket 实时通信层                       │
│              (双向流式传输 + 用户干预接口)                    │
└─────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌─────────────────────────────────────────────────────────────┐
│                     Orchestrator Agent                       │
│   • 任务分解: "分析NPE" → [堆栈分析, 代码检索, 变更追踪]      │
│   • 进度推送: 实时输出分析过程                                │
│   • 用户交互: 响应用户干预指令                                │
│   • 结果整合: 综合各Agent输出，生成最终报告                   │
└─────────────────────────────────────────────────────────────┘
         │                │                │                │
         ▼                ▼                ▼                ▼
┌───────────────┐ ┌───────────────┐ ┌───────────────┐ ┌───────────────┐
│ StackTrace    │ │ CodeContext   │ │ GitHistory    │ │ DocRetrieval  │
│ Agent         │ │ Agent         │ │ Agent         │ │ Agent(可选)   │
│               │ │               │ │               │ │               │
│ 能力:         │ │ 能力:         │ │ 能力:         │ │ 能力:         │
│ • 堆栈解析    │ │ • 代码检索    │ │ • Git历史     │ │ • 文档检索    │
│ • 异常定位    │ │ • 语义理解    │ │ • 变更关联    │ │ • API文档     │
│ • 业务过滤    │ │ • 调用链分析  │ │ • 责任人识别  │ │               │
└───────────────┘ └───────────────┘ └───────────────┘ └───────────────┘
                              │
                              ▼
┌─────────────────────────────────────────────────────────────┐
│                     Consensus Agent                          │
│   • 交叉验证: 多Agent结论一致性检查                           │
│   • 置信度计算: 综合各Agent置信度，加权平均                   │
│   • 证据链生成: 展示"为什么得出这个结论"                      │
│   • 决策输出: 最终根因 + 修复建议 + 置信度                    │
└─────────────────────────────────────────────────────────────┘
```

### 2.2 核心Agent设计

#### 2.2.1 Orchestrator Agent

```java
/**
 * 编排Agent - 任务分解与协调
 */
@Service
public class OrchestratorAgent {

    private final List<SpecializedAgent> agents;
    private final ConsensusAgent consensusAgent;
    private final AgentEventPublisher eventPublisher;

    /**
     * 执行诊断任务
     */
    public DiagnosisResult diagnose(DiagnosisRequest request) {
        String sessionId = request.getSessionId();

        // 1. 任务分解
        pushEvent(sessionId, "THINKING", "正在分解诊断任务...");
        List<AgentTask> tasks = decomposeTask(request);

        // 2. 分配任务到专业Agent
        Map<String, AgentResult> results = new ConcurrentHashMap<>();
        List<CompletableFuture<AgentResult>> futures = new ArrayList<>();

        for (AgentTask task : tasks) {
            SpecializedAgent agent = selectAgent(task);
            pushEvent(sessionId, "AGENT_START", agent.getName());

            futures.add(CompletableFuture.supplyAsync(() -> {
                AgentResult result = agent.execute(task);
                results.put(agent.getName(), result);
                pushEvent(sessionId, "AGENT_RESULT",
                    Map.of("agent", agent.getName(), "result", result));
                return result;
            }));
        }

        // 3. 等待所有Agent完成（支持中途干预）
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
            .join();

        // 4. Consensus验证
        pushEvent(sessionId, "CONSENSUS_START", "正在进行交叉验证...");
        ConsensusResult consensus = consensusAgent.validate(results);

        // 5. 生成最终报告
        return buildFinalReport(consensus, results);
    }

    /**
     * 任务分解
     */
    private List<AgentTask> decomposeTask(DiagnosisRequest request) {
        // 根据错误类型分解任务
        // NPE → [堆栈解析, 代码上下文, Git历史]
        // Timeout → [堆栈解析, 调用链分析, 性能检查]
        return switch (request.getErrorType()) {
            case "NullPointerException" -> List.of(
                new AgentTask("StackTrace", "parse", request.getStackTrace()),
                new AgentTask("CodeContext", "analyze", request.getLocation()),
                new AgentTask("GitHistory", "trace", request.getLocation())
            );
            case "TimeoutException" -> List.of(
                new AgentTask("StackTrace", "parse", request.getStackTrace()),
                new AgentTask("CodeContext", "callChain", request.getLocation())
            );
            default -> List.of(
                new AgentTask("StackTrace", "parse", request.getStackTrace())
            );
        };
    }
}
```

#### 2.2.2 StackTrace Agent

```java
/**
 * 堆栈分析Agent - 异常定位与业务过滤
 */
@Service
public class StackTraceAgent implements SpecializedAgent {

    private final LLMService llmService;
    private final BusinessRuleService businessRuleService;

    @Override
    public String getName() {
        return "StackTraceAgent";
    }

    @Override
    public AgentResult execute(AgentTask task) {
        String stackTrace = task.getInput();

        // 1. 解析堆栈结构
        List<StackFrame> frames = parseStackTrace(stackTrace);

        // 2. 业务过滤（排除框架层、工具类）
        List<StackFrame> businessFrames = frames.stream()
            .filter(f -> businessRuleService.isBusinessCode(f.getClassName()))
            .toList();

        // 3. 定位关键帧（第一帧业务代码）
        StackFrame keyFrame = businessFrames.isEmpty()
            ? frames.get(0)
            : businessFrames.get(0);

        // 4. LLM分析异常原因
        String analysis = analyzeWithLLM(stackTrace, keyFrame);

        return new AgentResult(
            getName(),
            Map.of(
                "keyFrame", keyFrame,
                "businessFrames", businessFrames,
                "analysis", analysis,
                "confidence", calculateConfidence(businessFrames)
            ),
            "堆栈分析完成"
        );
    }

    private String analyzeWithLLM(String stackTrace, StackFrame keyFrame) {
        String prompt = """
            分析以下堆栈信息，定位异常根因：

            关键位置：%s.%s(line %d)

            堆栈信息：
            %s

            请输出：
            1. 异常类型判断
            2. 可能的触发原因
            3. 建议的排查方向
            """.formatted(
                keyFrame.getClassName(),
                keyFrame.getMethodName(),
                keyFrame.getLineNumber(),
                stackTrace
            );

        return llmService.generateText(prompt);
    }
}
```

#### 2.2.3 CodeContext Agent

```java
/**
 * 代码上下文Agent - 代码检索与调用链分析
 */
@Service
public class CodeContextAgent implements SpecializedAgent {

    private final CodeSearchService codeSearchService;
    private final CallChainService callChainService;
    private final ClaudeSdkService claudeSdkService;

    @Override
    public String getName() {
        return "CodeContextAgent";
    }

    @Override
    public AgentResult execute(AgentTask task) {
        String location = task.getInput(); // 类名:方法名

        // 1. 检索相关代码
        List<CodeSnippet> snippets = codeSearchService.searchByLocation(location);

        // 2. 获取调用链
        CallChain chain = callChainService.getCallChain(location);

        // 3. Claude SDK深度分析
        String deepAnalysis = claudeSdkService.query("""
            分析以下代码上下文：

            代码片段：
            %s

            调用链：
            %s

            请分析：
            1. 方法职责
            2. 潜在风险点
            3. 依赖关系
            """.formatted(snippets, chain));

        return new AgentResult(
            getName(),
            Map.of(
                "snippets", snippets,
                "callChain", chain,
                "deepAnalysis", deepAnalysis,
                "confidence", 0.85
            ),
            "代码上下文分析完成"
        );
    }
}
```

#### 2.2.4 GitHistory Agent

```java
/**
 * Git历史Agent - 变更追踪与责任人识别
 */
@Service
public class GitHistoryAgent implements SpecializedAgent {

    private final GitService gitService;
    private final LLMService llmService;

    @Override
    public String getName() {
        return "GitHistoryAgent";
    }

    @Override
    public AgentResult execute(AgentTask task) {
        String location = task.getInput();

        // 1. 查询最近变更
        List<GitCommit> commits = gitService.getRecentCommits(location, 30); // 最近30天

        // 2. 识别责任人
        Set<String> authors = commits.stream()
            .map(GitCommit::getAuthor)
            .collect(Collectors.toSet());

        // 3. 分析变更内容
        String changeAnalysis = analyzeChanges(commits, location);

        return new AgentResult(
            getName(),
            Map.of(
                "recentCommits", commits,
                "authors", authors,
                "changeAnalysis", changeAnalysis,
                "confidence", commits.isEmpty() ? 0.5 : 0.8
            ),
            "Git历史分析完成"
        );
    }

    private String analyzeChanges(List<GitCommit> commits, String location) {
        if (commits.isEmpty()) {
            return "无近期变更记录";
        }

        String prompt = """
            分析以下代码位置的最近变更：

            位置：%s
            最近提交：%s

            请判断：
            1. 变更是否可能与当前问题相关
            2. 变更的风险程度
            """.formatted(location, commits.stream()
                .map(c -> c.getMessage() + " (" + c.getDate() + ")")
                .collect(Collectors.joining("\n")));

        return llmService.generateText(prompt);
    }
}
```

#### 2.2.5 Consensus Agent

```java
/**
 * 共识Agent - 交叉验证与置信度计算
 */
@Service
public class ConsensusAgent {

    /**
     * 验证多个Agent结论的一致性
     */
    public ConsensusResult validate(Map<String, AgentResult> agentResults) {
        // 1. 收集各Agent的结论
        List<AgentConclusion> conclusions = agentResults.values().stream()
            .map(this::extractConclusion)
            .toList();

        // 2. 交叉验证一致性
        double consistencyScore = calculateConsistency(conclusions);

        // 3. 加权置信度
        double weightedConfidence = calculateWeightedConfidence(agentResults);

        // 4. 生成证据链
        List<Evidence> evidenceChain = buildEvidenceChain(agentResults);

        return new ConsensusResult(
            consistencyScore,
            weightedConfidence,
            evidenceChain,
            generateFinalConclusion(conclusions, consistencyScore)
        );
    }

    /**
     * 计算结论一致性
     */
    private double calculateConsistency(List<AgentConclusion> conclusions) {
        // 比较各Agent结论的关键判断
        // 如：根因是否指向同一代码位置
        Set<String> rootCauses = conclusions.stream()
            .map(AgentConclusion::getRootCauseLocation)
            .collect(Collectors.toSet());

        // 一致性 = 结论数量 / 总Agent数
        return 1.0 - (rootCauses.size() - 1.0) / conclusions.size();
    }

    /**
     * 生成证据链
     */
    private List<Evidence> buildEvidenceChain(Map<String, AgentResult> results) {
        return results.entrySet().stream()
            .map(e -> new Evidence(
                e.getKey(),
                e.getValue().getSummary(),
                e.getValue().getData()
            ))
            .toList();
    }
}
```

### 2.3 Agent通信协议

```java
/**
 * Agent消息协议
 */
public class AgentMessage {
    private String sessionId;
    private MessageType type;
    private String sourceAgent;
    private String targetAgent;  // null表示广播
    private Object payload;
    private long timestamp;

    public enum MessageType {
        TASK_ASSIGN,       // 任务分配
        TASK_COMPLETE,     // 任务完成
        TASK_FAILED,       // 任务失败
        QUERY_CONTEXT,     // 请求上下文
        PROVIDE_CONTEXT,   // 提供上下文
        USER_INTERVENTION, // 用户干预
        PROGRESS_UPDATE,   // 进度更新
        FINAL_RESULT       // 最终结果
    }
}

/**
 * Agent事件推送（到前端）
 */
public class AgentEvent {
    private String sessionId;
    private EventType type;
    private String agentName;
    private String content;
    private Object data;
    private long timestamp;

    public enum EventType {
        THINKING,           // 正在思考
        AGENT_START,        // Agent启动
        AGENT_RESULT,       // Agent结果
        CONSENSUS_START,    // 开始验证
        USER_ACTION_REQUIRED, // 需用户操作
        FINAL_RESULT        // 最终结果
    }
}
```

---

## 三、实施步骤

### 3.1 版本迭代计划

```
┌─────────────────────────────────────────────────────────────┐
│                    v4.1 Agent框架搭建                        │
├─────────────────────────────────────────────────────────────┤
│ 时间：Week 1-2                                              │
│ 目标：建立Agent协作基础框架                                  │
│                                                             │
│ 功能：                                                      │
│ ├── Agent通信协议定义                                       │
│ ├── Orchestrator Agent核心实现                              │
│ ├── StackTrace Agent实现                                    │
│ ├── WebSocket事件推送                                       │
│ └── 基础单元测试                                            │
│                                                             │
│ 交付物：                                                    │
│ ├── AgentMessage协议                                        │
│ ├── OrchestratorAgent服务                                   │
│ ├── StackTraceAgent服务                                     │
│ └── AgentEventPublisher                                     │
└─────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌─────────────────────────────────────────────────────────────┐
│                    v4.2 专业Agent扩展                        │
├─────────────────────────────────────────────────────────────┤
│ 时间：Week 3-4                                              │
│ 目标：实现完整的专业Agent体系                                │
│                                                             │
│ 功能：                                                      │
│ ├── CodeContext Agent实现                                   │
│ ├── GitHistory Agent实现                                    │
│ ├── Agent并行执行框架                                        │
│ ├── 用户干预接口                                            │
│ └── 前端进度展示                                            │
│                                                             │
│ 交付物：                                                    │
│ ├── CodeContextAgent服务                                    │
│ ├── GitHistoryAgent服务                                     │
│ ├── ParallelExecutionService                                │
│ └── 前端Agent状态组件                                       │
└─────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌─────────────────────────────────────────────────────────────┐
│                    v4.3 Consensus验证                        │
├─────────────────────────────────────────────────────────────┤
│ 时间：Week 5-6                                              │
│ 目标：实现结论验证与置信度系统                               │
│                                                             │
│ 功能：                                                      │
│ ├── Consensus Agent实现                                     │
│ ├── 置信度计算算法                                          │
│ ├── 证据链生成                                              │
│ ├── 最终报告生成                                            │
│ └── 验证测试框架                                            │
│                                                             │
│ 交付物：                                                    │
│ ├── ConsensusAgent服务                                      │
│ ├── ConfidenceCalculator                                    │
│ ├── EvidenceChainBuilder                                    │
│ └── 100个历史案例验证测试                                   │
└─────────────────────────────────────────────────────────────┘
```

### 3.2 详细任务分解

#### v4.1 任务清单

| 任务 | 描述 | 工时 | 依赖 |
|------|------|------|------|
| T1.1 | 设计AgentMessage协议 | 4h | 无 |
| T1.2 | 实现OrchestratorAgent | 8h | T1.1 |
| T1.3 | 实现StackTraceAgent | 4h | T1.1 |
| T1.4 | 实现AgentEventPublisher | 4h | WebSocket |
| T1.5 | 单元测试 | 4h | T1.2-T1.4 |
| T1.6 | 集成验证 | 4h | T1.5 |

---

## 四、验收标准

### 4.1 功能验收标准

| 功能 | 验收标准 | 测试方法 |
|------|---------|---------|
| 任务分解 | 正确识别错误类型并分配Agent | 功能测试 |
| StackTrace解析 | 业务帧识别准确率≥95% | 100个堆栈样本 |
| CodeContext检索 | Top-5召回率≥80% | 50个查询测试 |
| GitHistory追踪 | 30天内变更覆盖率≥90% | Git仓库验证 |
| Consensus验证 | 一致性计算准确率≥85% | 多Agent结果对比 |

### 4.2 性能验收标准

| 指标 | 标准 | 测试方法 |
|------|------|---------|
| Agent响应时间 | <5s/Agent | 性能测试 |
| 并行执行效率 | 提升≥50% | 对比单Agent |
| 置信度计算延迟 | <1s | 性能测试 |
| WebSocket延迟 | <100ms | 网络测试 |

### 4.3 质量验收标准

| 指标 | 标准 |
|------|------|
| Agent单元测试覆盖率 | ≥80% |
| 协作集成测试 | 10个典型场景 |
| 100个历史案例验证 | 准确率≥80% |

---

## 五、依赖关系图

```
                    ┌─────────────────┐
                    │  用户/前端      │
                    └────────┬────────┘
                             │ WebSocket
                             ▼
┌─────────────────────────────────────────────────────────────┐
│                    本方案：多Agent协作诊断                    │
│  ┌─────────────────────────────────────────────────────┐   │
│  │  OrchestratorController                              │   │
│  │  - POST /api/agent/diagnose                          │   │
│  │  - WebSocket /topic/agent/{sessionId}                │   │
│  └─────────────────────────────────────────────────────┘   │
│                           │                                 │
│           ┌───────────────┼───────────────┐                │
│           ▼               ▼               ▼                │
│  ┌─────────────┐  ┌─────────────┐  ┌─────────────┐         │
│  │ Orchestrator│  │ Consensus   │  │ Event       │         │
│  │ Agent       │  │ Agent       │  │ Publisher   │         │
│  └──────┬──────┘  └──────┬──────┘  └──────┬──────┘         │
│         │                │                │                 │
└─────────┼────────────────┼────────────────┼─────────────────┘
          │                │                │
          ▼                ▼                ▼
┌─────────────────────────────────────────────────────────────┐
│                    专业Agent层                              │
│  ┌─────────────┐  ┌─────────────┐  ┌─────────────┐         │
│  │ StackTrace  │  │ CodeContext │  │ GitHistory  │         │
│  │ Agent       │  │ Agent       │  │ Agent       │         │
│  └──────┬──────┘  └──────┬──────┘  └──────┬──────┘         │
└─────────┼────────────────┼────────────────┼─────────────────┘
          │                │                │
          ▼                ▼                ▼
┌─────────────────────────────────────────────────────────────┐
│                    基设施层                                │
│  ┌─────────────┐  ┌─────────────┐  ┌─────────────┐         │
│  │ ClaudeSdk   │  │ LLMService  │  │ GitService  │         │
│  │ (已有)      │  │ (已有)      │  │ (已有)      │         │
│  └─────────────┘  └─────────────┘  └─────────────┘         │
└─────────────────────────────────────────────────────────────┘

与其他方案的关系：
┌─────────────┐     提供协作诊断能力      ┌─────────────┐
│  方案3      │ ◀─────────────────────── │  本方案     │
│  自然语言入口 │                          │  多Agent协作 │
└─────────────┘                          └─────────────┘
       │                                       │
       │         使用语义理解增强               │
       └───────────────────────────────────────▶
                                           ┌─────────────┐
                                           │  方案2      │
                                           │  LLM语义    │
                                           └─────────────┘
```

---

## 六、风险评估

| 风险 | 概率 | 影响 | 缓解措施 |
|------|------|------|---------|
| Agent协调失败 | 中 | 高 | 任务重试机制 + 超时处理 |
| Claude CLI不可用 | 低 | 高 | LLMService降级备用 |
| 并行执行竞态 | 中 | 中 | 结果合并锁 + 顺序保障 |
| 用户干预冲突 | 低 | 中 | 干预队列 + 状态检查 |

---

文档版本：v1.0
创建时间：2026-04-04
作者：hisi-evolution-v2专家组
状态：待评审