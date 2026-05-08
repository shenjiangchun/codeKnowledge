# Git Repository Scanning Feature Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Add API to scan project directory for existing git repositories and adapt frontend to display and operate on them.

**Architecture:** Backend scans configured project directory for `.git` folders using JGit, returns repository metadata. Frontend adds "Scan" button and merges scanned repos with cloned projects list, enabling git operations on all repos.

**Tech Stack:** Spring Boot 3.2, JGit, Vue 3, TypeScript, Element Plus

---

## Task 1: Create GitRepositoryInfo Model

**Files:**
- Create: `src/main/java/com/huawei/hisi/model/GitRepositoryInfo.java`

**Step 1: Write the model class**

```java
package com.huawei.hisi.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Git repository information DTO
 * Represents a scanned or cloned git repository
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GitRepositoryInfo {

    /** Repository name (folder name) */
    private String name;

    /** Full path to repository */
    private String path;

    /** Current branch name */
    private String branch;

    /** Remote URL (if configured) */
    private String remoteUrl;

    /** Whether working tree is clean */
    private boolean clean;

    /** Source: "scanned" or "cloned" */
    private String source;

    /** Last commit message (optional) */
    private String lastCommitMessage;

    /** Last commit date (optional) */
    private String lastCommitDate;
}
```

**Step 2: Verify compilation**

Run: `cd C:/Users/47583/projects/hisi-dev-tool && mvn compile -q`
Expected: BUILD SUCCESS

**Step 3: Commit**

```bash
git add src/main/java/com/huawei/hisi/model/GitRepositoryInfo.java
git commit -m "feat: add GitRepositoryInfo model for repository scanning"
```

---

## Task 2: Add Scan Method to ProjectService Interface

**Files:**
- Modify: `src/main/java/com/huawei/hisi/service/ProjectService.java`

**Step 1: Add method signature to interface**

Add after line 33:

```java
    /**
     * Scan project directory for git repositories
     * @return list of git repositories found
     */
    List<GitRepositoryInfo> scanGitRepositories();
```

**Step 2: Add import at top**

Add import after line 1:

```java
import com.huawei.hisi.model.GitRepositoryInfo;
```

**Step 3: Verify compilation**

Run: `cd C:/Users/47583/projects/hisi-dev-tool && mvn compile -q`
Expected: BUILD SUCCESS

**Step 4: Commit**

```bash
git add src/main/java/com/huawei/hisi/service/ProjectService.java
git commit -m "feat: add scanGitRepositories method to ProjectService interface"
```

---

## Task 3: Implement scanGitRepositories in ProjectServiceImpl

**Files:**
- Modify: `src/main/java/com/huawei/hisi/service/impl/ProjectServiceImpl.java`

**Step 1: Read current implementation first**

Run: Read the file to understand current structure.

**Step 2: Add imports at top**

Add after existing imports:

```java
import com.huawei.hisi.model.GitRepositoryInfo;
import com.huawei.hisi.service.AppConfigService;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.transport.RemoteConfig;
import org.eclipse.jgit.transport.URIish;
import org.springframework.beans.factory.annotation.Autowired;
```

**Step 3: Add AppConfigService dependency**

Add field injection:

```java
    @Autowired
    private AppConfigService appConfigService;
```

**Step 4: Implement scanGitRepositories method**

Add at end of class before closing brace:

