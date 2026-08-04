# 调用链追踪增强功能实施计划

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** 实现调用链追踪增强功能，包括桥接表集成、运行时配置、用户引导页面和 Git 操作支持。

**Architecture:** 采用分层架构改造：Phase 1 数据库层（已完成）、Phase 2 配置服务层、Phase 3 前端引导层、Phase 4 调用链核心层改造。

**Tech Stack:** Spring Boot 3.2 + Java 17 + OpenGauss/PostgreSQL + Vue 3 + TypeScript + Element Plus

---

## Phase 1: 数据库升级 (已完成)

数据库迁移脚本和初始化脚本已创建完成。

**已创建文件:**
- `src/main/resources/db/migration/V7__enhance_call_chain_tables.sql`
- `db/init.sql` (已更新)

---

## Phase 2: PROJECT_DIR 运行时配置

### Task 2.1: 创建 AppConfig 模型类

**Files:**
- Create: `src/main/java/com/huawei/hisi/model/AppConfig.java`

**Step 1: 编写模型类**

```java
package com.huawei.hisi.model;

import lombok.Data;
import java.time.LocalDateTime;

@Data
public class AppConfig {
    private String key;
    private String value;
    private String description;
    private LocalDateTime updatedAt;
    private String updatedBy;
}
```

**Step 2: 提交**

```bash
git add src/main/java/com/huawei/hisi/model/AppConfig.java
git commit -m "feat: add AppConfig model class"
```

---

### Task 2.2: 创建 AppConfigRepository

**Files:**
- Create: `src/main/java/com/huawei/hisi/repository/AppConfigRepository.java`

**Step 1: 编写 Repository 类**

```java
package com.huawei.hisi.repository;

import com.huawei.hisi.model.AppConfig;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;

@Repository
public class AppConfigRepository {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final RowMapper<AppConfig> rowMapper = (rs, rowNum) -> {
        AppConfig config = new AppConfig();
        config.setKey(rs.getString("key"));
        config.setValue(rs.getString("value"));
        config.setDescription(rs.getString("description"));
        config.setUpdatedAt(rs.getTimestamp("updated_at") != null ?
            rs.getTimestamp("updated_at").toLocalDateTime() : null);
        config.setUpdatedBy(rs.getString("updated_by"));
        return config;
    };

    public Optional<AppConfig> findByKey(String key) {
        String sql = "SELECT key, value, description, updated_at, updated_by FROM app_config WHERE key = ?";
        try {
            AppConfig config = jdbcTemplate.queryForObject(sql, rowMapper, key);
            return Optional.ofNullable(config);
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    public int update(String key, String value, String updatedBy) {
        String sql = "UPDATE app_config SET value = ?, updated_at = CURRENT_TIMESTAMP, updated_by = ? WHERE key = ?";
        return jdbcTemplate.update(sql, value, updatedBy, key);
    }

    public int insert(String key, String value, String description, String updatedBy) {
        String sql = "INSERT INTO app_config (key, value, description, updated_by) VALUES (?, ?, ?, ?) ON CONFLICT (key) DO UPDATE SET value = ?, updated_at = CURRENT_TIMESTAMP, updated_by = ?";
        return jdbcTemplate.update(sql, key, value, description, updatedBy, value, updatedBy);
    }
}
```

**Step 2: 提交**

```bash
git add src/main/java/com/huawei/hisi/repository/AppConfigRepository.java
git commit -m "feat: add AppConfigRepository for config persistence"
```

---

### Task 2.3: 创建 AppConfigService 接口

**Files:**
- Create: `src/main/java/com/huawei/hisi/service/AppConfigService.java`

**Step 1: 编写服务接口**

```java
package com.huawei.hisi.service;

import com.huawei.hisi.model.AppConfig;

public interface AppConfigService {

    /**
     * 获取配置项
     */
    AppConfig getConfig(String key);

    /**
     * 获取 PROJECT_DIR 配置
     */
    String getProjectDir();

    /**
     * 更新 PROJECT_DIR 配置
     */
    void updateProjectDir(String newPath, String updatedBy);

    /**
     * 验证路径有效性
     */
    boolean isValidPath(String path);
}
```

**Step 2: 提交**

```bash
git add src/main/java/com/huawei/hisi/service/AppConfigService.java
git commit -m "feat: add AppConfigService interface"
```

---

### Task 2.4: 创建 AppConfigServiceImpl 实现类

**Files:**
- Create: `src/main/java/com/huawei/hisi/service/AppConfigServiceImpl.java`

**Step 1: 编写服务实现**

