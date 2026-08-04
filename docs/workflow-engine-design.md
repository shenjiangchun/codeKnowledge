# 可插拔工作流引擎设计文档

> 创建日期: 2026-06-22
> 状态: Phase 1-3 已完成，Phase 4-7 待实施
> 关联整改计划: docs/refactor-plan-ram-log-analysis.md

---

## 一、背景与动机

### 1.1 当前问题

项目中存在 4 种工作流，各自独立实现，导致：

| 问题 | 具体表现 |
|------|----------|
| 数据获取模式不统一 | DraftPage 用 SSE 事件流，StatusPage 用 REST+轮询，LogAnalysis 用异步任务 |
| 接口不兼容 | LogAnalysis 有独立的 DagNode 接口，与 RAM 的 DagNode 结构相同但不互通 |
| Claude SDK 调用散落 | 6+ 个 LlmClient 各自实现，无统一抽象 |
| 新工作流成本高 | 需要从零实现 Controller + 编排器 + 前端页面 |
| 无法用户自定义 | 工作流 DAG 硬编码在 Java 代码中 |

### 1.2 已验证的设计决策

通过整改过程中的实际问题验证：

1. **REST 权威 + SSE 增量**：StatusPage 的无限重连 Bug 证明，SSE 不能作为唯一数据来源。必须先用 REST 获取权威数据，再用 SSE 增量更新。
2. **DagExecutor 可复用**：MergeAnalysis 已成功复用 RAM 的 DagExecutor，证明统一编排器可行。
3. **useRamSession 可泛化**：当前 composable 已管理 SSE 连接、状态机、事件去重，只需泛化为通用 workflow composable。

---

## 二、现有架构分析

### 2.1 四种工作流对比

| 工作流 | 入口 | 编排器 | DAG 节点 | HITL | 数据获取 |
|--------|------|--------|----------|------|----------|
| RAM 需求分析 | RamController | DagExecutor | 4+1 | clarify + confirm | SSE 事件流 |
| 合入分析 | MergeAnalysisController | DagExecutor | 3 | 无 | SSE 事件流 |
| 日志分析 | LogAnalysisController | LogAnalysisDagOrchestrator | 5 | 无 | 异步任务 + 轮询 |
| APM 诊断 | DiagnosisController | AgentOrchestrator | N (动态) | 无 | 流式 Flux |

### 2.2 可复用的公共组件

**后端（已存在，可直接复用）**：

| 组件 | 位置 | 说明 |
|------|------|------|
| DagExecutor | ram/orchestrator/ | DAG 编排 + checkpoint 缓存 + HITL 门控 |
| DagNode 接口 | ram/orchestrator/ | 节点契约：name() + execute() |
| InputsHasher | ram/orchestrator/ | 输入哈希，支持 checkpoint 缓存 |
| ClarifyRequiredException | ram/orchestrator/ | 澄清暂停异常 |
| AnthropicHttpClient | ram/sdk/impl/ | OkHttp SSE 客户端 |
| RamClaudeJsonClient | ram/nodes/impl/ | 单轮 JSON / 多轮 tool_use |
| KgMcpClient | ram/kg/ | 知识图谱查询接口 |
| KgToolRegistry | ram/nodes/impl/ | LLM tool_use 工具注册 |
| AgentSession/AgentEvent | ram/model/ | 会话和事件持久化模型 |
| AgentSessionRepository/AgentEventRepository | ram/repository/ | 数据访问层 |

**前端（已存在，可直接复用）**：

| 组件 | 位置 | 说明 |
|------|------|------|
| useRamSession | composables/ | SSE 连接管理 + 状态机 + 事件去重 |
| useDagEventHandler | composables/ | DAG 事件 → UI 状态映射 |
| dagModel.ts | components/ram/ | DAG 快照派生 |
| ram store | stores/ | Pinia store for impact payload |

### 2.3 当前数据流模式

**DraftPage（需求分析）— 事件驱动**：
```
initSession → rejoin(sid) → 加载历史事件 → useDagEventHandler 处理
  → 如果 session 运行中 → 开 SSE → 实时事件 → 更新 UI
  → 如果 session 已完成 → 不开 SSE → 从历史事件恢复 UI
  问题：已完成的 session 如果历史事件格式不匹配，UI 为空
```

