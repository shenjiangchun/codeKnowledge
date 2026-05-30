# Remote Project Management & Branch Merge Impact Analysis

**Date**: 2026-05-29
**Status**: Approved

---

## Feature 1: Remote Project Management

### Problem

Current project management only supports local projects discovered via filesystem scanning. Users need to:
- Configure and manage remote Git repositories
- Auto-clone/pull remote repos with credential storage
- Schedule periodic knowledge graph refresh (full & incremental)
- Use remote projects for KG analysis just like local ones

### Data Model

**Table `remote_project` (SQLite):**

| Column | Type | Notes |
|--------|------|-------|
| id | INTEGER PK AUTOINCREMENT | |
| name | TEXT NOT NULL | Display name |
| git_url | TEXT NOT NULL | Remote repo URL |
| username | TEXT | Git username |
| encrypted_password | TEXT | AES-256-GCM encrypted |
| branch | TEXT DEFAULT 'main' | Default branch to clone/pull |
| local_path | TEXT | Relative path under `remote-repos/` |
| clone_status | TEXT DEFAULT 'PENDING' | PENDING/CLONING/CLONED/FAILED |
| last_sync_at | INTEGER | Epoch ms of last git pull |
| created_at | INTEGER | |

**Table `kg_schedule` (SQLite):**

| Column | Type | Notes |
|--------|------|-------|
| id | INTEGER PK AUTOINCREMENT | |
| project_path | TEXT NOT NULL | Absolute project path (local or remote) |
| cron_expression | TEXT NOT NULL | e.g. `0 2 * * *` |
| task_type | TEXT NOT NULL | `FULL` or `INCREMENTAL` |
| enabled | INTEGER DEFAULT 1 | 0=disabled |
| last_run_at | INTEGER | |
| next_run_at | INTEGER | |
| created_at | INTEGER | |

### Backend Architecture

```
com.huawei.hisi.project.remote/
  controller/RemoteProjectController.java
  service/RemoteProjectService.java
  service/GitCredentialService.java       # AES encrypt/decrypt
  repository/RemoteProjectRepository.java
  model/RemoteProject.java

com.huawei.hisi.scheduler/
  controller/KgScheduleController.java
  service/KgSchedulerService.java         # Spring TaskScheduler + CronTrigger
  repository/KgScheduleRepository.java
  model/KgSchedule.java
```

**API Endpoints:**

| Method | Path | Description |
|--------|------|-------------|
| POST | `/api/remote-projects` | Create remote project config |
| GET | `/api/remote-projects` | List all remote projects |
| PUT | `/api/remote-projects/{id}` | Update config |
| DELETE | `/api/remote-projects/{id}` | Delete (+ cleanup local clone) |
| POST | `/api/remote-projects/{id}/clone` | Trigger clone |
| POST | `/api/remote-projects/{id}/pull` | Trigger pull |
| POST | `/api/kg-schedules` | Create schedule |
| GET | `/api/kg-schedules` | List all schedules |
| PUT | `/api/kg-schedules/{id}` | Update/enable/disable |
| DELETE | `/api/kg-schedules/{id}` | Delete schedule |

**AES Encryption:**
- Algorithm: `AES/GCM/NoPadding` (256-bit key)
- Key source: env var `HISI_ENCRYPT_KEY`; if absent, auto-generate and store in `app_config` table
- Each encrypted value prefixed with 12-byte IV

**Scheduling:**
- Spring `ThreadPoolTaskScheduler` with `CronTrigger`
- On startup: load all enabled `kg_schedule` rows, register with scheduler
- CRUD operations dynamically cancel/re-register tasks
- Scheduled task logic: git pull (if remote) -> invoke existing KG generation or refresh API

### Frontend Changes

