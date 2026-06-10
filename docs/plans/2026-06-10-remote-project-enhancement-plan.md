# 远端项目公共功能对齐 + 克隆认证增强 实施计划

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** 实现远端项目Tab与本地项目Tab的7项公共功能对齐，并增强克隆认证支持 PASSWORD/SSH_KEY/TOKEN 三种方式。

**Architecture:** 前端在远端Tab新增表头按钮和操作列按钮，复用本地Tab的辅助方法；后端扩展 RemoteProject 模型新增 authType/sshKeyPath/encryptedToken 字段，在 RemoteProjectService 中实现认证逻辑分支。

**Tech Stack:** Vue 3.5 + TypeScript + Element Plus（前端），Spring Boot 3.2 + Java 17 + JGit + SQLite（后端）

---

## Phase 1: 公共功能对齐（前端）

### Task 1: 远端Tab 操作列新增提交分析按钮

**Files:**
- Modify: `hisi-dev-tool-frontend/src/views/project/ProjectList.vue:309-350`

**Step 1: 添加提交分析按钮**

在远端Tab操作列（约第309行后）添加：

```vue
<el-button
  type="info"
  link
  @click="showCommitDialogForRemote(row)"
  :disabled="row.cloneStatus !== 'CLONED'"
>
  <el-icon><Document /></el-icon>
  提交分析
</el-button>
```

**Step 2: 添加辅助方法 showCommitDialogForRemote 和 loadCommitsForRemote**

在 script setup 部分添加：

```typescript
const showCommitDialogForRemote = (row: RemoteProject) => {
  selectedProjectForCommit.value = row.name
  commitDialogVisible.value = true
  loadCommitsForRemote(row.localPath)
}

const loadCommitsForRemote = async (localPath: string) => {
  commitsLoading.value = true
  try {
    const res = await gitApi.getCommits(localPath, 50)
    commits.value = res.data || []
  } catch {
    ElMessage.error('加载提交列表失败')
  } finally {
    commitsLoading.value = false
  }
}
```

**Step 3: 验证前端编译**

Run: `cd hisi-dev-tool-frontend && npm run build`
Expected: Build succeeds without TypeScript errors

**Step 4: Commit**

```bash
git add hisi-dev-tool-frontend/src/views/project/ProjectList.vue
git commit -m "feat(remote-project): add commit analysis button for remote projects"
```

---

### Task 2: 远端Tab 操作列新增 GitOperations 组件

**Files:**
- Modify: `hisi-dev-tool-frontend/src/views/project/ProjectList.vue:309-350`

**Step 1: 添加 GitOperations 组件**

在提交分析按钮后添加：

```vue
<GitOperations
  v-if="row.cloneStatus === 'CLONED'"
  :project-path="row.localPath"
/>
```

**Step 2: 验证前端编译**

Run: `cd hisi-dev-tool-frontend && npm run build`
Expected: Build succeeds

**Step 3: Commit**

```bash
git add hisi-dev-tool-frontend/src/views/project/ProjectList.vue
git commit -m "feat(remote-project): add GitOperations component for remote projects"
```

---

### Task 3: 远端Tab 操作列新增图谱刷新按钮

**Files:**
- Modify: `hisi-dev-tool-frontend/src/views/project/ProjectList.vue:309-350`

**Step 1: 添加图谱刷新按钮**

在 GitOperations 组件后添加：

```vue
<el-button
  type="info"
  link
  @click="handleRefreshProjectRemote(row)"
  :disabled="row.cloneStatus !== 'CLONED'"
>
  <el-icon><Refresh /></el-icon>
  图谱刷新
</el-button>
```

**Step 2: 添加辅助方法 handleRefreshProjectRemote**

```typescript
const handleRefreshProjectRemote = async (row: RemoteProject) => {
  try {
    const res = await knowledgeGraphApi.refresh(row.localPath)
    if (res.isNoop) {
      ElMessage.info('无变更，图谱已是最新')
    } else {
      ElMessage.success(`刷新完成：${res.changedFiles} 个文件变更`)
      await loadRemoteProjectTaskStatuses()
    }
  } catch (e: unknown) {
    if ((e as { response?: { status?: number } })?.response?.status === 412) {
      ElMessage.warning('工作区不干净，请先提交所有改动')
    } else if ((e as { response?: { status?: number } })?.response?.status === 409) {
      ElMessage.warning('无检查点记录，请先全量生成图谱')
    } else {
      ElMessage.error('图谱刷新失败')
    }
  }
}
```

**Step 3: 验证前端编译**

Run: `cd hisi-dev-tool-frontend && npm run build`
Expected: Build succeeds

**Step 4: Commit**

```bash
git add hisi-dev-tool-frontend/src/views/project/ProjectList.vue
git commit -m "feat(remote-project): add KG refresh button for remote projects"
```

---

### Task 4: 远端Tab 表头新增图谱屏蔽目录按钮

**Files:**
- Modify: `hisi-dev-tool-frontend/src/views/project/ProjectList.vue:221-238`

**Step 1: 添加图谱屏蔽目录按钮**

在远端Tab header-buttons 区域（第221行后）添加：

```vue
<el-button @click="openKgExcludeDialog">
  <el-icon><Setting /></el-icon>
  图谱屏蔽目录
</el-button>
```

**Note:** `openKgExcludeDialog` 方法已存在于本地Tab，直接复用。

**Step 2: 验证前端编译**

Run: `cd hisi-dev-tool-frontend && npm run build`
Expected: Build succeeds

**Step 3: Commit**

```bash
git add hisi-dev-tool-frontend/src/views/project/ProjectList.vue
git commit -m "feat(remote-project): add KG exclude paths button for remote projects"
```