**StatusPage（现状分析）— REST+轮询**：
```
initSession → getStatusReport(sid) → REST 获取报告
  → 如果 session 已完成 → 直接展示
  → 如果 session 运行中 → rejoin 开 SSE → 实时更新
  → SSE 失败 → fallback 轮询
  优势：REST 作为权威来源，不依赖 SSE 事件格式
```

**最优组合**：StatusPage 的 REST 权威策略 + DraftPage 的 SSE 增量更新

---

## 三、目标架构

### 3.1 整体架构图

```
┌─────────────────────────────────────────────────────────────┐
│                        前端层                                 │
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐       │
│  │ WorkflowPage  │  │ WorkflowPage  │  │ WorkflowPage  │       │
│  │ (demand)      │  │ (status)      │  │ (custom)      │       │
│  └──────┬───────┘  └──────┬───────┘  └──────┬───────┘       │
│         └─────────────────┼─────────────────┘               │
│                    ┌──────┴───────┐                          │
│                    │ useWorkflow   │  ← 统一 composable       │
│                    │ composable    │                          │
│                    └──────┬───────┘                          │
│         ┌─────────────────┼─────────────────┐               │
│    REST API          SSE Stream         REST Report          │
└─────────┼─────────────────┼─────────────────┼───────────────┘
          │                 │                 │
┌─────────┼─────────────────┼─────────────────┼───────────────┐
│         ▼                 ▼                 ▼   后端层        │
│  ┌──────────────────────────────────────────────┐           │
│  │            WorkflowController                  │           │
│  │  /workflow/start  /sessions/{sid}/stream       │           │
│  │  /sessions/{sid}/status  /sessions/{sid}/report│           │
│  └──────────────────┬───────────────────────────┘           │
│                     │                                        │
│  ┌──────────────────┴───────────────────────────┐           │
│  │           WorkflowRegistry                     │           │
│  │  注册节点类型 + 工作流定义                       │           │
│  └──────────────────┬───────────────────────────┘           │
│                     │                                        │
│  ┌──────────────────┴───────────────────────────┐           │
│  │             DagExecutor                        │           │
│  │  拓扑排序 + checkpoint 缓存 + HITL 门控         │           │
│  └──────────────────┬───────────────────────────┘           │
│                     │                                        │
│  ┌──────┬──────┬────┴────┬──────┬──────┐                    │
│  │Node1 │Node2 │  Node3  │Node4 │Node5 │                    │
│  │clarify│impact│implement│verify│report│                    │
│  └──────┴──────┴─────────┴──────┴──────┘                    │
│                                                              │
│  ┌──────────────────────────────────────────────┐           │
│  │         Claude SDK (公共)                       │           │
│  │  AnthropicHttpClient → RamClaudeJsonClient      │           │
│  │  KgToolRegistry (tool_use 工具集)               │           │
│  └──────────────────────────────────────────────┘           │
└──────────────────────────────────────────────────────────────┘
```

### 3.2 核心接口设计

#### DagNode（统一节点接口）

```java
package com.huawei.hisi.workflow;

/**
 * 工作流节点统一接口。
 * 所有工作流的节点都实现此接口。
 */
public interface DagNode {
    /** 节点唯一标识，如 "clarify", "impact", "kg-search" */
    String name();
    
    /** UI 显示名，如 "需求澄清", "影响分析" */
    String displayName();
    
    /** 依赖的前置节点名列表 */
    Set<String> dependsOn();
    
    /** 是否支持 HITL 人工确认暂停 */
    default boolean supportsHITL() { return false; }
    
    /** 是否支持澄清暂停 */
    default boolean supportsClarify() { return false; }
    
    /** 执行节点逻辑 */
    Map<String, Object> execute(Map<String, Object> input) throws Exception;
}
```

#### WorkflowDefinition（工作流定义）

