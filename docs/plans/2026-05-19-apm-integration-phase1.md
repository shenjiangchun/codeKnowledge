# APM Integration Phase 1 (MVP) Implementation Plan

**Date**: 2026-05-19
**Branch**: `worktree-feature+apm-integration`
**Estimated effort**: 7-10 days (12 tasks)

---

## Architecture Decisions (from roundtable)

| Decision | Choice | Rationale |
|----------|--------|-----------|
| OTLP Protocol | `http/json` | Zero new compile deps. Parse with existing Jackson. OTel agent supports `OTEL_EXPORTER_OTLP_PROTOCOL=http/json` natively. |
| New Maven Deps | **None** | Parse OTLP JSON with Jackson record DTOs. OTel agent JAR is runtime-only, downloaded on first use to `~/.hisi-devtool/otel-agent/`. |
| Agent Injection | `JAVA_TOOL_OPTIONS` env var | Works regardless of how target is launched (mvn, java -jar, gradle). Cleanest injection mechanism. |
| Readiness Detection | Socket poll on target port | 500ms interval, 60s max. More reliable than log parsing. |
| Span Storage | SQLite `debug_span` table | Follows existing JdbcTemplate + raw SQL pattern. Batch inserts. |
| Span-to-KG Mapping | 4-level fallback, cached in-memory | Level 1: className+methodName (unique). Level 2: overloaded (candidates). Level 3: proxy-stripped. Level 4: unmatched. |
| Runtime Call Chain | Separate `RUNTIME_CALLS` Neo4j relationship | Zero impact on existing KG queries. Session-scoped, opt-in persist. |
| WebSocket | Raw `TextWebSocketHandler` at `/ws/apm` | Follows existing TerminalWebSocketHandler / AgentEventPublisher pattern. |
| Frontend Visualization | ECharts graph series | Already installed (`echarts@^6.0.0`). Proven in existing ChainChart DAG view. |
| Frontend State | Composable local state (not Pinia) | Ephemeral session data, single-page scope. |

---

## Task Breakdown

### Task 1: OTLP JSON DTOs + Configuration

**Files to create:**
- `com.huawei.hisi.apm.model.OtlpTraceData.java` — Jackson records mirroring OTLP JSON structure
- `com.huawei.hisi.apm.config.ApmConfig.java` — `@ConfigurationProperties(prefix = "apm")`

**OTLP JSON payload structure** (only fields we need):
```java
public record OtlpTraceData(List<ResourceSpans> resourceSpans) {
    public record ResourceSpans(Resource resource, List<ScopeSpans> scopeSpans) {}
    public record Resource(List<KeyValue> attributes) {}
    public record ScopeSpans(List<SpanData> spans) {}
    public record SpanData(
        String traceId, String spanId, String parentSpanId,
        String name, int kind, String startTimeUnixNano, String endTimeUnixNano,
        Status status, List<KeyValue> attributes, List<Event> events
    ) {}
    public record Status(String code, String message) {}
    public record KeyValue(String key, Value value) {}
    public record Value(String stringValue, Long intValue, Boolean boolValue) {}
    public record Event(String name, String timeUnixNano, List<KeyValue> attributes) {}
}
```

**ApmConfig:**
```java
@Configuration
@ConfigurationProperties(prefix = "apm")
@Data
public class ApmConfig {
    private String otelAgentPath;                    // auto-resolved to ~/.hisi-devtool/otel-agent/
    private String otelAgentVersion = "2.14.0";      // for auto-download
    private int targetReadyTimeoutSeconds = 60;
    private int spanTtlHours = 24;
    private int targetShutdownGraceSeconds = 5;
}
```

**application.yml addition:**
```yaml
apm:
  otel-agent-version: "2.14.0"
  target-ready-timeout-seconds: 60
  span-ttl-hours: 24
```

**Acceptance criteria:** Classes compile. Jackson can deserialize sample OTLP JSON. Config is loaded at startup.

---

### Task 2: SQLite Schema + ApmSpanRepository

**Files to create:**
- `com.huawei.hisi.apm.repository.ApmSpanRepository.java`
- `com.huawei.hisi.apm.model.ApmSession.java`
- `com.huawei.hisi.apm.model.ApmSpanEntity.java`

