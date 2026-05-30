# Remote Project Management & Merge Impact Analysis — Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Add remote Git repository management with encrypted credentials and cron-scheduled KG refresh, plus a wizard-style branch merge impact analysis feature using KG + Claude LLM.

**Architecture:** Feature 1 extends the existing ProjectList with a tab-based layout, adding new backend modules for remote project CRUD and cron scheduling. Feature 2 follows the RAM (Requirements Analysis Master) architecture — session-based DAG execution with SSE event streaming, Claude LLM integration, and a 3-step wizard frontend.

**Tech Stack:** Spring Boot, JdbcTemplate (SQLite), JGit, AES-256-GCM, Spring TaskScheduler, Claude Anthropic API (via existing `AnthropicHttpClient`), SSE (SseEmitter), Vue 3 + Element Plus, Pinia.

**Base paths:**
- Backend: `hisi-dev-tool/src/main/java/com/huawei/hisi/`
- Frontend: `hisi-dev-tool-frontend/src/`

---

## Part A: Remote Project Management

### Task 1: Database Schema — remote_project + kg_schedule tables

**Files:**
- Modify: `hisi-dev-tool/src/main/java/com/huawei/hisi/config/SQLiteSchemaInitializer.java`

**Step 1: Add remote_project table DDL**

Add to the `initialize()` method after the existing `glossary_term` table creation:

```java
jdbcTemplate.execute("""
    CREATE TABLE IF NOT EXISTS remote_project (
        id              INTEGER PRIMARY KEY AUTOINCREMENT,
        name            TEXT    NOT NULL,
        git_url         TEXT    NOT NULL,
        username        TEXT,
        encrypted_password TEXT,
        branch          TEXT    DEFAULT 'main',
        local_path      TEXT,
        clone_status    TEXT    DEFAULT 'PENDING',
        last_sync_at    INTEGER,
        created_at      INTEGER DEFAULT (strftime('%s','now'))
    )
""");
```

**Step 2: Add kg_schedule table DDL**

```java
jdbcTemplate.execute("""
    CREATE TABLE IF NOT EXISTS kg_schedule (
        id              INTEGER PRIMARY KEY AUTOINCREMENT,
        project_path    TEXT    NOT NULL,
        cron_expression TEXT    NOT NULL,
        task_type       TEXT    NOT NULL,
        enabled         INTEGER DEFAULT 1,
        last_run_at     INTEGER,
        next_run_at     INTEGER,
        created_at      INTEGER DEFAULT (strftime('%s','now'))
    )
""");
```

**Step 3: Add .gitignore entry**

Append `remote-repos/` to the project root `.gitignore`.

**Step 4: Verify**

Run: `cd hisi-dev-tool && mvn compile -q`
Expected: BUILD SUCCESS

**Step 5: Commit**

```bash
git add -A && git commit -m "feat(remote-project): add remote_project and kg_schedule schema + .gitignore"
```

---

### Task 2: RemoteProject Model + Repository

**Files:**
- Create: `hisi-dev-tool/src/main/java/com/huawei/hisi/project/remote/model/RemoteProject.java`
- Create: `hisi-dev-tool/src/main/java/com/huawei/hisi/project/remote/repository/RemoteProjectRepository.java`

**Step 1: Create RemoteProject model**

```java
package com.huawei.hisi.project.remote.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RemoteProject {
    private Long id;
    private String name;
    private String gitUrl;
    private String username;
    private String encryptedPassword;
    @Builder.Default
    private String branch = "main";
    private String localPath;
    @Builder.Default
    private String cloneStatus = "PENDING";
    private Long lastSyncAt;
    private Long createdAt;
}
```

**Step 2: Create RemoteProjectRepository**

JDBC-based repository following existing codebase patterns (no Spring Data — project uses raw JdbcTemplate):

```java
package com.huawei.hisi.project.remote.repository;

import com.huawei.hisi.project.remote.model.RemoteProject;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Slf4j
@Repository
public class RemoteProjectRepository {

    private final JdbcTemplate jdbcTemplate;

    public RemoteProjectRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    private static final RowMapper<RemoteProject> ROW_MAPPER = (rs, rowNum) ->
        RemoteProject.builder()
            .id(rs.getLong("id"))
            .name(rs.getString("name"))
            .gitUrl(rs.getString("git_url"))
            .username(rs.getString("username"))
            .encryptedPassword(rs.getString("encrypted_password"))
            .branch(rs.getString("branch"))
            .localPath(rs.getString("local_path"))
            .cloneStatus(rs.getString("clone_status"))
            .lastSyncAt(rs.getObject("last_sync_at", Long.class))
            .createdAt(rs.getObject("created_at", Long.class))
            .build();

    public List<RemoteProject> findAll() {
        return jdbcTemplate.query("SELECT * FROM remote_project ORDER BY id", ROW_MAPPER);
    }

    public Optional<RemoteProject> findById(long id) {
        List<RemoteProject> list = jdbcTemplate.query(
            "SELECT * FROM remote_project WHERE id = ?", ROW_MAPPER, id);
        return list.isEmpty() ? Optional.empty() : Optional.of(list.get(0));
    }

    public long insert(RemoteProject p) {
        jdbcTemplate.update("""
            INSERT INTO remote_project (name, git_url, username, encrypted_password, branch, local_path, clone_status)
            VALUES (?, ?, ?, ?, ?, ?, ?)
            """,
            p.getName(), p.getGitUrl(), p.getUsername(), p.getEncryptedPassword(),
            p.getBranch(), p.getLocalPath(), p.getCloneStatus());
        return jdbcTemplate.queryForObject("SELECT last_insert_rowid()", Long.class);
    }

    public void update(RemoteProject p) {
        jdbcTemplate.update("""
            UPDATE remote_project
            SET name = ?, git_url = ?, username = ?, encrypted_password = ?, branch = ?, clone_status = ?, last_sync_at = ?
            WHERE id = ?
            """,
            p.getName(), p.getGitUrl(), p.getUsername(), p.getEncryptedPassword(),
            p.getBranch(), p.getCloneStatus(), p.getLastSyncAt(), p.getId());
    }

    public void deleteById(long id) {
        jdbcTemplate.update("DELETE FROM remote_project WHERE id = ?", id);
    }

    public void updateCloneStatus(long id, String status) {
        jdbcTemplate.update("UPDATE remote_project SET clone_status = ? WHERE id = ?", status, id);
    }

    public void updateLastSyncAt(long id, long epochSeconds) {
        jdbcTemplate.update("UPDATE remote_project SET last_sync_at = ? WHERE id = ?", epochSeconds, id);
    }
}
```