---

### Task 5: 远端Tab 表头新增术语配置按钮

**Files:**
- Modify: `hisi-dev-tool-frontend/src/views/project/ProjectList.vue:221-238`

**Step 1: 添加术语配置按钮**

在图谱屏蔽目录按钮后添加：

```vue
<el-button @click="openGlossaryDialogForRemote">
  <el-icon><EditPen /></el-icon>
  术语配置
</el-button>
```

**Step 2: 添加辅助方法 openGlossaryDialogForRemote**

```typescript
const openGlossaryDialogForRemote = () => {
  const cloned = remoteProjects.value.filter(p => p.cloneStatus === 'CLONED')
  if (cloned.length === 1) {
    glossaryProjectPath.value = normalizePath(cloned[0].localPath)
  } else if (cloned.length > 1) {
    // 多选时取第一个
    glossaryProjectPath.value = normalizePath(cloned[0].localPath)
  } else {
    glossaryProjectPath.value = ''
  }
  showGlossaryDialog.value = true
  if (glossaryProjectPath.value) loadGlossaryTerms()
}
```

**Step 3: 验证前端编译**

Run: `cd hisi-dev-tool-frontend && npm run build`
Expected: Build succeeds

**Step 4: Commit**

```bash
git add hisi-dev-tool-frontend/src/views/project/ProjectList.vue
git commit -m "feat(remote-project): add glossary config button for remote projects"
```

---

### Task 6: 远端Tab 表头新增跨服务依赖构建按钮

**Files:**
- Modify: `hisi-dev-tool-frontend/src/views/project/ProjectList.vue:221-238`

**Step 1: 添加跨服务依赖构建按钮**

在术语配置按钮后添加：

```vue
<el-button
  type="warning"
  @click="handleCrossServiceBuildRemote"
  :disabled="selectedRemoteProjectsWithKg.length < 2"
  :loading="crossServiceBuilding"
>
  跨服务依赖构建 ({{ selectedRemoteProjectsWithKg.length }})
</el-button>
```

**Step 2: 添加 computed 和辅助方法**

```typescript
const selectedRemoteProjectsWithKg = computed(() =>
  selectedRemoteProjects.value.filter((p: RemoteProject) => {
    if (p.cloneStatus !== 'CLONED') return false
    const status = knowledgeGraphStatusMap.value[normalizePath(p.localPath)]
    return status && (status.status === 'generated' || status.status === 'completed')
  })
)

const handleCrossServiceBuildRemote = async () => {
  crossServiceBuilding.value = true
  try {
    const paths = selectedRemoteProjectsWithKg.value.map((p: RemoteProject) => normalizePath(p.localPath))
    await knowledgeGraphApi.crossServiceBuild(paths)
    ElMessage.success('跨服务依赖构建完成')
  } catch {
    ElMessage.error('跨服务依赖构建失败')
  } finally {
    crossServiceBuilding.value = false
  }
}
```

**Step 3: 验证前端编译**

Run: `cd hisi-dev-tool-frontend && npm run build`
Expected: Build succeeds

**Step 4: Commit**

```bash
git add hisi-dev-tool-frontend/src/views/project/ProjectList.vue
git commit -m "feat(remote-project): add cross-service build button for remote projects"
```

---

### Task 7: 远端Tab 表头新增批量生成图谱按钮

**Files:**
- Modify: `hisi-dev-tool-frontend/src/views/project/ProjectList.vue:221-238`

**Step 1: 添加批量生成图谱按钮**

在跨服务依赖构建按钮后添加：

```vue
<el-button
  type="success"
  @click="handleBatchGenerateKGRemote"
  :disabled="selectedRemoteClonedProjects.length === 0"
  :loading="batchGeneratingKG"
>
  <el-icon><DataAnalysis /></el-icon>
  批量生成图谱 ({{ selectedRemoteClonedProjects.length }})
</el-button>
```

**Step 2: 添加 computed 和辅助方法**

```typescript
const selectedRemoteClonedProjects = computed(() =>
  selectedRemoteProjects.value.filter((p: RemoteProject) => p.cloneStatus === 'CLONED')
)

const handleBatchGenerateKGRemote = async () => {
  if (selectedRemoteClonedProjects.value.length === 0) {
    ElMessage.warning('请先勾选已克隆的项目')
    return
  }
  const paths = selectedRemoteClonedProjects.value.map((p: RemoteProject) => normalizePath(p.localPath))
  batchGeneratingKG.value = true
  try {
    const tasks = await knowledgeGraphApi.startGenerateTaskBatch(paths, kgExcludePaths.value.length > 0 ? kgExcludePaths.value : undefined)
    if (tasks && tasks.length > 0) {
      for (const task of tasks) {
        knowledgeGraphTaskStatusMap.value[normalizePath(task.projectPath)] = task
      }
      ElMessage.success(`已入队 ${tasks.length} 个项目`)
      startKgPolling()
    }
  } catch (error: any) {
    ElMessage.error(`批量生成入队失败: ${error.message || error}`)
  } finally {
    batchGeneratingKG.value = false
  }
}
```

**Step 3: 验证前端编译**

Run: `cd hisi-dev-tool-frontend && npm run build`
Expected: Build succeeds

**Step 4: Commit**

```bash
git add hisi-dev-tool-frontend/src/views/project/ProjectList.vue
git commit -m "feat(remote-project): add batch KG generation button for remote projects"
```

---

### Task 8: Phase 1 集成验证

**Step 1: 启动前端开发服务器**

Run: `cd hisi-dev-tool-frontend && npm run dev`
Expected: Dev server starts at http://localhost:5173