**Schema additions to `SQLiteSchemaInitializer`:**
```sql
CREATE TABLE IF NOT EXISTS apm_session (
    id              TEXT PRIMARY KEY,
    project_path    TEXT NOT NULL,
    service_name    TEXT,
    target_port     INTEGER,
    status          TEXT DEFAULT 'CREATED',
    created_at      INTEGER DEFAULT (strftime('%s','now')),
    finished_at     INTEGER
);

CREATE TABLE IF NOT EXISTS apm_span (
    id              INTEGER PRIMARY KEY AUTOINCREMENT,
    session_id      TEXT NOT NULL,
    trace_id        TEXT NOT NULL,
    span_id         TEXT NOT NULL,
    parent_span_id  TEXT,
    service_name    TEXT,
    operation_name  TEXT NOT NULL,
    span_kind       TEXT,
    start_time_ns   INTEGER NOT NULL,
    end_time_ns     INTEGER NOT NULL,
    status_code     TEXT,
    status_message  TEXT,
    attributes      TEXT,
    resource_attrs  TEXT,
    kg_node_id      TEXT,
    kg_match_level  INTEGER DEFAULT 3,
    created_at      INTEGER DEFAULT (strftime('%s','now'))
);

CREATE INDEX IF NOT EXISTS idx_apm_span_trace ON apm_span(trace_id);
CREATE INDEX IF NOT EXISTS idx_apm_span_session ON apm_span(session_id);
CREATE INDEX IF NOT EXISTS idx_apm_span_created ON apm_span(created_at);
```

**Repository methods:**
- `batchInsert(List<ApmSpanEntity> spans)` — single transaction
- `findBySessionId(String sessionId)` — all spans for a session
- `findByTraceId(String traceId)` — all spans for a trace
- `deleteBySessionId(String sessionId)` — cleanup
- `deleteOlderThan(long epochSeconds)` — TTL cleanup
- `updateKgMapping(String spanId, String kgNodeId, int matchLevel)`

**Acceptance criteria:** Tables are created at startup. Batch insert of 500 spans < 50ms. TTL cleanup works.

---

### Task 3: OTel Agent Manager (Download + Cache)

**Files to create:**
- `com.huawei.hisi.apm.service.OtelAgentManager.java`

**Responsibilities:**
- Check if OTel agent JAR exists at `~/.hisi-devtool/otel-agent/opentelemetry-javaagent-{version}.jar`
- If missing, download from GitHub releases: `https://github.com/open-telemetry/opentelemetry-java-instrumentation/releases/download/v{version}/opentelemetry-javaagent.jar`
- Return the local path for ProcessBuilder to use

**Methods:**
- `String ensureAgentAvailable()` — returns path, downloads if needed
- `boolean isAgentAvailable()` — quick check

**Acceptance criteria:** Agent JAR is downloaded on first use. Subsequent calls return cached path.

---

### Task 4: TargetProcessManager

**Files to create:**
- `com.huawei.hisi.apm.service.TargetProcessManager.java`
- `com.huawei.hisi.apm.model.TargetProcessInfo.java`

**Design:**
- `ConcurrentHashMap<String, ManagedProcess>` keyed by sessionId
- `ManagedProcess`: holds `Process`, status, port, output ring buffer (500 lines)

**Launch flow:**
```java
public TargetProcessInfo launch(String sessionId, String projectPath, int targetPort) {
    String agentPath = otelAgentManager.ensureAgentAvailable();
    
    ProcessBuilder pb = new ProcessBuilder(buildCommand(projectPath));
    pb.directory(new File(projectPath));
    pb.redirectErrorStream(true);
    
    Map<String, String> env = new HashMap<>(pb.environment());
    env.put("JAVA_TOOL_OPTIONS", "-javaagent:" + agentPath);
    env.put("OTEL_SERVICE_NAME", extractServiceName(projectPath));
    env.put("OTEL_EXPORTER_OTLP_PROTOCOL", "http/json");
    env.put("OTEL_EXPORTER_OTLP_ENDPOINT", "http://localhost:" + serverPort);
    env.put("OTEL_TRACES_EXPORTER", "otlp");
    env.put("OTEL_METRICS_EXPORTER", "none");
    env.put("OTEL_LOGS_EXPORTER", "none");
    env.put("SERVER_PORT", String.valueOf(targetPort));
    pb.environment().putAll(env);
    
    Process process = pb.start();
    // Start daemon thread for stdout reading
    // Start readiness polling thread
}
```