**Step 3: Verify**

Run: `cd hisi-dev-tool && mvn compile -q`
Expected: BUILD SUCCESS

**Step 4: Commit**

```bash
git add -A && git commit -m "feat(remote-project): add RemoteProject model and repository"
```

---

### Task 3: GitCredentialService (AES encryption)

**Files:**
- Create: `hisi-dev-tool/src/main/java/com/huawei/hisi/project/remote/service/GitCredentialService.java`
- Create: `hisi-dev-tool/src/test/java/com/huawei/hisi/project/remote/service/GitCredentialServiceTest.java`

**Step 1: Write the failing test**

```java
package com.huawei.hisi.project.remote.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class GitCredentialServiceTest {

    private GitCredentialService service;

    @BeforeEach
    void setUp() {
        service = new GitCredentialService("test-key-1234567890123456");
    }

    @Test
    @DisplayName("encrypt then decrypt returns original password")
    void encryptDecrypt_roundTrip() {
        String original = "my-secret-password";
        String encrypted = service.encrypt(original);
        assertThat(encrypted).isNotEqualTo(original);
        String decrypted = service.decrypt(encrypted);
        assertThat(decrypted).isEqualTo(original);
    }

    @Test
    @DisplayName("encrypt produces different ciphertext each time (random IV)")
    void encrypt_differentIvEachTime() {
        String encrypted1 = service.encrypt("same-password");
        String encrypted2 = service.encrypt("same-password");
        assertThat(encrypted1).isNotEqualTo(encrypted2);
    }

    @Test
    @DisplayName("null or empty input returns as-is")
    void encryptDecrypt_nullOrEmpty() {
        assertThat(service.encrypt(null)).isNull();
        assertThat(service.encrypt("")).isEmpty();
        assertThat(service.decrypt(null)).isNull();
        assertThat(service.decrypt("")).isEmpty();
    }
}
```

**Step 2: Run test to verify it fails**

Run: `cd hisi-dev-tool && mvn test -Dtest=GitCredentialServiceTest -q`
Expected: FAIL (class not found)

**Step 3: Implement GitCredentialService**

```java
package com.huawei.hisi.project.remote.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.security.SecureRandom;
import java.util.Base64;

@Slf4j
@Service
public class GitCredentialService {

    private static final String ALGORITHM = "AES/GCM/NoPadding";
    private static final int GCM_TAG_LENGTH = 128;
    private static final int IV_LENGTH = 12;

    private final SecretKeySpec keySpec;

    public GitCredentialService(@Value("${hisi.encrypt.key:#{null}}") String key) {
        if (key == null || key.length() < 16) {
            key = "hisi-default-key-0123456789abcdef";
            log.warn("[GitCredentialService] No HISI_ENCRYPT_KEY configured, using default key");
        }
        byte[] keyBytes = key.getBytes();
        byte[] paddedKey = new byte[32];
        System.arraycopy(keyBytes, 0, paddedKey, 0, Math.min(keyBytes.length, 32));
        this.keySpec = new SecretKeySpec(paddedKey, "AES");
    }

    public String encrypt(String plaintext) {
        if (plaintext == null || plaintext.isEmpty()) return plaintext;
        try {
            byte[] iv = new byte[IV_LENGTH];
            new SecureRandom().nextBytes(iv);
            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.ENCRYPT_MODE, keySpec, new GCMParameterSpec(GCM_TAG_LENGTH, iv));
            byte[] encrypted = cipher.doFinal(plaintext.getBytes());
            byte[] combined = new byte[IV_LENGTH + encrypted.length];
            System.arraycopy(iv, 0, combined, 0, IV_LENGTH);
            System.arraycopy(encrypted, 0, combined, IV_LENGTH, encrypted.length);
            return Base64.getEncoder().encodeToString(combined);
        } catch (Exception e) {
            throw new RuntimeException("Encryption failed", e);
        }
    }

    public String decrypt(String ciphertext) {
        if (ciphertext == null || ciphertext.isEmpty()) return ciphertext;
        try {
            byte[] combined = Base64.getDecoder().decode(ciphertext);
            byte[] iv = new byte[IV_LENGTH];
            System.arraycopy(combined, 0, iv, 0, IV_LENGTH);
            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.DECRYPT_MODE, keySpec, new GCMParameterSpec(GCM_TAG_LENGTH, iv));
            byte[] decrypted = cipher.doFinal(combined, IV_LENGTH, combined.length - IV_LENGTH);
            return new String(decrypted);
        } catch (Exception e) {
            throw new RuntimeException("Decryption failed", e);
        }
    }
}
```