```java
    @Override
    public List<GitRepositoryInfo> scanGitRepositories() {
        List<GitRepositoryInfo> repositories = new ArrayList<>();

        String projectDir = appConfigService.getProjectDir();
        if (projectDir == null || projectDir.trim().isEmpty()) {
            LOG.warn("Project directory not configured");
            return repositories;
        }

        File baseDir = new File(projectDir);
        if (!baseDir.exists() || !baseDir.isDirectory()) {
            LOG.warn("Project directory does not exist: {}", projectDir);
            return repositories;
        }

        // Scan immediate subdirectories for .git folders
        File[] subDirs = baseDir.listFiles(File::isDirectory);
        if (subDirs == null) {
            return repositories;
        }

        for (File subDir : subDirs) {
            File gitDir = new File(subDir, ".git");
            if (gitDir.exists() && gitDir.isDirectory()) {
                try {
                    GitRepositoryInfo repoInfo = extractGitInfo(subDir);
                    if (repoInfo != null) {
                        repositories.add(repoInfo);
                    }
                } catch (Exception e) {
                    LOG.warn("Failed to read git info for: {}", subDir.getName(), e);
                }
            }
        }

        LOG.info("Scanned {} git repositories in {}", repositories.size(), projectDir);
        return repositories;
    }

    private GitRepositoryInfo extractGitInfo(File repoDir) {
        try (Git git = Git.open(repoDir)) {
            String branch = getCurrentBranch(git);
            String remoteUrl = getRemoteUrl(git);
            boolean clean = git.status().call().isClean();

            // Get last commit info
            String lastCommitMessage = null;
            String lastCommitDate = null;
            try {
                Iterable<RevCommit> logs = git.log().setMaxCount(1).call();
                for (RevCommit commit : logs) {
                    lastCommitMessage = commit.getShortMessage();
                    lastCommitDate = commit.getAuthorIdent().getWhen().toString();
                    break;
                }
            } catch (Exception e) {
                LOG.debug("Could not get last commit for {}", repoDir.getName());
            }

            return GitRepositoryInfo.builder()
                    .name(repoDir.getName())
                    .path(repoDir.getAbsolutePath())
                    .branch(branch)
                    .remoteUrl(remoteUrl)
                    .clean(clean)
                    .source("scanned")
                    .lastCommitMessage(lastCommitMessage)
                    .lastCommitDate(lastCommitDate)
                    .build();
        } catch (Exception e) {
            LOG.error("Failed to open git repository: {}", repoDir.getAbsolutePath(), e);
            return null;
        }
    }

    private String getCurrentBranch(Git git) throws Exception {
        Ref head = git.getRepository().exactRef("HEAD");
        if (head != null && head.isSymbolic()) {
            return head.getTarget().getName().replace("refs/heads/", "");
        }
        return "detached";
    }

    private String getRemoteUrl(Git git) {
        try {
            RemoteConfig remote = new RemoteConfig(git.getRepository().getConfig(), "origin");
            URIish uri = remote.getURIs().stream().findFirst().orElse(null);
            return uri != null ? uri.toString() : null;
        } catch (Exception e) {
            return null;
        }
    }
```

**Step 5: Add LOG field if not present**

Add at class level if missing:

```java
    private static final Logger LOG = LoggerFactory.getLogger(ProjectServiceImpl.class);
```

**Step 6: Add missing imports**

Add if not present:

```java
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
```

**Step 7: Verify compilation**

Run: `cd C:/Users/47583/projects/hisi-dev-tool && mvn compile -q`
Expected: BUILD SUCCESS

**Step 8: Commit**

```bash
git add src/main/java/com/huawei/hisi/service/impl/ProjectServiceImpl.java
git commit -m "feat: implement scanGitRepositories in ProjectServiceImpl"
```

---

## Task 4: Add API Endpoint in ProjectController

**Files:**
- Modify: `src/main/java/com/huawei/hisi/controller/ProjectController.java`

**Step 1: Add import**

Add after existing imports:

```java
import com.huawei.hisi.model.GitRepositoryInfo;
```

**Step 2: Add endpoint method**

Add after line 58 (after getStatus method):

```java
    /**
     * Scan project directory for git repositories
     * GET /api/projects/scan-git-repos
     */
    @GetMapping("/scan-git-repos")
    public ApiResponse<List<GitRepositoryInfo>> scanGitRepositories() {
        List<GitRepositoryInfo> repositories = projectService.scanGitRepositories();
        return ApiResponse.success(repositories);
    }
```

**Step 3: Verify compilation**

Run: `cd C:/Users/47583/projects/hisi-dev-tool && mvn compile -q`
Expected: BUILD SUCCESS

**Step 4: Commit**

```bash
git add src/main/java/com/huawei/hisi/controller/ProjectController.java
git commit -m "feat: add /api/projects/scan-git-repos endpoint"
```

---

## Task 5: Update Frontend Types