- `ProjectList.vue`: Add `<el-tabs>` at top (Local Projects / Remote Projects)
- Local tab: unchanged
- Remote tab:
  - Table: name, git_url, branch, clone_status, kg_status, vector_status, actions
  - Actions: edit, clone/pull, generate KG, description&vector, schedule config, delete
  - Dialog: "Add Remote Project" form (URL, username, password, branch)
  - Dialog: "Schedule Config" (cron expression, FULL/INCREMENTAL toggle)
- Clone target: `{project_root}/remote-repos/{project_name}/`
- Add `remote-repos/` to `.gitignore`
- KG/vector operations reuse existing logic with `remote-repos/xxx` as projectPath

---

## Feature 2: Branch Merge Impact Analysis

### Problem

Current commit analysis just builds a prompt and forwards to Claude Terminal. Users need a structured, wizard-style flow that:
- Selects source/target branches for simulated merge
- Shows diff overview before analysis
- Analyzes code impact using knowledge graph + LLM
- Generates test scope recommendations
- Displays results in a step-by-step progress view

### User Flow (3-step wizard)

```
Step 1: Input              Step 2: Diff Preview       Step 3: Analysis
+-------------------+     +-------------------+     +------------------------+
| Select project    |     | Changed files list |     | DAG progress bar       |
| Source branch     | --> | Per-file diff stat | --> | [DiffExtract ✓]        |
| Target branch     |     | Code diff preview  |     | [ImpactAnalysis ●]     |
| [Next]            |     | [Start Analysis]   |     | [TestScope ○]          |
+-------------------+     +-------------------+     |                        |
                                                     | Impact results:        |
                                                     | - Affected entry points |
                                                     | - Call chain graph      |
                                                     | - Test recommendations  |
                                                     +------------------------+
```

### Backend Architecture

```
com.huawei.hisi.mergeanalysis/
  controller/MergeAnalysisController.java
  service/MergeAnalysisService.java        # Orchestrator
  service/DiffExtractService.java          # JGit diff
  service/ImpactAnalysisService.java       # KG + LLM impact
  service/TestScopeService.java            # LLM test scope generation
  model/
    MergeAnalysisSession.java
    DiffResult.java
    ImpactResult.java
    TestScopeResult.java
  config/MergeAnalysisSchemaInitializer.java
```

**Reuses from RAM:**
- `agent_session` table (add `session_type` column: `RAM` / `MERGE_ANALYSIS`)
- `agent_event` table (events linked by session_id)
- SSE delivery pattern (DB polling + SseEmitter)
- `AnthropicHttpClient` and `RamClaudeJsonClient` for Claude API
- KG tool registry (`KgMcpClient`) for impact analysis

**Does NOT reuse:**
- DAG node definitions (merge analysis has its own 3-node flow)
- MCP tool facade (not needed, direct service calls)

### DAG Flow (3 nodes)

**Node 1: DiffExtract** (no LLM)
- Input: projectPath, sourceBranch, targetBranch
- JGit: `sourceBranch..targetBranch` diff
- Output: changed files list + changed line ranges + mapped changed methods (via KG method location data)

**Node 2: ImpactAnalysis** (KG + LLM)
- Input: changed methods from Node 1
- For each changed method:
  - KG `rootEntries()` -> affected entry points
  - KG `downstream()` -> downstream call chain
  - KG `affecting()` -> upstream callers
- Claude: semantic analysis of changes -> business impact assessment
- Output: affected entry points, call chain graph, risk assessment

**Node 3: TestScopeGeneration** (LLM)
- Input: ImpactAnalysis output
- Claude generates:
  1. Required test cases (grouped by entry point)
  2. Regression test recommendations
  3. Risk level tags (HIGH/MEDIUM/LOW)
- Output: structured test scope JSON

### API Design

| Method | Path | Description |
|--------|------|-------------|
| GET | `/api/merge-analysis/branches?projectPath=xxx` | List project branches |
| POST | `/api/merge-analysis/diff` | Get diff overview (synchronous) |
| POST | `/api/merge-analysis/sessions` | Create analysis session, start DAG |
| GET | `/api/merge-analysis/sessions/{sid}` | Get session status |
| GET | `/api/merge-analysis/sessions/{sid}/stream?afterSeq=N` | SSE event stream |