**Step 4: Run test to verify it passes**

Run: `cd hisi-dev-tool && mvn test -Dtest=GitCredentialServiceTest -q`
Expected: 3 tests PASS

**Step 5: Commit**

```bash
git add -A && git commit -m "feat(remote-project): add AES-256-GCM credential encryption service"
```

---

### Task 4: RemoteProjectService + Controller

**Files:**
- Create: `hisi-dev-tool/src/main/java/com/huawei/hisi/project/remote/service/RemoteProjectService.java`
- Create: `hisi-dev-tool/src/main/java/com/huawei/hisi/project/remote/controller/RemoteProjectController.java`

**Step 1: Create RemoteProjectService**

Service handles clone/pull using JGit with encrypted credentials. Follow the `ProjectServiceImpl.cloneProject()` pattern (lines 122-164).

Key methods:
- `createProject(req)` — saves config to DB with encrypted password, returns ID
- `listProjects()` — returns all with password masked
- `updateProject(id, req)` — updates config
- `deleteProject(id)` — deletes DB row + removes local clone directory
- `cloneProject(id)` — async: sets status CLONING, clones via JGit, sets CLONED/FAILED
- `pullProject(id)` — async: pulls latest, updates lastSyncAt

Clone target: resolved from `System.getProperty("user.dir") + "/remote-repos/" + project.localPath`.

Use `@Async` or `CompletableFuture.runAsync()` for clone/pull operations (non-blocking).

**Step 2: Create RemoteProjectController**

Follow `RamController` pattern: `@RestController @RequestMapping("/api/remote-projects")`, inner record types for request/response, all return `ApiResponse<T>`.

```java
@RestController
@RequestMapping("/api/remote-projects")
@Slf4j
public class RemoteProjectController {

    public record CreateRequest(String name, String gitUrl, String username, String password, String branch) {}
    public record UpdateRequest(String name, String gitUrl, String username, String password, String branch) {}
    public record ProjectResponse(Long id, String name, String gitUrl, String username, String branch,
                                  String localPath, String cloneStatus, Long lastSyncAt) {}

    // POST /api/remote-projects
    // GET /api/remote-projects
    // PUT /api/remote-projects/{id}
    // DELETE /api/remote-projects/{id}
    // POST /api/remote-projects/{id}/clone
    // POST /api/remote-projects/{id}/pull
}
```

**Step 3: Verify**

Run: `cd hisi-dev-tool && mvn compile -q`
Expected: BUILD SUCCESS

**Step 4: Commit**

```bash
git add -A && git commit -m "feat(remote-project): add RemoteProjectService and controller with CRUD + clone/pull"
```

---

### Task 5: KgSchedule Model + Repository + Service + Controller

**Files:**
- Create: `hisi-dev-tool/src/main/java/com/huawei/hisi/scheduler/model/KgSchedule.java`
- Create: `hisi-dev-tool/src/main/java/com/huawei/hisi/scheduler/repository/KgScheduleRepository.java`
- Create: `hisi-dev-tool/src/main/java/com/huawei/hisi/scheduler/service/KgSchedulerService.java`
- Create: `hisi-dev-tool/src/main/java/com/huawei/hisi/scheduler/controller/KgScheduleController.java`

**Step 1: Create KgSchedule model**

Similar Lombok builder pattern as RemoteProject.

**Step 2: Create KgScheduleRepository**

Same JdbcTemplate pattern: `findAll()`, `findById()`, `findEnabled()`, `insert()`, `update()`, `deleteById()`.

**Step 3: Create KgSchedulerService**

```java
@Service
@Slf4j
public class KgSchedulerService {

    private final KgScheduleRepository repository;
    private final ThreadPoolTaskScheduler taskScheduler;
    private final Map<Long, ScheduledFuture<?>> activeTasks = new ConcurrentHashMap<>();

    // Inject existing KG task service and refresh controller for triggering KG generation
    private final KnowledgeGraphTaskService kgTaskService;

    @PostConstruct
    public void initSchedules() {
        taskScheduler.initialize();
        List<KgSchedule> enabled = repository.findEnabled();
        for (KgSchedule schedule : enabled) {
            registerTask(schedule);
        }
        log.info("[KgScheduler] Initialized {} scheduled tasks", enabled.size());
    }

    public void registerTask(KgSchedule schedule) { ... }
    public void cancelTask(long scheduleId) { ... }
    public void reRegisterTask(KgSchedule schedule) { cancelTask + registerTask }
}
```

