# Agent框架整合实施计划

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** 将Agent框架整合到日志查询→分析流程，移除独立诊断页面，保留Agent框架为后续扩展基础。

**Architecture:** StackTraceAgent改造为调用ClaudeSdkService获取工具能力；AgentOrchestrator新增流式诊断方法；DiagnosisController改为SSE输出；移除前端独立诊断页面和WebSocket。

**Tech Stack:** Spring Boot 3.2 + Reactor (Flux) + SSE, Vue 3 + TypeScript + EventSource

---

## Task 1: AgentContext 增加 sessionId 字段

**Files:**
- Modify: `hisi-dev-tool/src/main/java/com/huawei/hisi/agent/model/AgentContext.java`

**Step 1: 添加 sessionId 字段**

```java
// 在 AgentContext.java 中添加字段
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AgentContext {
    private String requestId;
    private String projectPath;
    private String errorMessage;
    private String stackTrace;
    private String logContent;
    private String sessionId;  // 新增：支持多轮对话
    private List<AgentResult> previousResults;

    // ... 其他现有代码
}
```

**Step 2: 验证编译通过**

Run: `cd hisi-dev-tool && mvn compile -q`
Expected: BUILD SUCCESS

**Step 3: Commit**

```bash
git add src/main/java/com/huawei/hisi/agent/model/AgentContext.java
git commit -m "feat(agent): AgentContext增加sessionId字段支持多轮对话"
```

---

## Task 2: AgentResult 增加流式支持

**Files:**
- Modify: `hisi-dev-tool/src/main/java/com/huawei/hisi/agent/model/AgentResult.java`

**Step 1: 添加流式输出字段和方法**

```java
// 在 AgentResult.java 中添加
import reactor.core.publisher.Flux;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AgentResult {
    // ... 现有字段 ...

    // 新增：流式输出支持
    private boolean streaming;
    private Flux<String> stream;
    private String sessionId;

    /**
     * 创建流式结果
     */
    public static AgentResult streaming(String sessionId, Flux<String> stream) {
        return AgentResult.builder()
                .streaming(true)
                .sessionId(sessionId)
                .stream(stream)
                .build();
    }

    /**
     * 是否为流式结果
     */
    public boolean isStreaming() {
        return streaming && stream != null;
    }

    /**
     * 获取流
     */
    public Flux<String> getStream() {
        return stream;
    }
}
```

**Step 2: 验证编译通过**

Run: `cd hisi-dev-tool && mvn compile -q`
Expected: BUILD SUCCESS

**Step 3: Commit**

```bash
git add src/main/java/com/huawei/hisi/agent/model/AgentResult.java
git commit -m "feat(agent): AgentResult增加流式输出支持"
```

---

## Task 3: StackTraceAgent 改造为调用 ClaudeSdkService

**Files:**
- Modify: `hisi-dev-tool/src/main/java/com/huawei/hisi/agent/impl/StackTraceAgent.java`
- Modify: `hisi-dev-tool/src/test/java/com/huawei/hisi/agent/impl/StackTraceAgentTest.java`

**Step 1: 修改 StackTraceAgent 注入依赖**

```java
// StackTraceAgent.java - 修改构造函数和字段
@Slf4j
@Component
public class StackTraceAgent implements DiagnosticAgent {

    private final StackTraceFilter stackTraceFilter;
    private final ClaudeSdkService claudeSdkService;  // 替换 LLMService
    private final SessionService sessionService;      // 新增会话管理

    @Autowired
    public StackTraceAgent(StackTraceFilter stackTraceFilter,
                          ClaudeSdkService claudeSdkService,
                          SessionService sessionService) {
        this.stackTraceFilter = stackTraceFilter;
        this.claudeSdkService = claudeSdkService;
        this.sessionService = sessionService;
    }

    // ... 其他代码保持不变，后续task改造execute方法 ...
}
```

**Step 2: 验证编译通过**