```java
package com.huawei.hisi.service;

import com.huawei.hisi.model.AppConfig;
import com.huawei.hisi.repository.AppConfigRepository;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.File;
import java.util.concurrent.atomic.AtomicReference;

@Service
public class AppConfigServiceImpl implements AppConfigService {

    private static final Logger LOG = LoggerFactory.getLogger(AppConfigServiceImpl.class);
    private static final String PROJECT_DIR_KEY = "PROJECT_DIR";

    @Autowired
    private AppConfigRepository configRepository;

    private final AtomicReference<String> projectDirCache = new AtomicReference<>("");

    @PostConstruct
    public void init() {
        loadProjectDir();
    }

    private void loadProjectDir() {
        configRepository.findByKey(PROJECT_DIR_KEY).ifPresentOrElse(
            config -> {
                projectDirCache.set(config.getValue());
                LOG.info("Loaded PROJECT_DIR from database: {}", config.getValue());
            },
            () -> {
                configRepository.insert(PROJECT_DIR_KEY, "", "项目代码存放目录", "system");
                LOG.info("Initialized PROJECT_DIR with empty value");
            }
        );
    }

    @Override
    public AppConfig getConfig(String key) {
        return configRepository.findByKey(key).orElse(null);
    }

    @Override
    public String getProjectDir() {
        return projectDirCache.get();
    }

    @Override
    public void updateProjectDir(String newPath, String updatedBy) {
        if (!isValidPath(newPath)) {
            throw new IllegalArgumentException("Invalid path: " + newPath);
        }

        configRepository.update(PROJECT_DIR_KEY, newPath, updatedBy);
        projectDirCache.set(newPath);

        LOG.info("PROJECT_DIR updated to: {} by {}", newPath, updatedBy);
    }

    @Override
    public boolean isValidPath(String path) {
        if (path == null || path.trim().isEmpty()) {
            return true; // Allow empty path
        }

        File dir = new File(path);
        // Path is valid if it exists as a directory, or parent exists (for new directories)
        return dir.exists() || dir.getParentFile() != null && dir.getParentFile().exists();
    }
}
```

**Step 2: 提交**

```bash
git add src/main/java/com/huawei/hisi/service/AppConfigServiceImpl.java
git commit -m "feat: implement AppConfigService with runtime PROJECT_DIR support"
```

---

### Task 2.5: 创建 ConfigController

**Files:**
- Create: `src/main/java/com/huawei/hisi/controller/ConfigController.java`

**Step 1: 编写控制器**

```java
package com.huawei.hisi.controller;

import com.huawei.hisi.model.ApiResponse;
import com.huawei.hisi.model.AppConfig;
import com.huawei.hisi.service.AppConfigService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/config")
public class ConfigController {

    @Autowired
    private AppConfigService configService;

    @GetMapping
    public ApiResponse<AppConfig> getConfig(@RequestParam String key) {
        AppConfig config = configService.getConfig(key);
        if (config == null) {
            return ApiResponse.error(404, "Configuration not found: " + key);
        }
        return ApiResponse.success(config);
    }

    @PutMapping
    public ApiResponse<AppConfig> updateConfig(@RequestBody UpdateConfigRequest request) {
        if ("PROJECT_DIR".equals(request.getKey())) {
            if (!configService.isValidPath(request.getValue())) {
                return ApiResponse.error(400, "Invalid path: " + request.getValue());
            }
            configService.updateProjectDir(request.getValue(), request.getUpdatedBy() != null ? request.getUpdatedBy() : "system");
            AppConfig updated = configService.getConfig(request.getKey());
            return ApiResponse.success(updated);
        }
        return ApiResponse.error(400, "Unsupported configuration key: " + request.getKey());
    }

    @GetMapping("/project-dir")
    public ApiResponse<AppConfig> getProjectDir() {
        AppConfig config = configService.getConfig("PROJECT_DIR");
        return ApiResponse.success(config);
    }

    @lombok.Data
    public static class UpdateConfigRequest {
        private String key;
        private String value;
        private String updatedBy;
    }
}
```

**Step 2: 提交**

```bash
git add src/main/java/com/huawei/hisi/controller/ConfigController.java
git commit -m "feat: add ConfigController for runtime configuration"
```

---

### Task 2.6: 修改 DataSourceConfig 使用动态 PROJECT_DIR

**Files:**
- Modify: `src/main/java/com/huawei/hisi/config/DataSourceConfig.java`

**Step 1: 移除静态 PROJECT_DIR，改为通过 AppConfigService 获取**

在文件顶部添加:
```java
@Autowired
private AppConfigService appConfigService;
```

移除静态 setter:
```java
// 删除以下代码
@Value("${app.project_dir}")
public void setProjectDir(String projectDir) {
    PROJECT_DIR = projectDir;
}
```

添加动态获取方法:
```java
/**
 * 获取当前 PROJECT_DIR (从运行时配置获取)
 */
public String getProjectDir() {
    String dir = appConfigService.getProjectDir();
    return dir != null && !dir.isEmpty() ? dir : PROJECT_DIR;
}
```

**Step 2: 提交**

```bash
git add src/main/java/com/huawei/hisi/config/DataSourceConfig.java
git commit -m "refactor: DataSourceConfig to use dynamic PROJECT_DIR from AppConfigService"
```

---