The scheduled runnable: for remote projects, `git pull` first, then invoke `kgTaskService.startTask(projectPath, excludePaths)` for FULL or the refresh endpoint for INCREMENTAL.

**Step 4: Create KgScheduleController**

`@RestController @RequestMapping("/api/kg-schedules")` with CRUD endpoints. On create/update, call `schedulerService.reRegisterTask()`. On delete, call `schedulerService.cancelTask()`.

**Step 5: Verify**

Run: `cd hisi-dev-tool && mvn compile -q`
Expected: BUILD SUCCESS

**Step 6: Commit**

```bash
git add -A && git commit -m "feat(scheduler): add KG cron scheduling with dynamic task management"
```

---

### Task 6: Frontend — API + Types for Remote Project & Schedule

**Files:**
- Create: `hisi-dev-tool-frontend/src/types/remote-project.ts`
- Create: `hisi-dev-tool-frontend/src/api/remote-project.ts`
- Create: `hisi-dev-tool-frontend/src/api/kg-schedule.ts`

**Step 1: Create types**

```typescript
// types/remote-project.ts
export interface RemoteProject {
  id: number
  name: string
  gitUrl: string
  username: string
  branch: string
  localPath: string
  cloneStatus: 'PENDING' | 'CLONING' | 'CLONED' | 'FAILED'
  lastSyncAt: number | null
}

export interface KgSchedule {
  id: number
  projectPath: string
  cronExpression: string
  taskType: 'FULL' | 'INCREMENTAL'
  enabled: boolean
  lastRunAt: number | null
  nextRunAt: number | null
}
```

**Step 2: Create API modules**

Follow `api/ram.ts` pattern — import `request` from `@/utils/request`, return typed promises.

```typescript
// api/remote-project.ts
import request from '@/utils/request'
import type { RemoteProject } from '@/types/remote-project'

export function listRemoteProjects(): Promise<RemoteProject[]> {
  return request.get('/remote-projects')
}
export function createRemoteProject(data: { name: string; gitUrl: string; username?: string; password?: string; branch: string }): Promise<{ id: number }> {
  return request.post('/remote-projects', data)
}
// ... updateRemoteProject, deleteRemoteProject, cloneRemoteProject, pullRemoteProject
```

```typescript
// api/kg-schedule.ts
import request from '@/utils/request'
import type { KgSchedule } from '@/types/remote-project'

export function listKgSchedules(): Promise<KgSchedule[]> {
  return request.get('/kg-schedules')
}
// ... createKgSchedule, updateKgSchedule, deleteKgSchedule
```

**Step 3: Commit**

```bash
git add -A && git commit -m "feat(remote-project): add frontend API and type definitions"
```

---

### Task 7: Frontend — ProjectList.vue Tab Layout + Remote Project UI

**Files:**
- Modify: `hisi-dev-tool-frontend/src/views/project/ProjectList.vue`

**Step 1: Add el-tabs wrapping existing content**

In the template, wrap the existing `<el-card>` content with:
```vue
<el-tabs v-model="activeTab" @tab-change="handleTabChange">
  <el-tab-pane label="本地项目" name="local">
    <!-- existing local project content moves here -->
  </el-tab-pane>
  <el-tab-pane label="远端项目" name="remote">
    <!-- new remote project content -->
  </el-tab-pane>
</el-tabs>
```

**Step 2: Build remote project tab content**

- Header buttons: "添加远端项目", "刷新列表"
- Table columns: name, gitUrl, branch, cloneStatus (tag), kgStatus, vectorStatus, actions
- Actions: 编辑, 克隆/拉取, 生成图谱, 描述&向量, 定时任务, 删除
- Add dialog: "添加远端项目" form (name, gitUrl, username, password, branch)
- Add dialog: "定时任务配置" (cronExpression input, taskType radio FULL/INCREMENTAL, enabled switch)

**Step 3: Add script state**

```typescript
const activeTab = ref('local')
const remoteProjects = ref<RemoteProject[]>([])
const showAddRemoteDialog = ref(false)
const showScheduleDialog = ref(false)
const remoteForm = reactive({ name: '', gitUrl: '', username: '', password: '', branch: 'main' })
const scheduleForm = reactive({ cronExpression: '0 2 * * *', taskType: 'FULL' as const, enabled: true })
```

**Step 4: Add CRUD handlers**

- `loadRemoteProjects()` — calls API, populates table
- `handleAddRemote()` — validates form, calls `createRemoteProject`, refreshes
- `handleCloneRemote(id)` — calls `cloneRemoteProject(id)`, polls status
- `handlePullRemote(id)` — calls `pullRemoteProject(id)`
- `handleDeleteRemote(id)` — confirm dialog, calls `deleteRemoteProject(id)`
- KG/vector operations: reuse existing `handleGenerateKnowledgeGraph` passing `remote-repos/<path>` as projectPath

**Step 5: Verify UI**

Start frontend dev server, navigate to `/project`, confirm two tabs render. Remote tab shows empty table and "添加远端项目" button opens dialog.

**Step 6: Commit**

```bash
git add -A && git commit -m "feat(remote-project): add remote project tab with CRUD UI and schedule config"
```

---

## Part B: Branch Merge Impact Analysis

### Task 8: Schema Migration — agent_session session_type column

