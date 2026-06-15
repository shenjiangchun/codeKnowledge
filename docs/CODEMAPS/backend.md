<!-- Generated: 2026-05-31 | Controllers: 35 | Token estimate: ~900 -->

# Backend Codemap

## API Routes → Service → Repository

### Knowledge Graph (build/refresh/vector)
```
POST /api/knowledge-graph/scan        → KnowledgeGraphController → ProjectScanner → Neo4jStorageService
POST /api/knowledge-graph/generate    → KnowledgeGraphController → LLMDescriptionService → VectorGenerationService
POST /api/knowledge-graph/refresh     → RefreshController → IncrementalRefreshService → GitStatusService
GET  /api/knowledge-graph/entry-types → KnowledgeGraphController → Neo4jMethodNodeRepository
POST /api/knowledge-graph/v2/*        → KnowledgeGraphV2Controller (projectPaths-only queries)
POST /api/vector-generation/*         → VectorGenerationController → EmbeddingService → ZhipuService
```

### Hybrid Search
```
POST /api/vector-search               → VectorSearchController → HybridSearchService → Neo4j repos
                                       9 QueryType strategies + RRF fusion (k=60)
                                       Three embeddings: description / code / sql
POST /api/search/semantic             → SemanticSearchController (legacy, limited)
```

### Call Chain & Impact
```
GET  /api/callchain/*                 → (CallChainService) → Neo4j CALLS graph traversal
POST /api/impact/predict              → ImpactPredictionController → N-layer reverse traversal
POST /api/impact/entries              → EntryPoint analysis (root_entries / affecting)
```

### RAM (Requirements Analysis Module)
```
POST /api/ram/start                   → RamController → RequirementAnalysisOrchestrator.start()
POST /api/ram/resume                  → RamController → Orchestrator.resume() (HITL answer)
POST /api/ram/confirm                 → RamController → Orchestrator.confirmAndResume()
GET  /api/ram/sessions/{id}/events    → AgentEventRepository (event sourcing)
DAG: ClarifyNode → ImpactNode → ImplementNode → VerifyNode → TechPlanNode
```

### APM (Trace Analysis)
```
POST /v1/traces                       → OtlpReceiverController → SpanIngestionService (OTLP protobuf)
POST /api/apm/diagnose                → DiagnoseController → FailureLocatorService (async)
GET  /api/apm/diagnose/{id}           → DiagnosisReportStore
POST /api/apm/debug/launch            → ApmDebugService → TargetProcessManager + OtelAgentManager
WS   /ws/apm/spans                    → WebSocket push (real-time span ingestion)
```

### Merge Analysis
```
GET  /api/merge-analysis/branches     → MergeAnalysisController → DiffExtractService (JGit)
POST /api/merge-analysis/diff         → DiffExtractService.extractDiff()
POST /api/merge-analysis/sessions     → MergeAnalysisService (async orchestration)
GET  /api/merge-analysis/sessions/{sid}/stream → SseEmitter (500ms polling, seq-based)
Pipeline: DiffExtract → ImpactAnalysis → TestScopeService
```

### Remote Project & Scheduler
```
CRUD /api/remote-projects             → RemoteProjectController → RemoteProjectService (JGit clone/pull)
POST /api/remote-projects/{id}/clone  → CompletableFuture.runAsync → Git.cloneRepository()
POST /api/remote-projects/{id}/pull   → Git.pull()
CRUD /api/kg-schedules                → KgScheduleController → KgSchedulerService (CronTrigger)
Credentials encrypted: AES-256-GCM (GitCredentialService)
```

### Claude Terminal & Sessions
```
WS   /ws/claude                       → ClaudeChatController → PTY4J + xterm.js
CRUD /api/sessions                    → SessionController → SQLite
CRUD /api/workspace-sessions          → WorkspaceSessionController
CRUD /api/skill                       → SkillController → SkillMarket
CRUD /api/mcp                         → McpController
CRUD /api/prompt                      → PromptController
GET  /api/settings                    → SettingsController
```

### Log Analysis
```
POST /api/log/query                   → LogAnalysisController → LogQueryService
POST /api/log/analyze                 → LogAnalysisController → LogAnalysisService (async AI)
GET  /api/log/report/{id}             → LogReportStore
```

### Other
```
CRUD /api/projects                    → ProjectController
CRUD /api/glossary                    → GlossaryController
POST /api/git/*                       → GitController (JGit operations)
GET  /api/ops/*                       → OpsController (system health)
POST /api/ai/analyze                  → AIAnalysisController
POST /api/cross-service/build         → CrossServiceBuildController
```

## Key Files
| File | Lines | Role |
|------|-------|------|
| ram/orchestrator/RequirementAnalysisOrchestrator.java | ~200 | RAM entry point |
| ram/orchestrator/DagExecutor.java | ~150 | DAG engine with min-recompute |
| apm/service/locator/FailureLocatorService.java | ~200 | APM async diagnosis pipeline |
| neo4j/service/HybridSearchService.java | ~400 | 9-strategy search + RRF |
| knowledgegraph/service/storage/Neo4jStorageService.java | ~300 | KG persistence |
| mergeanalysis/service/MergeAnalysisService.java | ~150 | Merge analysis orchestrator |