### Task 2.7: 修改 HisiURIMethodChainToDBServiceImpl 使用动态 PROJECT_DIR

**Files:**
- Modify: `src/main/java/com/huawei/hisi/service/HisiURIMethodChainToDBServiceImpl.java`

**Step 1: 注入 AppConfigService**

在类中添加:
```java
@Autowired
private AppConfigService appConfigService;
```

**Step 2: 修改 chainGenerator 方法**

将:
```java
List<Path> sourceRoots = findSourceRoots(Paths.get(PROJECT_DIR));
```

改为:
```java
String currentProjectDir = appConfigService.getProjectDir();
if (currentProjectDir == null || currentProjectDir.isEmpty()) {
    throw new IllegalStateException("PROJECT_DIR is not configured. Please configure it in project management.");
}
List<Path> sourceRoots = findSourceRoots(Paths.get(currentProjectDir));
```

**Step 3: 移除静态导入**

删除:
```java
import static com.huawei.hisi.config.DataSourceConfig.PROJECT_DIR;
```

**Step 4: 提交**

```bash
git add src/main/java/com/huawei/hisi/service/HisiURIMethodChainToDBServiceImpl.java
git commit -m "refactor: use dynamic PROJECT_DIR from AppConfigService"
```

---

### Task 2.8: 验证 Phase 2

**Step 1: 构建项目**

```bash
cd C:\Users\47583\projects\hisi-dev-tool
mvn clean compile -DskipTests
```

Expected: BUILD SUCCESS

**Step 2: 提交 Phase 2 完成标记**

```bash
git add .
git commit -m "feat(phase2): complete PROJECT_DIR runtime configuration"
```

---

## Phase 3: 前端引导页面与 Git 操作

### Task 3.1: 创建前端配置 API

**Files:**
- Create: `src/api/config.ts` (frontend)

**Step 1: 编写 API 模块**

```typescript
import request from '@/utils/request'

export interface AppConfig {
  key: string
  value: string
  description: string
  updatedAt?: string
  updatedBy?: string
}

export interface UpdateConfigRequest {
  key: string
  value: string
  updatedBy?: string
}

export const configApi = {
  // 获取配置
  getConfig(key: string) {
    return request.get<AppConfig>('/config', { params: { key } })
  },

  // 更新配置
  updateConfig(data: UpdateConfigRequest) {
    return request.put<AppConfig>('/config', data)
  },

  // 获取 PROJECT_DIR
  getProjectDir() {
    return request.get<AppConfig>('/config/project-dir')
  },

  // 更新 PROJECT_DIR
  updateProjectDir(value: string, updatedBy?: string) {
    return this.updateConfig({
      key: 'PROJECT_DIR',
      value,
      updatedBy
    })
  }
}
```

**Step 2: 提交**

```bash
git add src/api/config.ts
git commit -m "feat(frontend): add config API module"
```

---

### Task 3.2: 创建 Git 操作 API

**Files:**
- Create: `src/api/git.ts` (frontend)

**Step 1: 编写 API 模块**

```typescript
import request from '@/utils/request'

export interface GitStatus {
  branch: string
  clean: boolean
  ahead: number
  behind: number
  modified: string[]
  untracked: string[]
}

export interface GitLog {
  commitId: string
  message: string
  author: string
  date: string
}

export const gitApi = {
  // 获取 Git 状态
  getStatus(path: string) {
    return request.get<GitStatus>('/git/status', { params: { path } })
  },

  // 切换分支
  checkout(path: string, branch: string) {
    return request.post('/git/checkout', { path, branch })
  },

  // 拉取最新代码
  pull(path: string) {
    return request.post('/git/pull', { path })
  },

  // 获取提交日志
  getLogs(path: string, limit: number = 10) {
    return request.get<GitLog[]>('/git/logs', { params: { path, limit } })
  }
}
```

**Step 2: 提交**

```bash
git add src/api/git.ts
git commit -m "feat(frontend): add Git API module"
```

---

### Task 3.3: 创建后端 Git Controller

**Files:**
- Create: `src/main/java/com/huawei/hisi/controller/GitController.java`

**Step 1: 编写控制器**