**Step 2: 手动验证远端Tab功能**

1. 打开项目管理页面 → 远端项目Tab
2. 验证表头按钮：图谱屏蔽目录、术语配置、跨服务构建、批量生成图谱
3. 验证操作列按钮：提交分析、GitOperations、图谱刷新
4. 验证按钮禁用状态：未克隆项目操作按钮应为 disabled

**Step 3: Commit Phase 1 completion marker**

```bash
git commit --allow-empty -m "feat(remote-project): Phase 1 complete - public feature alignment"
```

---

## Phase 2: 克隆认证增强（后端）

### Task 9: 添加 JGit SSH 依赖

**Files:**
- Modify: `hisi-dev-tool/pom.xml`

**Step 1: 添加 JGit SSH JSch 依赖**

在 pom.xml 的 JGit 依赖后添加：

```xml
<dependency>
    <groupId>org.eclipse.jgit</groupId>
    <artifactId>org.eclipse.jgit.ssh.jsch</artifactId>
    <version>7.2.0.202503040940-r</version>
</dependency>
```

**Note:** 使用 JGit 7.2.0 版本（比现有 4.5.4 新），但 SSH 模块可独立引入。

**Step 2: 验证 Maven 编译**

Run: `cd hisi-dev-tool && mvn compile -q`
Expected: Compile succeeds with new dependency downloaded

**Step 3: Commit**

```bash
git add hisi-dev-tool/pom.xml
git commit -m "feat(remote-project): add JGit SSH JSch dependency for SSH key auth"
```

---

### Task 10: 数据库新增认证字段

**Files:**
- Modify: SQLite 表结构（通过代码迁移）

**Step 1: 在 RemoteProjectRepository 添加迁移逻辑**

在 RemoteProjectRepository 的构造方法或初始化方法中添加字段检查和迁移：

```java
@PostConstruct
public void migrateSchema() {
    try {
        // Check if auth_type column exists
        jdbcTemplate.queryForRowSet("SELECT auth_type FROM remote_project LIMIT 1");
    } catch (Exception e) {
        // Column doesn't exist, add it
        jdbcTemplate.execute("ALTER TABLE remote_project ADD COLUMN auth_type VARCHAR(20) DEFAULT 'PASSWORD'");
        jdbcTemplate.execute("ALTER TABLE remote_project ADD COLUMN ssh_key_path VARCHAR(255)");
        jdbcTemplate.execute("ALTER TABLE remote_project ADD COLUMN encrypted_token VARCHAR(500)");
        log.info("[Migration] Added auth_type, ssh_key_path, encrypted_token columns to remote_project");
    }
}
```

**Step 2: 验证迁移执行**

Run: `cd hisi-dev-tool && mvn spring-boot:run`
Expected: Log shows migration executed (or skipped if columns exist)

**Step 3: Commit**

```bash
git add hisi-dev-tool/src/main/java/com/huawei/hisi/project/remote/repository/RemoteProjectRepository.java
git commit -m "feat(remote-project): add auth_type, ssh_key_path, encrypted_token columns migration"
```

---

### Task 11: RemoteProject 模型扩展

**Files:**
- Modify: `hisi-dev-tool/src/main/java/com/huawei/hisi/project/remote/model/RemoteProject.java`

**Step 1: 添加认证字段**

```java
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
    private String cloneError;
    private Long lastSyncAt;
    private Long createdAt;
    
    // 新增认证字段
    @Builder.Default
    private String authType = "PASSWORD"; // PASSWORD / SSH_KEY / TOKEN
    private String sshKeyPath; // SSH 私钥文件路径
    private String encryptedToken; // AES 加密的 Token
}
```

**Step 2: 验证 Maven 编译**

Run: `cd hisi-dev-tool && mvn compile -q`
Expected: Compile succeeds

**Step 3: Commit**

```bash
git add hisi-dev-tool/src/main/java/com/huawei/hisi/project/remote/model/RemoteProject.java
git commit -m "feat(remote-project): add authType, sshKeyPath, encryptedToken fields to model"
```

---

### Task 12: 创建 AuthType 枚举类

**Files:**
- Create: `hisi-dev-tool/src/main/java/com/huawei/hisi/project/remote/model/AuthType.java`

**Step 1: 创建枚举类**

```java
package com.huawei.hisi.project.remote.model;

public enum AuthType {
    PASSWORD,
    SSH_KEY,
    TOKEN
}
```

**Step 2: 验证 Maven 编译**

Run: `cd hisi-dev-tool && mvn compile -q`
Expected: Compile succeeds

**Step 3: Commit**

```bash
git add hisi-dev-tool/src/main/java/com/huawei/hisi/project/remote/model/AuthType.java
git commit -m "feat(remote-project): create AuthType enum (PASSWORD/SSH_KEY/TOKEN)"
```

---

### Task 13: RemoteProjectRepository 新增字段读写

**Files:**
- Modify: `hisi-dev-tool/src/main/java/com/huawei/hisi/project/remote/repository/RemoteProjectRepository.java`

**Step 1: 更新 ROW_MAPPER**

```java
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
        .cloneError(rs.getString("clone_error"))
        .lastSyncAt(rs.getObject("last_sync_at") != null ? rs.getLong("last_sync_at") : null)
        .createdAt(rs.getObject("created_at") != null ? rs.getLong("created_at") : null)
        // 新增字段
        .authType(rs.getString("auth_type") != null ? rs.getString("auth_type") : "PASSWORD")
        .sshKeyPath(rs.getString("ssh_key_path"))
        .encryptedToken(rs.getString("encrypted_token"))
        .build();
```