```java
package com.huawei.hisi.workflow;

/**
 * 工作流定义：描述一个完整的工作流 DAG。
 */
public record WorkflowDefinition(
    String workflowType,              // 唯一标识: "demand", "status", "log", "custom-xxx"
    String displayName,               // UI 显示名
    String description,               // 描述
    List<String> nodeNames,           // 节点名列表（按拓扑序）
    boolean hitlEnabled,              // 全局 HITL 开关
    boolean clarifyEnabled,           // 全局澄清开关
    Map<String, Object> metadata      // 扩展元数据
) {}
```

#### WorkflowRegistry（注册中心）

```java
package com.huawei.hisi.workflow;

@Component
public class WorkflowRegistry {
    private final Map<String, DagNode> nodeRegistry = new ConcurrentHashMap<>();
    private final Map<String, WorkflowDefinition> workflowRegistry = new ConcurrentHashMap<>();
    
    /** 注册节点（Spring 自动发现） */
    @Autowired
    public void registerNodes(List<DagNode> nodes) {
        nodes.forEach(n -> nodeRegistry.put(n.name(), n));
    }
    
    /** 注册工作流定义 */
    public void registerWorkflow(WorkflowDefinition def) {
        workflowRegistry.put(def.workflowType(), def);
    }
    
    /** 获取工作流定义 */
    public WorkflowDefinition getWorkflow(String type) {
        return workflowRegistry.get(type);
    }
    
    /** 获取所有可用节点（供 UI 选择） */
    public Map<String, DagNode> getAvailableNodes() {
        return Collections.unmodifiableMap(nodeRegistry);
    }
    
    /** 构建工作流（从节点名列表组装 DAG） */
    public WorkflowDefinition buildWorkflow(String type, String displayName, List<String> nodeNames) {
        // 验证节点存在
        // 验证依赖关系无环
        // 返回 WorkflowDefinition
    }
}
```

#### WorkflowController（统一 API）

```java
@RestController
@RequestMapping("/api/workflow")
public class WorkflowController {
    
    @PostMapping("/start")
    public StartResponse start(@RequestBody StartRequest req) { ... }
    
    @GetMapping("/sessions/{sid}/stream")
    public SseEmitter stream(@PathVariable String sid, 
                             @RequestParam(required=false) Long afterSeq) { ... }
    
    @GetMapping("/sessions/{sid}/status")
    public StatusResponse getStatus(@PathVariable String sid) { ... }
    
    @GetMapping("/sessions/{sid}/report")
    public ReportResponse getReport(@PathVariable String sid) { ... }
    
    @GetMapping("/sessions/{sid}/events")
    public List<EventResponse> getEvents(@PathVariable String sid) { ... }
    
    @PostMapping("/sessions/{sid}/clarify")
    public ClarifyResponse clarify(@PathVariable String sid, @RequestBody ClarifyRequest req) { ... }
    
    @PostMapping("/sessions/{sid}/confirm")
    public ConfirmResponse confirm(@PathVariable String sid, @RequestBody ConfirmRequest req) { ... }
    
    @PostMapping("/sessions/{sid}/rerun-from/{nodeName}")
    public RerunResponse rerunFrom(@PathVariable String sid, @PathVariable String nodeName) { ... }
    
    @PostMapping("/sessions/{sid}/abort")
    public AbortResponse abort(@PathVariable String sid) { ... }
    
    @GetMapping("/definitions")
    public List<WorkflowDefinition> listDefinitions() { ... }
    
    @GetMapping("/nodes")
    public Map<String, NodeInfo> listNodes() { ... }
}
```

### 3.3 前端 useWorkflow composable