Run: `cd hisi-dev-tool && mvn compile -q`
Expected: BUILD SUCCESS

**Step 3: Commit**

```bash
git add src/main/java/com/huawei/hisi/agent/impl/StackTraceAgent.java
git commit -m "refactor(agent): StackTraceAgent注入ClaudeSdkService替换LLMService"
```

---

## Task 4: StackTraceAgent 实现 executeStreaming 方法

**Files:**
- Modify: `hisi-dev-tool/src/main/java/com/huawei/hisi/agent/impl/StackTraceAgent.java`

**Step 1: 添加 executeStreaming 方法**

```java
// 在 StackTraceAgent.java 中添加

/**
 * 流式执行诊断 - 支持SSE输出
 */
public AgentResult executeStreaming(AgentContext context) {
    String requestId = context.getRequestId();
    String sessionId = context.getSessionId();

    log.info("[{}] StackTraceAgent starting streaming execution", requestId);

    try {
        String stackTrace = context.getStackTrace();
        if (stackTrace == null || stackTrace.trim().isEmpty()) {
            return AgentResult.builder()
                    .agentType(AGENT_TYPE)
                    .requestId(requestId)
                    .status(AgentResult.Status.SKIPPED)
                    .confidence(0.0)
                    .conclusion("无堆栈信息提供")
                    .build();
        }

        // 1. 规则解析：提取异常信息
        ExceptionInfo exceptionInfo = extractExceptionInfo(stackTrace);
        List<StackFrame> filteredFrames = stackTraceFilter.filter(stackTrace);

        // 2. 创建/复用会话
        if (sessionId == null || sessionId.isEmpty()) {
            String metadata = buildMetadataJson(exceptionInfo, filteredFrames);
            ClaudeSession session = sessionService.createSession(
                    "log-analysis", null, metadata, context.getProjectPath());
            sessionId = session.getId();
            log.info("[{}] Created new session: {}", requestId, sessionId);
        }

        // 3. 构建提示词
        String prompt = buildAnalysisPrompt(context, exceptionInfo, filteredFrames);

        // 4. 调用 Claude 流式输出
        Flux<String> stream = claudeSdkService.streamQuery(sessionId, prompt);

        // 5. 返回流式结果
        return AgentResult.streaming(sessionId, stream)
                .toBuilder()
                .agentType(AGENT_TYPE)
                .requestId(requestId)
                .status(AgentResult.Status.SUCCESS)
                .confidence(calculateConfidence(context))
                .build();

    } catch (Exception e) {
        log.error("[{}] StackTraceAgent streaming failed: {}", requestId, e.getMessage(), e);
        return AgentResult.builder()
                .agentType(AGENT_TYPE)
                .requestId(requestId)
                .status(AgentResult.Status.FAILED)
                .confidence(0.0)
                .errorMessage("流式诊断失败: " + e.getMessage())
                .build();
    }
}

private String buildMetadataJson(ExceptionInfo info, List<StackFrame> frames) {
    try {
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("exceptionType", info.type);
        metadata.put("exceptionMessage", info.message);
        if (!frames.isEmpty()) {
            metadata.put("firstFrame", frames.get(0).getLocation());
        }
        return new ObjectMapper().writeValueAsString(metadata);
    } catch (Exception e) {
        return "{}";
    }
}

private String buildAnalysisPrompt(AgentContext context, ExceptionInfo info, List<StackFrame> frames) {
    StringBuilder prompt = new StringBuilder();
    prompt.append("你是一个专业的 Java 代码分析和故障诊断专家。请分析以下异常，提供详细的根因分析和修复建议。\n\n");

    prompt.append("## 异常信息\n");
    prompt.append("- **异常类型**: ").append(info.type).append("\n");
    if (info.message != null && !info.message.isEmpty()) {
        prompt.append("- **异常消息**: ").append(info.message).append("\n");
    }

    if (context.getErrorMessage() != null && !context.getErrorMessage().trim().isEmpty()) {
        prompt.append("- **错误描述**: ").append(context.getErrorMessage()).append("\n");
    }

    prompt.append("\n## 堆栈追踪（已过滤，仅显示业务代码）\n```\n");
    int frameCount = Math.min(frames.size(), 10);
    for (int i = 0; i < frameCount; i++) {
        StackFrame frame = frames.get(i);
        prompt.append(String.format("  at %s.%s(%s:%d)\n",
                frame.getClassName(), frame.getMethodName(),
                frame.getFileName(), frame.getLineNumber()));
    }
    if (frames.size() > frameCount) {
        prompt.append("  ... ").append(frames.size() - frameCount).append(" more\n");
    }
    prompt.append("```\n\n");

    prompt.append("请分析根因并给出修复建议。\n");

    return prompt.toString();
}
```

**Step 2: 验证编译通过**

Run: `cd hisi-dev-tool && mvn compile -q`
Expected: BUILD SUCCESS

**Step 3: Commit**

```bash
git add src/main/java/com/huawei/hisi/agent/impl/StackTraceAgent.java
git commit -m "feat(agent): StackTraceAgent实现流式诊断方法"
```

---

## Task 5: AgentOrchestrator 新增 diagnoseStream 方法

**Files:**
- Modify: `hisi-dev-tool/src/main/java/com/huawei/hisi/agent/orchestrator/AgentOrchestrator.java`

**Step 1: 添加 diagnoseStream 方法**

```java
// 在 AgentOrchestrator.java 中添加