**Files:**
- Modify: `src/types/callchain.ts` (in hisi-dev-tool-frontend)

**Step 1: Update ProjectCloneStatus interface**

Replace the existing ProjectCloneStatus interface (lines 45-51) with:

```typescript
export interface GitRepositoryInfo {
  name: string
  path: string
  branch: string
  remoteUrl?: string
  clean: boolean
  source: 'scanned' | 'cloned'
  lastCommitMessage?: string
  lastCommitDate?: string
  // Legacy fields for backward compatibility
  url?: string
  status?: string
  updateTime?: string
}
```

**Step 2: Verify TypeScript compilation**

Run: `cd C:/Users/47583/projects/hisi-dev-tool-frontend && npm run build 2>&1 | head -20`
Expected: Build succeeds or shows only warnings

**Step 3: Commit**

```bash
git add src/types/callchain.ts
git commit -m "feat: update GitRepositoryInfo type for repository scanning"
```

---

## Task 6: Update Frontend API

**Files:**
- Modify: `src/api/project.ts` (in hisi-dev-tool-frontend)

**Step 1: Add scanGitRepos method**

Add after line 27:

```typescript
  // Scan for existing git repositories
  scanGitRepos() {
    return request.get<GitRepositoryInfo[]>('/projects/scan-git-repos')
  }
```

**Step 2: Add type import at top**

Add at beginning of file:

```typescript
import type { GitRepositoryInfo } from '@/types/callchain'
```

**Step 3: Verify TypeScript compilation**

Run: `cd C:/Users/47583/projects/hisi-dev-tool-frontend && npm run build 2>&1 | head -20`
Expected: Build succeeds

**Step 4: Commit**

```bash
git add src/api/project.ts
git commit -m "feat: add scanGitRepos API method"
```

---

## Task 7: Update ProjectList Vue Component

**Files:**
- Modify: `src/views/project/ProjectList.vue` (in hisi-dev-tool-frontend)

**Step 1: Update script section**

Replace the entire `<script setup lang="ts">` section with:

```typescript
<script setup lang="ts">
import { ref, reactive, onMounted } from 'vue'
import { Plus, Check, Select, FolderOpened } from '@element-plus/icons-vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { projectApi } from '@/api/project'
import { useAppStore } from '@/stores/app'
import ProjectDirConfig from '@/components/ProjectDirConfig.vue'
import GitOperations from '@/components/GitOperations.vue'
import type { GitRepositoryInfo } from '@/types/callchain'

const appStore = useAppStore()

const loading = ref(false)
const cloning = ref(false)
const scanning = ref(false)
const showCloneDialog = ref(false)
const projects = ref<GitRepositoryInfo[]>([])

const cloneForm = reactive({
  url: '',
  branch: 'master',
  directory: ''
})

const getStatusType = (row: GitRepositoryInfo) => {
  if (row.source === 'scanned') return row.clean ? 'success' : 'warning'
  const types: Record<string, string> = {
    READY: 'success',
    CLONING: 'warning',
    ERROR: 'danger'
  }
  return types[row.status || ''] || 'info'
}

const getStatusText = (row: GitRepositoryInfo) => {
  if (row.source === 'scanned') return row.clean ? 'Clean' : 'Modified'
  return row.status || 'Unknown'
}

// All repos have git since they were scanned or cloned
const hasGit = (row: GitRepositoryInfo) => {
  return true
}

// Construct full project path
const getProjectPath = (projectName: string) => {
  return `${appStore.projectDir}/${projectName}`
}

// Handle project selection
const handleSelect = (row: GitRepositoryInfo) => {
  if (!appStore.projectDirConfigured) {
    ElMessage.warning('请先配置项目目录')
    return
  }
  appStore.selectProject(row.name)
  ElMessage.success(`已选择项目: ${row.name}`)
}

const loadProjects = async () => {
  loading.value = true
  try {
    // Merge scanned repos with existing project list
    const [scannedRes, listRes] = await Promise.all([
      projectApi.scanGitRepos(),
      projectApi.getProjects().catch(() => ({ data: [] }))
    ])

    const scannedRepos = scannedRes.data || []
    const existingNames = new Set(scannedRepos.map(r => r.name))

    // Convert legacy projects to GitRepositoryInfo format
    const legacyProjects = (listRes.data || [])
      .filter((name: string) => !existingNames.has(name))
      .map((name: string): GitRepositoryInfo => ({
        name,
        path: getProjectPath(name),
        branch: 'unknown',
        clean: true,
        source: 'cloned',
        status: 'READY'
      }))

    projects.value = [...scannedRepos, ...legacyProjects]
  } catch (error) {
    ElMessage.error('加载项目列表失败')
    console.error('Failed to load projects:', error)
  } finally {
    loading.value = false
  }
}

const handleScan = async () => {
  if (!appStore.projectDirConfigured) {
    ElMessage.warning('请先配置项目目录')
    return
  }
  scanning.value = true
  try {
    const res = await projectApi.scanGitRepos()
    projects.value = res.data || []
    ElMessage.success(`扫描完成，发现 ${projects.value.length} 个仓库`)
  } catch (error) {
    ElMessage.error('扫描失败')
  } finally {
    scanning.value = false
  }
}

const handleClone = async () => {
  if (!cloneForm.url) {
    ElMessage.warning('请输入仓库地址')
    return
  }
  cloning.value = true
  try {
    await projectApi.clone(cloneForm)
    ElMessage.success('克隆成功')
    showCloneDialog.value = false
    handleScan() // Refresh list after clone
  } catch (error) {
    ElMessage.error('克隆失败')
  } finally {
    cloning.value = false
  }
}

const handlePull = async (row: GitRepositoryInfo) => {
  try {
    await projectApi.pull(row.name)
    ElMessage.success('拉取成功')
    loadProjects()
  } catch (error) {
    ElMessage.error('拉取失败')
  }
}

const handleDelete = (row: GitRepositoryInfo) => {
  ElMessageBox.confirm(`确定要删除项目 ${row.name} 吗？`, '确认删除', {
    type: 'warning'
  }).then(async () => {
    try {
      await projectApi.delete(row.name)
      ElMessage.success('删除成功')
      // Clear selection if deleted project was selected
      if (appStore.selectedProject === row.name) {
        appStore.clearSelectedProject()
      }
      loadProjects()
    } catch (error) {
      ElMessage.error('删除失败')
    }
  }).catch(() => {})
}

onMounted(() => {
  if (appStore.projectDirConfigured) {
    handleScan()
  }
})
</script>
```

**Step 2: Update template - add Scan button**

Replace the card header section (lines 32-40) with:

```vue
    <el-card>
      <template #header>
        <div class="card-header">
          <span>项目管理</span>
          <div class="header-buttons">
            <el-button
              type="success"
              @click="handleScan"
              :loading="scanning"
              :disabled="!appStore.projectDirConfigured"
            >
              <el-icon><FolderOpened /></el-icon>
              扫描仓库
            </el-button>
            <el-button type="primary" @click="showCloneDialog = true">
              <el-icon><Plus /></el-icon>
              克隆项目
            </el-button>
          </div>
        </div>
      </template>
```

**Step 3: Update template - update table columns**

Replace the table section (lines 42-88) with:

```vue
      <el-table :data="projects" v-loading="loading" stripe>
        <el-table-column prop="name" label="项目名称">
          <template #default="{ row }">
            <div class="project-name-cell">
              <span>{{ row.name }}</span>
              <el-tag v-if="appStore.selectedProject === row.name" type="success" size="small">
                已选择
              </el-tag>
            </div>
          </template>
        </el-table-column>
        <el-table-column prop="branch" label="分支" width="100" />
        <el-table-column prop="remoteUrl" label="远程地址" show-overflow-tooltip>
          <template #default="{ row }">
            {{ row.remoteUrl || row.url || '-' }}
          </template>
        </el-table-column>
        <el-table-column label="状态" width="120">
          <template #default="{ row }">
            <el-tag :type="getStatusType(row)">{{ getStatusText(row) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="来源" width="80" align="center">
          <template #default="{ row }">
            <el-tag :type="row.source === 'scanned' ? 'primary' : 'info'" size="small">
              {{ row.source === 'scanned' ? '扫描' : '克隆' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="lastCommitMessage" label="最近提交" show-overflow-tooltip>
          <template #default="{ row }">
            <span v-if="row.lastCommitMessage">{{ row.lastCommitMessage }}</span>
            <span v-else class="text-muted">-</span>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="280">
          <template #default="{ row }">
            <el-button
              type="primary"
              link
              @click="handleSelect(row)"
              :disabled="!appStore.projectDirConfigured"
            >
              <el-icon><Select /></el-icon>
              选择
            </el-button>
            <GitOperations
              v-if="hasGit(row) && appStore.projectDirConfigured"
              :project-path="getProjectPath(row.name)"
            />
            <el-button type="primary" link @click="handlePull(row)">拉取</el-button>
            <el-button type="danger" link @click="handleDelete(row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>
```