```typescript
// composables/useWorkflow.ts

export interface WorkflowOptions {
  workflowType: string
}

export function useWorkflow(options: WorkflowOptions) {
    // === 状态 ===
    const sessionId = ref<string | null>(null)
    const events = ref<WorkflowEvent[]>([])
    const status = ref<WorkflowStatus>('idle')
    const report = ref<Record<string, unknown> | null>(null)
    const nodes = ref<DagNodeSnapshot[]>([])
    const cost = ref<CostSnapshot>({ tokens: 0, usd: 0 })
    const clarifyQuestions = ref<ClarifySchema | null>(null)
    const hitlSchema = ref<HitlSchema | null>(null)
    
    // === 核心方法 ===
    
    /** 创建并启动工作流 */
    async function start(input: WorkflowInput): Promise<string> {
        const resp = await workflowApi.start({
            workflowType: options.workflowType,
            ...input
        })
        sessionId.value = resp.sessionId
        status.value = 'running'
        openSseStream(resp.sessionId)
        return resp.sessionId
    }
    
    /** 重新接入已有会话（页面刷新/跳转回来） */
    async function rejoin(sid: string): Promise<void> {
        sessionId.value = sid
        
        // Step 1: REST 获取权威数据
        await loadFromRest(sid)
        
        // Step 2: 如果 session 仍在运行，开 SSE 增量更新
        if (status.value === 'running' || status.value === 'idle') {
            status.value = 'running'
            openSseStream(sid)
        }
    }
    
    /** REST 获取权威数据 */
    async function loadFromRest(sid: string): Promise<void> {
        const [statusResp, reportResp, eventsResp] = await Promise.all([
            workflowApi.getStatus(sid).catch(() => null),
            workflowApi.getReport(sid).catch(() => null),
            workflowApi.getEvents(sid).catch(() => [])
        ])
        
        // 从 status 推导终态
        if (statusResp) {
            if (statusResp.status === 'DONE') status.value = 'completed'
            else if (statusResp.status === 'FAILED') status.value = 'error'
            else if (statusResp.status === 'ABORTED') status.value = 'aborted'
        }
        
        // 从 report 获取报告数据
        if (reportResp?.report) {
            report.value = reportResp.report
        }
        
        // 从 events 恢复历史事件
        if (eventsResp?.length > 0) {
            events.value = eventsResp
            lastSeq.value = Math.max(...eventsResp.map(e => e.seq ?? 0))
        }
    }
    
    /** 提交澄清 */
    async function submitClarify(answers: Record<string, unknown>): Promise<void> {
        await workflowApi.clarify(sessionId.value!, { answers })
        clarifyQuestions.value = null
        status.value = 'running'
        openSseStream(sessionId.value!)
    }
    
    /** HITL 确认 */
    async function submitConfirm(action: 'approve' | 'reject', feedback?: string): Promise<void> {
        await workflowApi.confirm(sessionId.value!, { action, feedback })
        hitlSchema.value = null
        status.value = 'running'
        openSseStream(sessionId.value!)
    }
    
    /** 从指定节点重跑 */
    async function rerunFromNode(nodeName: string): Promise<void> {
        await workflowApi.rerunFrom(sessionId.value!, nodeName)
        openSseStream(sessionId.value!)
    }
    
    /** 终止 */
    async function abort(): Promise<void> {
        await workflowApi.abort(sessionId.value!)
        status.value = 'aborted'
        tearDown()
    }
    
    /** 断开 SSE */
    function disconnect(): void {
        tearDown()
    }
    
    // === 内部：SSE 管理 ===
    let source: EventSource | null = null
    
    function openSseStream(sid: string): void {
        tearDown()
        const url = `/api/workflow/sessions/${sid}/stream?afterSeq=${lastSeq.value}`
        source = new EventSource(url)
        
        source.onmessage = (raw) => {
            const evt = JSON.parse(raw.data)
            if (evt.seq <= lastSeq.value) return  // 去重
            lastSeq.value = evt.seq
            events.value = [...events.value, evt]
            handleEvent(evt)
        }
        
        source.onerror = () => {
            if (source?.readyState === EventSource.CLOSED) {
                // 连接被服务端关闭（session 终态），不再重连
                return
            }
            // 网络错误，浏览器会自动重连
        }
    }
    
    function handleEvent(evt: WorkflowEvent): void {
        switch (evt.type) {
            case 'CHECKPOINT':
                // 更新对应节点的输出
                const nodeName = evt.payload['nodeName']
                updateNodeOutput(nodeName, evt.payload)
                break
            case 'CLARIFY_REQ':
                clarifyQuestions.value = evt.payload
                status.value = 'clarify'
                tearDown()
                break
            case 'HITL_REQ':
                hitlSchema.value = evt.payload
                status.value = 'confirm'
                tearDown()
                break
            case 'RUN_COMPLETED':
                status.value = 'completed'
                tearDown()
                break
            case 'RUN_FAILED':
            case 'ERROR':
                status.value = 'error'
                tearDown()
                break
            case 'RUN_ABORTED':
                status.value = 'aborted'
                tearDown()
                break
        }
    }
    
    function tearDown(): void {
        if (source) {
            source.close()
            source = null
        }
    }
    
    return {
        sessionId, events, status, report, nodes, cost,
        clarifyQuestions, hitlSchema,
        start, rejoin, submitClarify, submitConfirm,
        rerunFromNode, abort, disconnect
    }
}
```