### Frontend Architecture

```
src/views/merge-analysis/
  InputPage.vue           # Step 1: project + branch selection
  DiffPreviewPage.vue     # Step 2: diff overview
  AnalysisPage.vue        # Step 3: DAG progress + results

src/api/merge-analysis.ts
src/composables/useMergeAnalysisSession.ts  # SSE lifecycle (adapted from useRamSession)
```

**Sidebar:** New entry "合入分析" with `Connection` icon, route `/merge-analysis`, positioned adjacent to "需求分析大师"

**InputPage:**
- Project selector (reuses scanGitRepos)
- Source/target branch dropdowns (fetched from `/api/merge-analysis/branches`)
- "Next" button

**DiffPreviewPage:**
- File tree with +/- line counts
- Expandable code diff per file
- "Start Analysis" button -> creates session, navigates to AnalysisPage

**AnalysisPage:**
- Top: 3-step DAG progress bar (simplified DagFlow)
- Left: impact results (entry point list, call chain tree)
- Right: test scope (grouped recommendations, risk labels)
- SSE real-time progress updates

### Schema Migration

Add `session_type` column to existing `agent_session` table:

```sql
ALTER TABLE agent_session ADD COLUMN session_type TEXT DEFAULT 'RAM';
```

Merge analysis sessions use `session_type = 'MERGE_ANALYSIS'`.

---

## File Change Summary

### Feature 1: Remote Project Management

| Area | Files | Action |
|------|-------|--------|
| Backend | `remote/controller/RemoteProjectController.java` | New |
| Backend | `remote/service/RemoteProjectService.java` | New |
| Backend | `remote/service/GitCredentialService.java` | New |
| Backend | `remote/repository/RemoteProjectRepository.java` | New |
| Backend | `remote/model/RemoteProject.java` | New |
| Backend | `scheduler/controller/KgScheduleController.java` | New |
| Backend | `scheduler/service/KgSchedulerService.java` | New |
| Backend | `scheduler/repository/KgScheduleRepository.java` | New |
| Backend | `scheduler/model/KgSchedule.java` | New |
| Backend | `config/SQLiteSchemaInitializer.java` | Modify (add tables) |
| Frontend | `views/project/ProjectList.vue` | Modify (add tabs) |
| Frontend | `api/remote-project.ts` | New |
| Frontend | `api/kg-schedule.ts` | New |
| Frontend | `types/remote-project.ts` | New |
| Config | `.gitignore` | Modify (add remote-repos/) |

### Feature 2: Branch Merge Impact Analysis

| Area | Files | Action |
|------|-------|--------|
| Backend | `mergeanalysis/controller/MergeAnalysisController.java` | New |
| Backend | `mergeanalysis/service/MergeAnalysisService.java` | New |
| Backend | `mergeanalysis/service/DiffExtractService.java` | New |
| Backend | `mergeanalysis/service/ImpactAnalysisService.java` | New |
| Backend | `mergeanalysis/service/TestScopeService.java` | New |
| Backend | `mergeanalysis/model/*.java` | New (4 files) |
| Backend | `mergeanalysis/config/MergeAnalysisSchemaInitializer.java` | New |
| Backend | `ram/config/RamSchemaInitializer.java` | Modify (add session_type) |
| Frontend | `views/merge-analysis/InputPage.vue` | New |
| Frontend | `views/merge-analysis/DiffPreviewPage.vue` | New |
| Frontend | `views/merge-analysis/AnalysisPage.vue` | New |
| Frontend | `api/merge-analysis.ts` | New |
| Frontend | `composables/useMergeAnalysisSession.ts` | New |
| Frontend | `components/layout/AppSidebar.vue` | Modify (add menu item) |
| Frontend | `router/index.ts` | Modify (add routes) |
