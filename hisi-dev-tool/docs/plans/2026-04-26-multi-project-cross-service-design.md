# Multi-Project & Cross-Service Refactoring Design

**Date**: 2026-04-26
**Status**: Approved

## Problem

The current "public knowledge graph" (`publicProjectPath`) architecture has fundamental issues:
1. `JavaLanguageAdapter` never passes `publicProjectPath` through — Java nodes get `publicProjectPath = projectPath`
2. Clean/link operations scoped by `publicProjectPath` fail to find Java nodes
3. The concept of a "public KG" duplicates data and adds complexity without clear value
4. Users cannot freely combine projects for search/analysis

## Decision

**Delete `publicProjectPath` entirely.** Replace with multi-project selection (`projectPaths: string[]`) at query time.

## Architecture

### Data Model

- **Remove** `publicProjectPath` from `MethodNode`, `EntryPointNode`, `GenerationCheckpointNode`
- **`projectPath`** remains the sole project identifier on every node
- **`EXTERNAL_CALL`** relationships connect MethodNodes across different `projectPath` values:
  ```
  (caller:Method)-[:EXTERNAL_CALL {bridgeType, targetUri, createdAt}]->(callee:Method)
  ```

### Backend API Changes

#### Deleted Endpoints
| Endpoint | Reason |
|---|---|
| `POST /api/knowledge-graph/public/scan` | No longer needed |
| `POST /api/knowledge-graph/public/generate` | Replaced by cross-service build |
| `GET /api/knowledge-graph/public/status` | Replaced |
| `POST /api/knowledge-graph/public/refresh` | Replaced |

#### New Endpoint
**`POST /api/knowledge-graph/cross-service/build`**

Request: `{ "projectPaths": ["path/a", "path/b"] }`
Response: `{ "taskId": 123, "message": "..." }`

Flow:
1. Validate all projects have existing knowledge graphs
2. Incremental refresh each project's KG to ensure freshness
3. Delete existing EXTERNAL_CALL relationships between these projects
4. Execute CrossServiceLinker strategies (HTTP/MQ/gRPC)
5. Record task completion

#### Modified Endpoints
- `POST /api/vector-search`: `projectPath: String` → `projectPaths: List<String>` (backward compatible)
- `/api/callchain/*`: Same change, supports cross-project call chain traversal
- `POST /api/knowledge-graph/refresh`: Remove `publicProjectPath` parameter

#### Unchanged Endpoints
- `POST /api/knowledge-graph/tasks/generate` (single project)
- `GET /api/knowledge-graph/status` (single project)

### Backend Deletion List

| File | Action |
|---|---|
| `PublicKnowledgeGraphController` | Delete |
| `PublicKnowledgeGraphService` | Delete |
| `WorkspaceScanner` | Delete |
| `ManifestDetector` | Delete |
| `ServiceManifest` | Delete |
| `ServiceDispatcher` | Delete |
| `LanguageGraphBuilder` interface | Delete |
| `JavaLanguageAdapter` | Delete |
| `PythonLanguageAdapter` | Delete |

### Backend New Files

| File | Purpose |
|---|---|
| `CrossServiceBuildController` | REST endpoint for cross-service build |
| `CrossServiceBuildService` | Orchestration: validate → refresh → clean → link |

### Backend Modifications

| File | Change |
|---|---|
| `MethodNode` | Remove `publicProjectPath` field |
| `EntryPointNode` | Remove `publicProjectPath` field |
| `GenerationCheckpointNode` | Remove `publicProjectPath`, `scope` fields |
| `Neo4jStorageService` | Remove `cleanByPublicPath()`, `setPublicProjectPath()` |
| `Neo4jInitializer` | Remove publicProjectPath backfill/index; add REMOVE migration |
| All `*ByScope` repository queries | Change to `*ByProjectPaths` (`IN $projectPaths`) |
| `CrossServiceLinker` + LinkStrategies | `link(String)` → `link(List<String>)` |
| `IncrementalRefreshService` | Remove `publicProjectPath` parameter |
| `RefreshController` | Remove `publicProjectPath` parameter |
| `HybridSearchService` | `scope` parameter → `projectPaths` parameter |

### Frontend Changes

| Area | Change |
|---|---|
| Project list (ProjectList.vue) | Add multi-select checkboxes; replace "公共知识图谱生成" with "跨服务依赖构建" button (enabled when ≥2 projects with KG selected) |
| KG page | Support multi-project selection for combined view |
| Vector search | `projectPath` → `projectPaths` |
| Call chain | Support cross-project traversal |
| `knowledgeGraph.ts` API | Delete public* methods; add `crossServiceBuild(projectPaths)` |

### Data Migration

On startup (`Neo4jInitializer`):
```cypher
MATCH (n) WHERE n.publicProjectPath IS NOT NULL
REMOVE n.publicProjectPath

DROP INDEX idx_method_public_project_path IF EXISTS
DROP INDEX idx_entry_public_project_path IF EXISTS
```