**Step 2: 更新 insert 方法**

```java
public long insert(RemoteProject p) {
    KeyHolder keyHolder = new GeneratedKeyHolder();
    jdbcTemplate.update(connection -> {
        PreparedStatement ps = connection.prepareStatement(
            "INSERT INTO remote_project (name, git_url, username, encrypted_password, branch, local_path, clone_status, auth_type, ssh_key_path, encrypted_token) " +
            "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            Statement.RETURN_GENERATED_KEYS
        );
        ps.setString(1, p.getName());
        ps.setString(2, p.getGitUrl());
        ps.setString(3, p.getUsername());
        ps.setString(4, p.getEncryptedPassword());
        ps.setString(5, p.getBranch());
        ps.setString(6, p.getLocalPath());
        ps.setString(7, p.getCloneStatus());
        ps.setString(8, p.getAuthType());
        ps.setString(9, p.getSshKeyPath());
        ps.setString(10, p.getEncryptedToken());
        return ps;
    }, keyHolder);

    Number key = keyHolder.getKey();
    return key != null ? key.longValue() : -1;
}
```

**Step 3: 更新 update 方法**

```java
public int update(RemoteProject p) {
    return jdbcTemplate.update(
        "UPDATE remote_project SET name = ?, git_url = ?, username = ?, encrypted_password = ?, " +
        "branch = ?, local_path = ?, clone_status = ?, auth_type = ?, ssh_key_path = ?, encrypted_token = ? WHERE id = ?",
        p.getName(), p.getGitUrl(), p.getUsername(), p.getEncryptedPassword(),
        p.getBranch(), p.getLocalPath(), p.getCloneStatus(),
        p.getAuthType(), p.getSshKeyPath(), p.getEncryptedToken(), p.getId()
    );
}
```

**Step 4: 验证 Maven 编译**

Run: `cd hisi-dev-tool && mvn compile -q`
Expected: Compile succeeds

**Step 5: Commit**

```bash
git add hisi-dev-tool/src/main/java/com/huawei/hisi/project/remote/repository/RemoteProjectRepository.java
git commit -m "feat(remote-project): add auth fields to repository mapper and queries"
```

---

### Task 14: RemoteProjectService 新增认证逻辑分支

**Files:**
- Modify: `hisi-dev-tool/src/main/java/com/huawei/hisi/project/remote/service/RemoteProjectService.java`

**Step 1: 添加 import**

```java
import org.eclipse.jgit.transport.SshSessionFactory;
import org.eclipse.jgit.transport.ssh.jsch.JschConfigSessionFactory;
import org.eclipse.jgit.transport.CredentialsProvider;
import org.eclipse.jgit.lib.FileRepositoryProvider;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.JSchException;
import com.jcraft.jsch.Session;
import com.huawei.hisi.project.remote.model.AuthType;
import java.nio.file.Paths;
```

**Step 2: 新增 create 方法重载**

```java
public long create(String name, String gitUrl, String username, String password,
                   String authType, String sshKeyPath, String token, String branch) {
    String encryptedPassword = null;
    String encryptedToken = null;
    
    if ("PASSWORD".equals(authType) && password != null && !password.isEmpty()) {
        encryptedPassword = gitCredentialService.encrypt(password);
    }
    if ("TOKEN".equals(authType) && token != null && !token.isEmpty()) {
        encryptedToken = gitCredentialService.encrypt(token);
    }
    
    String localPath = sanitizeName(name);
    
    RemoteProject project = RemoteProject.builder()
        .name(name)
        .gitUrl(gitUrl)
        .username(username)
        .encryptedPassword(encryptedPassword)
        .branch(branch != null ? branch : "main")
        .localPath(localPath)
        .cloneStatus("PENDING")
        .authType(authType != null ? authType : "PASSWORD")
        .sshKeyPath(sshKeyPath)
        .encryptedToken(encryptedToken)
        .build();
    
    return repository.insert(project);
}
```

**Step 3: 新增 update 方法重载**

```java
public void update(long id, String name, String gitUrl, String username, String password,
                   String authType, String sshKeyPath, String token, String branch) {
    RemoteProject existing = getById(id);
    
    String encryptedPassword = existing.getEncryptedPassword();
    String encryptedToken = existing.getEncryptedToken();
    
    if ("PASSWORD".equals(authType) && password != null && !password.isEmpty()) {
        encryptedPassword = gitCredentialService.encrypt(password);
    } else if (!"PASSWORD".equals(authType)) {
        encryptedPassword = null; // Clear password if switching auth type
    }
    
    if ("TOKEN".equals(authType) && token != null && !token.isEmpty()) {
        encryptedToken = gitCredentialService.encrypt(token);
    } else if (!"TOKEN".equals(authType)) {
        encryptedToken = null; // Clear token if switching auth type
    }
    
    existing.setName(name);
    existing.setGitUrl(gitUrl);
    existing.setUsername(username);
    existing.setEncryptedPassword(encryptedPassword);
    existing.setBranch(branch != null ? branch : existing.getBranch());
    existing.setAuthType(authType != null ? authType : existing.getAuthType());
    existing.setSshKeyPath("SSH_KEY".equals(authType) ? sshKeyPath : null);
    existing.setEncryptedToken(encryptedToken);
    
    repository.update(existing);
}
```

**Step 4: 新增 getCredentialsProvider 方法**