**Files:**
- Modify: `hisi-dev-tool/src/main/java/com/huawei/hisi/ram/config/RamSchemaInitializer.java`

**Step 1: Add column migration**

After the existing `CREATE TABLE IF NOT EXISTS agent_session` block, add:

```java
try {
    jdbcTemplate.execute("ALTER TABLE agent_session ADD COLUMN session_type TEXT DEFAULT 'RAM'");
    log.info("[RAM] Added session_type column to agent_session");
} catch (Exception e) {
    // Column already exists — safe to ignore
}
```

**Step 2: Verify**

Run: `cd hisi-dev-tool && mvn compile -q`
Expected: BUILD SUCCESS

**Step 3: Commit**

```bash
git add -A && git commit -m "feat(merge-analysis): add session_type column to agent_session"
```

---

### Task 9: DiffExtractService

**Files:**
- Create: `hisi-dev-tool/src/main/java/com/huawei/hisi/mergeanalysis/service/DiffExtractService.java`
- Create: `hisi-dev-tool/src/main/java/com/huawei/hisi/mergeanalysis/model/DiffResult.java`
- Create: `hisi-dev-tool/src/test/java/com/huawei/hisi/mergeanalysis/service/DiffExtractServiceTest.java`

**Step 1: Create DiffResult model**

```java
package com.huawei.hisi.mergeanalysis.model;

import lombok.Builder;
import lombok.Data;
import java.util.List;

@Data
@Builder
public class DiffResult {
    private String sourceBranch;
    private String targetBranch;
    private int totalFiles;
    private int totalAdditions;
    private int totalDeletions;
    private List<FileDiff> files;

    @Data
    @Builder
    public static class FileDiff {
        private String filePath;
        private String changeType; // ADD, MODIFY, DELETE, RENAME
        private int additions;
        private int deletions;
        private List<HunkRange> hunks;
        private String patch; // unified diff text
    }

    @Data
    @Builder
    public static class HunkRange {
        private int startLine;
        private int endLine;
    }
}
```

**Step 2: Implement DiffExtractService**

Uses JGit to compute `targetBranch..sourceBranch` diff (what changes would be merged INTO target FROM source). Follow the existing `GitController.getCommitDiff()` pattern (lines 305-354).

Key methods:
- `listBranches(String projectPath)` — returns all local + remote branch names
- `extractDiff(String projectPath, String sourceBranch, String targetBranch)` — returns `DiffResult`

JGit diff: resolve both branch refs to `ObjectId`, create `DiffFormatter` with `DiffEntry` list, iterate entries to build `FileDiff` objects.

**Step 3: Write test**

Create a test that initializes a temp Git repo, creates two branches with different commits, and verifies `extractDiff()` returns the correct file changes.

**Step 4: Run test**

Run: `cd hisi-dev-tool && mvn test -Dtest=DiffExtractServiceTest -q`
Expected: PASS

**Step 5: Commit**

```bash
git add -A && git commit -m "feat(merge-analysis): add DiffExtractService with JGit diff extraction"
```

---

### Task 10: MergeAnalysisController — Branches + Diff endpoints

**Files:**
- Create: `hisi-dev-tool/src/main/java/com/huawei/hisi/mergeanalysis/controller/MergeAnalysisController.java`

**Step 1: Create controller with sync endpoints**

```java
package com.huawei.hisi.mergeanalysis.controller;

@RestController
@RequestMapping("/api/merge-analysis")
@Slf4j
public class MergeAnalysisController {

    private final DiffExtractService diffExtractService;

    public record DiffRequest(String projectPath, String sourceBranch, String targetBranch) {}

    @GetMapping("/branches")
    public ApiResponse<List<String>> listBranches(@RequestParam String projectPath) {
        return ApiResponse.ok(diffExtractService.listBranches(projectPath));
    }

    @PostMapping("/diff")
    public ApiResponse<DiffResult> getDiff(@RequestBody DiffRequest request) {
        DiffResult result = diffExtractService.extractDiff(
            request.projectPath(), request.sourceBranch(), request.targetBranch());
        return ApiResponse.ok(result);
    }
}
```

**Step 2: Verify**

Run: `cd hisi-dev-tool && mvn compile -q`

**Step 3: Commit**

```bash
git add -A && git commit -m "feat(merge-analysis): add branches and diff REST endpoints"
```

---

### Task 11: MergeAnalysisService — Orchestrator + SSE Session

**Files:**
- Create: `hisi-dev-tool/src/main/java/com/huawei/hisi/mergeanalysis/service/MergeAnalysisService.java`
- Modify: `hisi-dev-tool/src/main/java/com/huawei/hisi/mergeanalysis/controller/MergeAnalysisController.java`

**Step 1: Create MergeAnalysisService**

Follow RAM's `RamController` SSE pattern. The service manages session lifecycle:

```java
@Service
@Slf4j
public class MergeAnalysisService {

    private final JdbcTemplate jdbcTemplate;
    private final DiffExtractService diffExtractService;
    private final ImpactAnalysisService impactAnalysisService;
    private final TestScopeService testScopeService;
    private final ExecutorService asyncExecutor;

    // Creates session row in agent_session with session_type='MERGE_ANALYSIS'
    public long createSession(String projectPath, String sourceBranch, String targetBranch) { ... }

    // Runs the 3-node DAG asynchronously, writing events to agent_event
    public void runAnalysis(long sessionId, String projectPath, String sourceBranch, String targetBranch) {
        CompletableFuture.runAsync(() -> {
            try {
                // Node 1: DiffExtract — write CHECKPOINT event with diff result
                emitEvent(sessionId, "CHECKPOINT", "diff_extract", diffResult);

                // Node 2: ImpactAnalysis — write CHECKPOINT with impact result
                emitEvent(sessionId, "CHECKPOINT", "impact_analysis", impactResult);

                // Node 3: TestScope — write CHECKPOINT with test scope result
                emitEvent(sessionId, "CHECKPOINT", "test_scope", testScopeResult);

                // Mark session DONE
                updateSessionStatus(sessionId, "DONE");
            } catch (Exception e) {
                updateSessionStatus(sessionId, "FAILED");
                emitEvent(sessionId, "ERROR", e.getMessage());
            }
        }, asyncExecutor);
    }

    // Writes event row to agent_event table (reuses RAM's event schema)
    private void emitEvent(long sessionId, String type, String nodeName, Object payload) { ... }
}
```

**Step 2: Add session/stream endpoints to controller**

Follow `RamController` SSE pattern (lines 271-350):

```java
// POST /api/merge-analysis/sessions
@PostMapping("/sessions")
public ApiResponse<StartResponse> startSession(@RequestBody StartRequest request) {
    String handle = UUID.randomUUID().toString();
    long id = service.createSession(request.projectPath(), request.sourceBranch(), request.targetBranch());
    sessionIdMap.put(handle, id);
    service.runAnalysis(id, request.projectPath(), request.sourceBranch(), request.targetBranch());
    return ApiResponse.ok(new StartResponse(handle));
}

// GET /api/merge-analysis/sessions/{sid}
// GET /api/merge-analysis/sessions/{sid}/stream?afterSeq=N  (SseEmitter)
```

SSE stream: poll `agent_event` table for events with matching `session_id`, send to client, complete on DONE/FAILED.

**Step 3: Verify**

Run: `cd hisi-dev-tool && mvn compile -q`

**Step 4: Commit**

```bash
git add -A && git commit -m "feat(merge-analysis): add session orchestrator with SSE event streaming"
```

---

### Task 12: ImpactAnalysisService (KG + LLM)

**Files:**
- Create: `hisi-dev-tool/src/main/java/com/huawei/hisi/mergeanalysis/service/ImpactAnalysisService.java`
- Create: `hisi-dev-tool/src/main/java/com/huawei/hisi/mergeanalysis/model/ImpactResult.java`

**Step 1: Create ImpactResult model**

```java
@Data @Builder
public class ImpactResult {
    private List<AffectedEntryPoint> affectedEntryPoints;
    private List<CallChainEdge> callChainEdges;
    private String businessImpactSummary; // LLM-generated
    private String riskLevel; // HIGH/MEDIUM/LOW

    @Data @Builder
    public static class AffectedEntryPoint {
        private String nodeId;
        private String entryType; // CONTROLLER, SCHEDULED, MQ_LISTENER, etc.
        private String httpMethod;
        private String urlPattern;
        private String className;
        private String methodName;
    }

    @Data @Builder
    public static class CallChainEdge {
        private String callerId;
        private String callerName;
        private String calleeId;
        private String calleeName;
        private String callType;
    }
}
```

**Step 2: Implement ImpactAnalysisService**

```java
@Service
@Slf4j
public class ImpactAnalysisService {

    private final KgMcpClient kgMcpClient;      // reuse from RAM
    private final RamClaudeJsonClient claudeClient; // reuse from RAM

    public ImpactResult analyze(String projectPath, List<ChangedMethod> changedMethods) {
        // 1. For each changed method, query KG
        for (ChangedMethod method : changedMethods) {
            // kgMcpClient.rootEntries(projectPath, className, methodName) -> affected entry points
            // kgMcpClient.downstream(projectPath, nodeId, maxDepth) -> downstream chain
            // kgMcpClient.affecting(projectPath, className, methodName) -> upstream callers
        }

        // 2. Call Claude for business impact assessment
        String prompt = buildImpactPrompt(changedMethods, kgResults);
        String llmResponse = claudeClient.callJson(systemPrompt, prompt);

        // 3. Parse and return structured result
        return ImpactResult.builder()...build();
    }
}
```

The `KgMcpClient` is already used by RAM's `ImpactNode` — reuse the same bean.

**Step 3: Verify**

Run: `cd hisi-dev-tool && mvn compile -q`

**Step 4: Commit**

```bash
git add -A && git commit -m "feat(merge-analysis): add KG + LLM impact analysis service"
```

---

### Task 13: TestScopeService (LLM)

**Files:**
- Create: `hisi-dev-tool/src/main/java/com/huawei/hisi/mergeanalysis/service/TestScopeService.java`
- Create: `hisi-dev-tool/src/main/java/com/huawei/hisi/mergeanalysis/model/TestScopeResult.java`

**Step 1: Create TestScopeResult model**