```java
package com.huawei.hisi.controller;

import com.huawei.hisi.model.ApiResponse;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.PullResult;
import org.eclipse.jgit.api.Status;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;

import java.io.File;
import java.util.*;

@RestController
@RequestMapping("/api/git")
public class GitController {

    @Value("${app.codeHubUser:}")
    private String gitUser;

    @Value("${app.codeHubPassword:}")
    private String gitPassword;

    @GetMapping("/status")
    public ApiResponse<Map<String, Object>> getStatus(@RequestParam String path) {
        try (Git git = Git.open(new File(path))) {
            Status status = git.status().call();

            Map<String, Object> result = new HashMap<>();
            result.put("branch", getCurrentBranch(git));
            result.put("clean", status.isClean());
            result.put("modified", new ArrayList<>(status.getModified()));
            result.put("untracked", new ArrayList<>(status.getUntracked()));
            result.put("added", new ArrayList<>(status.getAdded()));
            result.put("removed", new ArrayList<>(status.getRemoved()));

            return ApiResponse.success(result);
        } catch (Exception e) {
            return ApiResponse.error(500, "Failed to get git status: " + e.getMessage());
        }
    }

    @PostMapping("/checkout")
    public ApiResponse<String> checkout(@RequestBody CheckoutRequest request) {
        try (Git git = Git.open(new File(request.getPath()))) {
            git.checkout().setName(request.getBranch()).call();
            return ApiResponse.success("Switched to branch: " + request.getBranch());
        } catch (Exception e) {
            return ApiResponse.error(500, "Failed to checkout: " + e.getMessage());
        }
    }

    @PostMapping("/pull")
    public ApiResponse<Map<String, Object>> pull(@RequestBody PullRequest request) {
        try (Git git = Git.open(new File(request.getPath()))) {
            PullResult result = git.pull()
                .setCredentialsProvider(getCredentialsProvider())
                .call();

            Map<String, Object> response = new HashMap<>();
            response.put("successful", result.isSuccessful());
            response.put("branch", getCurrentBranch(git));

            return ApiResponse.success(response);
        } catch (Exception e) {
            return ApiResponse.error(500, "Failed to pull: " + e.getMessage());
        }
    }

    @GetMapping("/logs")
    public ApiResponse<List<Map<String, Object>>> getLogs(
            @RequestParam String path,
            @RequestParam(defaultValue = "10") int limit) {
        try (Git git = Git.open(new File(path))) {
            Iterable<RevCommit> logs = git.log().setMaxCount(limit).call();

            List<Map<String, Object>> result = new ArrayList<>();
            for (RevCommit commit : logs) {
                Map<String, Object> logEntry = new HashMap<>();
                logEntry.put("commitId", commit.getName().substring(0, 8));
                logEntry.put("message", commit.getFullMessage());
                logEntry.put("author", commit.getAuthorIdent().getName());
                logEntry.put("date", commit.getAuthorIdent().getWhen());
                result.add(logEntry);
            }

            return ApiResponse.success(result);
        } catch (Exception e) {
            return ApiResponse.error(500, "Failed to get logs: " + e.getMessage());
        }
    }

    private String getCurrentBranch(Git git) throws Exception {
        Ref head = git.getRepository().exactRef("HEAD");
        if (head != null && head.isSymbolic()) {
            return head.getTarget().getName().replace("refs/heads/", "");
        }
        return "detached";
    }

    private UsernamePasswordCredentialsProvider getCredentialsProvider() {
        if (gitUser != null && !gitUser.isEmpty() && gitPassword != null && !gitPassword.isEmpty()) {
            return new UsernamePasswordCredentialsProvider(gitUser, gitPassword);
        }
        return null;
    }

    @lombok.Data
    public static class CheckoutRequest {
        private String path;
        private String branch;
    }

    @lombok.Data
    public static class PullRequest {
        private String path;
    }
}
```

**Step 2: 提交**

```bash
git add src/main/java/com/huawei/hisi/controller/GitController.java
git commit -m "feat: add GitController for Git operations API"
```

---

### Task 3.4: 创建应用状态 Store

**Files:**
- Create: `src/stores/app.ts` (frontend)

**Step 1: 编写 Store**

```typescript
import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import { configApi, type AppConfig } from '@/api/config'

export const useAppStore = defineStore('app', () => {
  // State
  const projectDir = ref<string>('')
  const projectDirConfigured = computed(() => projectDir.value.trim() !== '')
  const selectedProject = ref<string>('')
  const projectSelected = computed(() => selectedProject.value.trim() !== '')

  // Config loading state
  const configLoading = ref(false)
  const configError = ref<string>('')

  // Menu availability
  const availableMenus = computed(() => ({
    'call-chain': projectDirConfigured.value && projectSelected.value,
    'log-analysis': projectDirConfigured.value && projectSelected.value,
    'ops': false, // Permanently disabled
    'project-management': true // Always available
  }))

  // Actions
  async function loadProjectDir() {
    configLoading.value = true
    configError.value = ''

    try {
      const response = await configApi.getProjectDir()
      if (response.data) {
        projectDir.value = response.data.value || ''
      }
    } catch (e: any) {
      configError.value = e.message || 'Failed to load configuration'
    } finally {
      configLoading.value = false
    }
  }

  async function updateProjectDir(newPath: string) {
    try {
      await configApi.updateProjectDir(newPath)
      projectDir.value = newPath
      return true
    } catch (e: any) {
      configError.value = e.message || 'Failed to update configuration'
      return false
    }
  }

  function selectProject(project: string) {
    selectedProject.value = project
  }

  function clearSelectedProject() {
    selectedProject.value = ''
  }

  return {
    // State
    projectDir,
    projectDirConfigured,
    selectedProject,
    projectSelected,
    configLoading,
    configError,
    availableMenus,
    // Actions
    loadProjectDir,
    updateProjectDir,
    selectProject,
    clearSelectedProject
  }
})
```