```java
private CredentialsProvider getCredentialsProvider(RemoteProject project) {
    AuthType authType = AuthType.valueOf(project.getAuthType());
    
    switch (authType) {
        case PASSWORD:
            String password = gitCredentialService.decrypt(project.getEncryptedPassword());
            return new UsernamePasswordCredentialsProvider(
                project.getUsername() != null ? project.getUsername() : "",
                password != null ? password : ""
            );
        
        case TOKEN:
            String token = gitCredentialService.decrypt(project.getEncryptedToken());
            return new UsernamePasswordCredentialsProvider("oauth2", token != null ? token : "");
        
        case SSH_KEY:
            // SSH 认证使用 SshSessionFactory，无需 CredentialsProvider
            return null;
        
        default:
            throw new IllegalArgumentException("Unsupported auth type: " + authType);
    }
}
```

**Step 5: 新增 configureSshSessionFactory 方法**

```java
private void configureSshSessionFactory(String sshKeyPath) {
    // Expand ~ to user home
    String expandedPath = sshKeyPath;
    if (sshKeyPath != null && sshKeyPath.startsWith("~")) {
        expandedPath = Paths.get(System.getProperty("user.home"), sshKeyPath.substring(2)).toString();
    }
    
    SshSessionFactory.setInstance(new JschConfigSessionFactory() {
        @Override
        protected void configure(Host hc, Session session) {
            session.setConfig("StrictHostKeyChecking", "no");
        }
        
        @Override
        protected JSch createJSch(FileRepositoryProvider fs) throws JSchException {
            JSch jsch = super.createJSch(fs);
            if (expandedPath != null && !expandedPath.isEmpty()) {
                jsch.addIdentity(expandedPath);
            }
            return jsch;
        }
    });
}
```

**Step 6: 重写 cloneProject 方法**

```java
public void cloneProject(long id) {
    RemoteProject project = getById(id);
    repository.updateCloneStatus(id, "CLONING");

    // 修复旧数据中 localPath 为纯短横线的情况
    String localPath = project.getLocalPath();
    String cleaned = localPath.replaceAll("-+", "-").replaceAll("^-|-$", "");
    if (cleaned.isBlank()) {
        String oldPath = localPath;
        localPath = sanitizeName(project.getName());
        project.setLocalPath(localPath);
        repository.update(project);
        log.info("[Clone] Fixed invalid localPath: {} -> {}", oldPath, localPath);
    }

    Path targetDir = resolveCloneDir(localPath);
    AuthType authType = AuthType.valueOf(project.getAuthType());
    
    try {
        if (Files.exists(targetDir) && Files.list(targetDir).findAny().isPresent()) {
            log.warn("[Clone] Target directory not empty, cleaning: {}", targetDir);
            deleteDirectory(targetDir);
        }
        Files.createDirectories(targetDir);
        
        Git cloneCommand = Git.cloneRepository()
            .setURI(project.getGitUrl())
            .setDirectory(targetDir.toFile())
            .setBranch(project.getBranch());
        
        if (authType == AuthType.SSH_KEY) {
            configureSshSessionFactory(project.getSshKeyPath());
        } else {
            cloneCommand.setCredentialsProvider(getCredentialsProvider(project));
        }
        
        try (Git git = cloneCommand.call()) {
            log.info("[Clone] Success: url={}, target={}", project.getGitUrl(), targetDir);
        }

        repository.updateCloneStatus(id, "CLONED");
        repository.updateLastSyncAt(id, Instant.now().getEpochSecond());
        
    } catch (Exception e) {
        String errorDetail = extractRootCause(e);
        log.error("[Clone] Failed: project={}, error={}", project.getName(), errorDetail, e);
        repository.updateCloneError(id, errorDetail);
    } finally {
        // 重置 SSH Factory（避免影响其他克隆）
        if (authType == AuthType.SSH_KEY) {
            SshSessionFactory.setInstance(null);
        }
    }
}
```

**Step 7: 重写 pullProject 方法**

```java
public void pullProject(long id) {
    RemoteProject project = getById(id);
    if (!"CLONED".equals(project.getCloneStatus())) {
        throw new RuntimeException("Project is not cloned: " + project.getCloneStatus());
    }

    Path repoDir = resolveCloneDir(project.getLocalPath());
    AuthType authType = AuthType.valueOf(project.getAuthType());
    
    try {
        if (authType == AuthType.SSH_KEY) {
            configureSshSessionFactory(project.getSshKeyPath());
        }
        
        try (Git git = Git.open(repoDir.toFile())) {
            PullCommand pullCmd = git.pull();
            if (authType != AuthType.SSH_KEY) {
                pullCmd.setCredentialsProvider(getCredentialsProvider(project));
            }
            PullResult result = pullCmd.call();
            log.info("[Pull] Success: {}, {}", project.getName(), result.isSuccessful());
        }

        repository.updateLastSyncAt(id, Instant.now().getEpochSecond());
        
    } catch (Exception e) {
        log.error("[Pull] Failed: {}, {}", project.getName(), e.getMessage(), e);
        throw new RuntimeException("Pull failed: " + e.getMessage(), e);
    } finally {
        if (authType == AuthType.SSH_KEY) {
            SshSessionFactory.setInstance(null);
        }
    }
}
```

**Step 8: 验证 Maven 编译**

Run: `cd hisi-dev-tool && mvn compile -q`
Expected: Compile succeeds

**Step 9: Commit**

```bash
git add hisi-dev-tool/src/main/java/com/huawei/hisi/project/remote/service/RemoteProjectService.java
git commit -m "feat(remote-project): implement auth type branching (PASSWORD/SSH_KEY/TOKEN)"
```

---

### Task 15: RemoteProjectController 新增认证参数

**Files:**
- Modify: `hisi-dev-tool/src/main/java/com/huawei/hisi/project/remote/controller/RemoteProjectController.java`