**Build command detection:**
- If `pom.xml` exists: `mvn spring-boot:run` (or `mvnw`)
- If `build.gradle` exists: `gradle bootRun` (or `gradlew`)
- Fallback: look for `target/*.jar` and use `java -jar`
- Windows: prefix with `cmd /c`

**Readiness polling:**
- Socket connect to `localhost:targetPort` every 500ms
- Max 60s (configurable)
- On ready: notify via callback → WebSocket push

**Shutdown:**
- `process.destroy()` → wait 5s → `process.destroyForcibly()`
- Remove from map, close reader thread

**Acceptance criteria:** Can launch a Spring Boot project with OTel agent. Detects readiness. Forwards stdout. Cleans up on stop.

---

### Task 5: OTLP HTTP Receiver Controller

**Files to create:**
- `com.huawei.hisi.apm.controller.OtlpReceiverController.java`
- `com.huawei.hisi.apm.service.SpanIngestionService.java`

**OTLP endpoint:**
```java
@RestController
public class OtlpReceiverController {
    @PostMapping(value = "/v1/traces", consumes = "application/json")
    public ResponseEntity<Void> receiveTraces(@RequestBody OtlpTraceData traceData) {
        spanIngestionService.ingest(traceData);
        return ResponseEntity.ok().build();
    }
}
```

**SpanIngestionService pipeline (synchronous):**
1. Flatten `resourceSpans` → `scopeSpans` → `spans` into `List<ApmSpanEntity>`
2. Extract `service.name` from resource attributes
3. Find active session by service name → get sessionId
4. Run SpanToKgMapper on each span (in-memory lookup)
5. Batch insert into SQLite
6. Async push to WebSocket via `CompletableFuture.runAsync()`

**Acceptance criteria:** Can receive OTLP JSON POST from OTel agent. Spans stored in SQLite with correct fields. Returns 200 OK.

---

### Task 6: SpanToKgMapper

**Files to create:**
- `com.huawei.hisi.apm.service.SpanToKgMapper.java`

**Design:**
- On session start, load all MethodNodes for the project path into `Map<String, List<MethodNode>>` keyed by className
- 4-level matching:

```java
public record MatchResult(String nodeId, int matchLevel) {}

public Optional<MatchResult> match(ApmSpanEntity span) {
    // Level 1: exact className + methodName (unique match)
    String className = normalizeClassName(span.getAttributes().get("code.namespace"));
    String methodName = span.getAttributes().get("code.function");
    
    if (className != null && methodName != null) {
        List<MethodNode> candidates = methodIndex.get(className);
        if (candidates != null) {
            List<MethodNode> matches = candidates.stream()
                .filter(m -> m.getMethodName().equals(methodName))
                .toList();
            if (matches.size() == 1) return Optional.of(new MatchResult(matches.get(0).getNodeId(), 1));
            if (matches.size() > 1) return Optional.of(new MatchResult(matches.get(0).getNodeId(), 2)); // first candidate
        }
    }
    
    // Level 2: HTTP span → entry point
    // Level 3: proxy-stripped className
    // Level 4: unmatched
    return Optional.empty();
}
```

**Proxy class normalization:**
```java
static String normalizeClassName(String className) {
    if (className == null) return null;
    return className
        .replaceAll("\\$\\$EnhancerBySpringCGLIB\\$\\$.*", "")
        .replaceAll("\\$\\$FastClassBySpringCGLIB\\$\\$.*", "")
        .replaceAll("\\$ByteBuddy\\$.*", "")
        .replaceAll("\\$HibernateProxy\\$.*", "")
        .replaceAll("\\$Proxy\\d+", "");
}
```

**Acceptance criteria:** Maps test spans to correct MethodNodes. Handles proxy classes. Handles overloaded methods (returns first candidate with level=2).

---

### Task 7: ApmWebSocketHandler

**Files to create:**
- `com.huawei.hisi.apm.handler.ApmWebSocketHandler.java`

**Design:**
```java
@Component
public class ApmWebSocketHandler extends TextWebSocketHandler {
    private final Map<String, ConcurrentWebSocketSessionDecorator> sessionMap = new ConcurrentHashMap<>();
    
    // Message types:
    // SESSION_CREATED, PROCESS_STARTED, PROCESS_READY, PROCESS_LOG
    // SPAN_BATCH (batch of spans), EXECUTION_COMPLETE, EXECUTION_ERROR
    // PROCESS_EXITED, PONG
    
    public void pushSpans(String sessionId, List<ApmSpanEntity> spans) { ... }
    public void pushEvent(String sessionId, String type, Object data) { ... }
}
```