**Step 2: 提交**

```bash
git add src/stores/app.ts
git commit -m "feat(frontend): add app store for global state management"
```

---

### Task 3.5: 创建 ProjectDirConfig 组件

**Files:**
- Create: `src/components/ProjectDirConfig.vue` (frontend)

**Step 1: 编写组件**

```vue
<template>
  <el-card class="project-dir-config">
    <template #header>
      <div class="card-header">
        <span>项目目录配置</span>
        <el-tag v-if="configured" type="success">已配置</el-tag>
        <el-tag v-else type="warning">未配置</el-tag>
      </div>
    </template>

    <el-form :model="form" label-width="100px">
      <el-form-item label="项目目录">
        <el-input
          v-model="form.path"
          placeholder="请输入项目代码存放目录"
          clearable
        >
          <template #append>
            <el-button @click="selectDirectory">选择目录</el-button>
          </template>
        </el-input>
      </el-form-item>

      <el-form-item>
        <el-button type="primary" @click="handleSave" :loading="saving">
          保存配置
        </el-button>
        <el-button @click="handleReset">重置</el-button>
      </el-form-item>
    </el-form>

    <el-alert
      v-if="error"
      :title="error"
      type="error"
      show-icon
      closable
      @close="error = ''"
    />
  </el-card>
</template>

<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import { useAppStore } from '@/stores/app'

const appStore = useAppStore()

const form = ref({
  path: ''
})

const saving = ref(false)
const error = ref('')

const configured = computed(() => appStore.projectDirConfigured)

onMounted(() => {
  form.value.path = appStore.projectDir
})

function selectDirectory() {
  // In browser environment, we can only use input
  // For Electron app, could use dialog.showOpenDialog
  ElMessage.info('请手动输入目录路径')
}

async function handleSave() {
  if (!form.value.path.trim()) {
    ElMessage.warning('请输入项目目录')
    return
  }

  saving.value = true
  try {
    const success = await appStore.updateProjectDir(form.value.path.trim())
    if (success) {
      ElMessage.success('配置保存成功')
    } else {
      error.value = appStore.configError || '保存失败'
    }
  } finally {
    saving.value = false
  }
}

function handleReset() {
  form.value.path = appStore.projectDir
}
</script>

<style scoped>
.project-dir-config {
  margin-bottom: 20px;
}

.card-header {
  display: flex;
  align-items: center;
  gap: 10px;
}
</style>
```

**Step 2: 提交**

```bash
git add src/components/ProjectDirConfig.vue
git commit -m "feat(frontend): add ProjectDirConfig component"
```

---

### Task 3.6: 创建 GitOperations 组件

**Files:**
- Create: `src/components/GitOperations.vue` (frontend)

**Step 1: 编写组件**

```vue
<template>
  <el-dropdown trigger="click" @command="handleCommand">
    <el-button type="primary" link>
      <el-icon><Operation /></el-icon>
      Git 操作
    </el-button>
    <template #dropdown>
      <el-dropdown-menu>
        <el-dropdown-item command="status">
          <el-icon><InfoFilled /></el-icon>
          查看状态
        </el-dropdown-item>
        <el-dropdown-item command="pull">
          <el-icon><Download /></el-icon>
          拉取更新
        </el-dropdown-item>
        <el-dropdown-item command="logs">
          <el-icon><Document /></el-icon>
          提交记录
        </el-dropdown-item>
      </el-dropdown-menu>
    </template>
  </el-dropdown>

  <!-- Status Dialog -->
  <el-dialog v-model="statusDialogVisible" title="Git 状态" width="500px">
    <el-descriptions :column="1" border v-if="status">
      <el-descriptions-item label="当前分支">
        <el-tag>{{ status.branch }}</el-tag>
      </el-descriptions-item>
      <el-descriptions-item label="工作区状态">
        <el-tag :type="status.clean ? 'success' : 'warning'">
          {{ status.clean ? '干净' : '有改动' }}
        </el-tag>
      </el-descriptions-item>
      <el-descriptions-item label="修改文件" v-if="status.modified?.length">
        {{ status.modified.join(', ') }}
      </el-descriptions-item>
      <el-descriptions-item label="未跟踪文件" v-if="status.untracked?.length">
        {{ status.untracked.join(', ') }}
      </el-descriptions-item>
    </el-descriptions>
  </el-dialog>

  <!-- Logs Dialog -->
  <el-dialog v-model="logsDialogVisible" title="提交记录" width="600px">
    <el-table :data="logs" v-loading="loading">
      <el-table-column prop="commitId" label="Commit" width="100" />
      <el-table-column prop="message" label="消息" show-overflow-tooltip />
      <el-table-column prop="author" label="作者" width="100" />
      <el-table-column prop="date" label="日期" width="150">
        <template #default="{ row }">
          {{ formatDate(row.date) }}
        </template>
      </el-table-column>
    </el-table>
  </el-dialog>
</template>

<script setup lang="ts">
import { ref } from 'vue'
import { ElMessage } from 'element-plus'
import { Operation, InfoFilled, Download, Document } from '@element-plus/icons-vue'
import { gitApi, type GitStatus, type GitLog } from '@/api/git'

const props = defineProps<{
  projectPath: string
}>()

const loading = ref(false)
const statusDialogVisible = ref(false)
const logsDialogVisible = ref(false)
const status = ref<GitStatus | null>(null)
const logs = ref<GitLog[]>([])

async function handleCommand(command: string) {
  switch (command) {
    case 'status':
      await loadStatus()
      break
    case 'pull':
      await handlePull()
      break
    case 'logs':
      await loadLogs()
      break
  }
}

async function loadStatus() {
  loading.value = true
  try {
    const response = await gitApi.getStatus(props.projectPath)
    status.value = response.data
    statusDialogVisible.value = true
  } catch (e: any) {
    ElMessage.error('获取状态失败: ' + e.message)
  } finally {
    loading.value = false
  }
}

async function handlePull() {
  loading.value = true
  try {
    await gitApi.pull(props.projectPath)
    ElMessage.success('拉取成功')
  } catch (e: any) {
    ElMessage.error('拉取失败: ' + e.message)
  } finally {
    loading.value = false
  }
}

async function loadLogs() {
  loading.value = true
  try {
    const response = await gitApi.getLogs(props.projectPath, 20)
    logs.value = response.data || []
    logsDialogVisible.value = true
  } catch (e: any) {
    ElMessage.error('获取记录失败: ' + e.message)
  } finally {
    loading.value = false
  }
}

function formatDate(date: string | Date) {
  return new Date(date).toLocaleString()
}
</script>
```

