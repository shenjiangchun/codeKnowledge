# Agent框架整合设计方案

## 概述

**文档版本**: v1.0
**日期**: 2026-04-05
**状态**: 已批准

### 背景

v4.1引入的Agent框架与原有Claude诊断系统存在以下问题：

1. **两套独立系统并存**：日志查询页面使用Claude SDK，诊断页面使用Agent+LLMService
2. **用户体验倒退**：用户需要手动切换页面、手动粘贴堆栈
3. **能力缺失**：Agent框架无工具调用能力、无多轮对话支持
4. **维护成本增加**：两套系统需要分别维护

### 设计目标

将Agent框架整合到**日志查询→分析**流程，保留Claude的工具调用能力和多轮对话能力。

---

## 一、目标架构

```
┌─────────────────────────────────────────────────────────────┐
│                    整合后架构                                │
├─────────────────────────────────────────────────────────────┤
│                                                             │
│  LogQuery.vue                                               │
│       │                                                     │
│       │ 点击"分析"                                          │
│       ▼                                                     │
│  ┌─────────────────────────────────────────────────────┐   │
│  │ AgentOrchestrator.diagnose(context)                 │   │
│  │   ├── StackTraceAgent (改造)                        │   │
│  │   │     └── 调用 ClaudeSdkService (有工具能力)      │   │
│  │   ├── CodeContextAgent (v4.2规划)                   │   │
│  │   └── GitHistoryAgent (v4.2规划)                    │   │
│  └─────────────────────────────────────────────────────┘   │
│       │                                                     │
│       │ SSE流式输出                                         │
│       ▼                                                     │
│  日志分析结果面板 (保留多轮对话)                            │
│                                                             │
└─────────────────────────────────────────────────────────────┘
```

---

## 二、端到端流程

```
1. LogQuery.vue
   └── 用户查询日志 → 日志列表 → 点击"分析"按钮

2. 前端调用
   └── POST /api/diagnosis/analyze
       body: {
         logEntry: {...},
         sessionId: "xxx" (可选)
       }

3. DiagnosisController
   └── AgentOrchestrator.diagnose(context)

4. StackTraceAgent.execute()
   ├── 解析堆栈，过滤业务代码
   ├── ClaudeSdkService.streamQuery(sessionId, prompt)
   └── 返回 Flux<String> 流式结果

5. SSE 推送
   ├── event: session → sessionId
   ├── event: output → 分析内容片段
   └── event: done → 完成

6. LogQuery.vue 接收
   └── 显示分析结果 → 支持追问 (传入sessionId)
```

---

## 三、组件改造详情

### 3.1 StackTraceAgent 改造

**当前实现问题**：
- 调用 `LLMService` (无工具能力)
- 同步返回，不支持流式输出
- 无会话管理

**改造方案**：

```java
@Slf4j
@Component
public class StackTraceAgent implements DiagnosticAgent {

    private final StackTraceFilter stackTraceFilter;
    private final ClaudeSdkService claudeSdkService;  // 替换 LLMService
    private final SessionService sessionService;      // 新增会话管理

    @Override
    public AgentResult execute(AgentContext context) {
        String sessionId = context.getSessionId();

        // 1. 创建/复用会话
        if (sessionId == null) {
            ClaudeSession session = sessionService.createSession(
                "log-analysis", null, buildMetadata(context), context.getProjectPath());
            sessionId = session.getId();
        }

        // 2. 构建提示词 (复用现有逻辑)
        String prompt = buildAnalysisPrompt(context);

        // 3. 流式调用 Claude (带工具)
        Flux<String> stream = claudeSdkService.streamQuery(sessionId, prompt);

        // 4. 返回支持流式的 AgentResult
        return AgentResult.streaming(sessionId, stream);
    }
}
```

**关键变化**：

| 维度 | 改造前 | 改造后 |
|------|-------|-------|
| LLM调用 | `LLMService.generateText()` | `ClaudeSdkService.streamQuery()` |
| 工具能力 | 无 | search_methods, find_callers等 |
| 输出方式 | 同步返回 | 流式Flux |
| 会话支持 | 无 | SessionService管理 |

### 3.2 AgentOrchestrator 改造

**新增流式支持**：