### 3.4 用户自定义工作流

#### YAML 定义格式

```yaml
# workflows/custom-analysis.yaml
name: custom-analysis
displayName: 自定义代码分析
description: 用户自定义的代码分析工作流

nodes:
  - name: search
    type: builtin:kg-search
    dependsOn: []
    config:
      query: "${input.query}"
      
  - name: load-code
    type: builtin:code-context
    dependsOn: [search]
    
  - name: analyze
    type: builtin:claude-analyze
    dependsOn: [load-code]
    config:
      systemPrompt: |
        分析以下代码，找出潜在问题。
        输入: ${input.description}
        
  - name: report
    type: builtin:report
    dependsOn: [analyze]

hitlEnabled: false
clarifyEnabled: false
```

#### 内置节点类型

| 类型 | 说明 | HITL |
|------|------|------|
| `builtin:clarify` | 需求澄清 | 支持 |
| `builtin:impact` | 影响分析 | 支持 |
| `builtin:implement` | 实现方案 | 支持 |
| `builtin:verify` | 验证检查 | 支持 |
| `builtin:tech-plan` | 技术方案 | 支持 |
| `builtin:kg-search` | 知识图谱搜索 | 无 |
| `builtin:code-context` | 代码上下文加载 | 无 |
| `builtin:claude-analyze` | Claude AI 分析 | 无 |
| `builtin:report` | 报告生成 | 无 |
| `builtin:diff-extract` | Git diff 提取 | 无 |
| `builtin:test-scope` | 测试范围分析 | 无 |

---

## 四、迁移计划

### Phase 1: 提取公共基础设施（不影响现有功能）

**后端**：
- 将 `DagNode` 接口从 `ram/orchestrator/` 移到 `workflow/` 包
- 将 `DagExecutor` 从 `ram/orchestrator/` 移到 `workflow/` 包
- 将 `InputsHasher`、`ClarifyRequiredException`、`ExecutionResult` 同步移动
- 更新所有 import 语句（IDE 自动重构）
- 验证：所有现有测试通过

**前端**：
- 无变更

### Phase 2: 创建 WorkflowRegistry + WorkflowController（新增 API）

**后端**：
- 创建 `WorkflowRegistry` 组件
- 创建 `WorkflowDefinition` record
- 创建 `WorkflowController`（新 API 端点）
- 将现有 RAM 工作流注册到 WorkflowRegistry
- 验证：新 API 可调用，旧 API 不受影响

**前端**：
- 无变更

### Phase 3: 创建前端 useWorkflow composable（新增组件）

**前端**：
- 创建 `composables/useWorkflow.ts`
- 创建 `api/workflow.ts`（API 封装）
- 创建 `types/workflow.ts`（类型定义）
- 验证：useWorkflow 可驱动 RAM 需求分析流程

### Phase 4: 迁移 RAM 需求分析到新架构

**后端**：
- `RamController` 保留旧 API（向后兼容）
- 新增 `/workflow` API 调用 WorkflowRegistry

**前端**：
- DraftPage 改用 `useWorkflow` composable
- 删除 `useRamSession`（被 useWorkflow 替代）
- 删除 `useDagEventHandler`（逻辑合并到 useWorkflow）
- 验证：RAM 需求分析完整流程正常

### Phase 5: 迁移 StatusPage/Phase2Page