**Step 2: 提交**

```bash
git add src/components/GitOperations.vue
git commit -m "feat(frontend): add GitOperations component"
```

---

### Task 3.7: 增强 ProjectList 视图

**Files:**
- Modify: `src/views/project/ProjectList.vue` (frontend)

**Step 1: 集成新组件**

在现有 ProjectList.vue 中：
1. 添加 ProjectDirConfig 组件
2. 添加 GitOperations 组件（对有 .git 的项目）
3. 添加项目选择逻辑
4. 添加引导提示

**Step 2: 提交**

```bash
git add src/views/project/ProjectList.vue
git commit -m "feat(frontend): enhance ProjectList with config and Git operations"
```

---

### Task 3.8: 修改路由守卫

**Files:**
- Modify: `src/router/index.ts` (frontend)

**Step 1: 添加权限守卫**

```typescript
import { useAppStore } from '@/stores/app'

router.beforeEach(async (to, _from, next) => {
  const title = to.meta.title as string
  if (title) {
    document.title = `${title} - HiSi Dev Tool`
  }

  // Load config on first navigation
  const appStore = useAppStore()
  if (!appStore.projectDir && !appStore.configLoading) {
    await appStore.loadProjectDir()
  }

  // Check menu availability
  const menuAvailability = appStore.availableMenus

  // Block access to restricted pages
  if (to.path.startsWith('/call-chain') && !menuAvailability['call-chain']) {
    ElMessage.warning('请先配置项目目录并选择项目')
    return next('/project')
  }

  if (to.path.startsWith('/log-analysis') && !menuAvailability['log-analysis']) {
    ElMessage.warning('请先配置项目目录并选择项目')
    return next('/project')
  }

  // Redirect ops to project (ops is permanently disabled)
  if (to.path.startsWith('/ops')) {
    return next('/project')
  }

  next()
})
```

**Step 2: 提交**

```bash
git add src/router/index.ts
git commit -m "feat(frontend): add router guard for menu access control"
```

---

### Task 3.9: 修改侧边栏菜单

**Files:**
- Modify: `src/components/layout/AppSidebar.vue` (frontend)

**Step 1: 根据状态禁用菜单**

使用 `useAppStore().availableMenus` 控制菜单项的可用状态。

**Step 2: 移除运维监控菜单**

**Step 3: 提交**

```bash
git add src/components/layout/AppSidebar.vue
git commit -m "feat(frontend): update sidebar menu with access control and remove ops"
```

---

### Task 3.10: 验证 Phase 3

**Step 1: 构建后端**

```bash
cd C:\Users\47583\projects\hisi-dev-tool
mvn clean package -DskipTests
```

Expected: BUILD SUCCESS

**Step 2: 构建前端**

```bash
cd C:\Users\47583\projects\hisi-dev-tool-frontend
npm run build
```

Expected: Build successful

**Step 3: 提交 Phase 3 完成标记**

```bash
git add .
git commit -m "feat(phase3): complete frontend guidance and Git operations"
```

---

## Phase 4: 调用链生成器深度改造