**Step 4: Update style section**

Replace the style section with:

```vue
<style scoped>
.guidance-alert {
  margin-bottom: 16px;
}

.card-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.header-buttons {
  display: flex;
  gap: 8px;
}

.project-name-cell {
  display: flex;
  align-items: center;
  gap: 8px;
}

.text-muted {
  color: #909399;
}
</style>
```

**Step 5: Verify Vue compilation**

Run: `cd C:/Users/47583/projects/hisi-dev-tool-frontend && npm run build 2>&1 | head -30`
Expected: Build succeeds

**Step 6: Commit**

```bash
git add src/views/project/ProjectList.vue
git commit -m "feat: update ProjectList with git repository scanning"
```

---

## Task 8: Update App Store for Better Menu Control

**Files:**
- Modify: `src/stores/app.ts` (in hisi-dev-tool-frontend)

**Step 1: Update availableMenus computed property**

Replace the availableMenus computed (lines 17-22) with:

```typescript
  // Menu availability - requires project selection for analysis features
  const availableMenus = computed(() => ({
    'project-management': true, // Always available
    'call-chain': projectDirConfigured.value && projectSelected.value,
    'log-analysis': projectDirConfigured.value && projectSelected.value,
    'ops': false // Permanently disabled - no local monitoring capability
  }))
```

**Step 2: Commit**

```bash
git add src/stores/app.ts
git commit -m "refactor: clarify menu availability logic in app store"
```

---

## Task 9: Build and Test Backend

**Step 1: Build backend**

Run: `cd C:/Users/47583/projects/hisi-dev-tool && mvn clean package -DskipTests -q`
Expected: BUILD SUCCESS

**Step 2: Stop any running Java processes**

Run: `powershell -Command "Get-Process java -ErrorAction SilentlyContinue | Stop-Process -Force"`

**Step 3: Start backend**

Run: `cd C:/Users/47583/projects/hisi-dev-tool && java -jar target/devTools-1.0.0.jar &`
Expected: Application starts on port 8080

**Step 4: Test API endpoint**

Run: `curl -s http://localhost:8080/api/projects/scan-git-repos | head -100`
Expected: JSON response with list of repositories

---

## Task 10: Build and Test Frontend

**Step 1: Start frontend dev server**

Run: `cd C:/Users/47583/projects/hisi-dev-tool-frontend && npm run dev &`
Expected: Dev server starts on port 5173 or 5174

**Step 2: Test in browser**

1. Navigate to project management page
2. Configure project directory
3. Click "扫描仓库" button
4. Verify repositories appear in table
5. Select a repository and verify menu becomes available

---

## Task 11: Final Commit and Push

**Step 1: Commit all changes**

```bash
git add -A
git commit -m "feat: add git repository scanning and improved project management"
```

**Step 2: Push to remote**

```bash
git push origin release1
```

---

## Verification Checklist

- [ ] Backend API `/api/projects/scan-git-repos` returns repository list
- [ ] Frontend shows "扫描仓库" button
- [ ] Scanning finds all `.git` folders in project directory
- [ ] Each repo shows: name, branch, remote URL, status
- [ ] Git operations (pull, checkout, logs) work on scanned repos
- [ ] Project selection enables call-chain and log-analysis menus