import reactor.core.publisher.Flux;

/**
 * 流式诊断 - 支持SSE推送
 */
public Flux<AgentEvent> diagnoseStream(AgentContext context) {
    return Flux.create(emitter -> {
        String requestId = context.getRequestId();

        log.info("[{}] Starting streaming multi-agent diagnosis", requestId);

        // 发布开始事件
        emitter.next(AgentEvent.orchestrationStart(requestId));
        emitter.next(AgentEvent.requestReceived(requestId));

        try {
            // 计算置信度并筛选Agent
            Map<DiagnosticAgent, Double> agentConfidences = calculateConfidences(context);
            List<DiagnosticAgent> executableAgents = filterExecutableAgents(agentConfidences);

            if (executableAgents.isEmpty()) {
                emitter.next(AgentEvent.agentSkipped(requestId, "NONE", "没有可用的诊断Agent"));
                emitter.next(AgentEvent.orchestrationEnd(requestId));
                emitter.complete();
                return;
            }

            // 执行第一个Agent (StackTraceAgent)
            DiagnosticAgent primaryAgent = executableAgents.get(0);
            String agentType = primaryAgent.getAgentType();

            emitter.next(AgentEvent.agentStarted(requestId, agentType, primaryAgent.getAgentName()));

            // 调用流式方法
            if (primaryAgent instanceof StackTraceAgent) {
                StackTraceAgent stackTraceAgent = (StackTraceAgent) primaryAgent;
                AgentResult result = stackTraceAgent.executeStreaming(context);

                if (result.isStreaming()) {
                    // 流式输出内容
                    result.getStream().subscribe(
                            chunk -> emitter.next(AgentEvent.output(requestId, chunk)),
                            error -> {
                                log.error("[{}] Stream error: {}", requestId, error.getMessage());
                                emitter.next(AgentEvent.agentFailed(requestId, agentType, error.getMessage()));
                                emitter.next(AgentEvent.orchestrationEnd(requestId));
                                emitter.complete();
                            },
                            () -> {
                                emitter.next(AgentEvent.agentCompleted(requestId, agentType));
                                emitter.next(AgentEvent.orchestrationEnd(requestId));
                                emitter.complete();
                            }
                    );
                } else {
                    // 非流式结果，直接完成
                    emitter.next(AgentEvent.agentCompleted(requestId, agentType));
                    emitter.next(AgentEvent.orchestrationEnd(requestId));
                    emitter.complete();
                }
            }

        } catch (Exception e) {
            log.error("[{}] Streaming diagnosis failed: {}", requestId, e.getMessage(), e);
            emitter.next(AgentEvent.agentFailed(requestId, "ORCHESTRATOR", e.getMessage()));
            emitter.next(AgentEvent.orchestrationEnd(requestId));
            emitter.complete();
        }
    });
}
```

**Step 2: 在 AgentEvent 中添加 output 事件类型**

```java
// 在 AgentEvent.java 中添加静态方法
public static AgentEvent output(String requestId, String content) {
    return AgentEvent.builder()
            .requestId(requestId)
            .eventType("output")
            .message(content)
            .timestamp(LocalDateTime.now())
            .build();
}
```

**Step 3: 验证编译通过**

Run: `cd hisi-dev-tool && mvn compile -q`
Expected: BUILD SUCCESS

**Step 4: Commit**

```bash
git add src/main/java/com/huawei/hisi/agent/orchestrator/AgentOrchestrator.java
git add src/main/java/com/huawei/hisi/agent/model/AgentEvent.java
git commit -m "feat(agent): AgentOrchestrator新增流式诊断方法"
```

---

## Task 6: DiagnosisController 改为 SSE 输出

**Files:**
- Modify: `hisi-dev-tool/src/main/java/com/huawei/hisi/agent/controller/DiagnosisController.java`

**Step 1: 修改 analyze 方法为 SSE 输出**

```java
// DiagnosisController.java - 修改 analyze 方法