**Key points:**
- Wrap sessions in `ConcurrentWebSocketSessionDecorator` for thread safety
- Register at `/ws/apm` in `WebSocketConfig`
- Client sends: `{"action":"connect","sessionId":"xxx"}`
- Parse sessionId from connect message, store in map

**Acceptance criteria:** WebSocket connects and receives JSON messages. Span batches arrive in real-time. Thread-safe concurrent sends.

---

### Task 8: ApmController (REST API)

**Files to create:**
- `com.huawei.hisi.apm.controller.ApmController.java`
- `com.huawei.hisi.apm.service.ApmDebugService.java`

**Endpoints:**
```
POST   /api/apm/launch          — Launch target process, create session
POST   /api/apm/execute         — Send HTTP request to target, trigger tracing
POST   /api/apm/stop            — Stop target process, end session
GET    /api/apm/sessions        — List active/recent sessions
GET    /api/apm/session/{id}    — Get session details
GET    /api/apm/spans/{sessionId} — Get all spans for session
GET    /api/apm/trace/{traceId}   — Get span tree for a trace
GET    /api/apm/report/{sessionId} — Get execution report
GET    /api/apm/entry-points/{projectPath} — Get HTTP entry points (delegates to KG)
```

**ApmDebugService orchestrates:**
- `launch()` → creates session, calls TargetProcessManager, returns sessionId
- `execute()` → sends HTTP request to target via OkHttp, returns correlation info
- `stop()` → calls TargetProcessManager.shutdown, updates session status
- `getReport()` → generates DebugReport from stored spans

**Acceptance criteria:** All endpoints return proper `ApiResponse<T>`. Launch/execute/stop lifecycle works end-to-end.

---

### Task 9: DebugReportService

**Files to create:**
- `com.huawei.hisi.apm.service.DebugReportService.java`
- `com.huawei.hisi.apm.model.DebugReport.java`

**Report includes:**
```java
public record DebugReport(
    String sessionId,
    String traceId,
    String entryPoint,
    boolean success,
    long totalDurationMs,
    int httpStatus,
    String responseBody,
    List<SpanNode> spanTree,
    List<Hotspot> hotspots,
    List<ErrorPoint> errors
) {}

public record SpanNode(
    String spanId, String parentSpanId, String operationName,
    String className, String methodName, long durationMs,
    String status, String kgNodeId, int kgMatchLevel,
    List<SpanNode> children
) {}

public record Hotspot(String nodeId, String className, String methodName,
    long durationMs, double percentOfTotal) {}

public record ErrorPoint(String spanId, String className, String methodName,
    String exceptionType, String errorMessage) {}
```

**Generation logic:**
1. Load all spans for a trace, build parent→children tree
2. Find root span (parentSpanId is null)
3. Calculate hotspots: top-5 spans by duration
4. Find error points: spans with status_code = ERROR
5. Assemble into DebugReport

**Acceptance criteria:** Generates correct span tree from flat spans. Identifies hotspots and errors correctly.

---

### Task 10: TTL Cleanup Scheduler

**Files to create:**
- `com.huawei.hisi.apm.service.ApmCleanupService.java`

```java
@Service
@RequiredArgsConstructor
public class ApmCleanupService {
    @Scheduled(fixedRate = 600_000) // every 10 minutes
    public void cleanupExpiredSpans() {
        long cutoff = Instant.now().minus(config.getSpanTtlHours(), ChronoUnit.HOURS).getEpochSecond();
        int deleted = apmSpanRepository.deleteOlderThan(cutoff);
        if (deleted > 0) log.info("[APM] Cleaned up {} expired spans", deleted);
    }
}
```

**Acceptance criteria:** Old spans are automatically cleaned up. Does not affect active sessions.

---

### Task 11: MCP APM Tools (TypeScript)

**Files to create:**
- `hisi-mcp-server/src/tools/apmTools.ts`

**Modify:**
- `hisi-mcp-server/src/tools/index.ts` — add APM tool registration