**Step 1: 更新 CreateRequest 和 UpdateRequest record**

```java
public record CreateRequest(
    String name, String gitUrl, String username, String password,
    String authType, String sshKeyPath, String token, String branch
) {}

public record UpdateRequest(
    String name, String gitUrl, String username, String password,
    String authType, String sshKeyPath, String token, String branch
) {}
```

**Step 2: 更新 ProjectResponse record**

```java
public record ProjectResponse(
    Long id, String name, String gitUrl, String username, String branch,
    String localPath, String cloneStatus, String cloneError, Long lastSyncAt,
    String authType, String sshKeyPath
) {}
```

**Step 3: 更新 create 方法**

```java
@PostMapping
public ApiResponse<Map<String, Long>> create(@RequestBody CreateRequest req) {
    long id = service.create(
        req.name(), req.gitUrl(), req.username(), req.password(),
        req.authType(), req.sshKeyPath(), req.token(), req.branch()
    );
    return ApiResponse.success(Map.of("id", id));
}
```

**Step 4: 更新 update 方法**

```java
@PutMapping("/{id}")
public ApiResponse<String> update(@PathVariable long id, @RequestBody UpdateRequest req) {
    service.update(
        id, req.name(), req.gitUrl(), req.username(), req.password(),
        req.authType(), req.sshKeyPath(), req.token(), req.branch()
    );
    return ApiResponse.success("Updated");
}
```

**Step 5: 更新 toResponse 方法**

```java
private static ProjectResponse toResponse(RemoteProject p) {
    String fullPath = java.nio.file.Paths.get(System.getProperty("user.dir"), "remote-repos", p.getLocalPath()).toString();
    Long lastSyncAtMs = p.getLastSyncAt() != null ? p.getLastSyncAt() * 1000 : null;
    return new ProjectResponse(
        p.getId(), p.getName(), p.getGitUrl(), p.getUsername(),
        p.getBranch(), fullPath, p.getCloneStatus(), p.getCloneError(), lastSyncAtMs,
        p.getAuthType(), p.getSshKeyPath()
    );
}
```

**Step 6: 验证 Maven 编译**

Run: `cd hisi-dev-tool && mvn compile -q`
Expected: Compile succeeds

**Step 7: Commit**

```bash
git add hisi-dev-tool/src/main/java/com/huawei/hisi/project/remote/controller/RemoteProjectController.java
git commit -m "feat(remote-project): add authType/sshKeyPath/token params to controller"
```

---

### Task 16: Phase 2 后端集成测试

**Files:**
- Test: `hisi-dev-tool/src/test/java/com/huawei/hisi/project/remote/service/RemoteProjectServiceTest.java`

**Step 1: 创建测试类**

```java
package com.huawei.hisi.project.remote.service;

import com.huawei.hisi.project.remote.model.RemoteProject;
import com.huawei.hisi.project.remote.repository.RemoteProjectRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class RemoteProjectServiceTest {

    @Mock
    private RemoteProjectRepository repository;
    
    @Mock
    private GitCredentialService gitCredentialService;

    private RemoteProjectService service;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        service = new RemoteProjectService(repository, gitCredentialService);
    }

    @Test
    void testCreateWithPasswordAuth() {
        when(gitCredentialService.encrypt("secret")).thenReturn("encrypted");
        when(repository.insert(any())).thenReturn(1L);
        
        long id = service.create("test", "https://github.com/test.git", "user", "secret", "PASSWORD", null, null, "main");
        
        assertEquals(1L, id);
        verify(gitCredentialService).encrypt("secret");
    }

    @Test
    void testCreateWithTokenAuth() {
        when(gitCredentialService.encrypt("token123")).thenReturn("encrypted-token");
        when(repository.insert(any())).thenReturn(2L);
        
        long id = service.create("test", "https://github.com/test.git", null, null, "TOKEN", null, "token123", "main");
        
        assertEquals(2L, id);
        verify(gitCredentialService).encrypt("token123");
        verify(gitCredentialService, never()).encrypt(anyString()); // password not encrypted
    }

    @Test
    void testCreateWithSshKeyAuth() {
        when(repository.insert(any())).thenReturn(3L);
        
        long id = service.create("test", "git@github.com:test.git", null, null, "SSH_KEY", "~/.ssh/id_rsa", null, "main");
        
        assertEquals(3L, id);
        verify(gitCredentialService, never()).encrypt(anyString()); // no encryption for SSH
    }
}
```

**Step 2: 运行单元测试**

Run: `cd hisi-dev-tool && mvn test -Dtest=RemoteProjectServiceTest`
Expected: Tests pass

**Step 3: Commit**

```bash
git add hisi-dev-tool/src/test/java/com/huawei/hisi/project/remote/service/RemoteProjectServiceTest.java
git commit -m "test(remote-project): add unit tests for auth type creation"
```

---

### Task 17: Phase 2 集成验证

**Step 1: 启动后端服务**

Run: `cd hisi-dev-tool && mvn spring-boot:run`
Expected: Application starts without errors

**Step 2: 验证数据库迁移**

检查日志：应显示 `[Migration] Added auth_type, ssh_key_path, encrypted_token columns`

**Step 3: 验证 API 响应**

调用 `GET /api/remote-projects`，响应应包含 `authType` 和 `sshKeyPath` 字段

**Step 4: Commit Phase 2 completion marker**

```bash
git commit --allow-empty -m "feat(remote-project): Phase 2 complete - clone auth enhancement backend"
```

---

## Phase 3: 克隆认证增强（前端）

### Task 18: RemoteProject 类型扩展

**Files:**
- Modify: `hisi-dev-tool-frontend/src/types/remote-project.ts`