@PostMapping(value = "/analyze", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
public SseEmitter analyze(@RequestBody DiagnosisRequest request) {
    log.info("Received streaming diagnosis request: {}", request.getRequestId());

    SseEmitter emitter = new SseEmitter(600000L); // 10分钟超时

    AgentContext context = AgentContext.builder()
            .requestId(request.getRequestId() != null ? request.getRequestId() : UUID.randomUUID().toString())
            .projectPath(request.getProjectPath())
            .errorMessage(request.getErrorMessage())
            .stackTrace(request.getStackTrace())
            .logContent(request.getLogContent())
            .sessionId(request.getSessionId())
            .build();

    final String sessionId = context.getSessionId();

    // 订阅流式事件
    orchestrator.diagnoseStream(context).subscribe(
            event -> {
                try {
                    // 处理 output 事件 - 直接发送内容
                    if ("output".equals(event.getEventType())) {
                        emitter.send(SseEmitter.event()
                                .name("output")
                                .data(event.getMessage()));
                    } else if ("session".equals(event.getEventType())) {
                        emitter.send(SseEmitter.event()
                                .name("session")
                                .data(sessionId));
                    } else {
                        emitter.send(SseEmitter.event()
                                .name(event.getEventType())
                                .data(event.getMessage() != null ? event.getMessage() : ""));
                    }
                } catch (IOException e) {
                    log.error("SSE send error: {}", e.getMessage());
                }
            },
            error -> {
                log.error("Diagnosis stream error: {}", error.getMessage());
                try {
                    emitter.send(SseEmitter.event()
                            .name("error")
                            .data(error.getMessage()));
                    emitter.complete();
                } catch (IOException e) {
                    emitter.completeWithError(error);
                }
            },
            () -> {
                try {
                    emitter.send(SseEmitter.event()
                            .name("done")
                            .data("completed"));
                    emitter.complete();
                } catch (IOException e) {
                    log.error("SSE done send error: {}", e.getMessage());
                }
            }
    );

    emitter.onTimeout(() -> {
        log.warn("SSE connection timeout for request: {}", context.getRequestId());
    });

    return emitter;
}
```

**Step 2: 在 DiagnosisRequest 中添加 sessionId 字段**

```java
// DiagnosisRequest.java 中添加
private String sessionId;
```

**Step 3: 验证编译通过**

Run: `cd hisi-dev-tool && mvn compile -q`
Expected: BUILD SUCCESS

**Step 4: Commit**

```bash
git add src/main/java/com/huawei/hisi/agent/controller/DiagnosisController.java
git commit -m "refactor(agent): DiagnosisController改为SSE流式输出"
```

---

## Task 7: 后端单元测试更新

**Files:**
- Modify: `hisi-dev-tool/src/test/java/com/huawei/hisi/agent/impl/StackTraceAgentTest.java`

**Step 1: 更新测试类 mock ClaudeSdkService**

```java
// StackTraceAgentTest.java - 修改测试

@ExtendWith(MockitoExtension.class)
@DisplayName("StackTraceAgent 单元测试")
class StackTraceAgentTest {

    @Mock
    private StackTraceFilter stackTraceFilter;

    @Mock
    private ClaudeSdkService claudeSdkService;  // 替换 LLMService

    @Mock
    private SessionService sessionService;      // 新增

    private StackTraceAgent agent;

    @BeforeEach
    void setUp() {
        agent = new StackTraceAgent(stackTraceFilter, claudeSdkService, sessionService);
    }

    // ... 保留现有测试，修改 LLMService 相关调用 ...

    @Test
    @DisplayName("测试流式执行 - 成功")
    void testExecuteStreamingSuccess() {
        String stackTrace = "java.lang.NullPointerException: test\n\tat com.example.Test.method(Test.java:10)";

        StackFrame frame = new StackFrame();
        frame.setClassName("com.example.Test");
        frame.setMethodName("method");
        frame.setFileName("Test.java");
        frame.setLineNumber(10);

        when(stackTraceFilter.filter(anyString())).thenReturn(List.of(frame));
        when(sessionService.createSession(anyString(), any(), anyString(), anyString()))
                .thenReturn(ClaudeSession.builder().id("session-123").build());
        when(claudeSdkService.streamQuery(anyString(), anyString()))
                .thenReturn(Flux.just("分析结果1", "分析结果2"));

        AgentContext context = AgentContext.builder()
                .requestId("req-001")
                .projectPath("/project")
                .stackTrace(stackTrace)
                .build();

        AgentResult result = agent.executeStreaming(context);

        assertTrue(result.isStreaming());
        assertEquals("session-123", result.getSessionId());

        // 验证流式内容
        StepVerifier.create(result.getStream())
                .expectNext("分析结果1")
                .expectNext("分析结果2")
                .verifyComplete();
    }
}
```

**Step 2: 运行测试验证**

Run: `cd hisi-dev-tool && mvn test -Dtest=StackTraceAgentTest -q`
Expected: Tests run: X, Failures: 0

**Step 3: Commit**

```bash
git add src/test/java/com/huawei/hisi/agent/impl/StackTraceAgentTest.java
git commit -m "test(agent): 更新StackTraceAgent测试为ClaudeSdkService"
```

---

## Task 8: 前端移除独立诊断页面

**Files:**
- Delete: `hisi-dev-tool-frontend/src/views/diagnostic/DiagnosticView.vue`
- Delete: `hisi-dev-tool-frontend/src/views/diagnostic/components/AgentProgressPanel.vue`
- Delete: `hisi-dev-tool-frontend/src/views/diagnostic/components/DiagnosticResultPanel.vue`
- Delete: `hisi-dev-tool-frontend/src/views/diagnostic/` 目录
- Modify: `hisi-dev-tool-frontend/src/router/index.ts`

**Step 1: 删除诊断页面目录**

Run: `rm -rf hisi-dev-tool-frontend/src/views/diagnostic/`

**Step 2: 移除路由配置**

```typescript
// router/index.ts - 移除以下内容
// 删除:
// {
//   path: '/diagnostic',
//   name: 'Diagnostic',
//   component: () => import('@/views/diagnostic/DiagnosticView.vue'),
//   meta: { title: '智能诊断' }
// },
```

**Step 3: 移除侧边栏菜单项**

```typescript
// 在 AppSidebar.vue 或菜单配置中移除"智能诊断"菜单项
```

**Step 4: 验证前端编译**

Run: `cd hisi-dev-tool-frontend && npm run build`
Expected: Build success without errors

**Step 5: Commit**

```bash
git add -A
git commit -m "refactor(frontend): 移除独立诊断页面，整合到日志查询流程"
```

---

## Task 9: 前端 LogQuery 整合 Agent 诊断

**Files:**
- Modify: `hisi-dev-tool-frontend/src/views/log-analysis/LogQuery.vue`
- Modify: `hisi-dev-tool-frontend/src/api/logAnalysis.ts`

**Step 1: 在 api/logAnalysis.ts 添加 Agent 诊断接口**

```typescript
// api/logAnalysis.ts 添加

export interface AgentDiagnosisRequest {
  errorMessage: string
  errorType?: string
  stackTrace?: string
  projectPath?: string
  sessionId?: string
}

/**
 * Agent诊断 - SSE流式输出
 */
export function diagnoseWithAgent(
  params: AgentDiagnosisRequest,
  callbacks: {
    onSession?: (sessionId: string) => void
    onOutput?: (content: string) => void
    onDone?: () => void
    onError?: (error: string) => void
  }
): () => void {
  const queryParams = new URLSearchParams()
  queryParams.append('errorMessage', params.errorMessage)
  if (params.errorType) queryParams.append('errorType', params.errorType)
  if (params.stackTrace) queryParams.append('stackTrace', params.stackTrace)
  if (params.projectPath) queryParams.append('projectPath', params.projectPath)
  if (params.sessionId) queryParams.append('sessionId', params.sessionId)

  // POST 请求体方式
  fetch('/api/diagnosis/analyze', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(params)
  }).then(response => {
    const reader = response.body?.getReader()
    if (!reader) throw new Error('No reader')

    const decoder = new TextDecoder()
    let buffer = ''

    const readChunk = () => {
      reader.read().then(({ done, value }) => {
        if (done) {
          callbacks.onDone?.()
          return
        }

        buffer += decoder.decode(value, { stream: true })
        const lines = buffer.split('\n')
        buffer = lines.pop() || ''

        for (const line of lines) {
          if (line.startsWith('event:session')) {
            // 下一个data行是sessionId
          } else if (line.startsWith('data:')) {
            const content = line.slice(5).trim()
            callbacks.onOutput?.(content)
          }
        }

        readChunk()
      })
    }

    readChunk()
  }).catch(err => callbacks.onError?.(err.message))

  // 返回取消函数
  return () => {}
}
```

**Step 2: 修改 LogQuery.vue 分析按钮调用**

```vue
<!-- LogQuery.vue - 修改 handleAnalyze 方法 -->