### Task 4.1: 创建桥接索引缓存

**Files:**
- Modify: `src/main/java/com/huawei/hisi/cache/GlobalAnalysisCache.java`

**Step 1: 添加桥接索引字段**

在 GlobalAnalysisCache 中添加:
```java
// MQ Topic -> List of MQEndpoint (consumers)
private final Map<String, List<MQEndpoint>> mqConsumerIndex = new ConcurrentHashMap<>();

// MQ Topic -> List of MQEndpoint (producers)
private final Map<String, List<MQEndpoint>> mqProducerIndex = new ConcurrentHashMap<>();

// ServiceName + URI -> List of ControllerEndpoint
private final Map<String, List<FeignClientInfo>> feignUriIndex = new ConcurrentHashMap<>();

// Interface Name -> ProxyMetadata
private final Map<String, ProxyMetadata> proxyIndex = new ConcurrentHashMap<>();
```

**Step 2: 添加 getter/setter 方法**

**Step 3: 提交**

```bash
git add src/main/java/com/huawei/hisi/cache/GlobalAnalysisCache.java
git commit -m "feat: add bridge indexes to GlobalAnalysisCache"
```

---

### Task 4.2: 修改 ChainAnalysisCoordinatorImpl 添加项目目录参数

**Files:**
- Modify: `src/main/java/com/huawei/hisi/service/ChainAnalysisCoordinatorImpl.java`

**Step 1: 修改 buildBridgeTables 方法**

添加 `projectDir` 参数并在保存数据时设置:

```java
public void buildBridgeTables(String projectDir) {
    // Clear existing data for this project
    mqBridge.clearBridgeData(dataSource, projectDir);
    httpBridge.clearBridgeData(dataSource, projectDir);
    proxyBridge.clearBridgeData(dataSource, projectDir);

    // Build and save with project_dir
    var mqData = mqBridge.buildBridgeData(globalCache);
    mqData.forEach(d -> d.setProjectDir(projectDir));
    mqBridge.saveBridgeData(mqData, dataSource);

    // Similar for http and proxy...
}
```

**Step 2: 提交**

```bash
git add src/main/java/com/huawei/hisi/service/ChainAnalysisCoordinatorImpl.java
git commit -m "refactor: add projectDir parameter to buildBridgeTables"
```

---

### Task 4.3: 修改桥接器支持 project_dir

**Files:**
- Modify: `src/main/java/com/huawei/hisi/bridge/MQChainBridge.java`
- Modify: `src/main/java/com/huawei/hisi/bridge/HttpChainBridge.java`
- Modify: `src/main/java/com/huawei/hisi/bridge/ProxyChainBridge.java`

**Step 1: 修改 clearBridgeData 方法**

添加 `projectDir` 参数:
```java
public void clearBridgeData(DataSource dataSource, String projectDir) {
    String sql = projectDir != null ?
        "DELETE FROM mq_call_bridge WHERE project_dir = ?" :
        "DELETE FROM mq_call_bridge";
    // Execute with parameter if provided
}
```

**Step 2: 修改 saveBridgeData 方法**

在 INSERT 语句中包含 `project_dir` 字段。

**Step 3: 提交**

```bash
git add src/main/java/com/huawei/hisi/bridge/*.java
git commit -m "refactor: add projectDir support to bridge classes"
```

---

### Task 4.4: 修改 HisiURIMethodChainToDBServiceImpl 集成桥接表

**Files:**
- Modify: `src/main/java/com/huawei/hisi/service/HisiURIMethodChainToDBServiceImpl.java`

**Step 1: 注入 ChainAnalysisCoordinator**

```java
@Autowired
private ChainAnalysisCoordinator chainCoordinator;
```

**Step 2: 在 chainGenerator 开始时调用桥接表构建**

```java
public void chainGenerator() throws Exception {
    String currentProjectDir = appConfigService.getProjectDir();

    // Step 1: Build bridge tables
    chainCoordinator.buildBridgeTables(currentProjectDir);

    // Step 2: Build indexes in cache
    buildBridgeIndexes();

    // Step 3: Continue with existing logic...
}
```

**Step 3: 实现 buildBridgeIndexes 方法**

```java
private void buildBridgeIndexes() {
    // Load MQ consumers/producers from bridge table
    // Load Feign endpoints
    // Load Proxy metadata
}
```

**Step 4: 修改 saveCallGraphToDB 方法**

添加 `call_type` 和 `project_dir` 字段:
```java
PreparedStatement stmt = conn.prepareStatement(
    "INSERT INTO hiapm_test.method_call_graph5(root_uri, parent_method, child_method, depth, method_body, package, call_type, project_dir) VALUES (?, ?, ?, ?, ?, ?, ?, ?)");
```

**Step 5: 修改 DFS 方法识别非直接调用**

在遍历方法调用时检查是否为 MQ/Feign/Proxy 调用，并正确标记 `call_type`。

**Step 6: 提交**