```java
@Data @Builder
public class TestScopeResult {
    private List<TestCaseGroup> groups;
    private List<String> regressionSuggestions;

    @Data @Builder
    public static class TestCaseGroup {
        private String entryPointName;
        private String urlPattern;
        private String riskLevel; // HIGH/MEDIUM/LOW
        private List<TestCase> testCases;
    }

    @Data @Builder
    public static class TestCase {
        private String description;
        private String riskLevel;
        private String reason;
    }
}
```

**Step 2: Implement TestScopeService**

```java
@Service
@Slf4j
public class TestScopeService {

    private final RamClaudeJsonClient claudeClient;

    public TestScopeResult generateTestScope(ImpactResult impactResult, DiffResult diffResult) {
        String prompt = buildTestScopePrompt(impactResult, diffResult);
        String response = claudeClient.callJson(SYSTEM_PROMPT, prompt);
        return parseTestScopeResponse(response);
    }
}
```

System prompt instructs Claude to output structured JSON with test case groups, risk levels, and regression suggestions.

**Step 3: Verify**

Run: `cd hisi-dev-tool && mvn compile -q`

**Step 4: Commit**

```bash
git add -A && git commit -m "feat(merge-analysis): add LLM test scope generation service"
```

---

### Task 14: Frontend — API + Types + SSE Composable

**Files:**
- Create: `hisi-dev-tool-frontend/src/types/merge-analysis.ts`
- Create: `hisi-dev-tool-frontend/src/api/merge-analysis.ts`
- Create: `hisi-dev-tool-frontend/src/composables/useMergeAnalysisSession.ts`

**Step 1: Create types**

```typescript
// types/merge-analysis.ts
export interface DiffResult {
  sourceBranch: string
  targetBranch: string
  totalFiles: number
  totalAdditions: number
  totalDeletions: number
  files: FileDiff[]
}

export interface FileDiff {
  filePath: string
  changeType: 'ADD' | 'MODIFY' | 'DELETE' | 'RENAME'
  additions: number
  deletions: number
  patch: string
}

export interface ImpactResult {
  affectedEntryPoints: AffectedEntryPoint[]
  callChainEdges: CallChainEdge[]
  businessImpactSummary: string
  riskLevel: 'HIGH' | 'MEDIUM' | 'LOW'
}

export interface TestScopeResult {
  groups: TestCaseGroup[]
  regressionSuggestions: string[]
}

// ... AffectedEntryPoint, CallChainEdge, TestCaseGroup, TestCase interfaces
```

**Step 2: Create API module**

Follow `api/ram.ts` pattern.

```typescript
// api/merge-analysis.ts
import request from '@/utils/request'
import type { DiffResult } from '@/types/merge-analysis'

export function listBranches(projectPath: string): Promise<string[]> {
  return request.get('/merge-analysis/branches', { params: { projectPath } })
}

export function getDiff(data: { projectPath: string; sourceBranch: string; targetBranch: string }): Promise<DiffResult> {
  return request.post('/merge-analysis/diff', data)
}

export function startMergeAnalysis(data: { projectPath: string; sourceBranch: string; targetBranch: string }): Promise<{ sessionId: string }> {
  return request.post('/merge-analysis/sessions', data)
}

export function getMergeAnalysisSession(sessionId: string): Promise<{ status: string; currentSeq: number }> {
  return request.get(`/merge-analysis/sessions/${sessionId}`)
}

export function mergeAnalysisStreamUrl(sessionId: string, afterSeq = 0): string {
  return `/api/merge-analysis/sessions/${sessionId}/stream?afterSeq=${afterSeq}`
}
```

**Step 3: Create SSE composable**

Adapt `useRamSession.ts` (324 lines). Simpler version — no clarify/confirm, just stream events until DONE/FAILED.

```typescript
// composables/useMergeAnalysisSession.ts
export function useMergeAnalysisSession() {
  const sessionId = ref('')
  const status = ref<'idle' | 'running' | 'completed' | 'error'>('idle')
  const events = ref<MergeAnalysisEvent[]>([])
  const lastSeq = ref(0)
  const currentNode = ref('')

  // EventSource lifecycle: openStream, tearDown
  // Event handler: parse JSON, dedup by seq, push to events, detect terminal states
  // start(projectPath, sourceBranch, targetBranch): POST session, open SSE
  // rejoin(sid, afterSeq): reattach to existing session

  return { sessionId, status, events, currentNode, lastSeq, start, rejoin }
}
```

**Step 4: Commit**

```bash
git add -A && git commit -m "feat(merge-analysis): add frontend API, types, and SSE composable"
```

---

### Task 15: Frontend — InputPage.vue

**Files:**
- Create: `hisi-dev-tool-frontend/src/views/merge-analysis/InputPage.vue`

**Step 1: Build InputPage**