<script setup lang="ts">
import { diagnoseWithAgent } from '@/api/logAnalysis'

// 修改现有的 handleAnalyze 方法
const handleAnalyze = async (row: LogEntry) => {
  // 解析日志
  const rawLog = row.message || row.stackTrace || ''
  const parsed = parseJavaErrorLog(rawLog)

  // 调用 Agent 诊断
  currentAnalysisSession.value = null
  analysisOutput.value = ''
  analysisVisible.value = true
  analysisLoading.value = true

  const cancel = diagnoseWithAgent(
    {
      errorMessage: parsed.errorMessage || rawLog,
      errorType: parsed.errorType,
      stackTrace: parsed.rawStackTrace,
      projectPath: row.serviceName
    },
    {
      onSession: (sessionId) => {
        currentAnalysisSession.value = sessionId
      },
      onOutput: (content) => {
        analysisOutput.value += content
      },
      onDone: () => {
        analysisLoading.value = false
      },
      onError: (error) => {
        analysisError.value = error
        analysisLoading.value = false
      }
    }
  )
}
</script>
```

**Step 3: 验证前端编译**

Run: `cd hisi-dev-tool-frontend && npm run build`
Expected: Build success

**Step 4: Commit**

```bash
git add src/views/log-analysis/LogQuery.vue src/api/logAnalysis.ts
git commit -m "feat(frontend): LogQuery整合Agent诊断，移除Claude直接调用"
```

---

## Task 10: 端到端测试验证

**Files:**
- Create: `hisi-dev-tool-frontend/e2e/agent-diagnosis.spec.ts`

**Step 1: 创建端到端测试**

```typescript
// e2e/agent-diagnosis.spec.ts