**前端**：
- StatusPage 改用 `useWorkflow({ workflowType: 'status' })`
- Phase2Page 改用 `useWorkflow({ workflowType: 'phase2' })`
- 删除 fallback 轮询逻辑
- 验证：现状分析和 Phase2 正常，无无限重连

### Phase 6: 迁移 LogAnalysis

**后端**：
- 将 `LogAnalysisDagNode` 适配为 `DagNode` 接口
- 将 `LogAnalysisDagOrchestrator` 替换为 `DagExecutor`
- 注册 LogAnalysis 工作流到 WorkflowRegistry

**前端**：
- LogQuery.vue 的分析流程改用 useWorkflow
- 验证：日志分析支持 checkpoint 缓存

### Phase 7: 用户自定义工作流

**后端**：
- 创建 YAML 工作流解析器
- 创建 `POST /workflow/definitions` API（注册自定义工作流）
- 验证：YAML 定义的工作流可执行

**前端**：
- 创建工作流编辑器页面（拖拽式 DAG 编辑）
- 创建节点选择面板（从 WorkflowRegistry 获取可用节点）
- 验证：用户可通过 UI 创建和执行自定义工作流

---

## 五、验证清单

| 阶段 | 验证点 | 通过标准 |
|------|--------|----------|
| Phase 1 | 所有现有测试 | 全部通过 |
| Phase 2 | 新 API 可调用 | POST /workflow/start 返回 sessionId |
| Phase 3 | useWorkflow 可用 | composable 正确管理状态机 |
| Phase 4 | RAM 需求分析 | 完整流程：创建→澄清→确认→完成 |
| Phase 5 | StatusPage | 无无限重连，报告正确展示 |
| Phase 6 | LogAnalysis | 支持 checkpoint 缓存 |
| Phase 7 | 自定义工作流 | YAML 定义 → 执行 → 获取报告 |

---

## 六、文件清单

### 新增文件

| 文件 | 说明 |
|------|------|
| `hisi-dev-tool/src/main/java/com/huawei/hisi/workflow/DagNode.java` | 统一节点接口 |
| `hisi-dev-tool/src/main/java/com/huawei/hisi/workflow/DagExecutor.java` | 统一编排器 |
| `hisi-dev-tool/src/main/java/com/huawei/hisi/workflow/WorkflowDefinition.java` | 工作流定义 |
| `hisi-dev-tool/src/main/java/com/huawei/hisi/workflow/WorkflowRegistry.java` | 注册中心 |
| `hisi-dev-tool/src/main/java/com/huawei/hisi/workflow/WorkflowController.java` | 统一 API |
| `hisi-dev-tool-frontend/src/composables/useWorkflow.ts` | 统一 composable |
| `hisi-dev-tool-frontend/src/api/workflow.ts` | API 封装 |
| `hisi-dev-tool-frontend/src/types/workflow.ts` | 类型定义 |

### 修改文件

| 文件 | 改动 |
|------|------|
| `ram/orchestrator/DagNode.java` | 移到 workflow 包，保留 deprecated 别名 |
| `ram/orchestrator/DagExecutor.java` | 移到 workflow 包 |
| `ram/controller/RamController.java` | 保留旧 API，新增委托到 WorkflowRegistry |
| `views/ram/DraftPage.vue` | 改用 useWorkflow |
| `views/ram/StatusPage.vue` | 改用 useWorkflow，删除轮询 |
| `views/ram/Phase2Page.vue` | 改用 useWorkflow，删除轮询 |
| `composables/useRamSession.ts` | deprecated，被 useWorkflow 替代 |

### 删除文件（Phase 4+ 完成后）

| 文件 | 原因 |
|------|------|
| `composables/useRamSession.ts` | 被 useWorkflow 替代 |
| `composables/useDagEventHandler.ts` | 逻辑合并到 useWorkflow |
| `loganalysis/orchestrator/LogAnalysisDagOrchestrator.java` | 被 DagExecutor 替代 |
| `loganalysis/orchestrator/LogAnalysisDagNode.java` | 被 DagNode 替代 |