**Tool definitions (6 tools):**
```typescript
const apmToolDefinitions = [
    { name: 'apm_start_session', description: 'Start an APM debug session for a project', inputSchema: { projectPath, targetPort? } },
    { name: 'apm_list_traces', description: 'List captured traces for a session', inputSchema: { sessionId, limit? } },
    { name: 'apm_get_trace', description: 'Get full span tree for a trace with KG mapping', inputSchema: { traceId } },
    { name: 'apm_execute_request', description: 'Execute an HTTP request against the target service', inputSchema: { sessionId, method, path, body?, headers? } },
    { name: 'apm_get_report', description: 'Get execution report with hotspots and errors', inputSchema: { sessionId } },
    { name: 'apm_stop_session', description: 'Stop the target process and end the session', inputSchema: { sessionId } },
];
```

**Acceptance criteria:** Tools are registered in MCP server. `apm_start_session` and `apm_stop_session` work end-to-end.

---

### Task 12: Frontend APM Debug Page

**Files to create:**
```
hisi-dev-tool-frontend/src/views/apm-debug/ApmDebugView.vue
hisi-dev-tool-frontend/src/views/apm-debug/components/SessionControlBar.vue
hisi-dev-tool-frontend/src/views/apm-debug/components/EntryPointSelector.vue
hisi-dev-tool-frontend/src/views/apm-debug/components/ParameterForm.vue
hisi-dev-tool-frontend/src/views/apm-debug/components/SpanFlowChart.vue
hisi-dev-tool-frontend/src/views/apm-debug/components/ExecutionReport.vue
hisi-dev-tool-frontend/src/views/apm-debug/components/SpanDetailDrawer.vue
hisi-dev-tool-frontend/src/composables/useApmWebSocket.ts
hisi-dev-tool-frontend/src/api/apmDebug.ts
hisi-dev-tool-frontend/src/types/apm.ts
```

**Modify:**
- `hisi-dev-tool-frontend/src/router/index.ts` — add `/apm-debug` route
- `hisi-dev-tool-frontend/src/components/layout/AppSidebar.vue` — add menu item
- `hisi-dev-tool-frontend/src/views/project/ProjectList.vue` — add "集成测试" button

**State machine:** IDLE → LAUNCHING → READY → EXECUTING → STREAMING → COMPLETE

**Key components:**
- `ApmDebugView.vue` (~200 lines) — orchestrates state machine
- `SpanFlowChart.vue` (~300 lines) — ECharts graph for real-time spans
- `useApmWebSocket.ts` (~200 lines) — WebSocket composable

**Acceptance criteria:** Can navigate from project list to APM page. WebSocket connects. Span flow chart renders in real-time. Execution report displays.

---

## Execution Order & Dependencies

```
Task 1 (DTOs + Config)        ← no dependency
Task 2 (SQLite Schema)        ← depends on Task 1 (model classes)
Task 3 (OTel Agent Manager)   ← no dependency
Task 4 (ProcessManager)       ← depends on Task 3
Task 5 (OTLP Receiver)        ← depends on Task 1, Task 2
Task 6 (SpanToKgMapper)       ← depends on Task 2
Task 7 (WebSocket Handler)    ← depends on Task 2
Task 8 (REST API)             ← depends on Task 4, Task 5, Task 6, Task 7
Task 9 (DebugReport)          ← depends on Task 2, Task 6
Task 10 (TTL Cleanup)         ← depends on Task 2
Task 11 (MCP Tools)           ← depends on Task 8
Task 12 (Frontend)            ← depends on Task 8
```

**Parallelizable batches:**
- **Batch 1**: Tasks 1, 3 (parallel)
- **Batch 2**: Tasks 2, 4 (parallel, after Batch 1)
- **Batch 3**: Tasks 5, 6, 7, 10 (parallel, after Batch 2)
- **Batch 4**: Tasks 8, 9 (after Batch 3)
- **Batch 5**: Tasks 11, 12 (parallel, after Batch 4)

---

## Risk Mitigation

| Risk | Impact | Mitigation |
|------|--------|------------|
| OTel agent download fails (network) | Cannot start target | Bundle a fallback download URL. Allow manual placement at `~/.hisi-devtool/otel-agent/`. |
| Target project startup too slow | Timeout before ready | Make timeout configurable. Show progress in WebSocket. |
| OTLP JSON format changes | Parse failures | Pin agent version in config. Use lenient Jackson deserialization (`DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES = false`). |
| Span matching rate < 50% | Limited usefulness | Log unmatched spans for debugging. Allow manual nodeId association. |
| WebSocket concurrency issues | Corrupted frames | Use `ConcurrentWebSocketSessionDecorator` (Spring built-in). |
| Large span volume | SQLite slow | Batch inserts (500 per tx). TTL cleanup. Limit session duration. |