import { test, expect } from '@playwright/test'

const BASE_URL = process.env.FRONTEND_URL || 'http://localhost:5173'
const BACKEND_URL = process.env.BACKEND_URL || 'http://localhost:8080'

test.describe('Agent诊断整合测试', () => {

  test('日志查询页面分析按钮正常工作', async ({ page }) => {
    await page.goto(`${BASE_URL}/log-analysis`)

    // 等待页面加载
    await expect(page.locator('.log-query')).toBeVisible()

    // 查询日志
    await page.click('button:has-text("查询")')

    // 等待日志列表
    await page.waitForSelector('.el-table__row', { timeout: 10000 })

    // 点击第一个分析按钮
    const analyzeButton = page.locator('button:has-text("分析")').first()
    await expect(analyzeButton).toBeVisible()
  })

  test('独立诊断页面已移除', async ({ page }) => {
    await page.goto(`${BASE_URL}/diagnostic`)

    // 应该重定向或显示404
    await expect(page.locator('body')).not.toContainText('智能诊断')
  })

  test('Agent诊断API可用', async () => {
    const response = await fetch(`${BACKEND_URL}/api/diagnosis/analyze`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        errorMessage: 'Test error',
        stackTrace: 'java.lang.Exception\n\tat Test.main(Test.java:1)'
      })
    })

    expect(response.ok).toBeTruthy()
    expect(response.headers.get('content-type')).toContain('text/event-stream')
  })
})
```

**Step 2: 运行端到端测试**

Run: `cd hisi-dev-tool-frontend && npx playwright test e2e/agent-diagnosis.spec.ts`
Expected: All tests pass

**Step 3: Commit**

```bash
git add e2e/agent-diagnosis.spec.ts
git commit -m "test(e2e): 添加Agent诊断整合端到端测试"
```

---

## Task 11: 回归测试和文档更新

**Files:**
- Modify: `hisi-dev-tool/docs/RELEASE-NOTES-v4.1.md`

**Step 1: 运行完整后端测试**

Run: `cd hisi-dev-tool && mvn test`
Expected: Tests run: X, Failures: 0, Errors: 0

**Step 2: 运行完整前端测试**

Run: `cd hisi-dev-tool-frontend && npm run test`
Expected: All tests pass

**Step 3: 更新版本说明**

```markdown
// RELEASE-NOTES-v4.1.md 添加说明

## 架构调整

### Agent框架整合

v4.1.1 版本对 Agent 框架进行了重要整合：

1. **移除独立诊断页面**：原有的 `/diagnostic` 页面已移除，诊断功能整合到日志查询流程
2. **StackTraceAgent 增强**：现在调用 ClaudeSdkService，支持工具调用和多轮对话
3. **统一 SSE 输出**：所有诊断输出统一使用 SSE 流式推送，移除 WebSocket

这一调整解决了两套诊断系统并存的问题，提升了用户体验一致性。
```

**Step 4: 最终 Commit**

```bash
git add docs/RELEASE-NOTES-v4.1.md
git commit -m "docs: 更新版本说明，记录Agent框架整合变更"
```

---

## 验收标准

- [ ] 后端所有测试通过
- [ ] 前端构建成功
- [ ] 端到端测试通过
- [ ] 日志查询→分析流程正常工作
- [ ] 独立诊断页面已移除
- [ ] 多轮对话功能保留

---

**计划创建时间**: 2026-04-05
**预计实施时间**: 4天