```vue
<template>
  <div class="merge-analysis-input">
    <el-card>
      <template #header><h3>合入分析 — 选择项目与分支</h3></template>
      <el-form :model="form" label-width="120px">
        <el-form-item label="选择项目">
          <el-select v-model="form.projectPath" filterable placeholder="选择已有项目">
            <el-option v-for="p in projects" :key="p.path" :label="p.name" :value="p.path" />
          </el-select>
        </el-form-item>
        <el-form-item label="源分支 (feature)">
          <el-select v-model="form.sourceBranch" filterable placeholder="选择源分支">
            <el-option v-for="b in branches" :key="b" :label="b" :value="b" />
          </el-select>
        </el-form-item>
        <el-form-item label="目标分支 (main)">
          <el-select v-model="form.targetBranch" filterable placeholder="选择目标分支">
            <el-option v-for="b in branches" :key="b" :label="b" :value="b" />
          </el-select>
        </el-form-item>
        <el-form-item>
          <el-button type="primary" @click="handleNext" :disabled="!canProceed">
            下一步 — 查看 Diff
          </el-button>
        </el-form-item>
      </el-form>
    </el-card>
  </div>
</template>
```

On project select, fetch branches via `listBranches(projectPath)`. On "下一步", navigate to `MergeAnalysisDiff` route with query params.

**Step 2: Commit**

```bash
git add -A && git commit -m "feat(merge-analysis): add InputPage with project and branch selection"
```

---

### Task 16: Frontend — DiffPreviewPage.vue

**Files:**
- Create: `hisi-dev-tool-frontend/src/views/merge-analysis/DiffPreviewPage.vue`

**Step 1: Build DiffPreviewPage**

Layout:
- Stats bar: `N files changed, +X additions, -Y deletions`
- File list with expandable diff patches
- "开始分析" button at bottom

On mount, calls `getDiff(projectPath, sourceBranch, targetBranch)` to fetch diff data. Each `FileDiff` shown as a collapsible card with `changeType` tag color and unified diff text in `<pre>`.

On "开始分析" click: navigates to `MergeAnalysisResult` route.

**Step 2: Commit**

```bash
git add -A && git commit -m "feat(merge-analysis): add DiffPreviewPage with file diff display"
```

---

### Task 17: Frontend — AnalysisPage.vue

**Files:**
- Create: `hisi-dev-tool-frontend/src/views/merge-analysis/AnalysisPage.vue`

**Step 1: Build AnalysisPage**

Layout:
- **Top**: 3-step progress bar (`el-steps`): Diff提取 → 影响分析 → 测试范围
- **Body** (two columns):
  - Left: Impact results — `el-table` of affected entry points + call chain tree
  - Right: Test scope — grouped test case cards with risk badges

On mount: call `start(projectPath, sourceBranch, targetBranch)` from composable. Watch `events` to update `currentNode` and populate result sections as CHECKPOINT events arrive.

**Step 2: Commit**

```bash
git add -A && git commit -m "feat(merge-analysis): add AnalysisPage with DAG progress and results display"
```

---

### Task 18: Frontend — Sidebar + Router Integration

**Files:**
- Modify: `hisi-dev-tool-frontend/src/components/layout/AppSidebar.vue`
- Modify: `hisi-dev-tool-frontend/src/router/index.ts`

**Step 1: Add sidebar menu item**

In `baseMenuItems` array, insert after the RAM entry (index `/ram`):

```typescript
{
  index: '/merge-analysis',
  title: '合入分析',
  icon: Connection,
  menuKey: 'knowledge-graph' as MenuKey  // available when KG is enabled
},
```

Import `Connection` from `@element-plus/icons-vue`.

**Step 2: Add routes**

```typescript
{
  path: '/merge-analysis',
  name: 'MergeAnalysisInput',
  component: () => import('@/views/merge-analysis/InputPage.vue'),
  meta: { title: '合入分析' }
},
{
  path: '/merge-analysis/diff',
  name: 'MergeAnalysisDiff',
  component: () => import('@/views/merge-analysis/DiffPreviewPage.vue'),
  meta: { title: '合入分析 - Diff' }
},
{
  path: '/merge-analysis/result/:sid',
  name: 'MergeAnalysisResult',
  component: () => import('@/views/merge-analysis/AnalysisPage.vue'),
  meta: { title: '合入分析 - 结果' }
},
```

**Step 3: Verify full flow**

Start both backend and frontend. Navigate through: sidebar "合入分析" → select project/branches → view diff → start analysis → see progress + results.

**Step 4: Commit**

```bash
git add -A && git commit -m "feat(merge-analysis): add sidebar entry and router integration"
```

---

## Implementation Order & Dependencies

```
Task 1 (schema)
  └→ Task 2 (model+repo) → Task 3 (encryption) → Task 4 (service+controller)
  └→ Task 5 (scheduler)
  └→ Task 6 (frontend API) → Task 7 (ProjectList UI)

Task 8 (session_type migration)
  └→ Task 9 (DiffExtract) → Task 10 (controller) → Task 11 (orchestrator+SSE)
  └→ Task 12 (ImpactAnalysis) → Task 13 (TestScope)
  └→ Task 14 (frontend API+composable) → Task 15 (InputPage) → Task 16 (DiffPreview) → Task 17 (AnalysisPage) → Task 18 (sidebar+router)
```

**Parallelizable batches:**
- Batch 1: Tasks 1-3 (schema + model + encryption) | Task 8 (session_type)
- Batch 2: Tasks 4-5 (service layer) | Tasks 9-10 (diff + controller)
- Batch 3: Tasks 6-7 (frontend remote project) | Tasks 11-13 (orchestrator + analysis)
- Batch 4: Tasks 14-18 (frontend merge analysis)