**Step 1: 更新 RemoteProject interface**

```typescript
export interface RemoteProject {
  id: number
  name: string
  gitUrl: string
  username: string
  branch: string
  localPath: string
  cloneStatus: 'PENDING' | 'CLONING' | 'CLONED' | 'FAILED'
  cloneError: string | null
  lastSyncAt: number | null
  authType: 'PASSWORD' | 'SSH_KEY' | 'TOKEN'
  sshKeyPath?: string
}
```

**Step 2: 更新 CreateRemoteProjectRequest**

```typescript
export interface CreateRemoteProjectRequest {
  name: string
  gitUrl: string
  username?: string
  password?: string
  branch?: string
  authType?: 'PASSWORD' | 'SSH_KEY' | 'TOKEN'
  sshKeyPath?: string
  token?: string
}
```

**Step 3: 更新 UpdateRemoteProjectRequest**

```typescript
export interface UpdateRemoteProjectRequest {
  name: string
  gitUrl: string
  username?: string
  password?: string
  branch?: string
  authType?: 'PASSWORD' | 'SSH_KEY' | 'TOKEN'
  sshKeyPath?: string
  token?: string
}
```

**Step 4: 验证前端编译**

Run: `cd hisi-dev-tool-frontend && npm run build`
Expected: Build succeeds

**Step 5: Commit**

```bash
git add hisi-dev-tool-frontend/src/types/remote-project.ts
git commit -m "feat(remote-project): add authType/sshKeyPath to frontend types"
```

---

### Task 19: 远端项目弹窗新增认证方式下拉

**Files:**
- Modify: `hisi-dev-tool-frontend/src/views/project/ProjectList.vue:485-509`

**Step 1: 更新 remoteForm 初始值**

```typescript
const remoteForm = ref<CreateRemoteProjectRequest & { password?: string; token?: string }>({
  name: '',
  gitUrl: '',
  username: '',
  password: '',
  branch: 'main',
  authType: 'PASSWORD',
  sshKeyPath: '',
  token: ''
})
```

**Step 2: 添加认证方式下拉框**

在弹窗表单中（用户名输入框前）添加：

```vue
<el-form-item label="认证方式" required>
  <el-select v-model="remoteForm.authType" @change="handleAuthTypeChange">
    <el-option value="PASSWORD" label="账号密码" />
    <el-option value="SSH_KEY" label="SSH密钥" />
    <el-option value="TOKEN" label="Token" />
  </el-select>
</el-form-item>
```

**Step 3: 条件显示密码字段**

将用户名和密码输入框改为条件渲染：

```vue
<el-form-item v-if="remoteForm.authType === 'PASSWORD'" label="用户名">
  <el-input v-model="remoteForm.username" placeholder="可选，私有仓库需要" />
</el-form-item>
<el-form-item v-if="remoteForm.authType === 'PASSWORD'" label="密码">
  <el-input v-model="remoteForm.password" type="password" show-password placeholder="可选，私有仓库需要" />
</el-form-item>
```

**Step 4: 添加 SSH 密钥路径输入框**

```vue
<el-form-item v-if="remoteForm.authType === 'SSH_KEY'" label="私钥路径">
  <el-input v-model="remoteForm.sshKeyPath" placeholder="如 ~/.ssh/id_rsa 或 C:\Users\xxx\.ssh\id_rsa" />
</el-form-item>
```

**Step 5: 添加 Token 输入框**

```vue
<el-form-item v-if="remoteForm.authType === 'TOKEN'" label="Token">
  <el-input v-model="remoteForm.token" type="password" show-password placeholder="OAuth Token 或 Personal Access Token" />
</el-form-item>
```

**Step 6: 添加 handleAuthTypeChange 方法**

```typescript
const handleAuthTypeChange = () => {
  // 切换认证方式时清空其他字段
  if (remoteForm.value.authType !== 'PASSWORD') {
    remoteForm.value.username = ''
    remoteForm.value.password = ''
  }
  if (remoteForm.value.authType !== 'SSH_KEY') {
    remoteForm.value.sshKeyPath = ''
  }
  if (remoteForm.value.authType !== 'TOKEN') {
    remoteForm.value.token = ''
  }
}
```

**Step 7: 更新 handleRemoteSubmit 方法**

```typescript
const handleRemoteSubmit = async () => {
  if (!remoteForm.value.name.trim() || !remoteForm.value.gitUrl.trim()) {
    ElMessage.warning('项目名称和Git地址不能为空')
    return
  }
  if (remoteForm.value.authType === 'SSH_KEY' && !remoteForm.value.sshKeyPath?.trim()) {
    ElMessage.warning('SSH密钥认证需要提供私钥路径')
    return
  }
  if (remoteForm.value.authType === 'TOKEN' && !remoteForm.value.token?.trim()) {
    ElMessage.warning('Token认证需要提供Token')
    return
  }
  
  remoteSubmitting.value = true
  try {
    const payload: CreateRemoteProjectRequest = {
      name: remoteForm.value.name.trim(),
      gitUrl: remoteForm.value.gitUrl.trim(),
      username: remoteForm.value.authType === 'PASSWORD' ? remoteForm.value.username?.trim() : undefined,
      password: remoteForm.value.authType === 'PASSWORD' ? remoteForm.value.password?.trim() : undefined,
      branch: remoteForm.value.branch.trim() || 'main',
      authType: remoteForm.value.authType,
      sshKeyPath: remoteForm.value.authType === 'SSH_KEY' ? remoteForm.value.sshKeyPath?.trim() : undefined,
      token: remoteForm.value.authType === 'TOKEN' ? remoteForm.value.token?.trim() : undefined
    }
    if (remoteIsEdit.value && remoteEditId.value != null) {
      await updateRemoteProject(remoteEditId.value, payload as UpdateRemoteProjectRequest)
      ElMessage.success('远端项目已更新')
    } else {
      await createRemoteProject(payload)
      ElMessage.success('远端项目已创建')
    }
    showRemoteDialog.value = false
    await loadRemoteProjects()
  } catch {
    ElMessage.error(remoteIsEdit.value ? '更新失败' : '创建失败')
  } finally {
    remoteSubmitting.value = false
  }
}
```