```java
public class AgentOrchestrator {

    /**
     * 流式诊断 - 支持SSE推送
     */
    public Flux<AgentEvent> diagnoseStream(AgentContext context) {
        return Flux.create(emitter -> {
            String requestId = context.getRequestId();

            // 发布开始事件
            emitter.next(AgentEvent.orchestrationStart(requestId));

            // 执行Agent
            for (DiagnosticAgent agent : getExecutableAgents(context)) {
                emitter.next(AgentEvent.agentStarted(requestId, agent.getAgentType()));

                AgentResult result = agent.execute(context);

                // 流式输出
                if (result.isStreaming()) {
                    result.getStream().subscribe(
                        chunk -> emitter.next(AgentEvent.output(requestId, chunk)),
                        error -> emitter.next(AgentEvent.agentFailed(requestId, agent.getAgentType(), error.getMessage())),
                        () -> emitter.next(AgentEvent.agentCompleted(requestId, agent.getAgentType()))
                    );
                }
            }

            emitter.next(AgentEvent.orchestrationEnd(requestId));
            emitter.complete();
        });
    }
}
```

### 3.3 DiagnosisController 改造

**改为SSE输出**：

```java
@RestController
@RequestMapping("/api/diagnosis")
public class DiagnosisController {

    private final AgentOrchestrator orchestrator;

    @PostMapping(value = "/analyze", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter analyze(@RequestBody DiagnosisRequest request) {
        SseEmitter emitter = new SseEmitter(600000L);

        AgentContext context = AgentContext.builder()
            .requestId(UUID.randomUUID().toString())
            .stackTrace(request.getStackTrace())
            .errorMessage(request.getErrorMessage())
            .projectPath(request.getProjectPath())
            .sessionId(request.getSessionId())
            .build();

        orchestrator.diagnoseStream(context).subscribe(
            event -> {
                try {
                    emitter.send(SseEmitter.event()
                        .name(event.getEventType())
                        .data(event.getData()));
                } catch (IOException e) {
                    log.error("SSE send error", e);
                }
            },
            error -> emitter.completeWithError(error),
            () -> emitter.complete()
        );

        return emitter;
    }
}
```

---

## 四、移除清单

### 4.1 前端移除

| 文件/目录 | 说明 |
|----------|------|
| `src/views/diagnostic/DiagnosticView.vue` | 独立诊断页面 |
| `src/views/diagnostic/components/AgentProgressPanel.vue` | Agent进度组件 |
| `src/views/diagnostic/components/DiagnosticResultPanel.vue` | 诊断结果组件 |
| `src/views/diagnostic/` 目录 | 整个目录删除 |
| `router/index.ts` 中的 `/diagnostic` 路由 | 移除路由配置 |

### 4.2 后端移除

| 文件 | 说明 |
|------|------|
| WebSocket配置类 | `/ws/diagnosis` 端点 |
| `AgentEventPublisher` 中的WebSocket推送逻辑 | 改为SSE |

---

## 五、保留清单

### 5.1 保留并改造

| 内容 | 改造方向 |
|------|---------|
| `DiagnosticAgent` 接口 | 保留，增加流式支持 |
| `AgentOrchestrator` | 改造为支持流式输出 |
| `StackTraceAgent` | 改造为调用ClaudeSdkService |
| `AgentContext` | 增加sessionId字段 |
| `AgentResult` | 增加流式结果支持 |

### 5.2 保留不变

| 内容 | 原因 |
|------|------|
| `ClaudeSdkService` | 核心能力，不变 |
| `SessionService` | 会话管理，不变 |
| `/api/claude/*` 接口 | 兼容现有调用 |
| `/search` 语义搜索页面 | 独立功能 |
| `code_nodes` 等语义表 | 未来能力预留 |

---

## 六、实施计划

### 阶段一：后端改造（2天）

1. 改造 `StackTraceAgent`：注入 `ClaudeSdkService`，支持流式输出
2. 改造 `AgentOrchestrator`：新增 `diagnoseStream()` 方法
3. 改造 `DiagnosisController`：改为SSE输出
4. 移除WebSocket相关代码

### 阶段二：前端整合（1天）

1. 修改 `LogQuery.vue` 分析按钮：调用 `/api/diagnosis/analyze`
2. 删除 `DiagnosticView.vue` 及相关组件
3. 移除 `/diagnostic` 路由

### 阶段三：测试验证（1天）

1. 端到端测试：日志查询 → 分析 → 多轮对话
2. 回归测试：确保原有功能不受影响
3. 性能测试：SSE连接稳定性

---

## 七、风险与缓解

| 风险 | 缓解措施 |
|------|---------|
| 流式输出兼容性 | 复用现有SSE基础设施 |
| 会话状态管理 | 复用现有SessionService |
| 前端改动范围 | 最小化改动，只改分析按钮调用 |

---

## 八、后续规划

整合完成后，Agent框架将为后续扩展提供基础：

- **v4.2**: 新增 `CodeContextAgent`（代码上下文分析）
- **v4.2**: 新增 `GitHistoryAgent`（Git历史关联）
- **v4.3**: 新增 `ConsensusAgent`（多Agent结果验证）

---

**批准人**:
**批准日期**: 2026-04-05