```bash
git add src/main/java/com/huawei/hisi/service/HisiURIMethodChainToDBServiceImpl.java
git commit -m "feat: integrate bridge tables in call chain generation"
```

---

### Task 4.5: 添加调用类型识别方法

**Files:**
- Modify: `src/main/java/com/huawei/hisi/service/HisiURIMethodChainToDBServiceImpl.java`

**Step 1: 添加 MQ 发送识别方法**

```java
private boolean isMQSendCall(MethodCallExpr call) {
    String methodName = call.getNameAsString();
    // KafkaTemplate.send, rabbitTemplate.convertAndSend, etc.
    return methodName.equals("send") || methodName.equals("convertAndSend") || methodName.equals("produce");
}

private String extractTopicFromMQCall(MethodCallExpr call) {
    // Extract topic from arguments
    // Return topic string
}
```

**Step 2: 添加 Feign 调用识别方法**

```java
private boolean isFeignCall(MethodCallExpr call) {
    // Check if scope is a Feign client interface
    // Return true if so
}
```

**Step 3: 添加 Proxy 调用识别方法**

```java
private boolean isProxyCall(MethodCallExpr call) {
    // Check if scope is MyBatis Mapper, JPA Repository, or AOP
    // Return true if so
}

private String getProxyType(MethodCallExpr call) {
    // Return MYBATIS, JPA, or AOP
}
```

**Step 4: 提交**

```bash
git add src/main/java/com/huawei/hisi/service/HisiURIMethodChainToDBServiceImpl.java
git commit -m "feat: add call type detection methods for MQ/Feign/Proxy"
```

---

### Task 4.6: 修改查询服务支持 project_dir 过滤

**Files:**
- Modify: `src/main/java/com/huawei/hisi/service/CallChainServiceImpl.java`

**Step 1: 在查询方法中添加 project_dir 过滤**

所有查询 `method_call_graph5` 的地方都添加:
```sql
WHERE project_dir = ? AND ...
```

**Step 2: 提交**

```bash
git add src/main/java/com/huawei/hisi/service/CallChainServiceImpl.java
git commit -m "feat: add project_dir filtering to call chain queries"
```

---

### Task 4.7: 验证 Phase 4

**Step 1: 构建项目**

```bash
cd C:\Users\47583\projects\hisi-dev-tool
mvn clean package -DskipTests
```

Expected: BUILD SUCCESS

**Step 2: 提交 Phase 4 完成标记**

```bash
git add .
git commit -m "feat(phase4): complete call chain generator transformation"
```

---

## 最终验证

### 集成测试

1. **启动后端**
```bash
java -jar target/devTools-1.0.0.jar
```

2. **启动前端**
```bash
npm run dev
```

3. **测试流程**
   - 访问前端，应自动跳转到项目管理页面
   - 配置 PROJECT_DIR
   - 查看项目列表，确认 Git 状态显示
   - 选择项目后，调用链分析和日志分析菜单应可用
   - 执行调用链生成，验证数据包含 call_type 和 project_dir

4. **验证数据库**
```sql
SELECT DISTINCT call_type FROM hiapm_test.method_call_graph5;
SELECT DISTINCT project_dir FROM hiapm_test.method_call_graph5;
SELECT * FROM mq_call_bridge WHERE project_dir IS NOT NULL;
```

---

## 文件变更清单

### 新增文件 (后端)
- `src/main/java/com/huawei/hisi/model/AppConfig.java`
- `src/main/java/com/huawei/hisi/repository/AppConfigRepository.java`
- `src/main/java/com/huawei/hisi/service/AppConfigService.java`
- `src/main/java/com/huawei/hisi/service/AppConfigServiceImpl.java`
- `src/main/java/com/huawei/hisi/controller/ConfigController.java`
- `src/main/java/com/huawei/hisi/controller/GitController.java`

### 修改文件 (后端)
- `src/main/java/com/huawei/hisi/config/DataSourceConfig.java`
- `src/main/java/com/huawei/hisi/service/HisiURIMethodChainToDBServiceImpl.java`
- `src/main/java/com/huawei/hisi/service/ChainAnalysisCoordinatorImpl.java`
- `src/main/java/com/huawei/hisi/service/CallChainServiceImpl.java`
- `src/main/java/com/huawei/hisi/cache/GlobalAnalysisCache.java`
- `src/main/java/com/huawei/hisi/bridge/MQChainBridge.java`
- `src/main/java/com/huawei/hisi/bridge/HttpChainBridge.java`
- `src/main/java/com/huawei/hisi/bridge/ProxyChainBridge.java`

### 新增文件 (前端)
- `src/api/config.ts`
- `src/api/git.ts`
- `src/stores/app.ts`
- `src/components/ProjectDirConfig.vue`
- `src/components/GitOperations.vue`

### 修改文件 (前端)
- `src/router/index.ts`
- `src/views/project/ProjectList.vue`
- `src/components/layout/AppSidebar.vue`