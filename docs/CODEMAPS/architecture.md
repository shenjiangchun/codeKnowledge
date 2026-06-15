<!-- Generated: 2026-05-31 | Modules: 10 | Token estimate: ~600 -->

# HiSi DevTool v5.0 — System Architecture

## Monorepo Layout
```
hisi-dev-tool/            → Spring Boot 3.2 + Java 17 backend
hisi-dev-tool-frontend/   → Vue 3 + TypeScript + Element Plus frontend
hisi-mcp-server/          → MCP Server (Node.js/TypeScript)
```

## Module Boundaries (Backend)
```
com.huawei.hisi
├── knowledgegraph/   ★ KG build engine (AST parse → Neo4j)
├── neo4j/            ★ Hybrid search (9 QueryType + RRF + vector)
├── ram/              ★ Requirements Analysis Module (DAG orchestrator)
├── apm/              ★ APM Trace analysis (OTel → KG → LLM diagnosis)
├── mergeanalysis/    ★ Merge impact analysis (JGit diff → KG → SSE)
├── project/remote/   Remote project management (clone/pull/encrypt)
├── scheduler/        KG cron scheduling
├── agent/            Claude CLI terminal (WebSocket + PTY)
├── controller/       Legacy controllers (session, config, git, ops, ...)
├── service/          Shared services (semantic search, intent dialog, ...)
├── glossary/         Business glossary
├── skill/            Skill marketplace
├── scanner/          AST scanners (JavaParser + ANTLR4 Python)
├── model/            Shared DTOs / models
├── config/           Spring configuration
├── cache/            Caffeine caches
├── utils/            Utilities
└── handler/          Exception handlers
```

## Data Flow
```
Source Code → AST Scanner → Knowledge Graph (Neo4j)
                                ↓
                    HybridSearchService (keyword + vector + graph)
                                ↓
                    ┌───────────┼───────────┐
                    ↓           ↓           ↓
              ImpactNode    MergeAnalysis  APM Diagnosis
              (RAM DAG)    (JGit→KG→SSE)  (OTel→KG→LLM)
                    ↓           ↓           ↓
              TechPlan     TestScope    Root Cause Report
```

## Storage
| Store | Purpose | Location |
|-------|---------|----------|
| Neo4j 5.11+ | Graph structure + vector index (cosine) | neo4j://localhost:7687 |
| SQLite | Sessions, events, schedules, credentials | ~/.hisi-devtool/devtool.db |
| Caffeine | In-memory caches (span index, dedup) | JVM heap |

## AI Layer
| Service | Model | Purpose |
|---------|-------|---------|
| UnifiedTextService | OpenAI-compatible (glm-4-flash default) | Text generation |
| EmbeddingService | Qwen3-VL-Embedding-8B / embedding-3 | Vector embeddings |
| ApmClaudeLlmClient | Claude via dmxapi | APM diagnosis (isolated) |