**Step 8: 更新 handleEditRemote 方法**

```typescript
const handleEditRemote = (row: RemoteProject) => {
  remoteIsEdit.value = true
  remoteEditId.value = row.id
  remoteForm.value = {
    name: row.name,
    gitUrl: row.gitUrl,
    username: row.username || '',
    password: '',
    branch: row.branch,
    authType: row.authType || 'PASSWORD',
    sshKeyPath: row.sshKeyPath || '',
    token: ''
  }
  showRemoteDialog.value = true
}
```

**Step 9: 验证前端编译**

Run: `cd hisi-dev-tool-frontend && npm run build`
Expected: Build succeeds

**Step 10: Commit**

```bash
git add hisi-dev-tool-frontend/src/views/project/ProjectList.vue
git commit -m "feat(remote-project): add auth type selector to remote project dialog"
```

---

### Task 20: Phase 3 集成验证

**Step 1: 启动前端开发服务器**

Run: `cd hisi-dev-tool-frontend && npm run dev`
Expected: Dev server starts

**Step 2: 手动验证认证方式切换**

1. 打开项目管理 → 远端项目 → 添加远端项目
2. 切换认证方式：账号密码 → SSH密钥 → Token
3. 验证条件显示的字段正确切换
4. 验证提交时参数正确传递

**Step 3: 测试克隆功能**

1. 添加一个 SSH 密钥认证的远端项目（使用测试仓库）
2. 点击克隆，验证 SSH 认证流程

**Step 4: Commit Phase 3 completion marker**

```bash
git commit --allow-empty -m "feat(remote-project): Phase 3 complete - clone auth enhancement frontend"
```

---

## Phase 4: 最终集成验证

### Task 21: 全量功能测试

**Step 1: 启动前后端服务**

```bash
cd hisi-dev-tool && mvn spring-boot:run
cd hisi-dev-tool-frontend && npm run dev
```

**Step 2: 验证公共功能对齐**

| 功能 | 验证方法 |
|------|---------|
| 图谱屏蔽目录 | 点击按钮 → 配置屏蔽目录 → 验证 localStorage |
| 术语配置 | 点击按钮 → 选择已克隆项目 → 配置术语 |
| 跨服务依赖构建 | 勾选 2+ 已克隆且有 KG 的项目 → 点击构建 |
| 批量生成图谱 | 勾选已克隆项目 → 点击批量生成 → 验证入队 |
| 提交分析 | 点击操作列按钮 → 验证提交列表加载 |
| GitOperations | 验证分支切换、回退等操作 |
| 图谱刷新 | 点击操作列按钮 → 验证增量刷新 |

**Step 3: 验证克隆认证增强**

| 认证方式 | 验证方法 |
|---------|---------|
| PASSWORD | 添加 GitHub/GitLab 私有仓库 → 使用账号密码克隆 |
| SSH_KEY | 添加支持 SSH 的仓库 → 使用私钥路径克隆 |
| TOKEN | 添加支持 Token 的仓库 → 使用 Token 克隆 |

**Step 4: 最终 Commit**

```bash
git add -A
git commit -m "feat(remote-project): complete public feature alignment + clone auth enhancement

Phase 1: Remote tab public features (7 items)
- KG exclude paths, glossary config, cross-service build, batch KG generation
- Commit analysis, GitOperations, KG refresh buttons

Phase 2: Backend clone auth enhancement
- AuthType enum (PASSWORD/SSH_KEY/TOKEN)
- RemoteProject model + repository + service auth logic
- JGit SSH JSch integration

Phase 3: Frontend auth type selector
- Dynamic form fields based on auth type
- Validation for required auth fields"
```

---

## 文件变更清单

| 文件 | Phase | 变更类型 |
|------|-------|---------|
| `ProjectList.vue` | 1, 3 | 修改 |
| `pom.xml` | 2 | 修改 |
| `RemoteProjectRepository.java` | 2 | 修改 |
| `RemoteProject.java` | 2 | 修改 |
| `AuthType.java` | 2 | 新建 |
| `RemoteProjectService.java` | 2 | 修改 |
| `RemoteProjectController.java` | 2 | 修改 |
| `RemoteProjectServiceTest.java` | 2 | 新建 |
| `remote-project.ts` | 3 | 修改 |

---

## 验证清单

### Phase 1
- [ ] 前端编译成功
- [ ] 远端Tab表头按钮：图谱屏蔽目录、术语配置、跨服务构建、批量KG
- [ ] 远端Tab操作按钮：提交分析、GitOperations、图谱刷新
- [ ] 未克隆项目按钮禁用

### Phase 2
- [ ] Maven 编译成功
- [ ] 数据库迁移执行成功
- [ ] API 响应包含 authType/sshKeyPath
- [ ] 单元测试通过

### Phase 3
- [ ] 前端编译成功
- [ ] 认证方式下拉正确切换
- [ ] 条件字段正确显示/隐藏
- [ ] 表单验证正确

### Phase 4
- [ ] 公共功能全部可用
- [ ] 三种认证方式均可成功克隆