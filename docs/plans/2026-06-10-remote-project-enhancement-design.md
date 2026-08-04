# 远端项目公共功能对齐 + 克隆认证增强设计

> Created: 2026-06-10
> Status: Draft

---

## Context

当前项目管理页面分两个 Tab：
- **本地项目Tab**：扫描发现本地仓库，提供完整的 KG 生成、Git 操作、提交分析等功能
- **远端项目Tab**：管理远端项目配置、克隆、定时任务，但缺失多个公共功能

**用户需求**：
1. 公共功能对齐：远端Tab 缺失的 7 项公共功能需要对齐
2. 克隆认证增强：支持 SSH 密钥、Token 认证方式，适配华为 CodeHub

---

## Decision Summary

| 决策点 | 用户选择 |
|--------|---------|
| 公共功能对齐范围 | **全部对齐**（7项） |
| CodeHub 认证方式 | SSH 公钥、Token、账号密码 |
| SSH 密钥存储方式 | **私钥文件路径**（先实现路径方案） |
| 认证类型枚举 | **简单枚举**（PASSWORD/SSH_KEY/TOKEN） |
| GPG 认证支持 | **不支持**（GPG 用于签名而非认证） |

---

## Module A: 公共功能对齐

### 功能差异矩阵

| 功能 | 本地Tab | 远端Tab | 对齐方案 |
|------|---------|---------|----------|
| 图谱屏蔽目录 | ✅ 表头按钮 | ❌ | 新增表头按钮，复用 localStorage |
| 术语配置 | ✅ 表头按钮 | ❌ | 新增表头按钮，弹窗需传入 localPath |
| 跨服务依赖构建 | ✅ 表头按钮 | ❌ | 新增表头按钮，过滤已克隆项目 |
| 批量生成图谱 | ✅ 表头按钮 | ❌ | 新增表头按钮，仅处理已克隆项目 |
| 提交分析 | ✅ 操作列 | ❌ | 新增操作按钮，弹窗需传入 localPath |
| GitOperations组件 | ✅ 操作列 | ❌ | 新增组件，传入 localPath |
| 图谱刷新 | ✅ 操作列 | ❌ | 新增操作按钮 |

### 前端改动

**文件**: `hisi-dev-tool-frontend/src/views/project/ProjectList.vue`

#### 改动1：远端Tab 表头按钮区扩展

在第 221-238 行的 header-buttons 区域添加：

```vue
<el-button @click="openKgExcludeDialog">
  <el-icon><Setting /></el-icon>
  图谱屏蔽目录
</el-button>
<el-button @click="openGlossaryDialogForRemote">
  <el-icon><EditPen /></el-icon>
  术语配置
</el-button>
<el-button
  type="warning"
  @click="handleCrossServiceBuildRemote"
  :disabled="selectedRemoteProjectsWithKg.length < 2"
  :loading="crossServiceBuilding"
>
  跨服务依赖构建 ({{ selectedRemoteProjectsWithKg.length }})
</el-button>
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

#### 改动2：远端Tab 操作列扩展

在第 309-350 行的操作列添加：

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
<GitOperations
  v-if="row.cloneStatus === 'CLONED'"
  :project-path="row.localPath"
/>
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

#### 改动3：新增辅助方法

```typescript
// 术语配置 — 远端项目选择时需要 localPath
const openGlossaryDialogForRemote = () => {
  const cloned = remoteProjects.value.filter(p => p.cloneStatus === 'CLONED')
  if (cloned.length === 1) {
    glossaryProjectPath.value = normalizePath(cloned[0].localPath)
  }
  showGlossaryDialog.value = true
  if (glossaryProjectPath.value) loadGlossaryTerms()
}

// 提交分析 — 远端项目
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

// 跨服务构建 — 远端项目
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

// 批量生成图谱 — 远端项目
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
    const tasks = await knowledgeGraphApi.startGenerateTaskBatch(paths, kgExcludePaths.value)
    if (tasks && tasks.length > 0) {
      for (const task of tasks) {
        knowledgeGraphTaskStatusMap.value[normalizePath(task.projectPath)] = task
      }
      ElMessage.success(`已入队 ${tasks.length} 个项目`)
      startKgPolling()
    }
  } catch {
    ElMessage.error('批量生成入队失败')
  } finally {
    batchGeneratingKG.value = false
  }
}

// 图谱刷新 — 远端项目
const handleRefreshProjectRemote = async (row: RemoteProject) => {
  try {
    const res = await knowledgeGraphApi.refresh(row.localPath)
    if (res.isNoop) {
      ElMessage.info('无变更，图谱已是最新')
    } else {
      ElMessage.success(`刷新完成：${res.changedFiles} 个文件变更`)
      await loadRemoteProjectTaskStatuses()
    }
  } catch (e) {
    // 错误处理同本地
  }
}
```

---

## Module B: 克隆认证增强

### 数据库扩展

**文件**: `hisi-dev-tool/src/main/resources/schema.sql`

```sql
-- 新增认证类型字段
ALTER TABLE remote_project ADD COLUMN auth_type VARCHAR(20) DEFAULT 'PASSWORD';
ALTER TABLE remote_project ADD COLUMN ssh_key_path VARCHAR(255);
ALTER TABLE remote_project ADD COLUMN encrypted_token VARCHAR(500);

-- auth_type 枚举值：PASSWORD / SSH_KEY / TOKEN
```

### 后端改动

#### 改动1：RemoteProject 模型扩展

**文件**: `hisi-dev-tool/src/main/java/com/huawei/hisi/project/remote/model/RemoteProject.java`

```java
@Property("authType")
private String authType; // PASSWORD / SSH_KEY / TOKEN

@Property("sshKeyPath")
private String sshKeyPath; // SSH 私钥文件路径

@Property("encryptedToken")
private String encryptedToken; // AES 加密的 Token
```

#### 改动2：认证枚举类

**文件**: `hisi-dev-tool/src/main/java/com/huawei/hisi/project/remote/model/AuthType.java`

```java
public enum AuthType {
    PASSWORD,
    SSH_KEY,
    TOKEN
}
```

#### 改动3：RemoteProjectService 认证逻辑分支

**文件**: `hisi-dev-tool/src/main/java/com/huawei/hisi/project/remote/service/RemoteProjectService.java`

```java
import org.eclipse.jgit.transport.SshSessionFactory;
import org.eclipse.jgit.transport.ssh.jsch.JschConfigSessionFactory;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.JSchException;

private CredentialsProvider getCredentialsProvider(RemoteProject project) {
    AuthType authType = AuthType.valueOf(project.getAuthType());
    
    switch (authType) {
        case PASSWORD:
            String password = gitCredentialService.decrypt(project.getEncryptedPassword());
            return new UsernamePasswordCredentialsProvider(project.getUsername(), password);
        
        case TOKEN:
            String token = gitCredentialService.decrypt(project.getEncryptedToken());
            return new UsernamePasswordCredentialsProvider("oauth2", token);
        
        case SSH_KEY:
            // SSH 认证使用 SshSessionFactory，无需 CredentialsProvider
            return null;
        
        default:
            throw new IllegalArgumentException("Unsupported auth type: " + authType);
    }
}

private void configureSshSessionFactory(String sshKeyPath) {
    SshSessionFactory.setInstance(new JschConfigSessionFactory() {
        @Override
        protected void configure(Host hc, Session session) {
            session.setConfig("StrictHostKeyChecking", "no");
        }
        
        @Override
        protected JSch createJSch(FileRepositoryProvider fs) throws JSchException {
            JSch jsch = super.createJSch(fs);
            jsch.addIdentity(sshKeyPath);
            return jsch;
        }
    });
}

public void cloneProject(long id) {
    RemoteProject project = getById(id);
    repository.updateCloneStatus(id, "CLONING");
    
    // ... 清理目录逻辑（保持不变）...
    
    Path targetDir = resolveCloneDir(localPath);
    try {
        Files.createDirectories(targetDir);
        
        AuthType authType = AuthType.valueOf(project.getAuthType());
        
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
        // 错误处理（保持不变）
    } finally {
        // 重置 SSH Factory（避免影响其他克隆）
        if (authType == AuthType.SSH_KEY) {
            SshSessionFactory.setInstance(null);
        }
    }
}
```

#### 改动4：Controller 新增认证字段

**文件**: `hisi-dev-tool/src/main/java/com/huawei/hisi/project/remote/controller/RemoteProjectController.java`

```java
@PostMapping
public ApiResponse<Long> create(
    @RequestParam String name,
    @RequestParam String gitUrl,
    @RequestParam(required = false) String username,
    @RequestParam(required = false) String password,
    @RequestParam(required = false, defaultValue = "PASSWORD") String authType,
    @RequestParam(required = false) String sshKeyPath,
    @RequestParam(required = false) String token,
    @RequestParam(required = false, defaultValue = "main") String branch
) {
    long id = remoteProjectService.create(
        name, gitUrl, username, password, authType, sshKeyPath, token, branch
    );
    return ApiResponse.ok(id);
}
```

#### 改动5：GitCredentialService Token 加密

复用现有 AES 加密方法，新增加密 Token 字段处理：

```java
// create() 方法中
if (token != null && !token.isEmpty() && "TOKEN".equals(authType)) {
    encryptedToken = gitCredentialService.encrypt(token);
}
```

### 前端改动

#### 改动1：RemoteProject 类型扩展

**文件**: `hisi-dev-tool-frontend/src/types/remote-project.ts`

```typescript
export interface RemoteProject {
  id: number
  name: string
  gitUrl: string
  username?: string
  branch: string
  localPath: string
  cloneStatus: string
  cloneError?: string
  lastSyncAt?: number
  authType: 'PASSWORD' | 'SSH_KEY' | 'TOKEN'
  sshKeyPath?: string
}

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

#### 改动2：远端项目添加/编辑弹窗

**文件**: `hisi-dev-tool-frontend/src/views/project/ProjectList.vue`

```vue
<el-form-item label="认证方式" required>
  <el-select v-model="remoteForm.authType" @change="handleAuthTypeChange">
    <el-option value="PASSWORD" label="账号密码" />
    <el-option value="SSH_KEY" label="SSH密钥" />
    <el-option value="TOKEN" label="Token" />
  </el-select>
</el-form-item>

<el-form-item v-if="remoteForm.authType === 'PASSWORD'" label="用户名">
  <el-input v-model="remoteForm.username" placeholder="可选，私有仓库需要" />
</el-form-item>
<el-form-item v-if="remoteForm.authType === 'PASSWORD'" label="密码">
  <el-input v-model="remoteForm.password" type="password" show-password placeholder="可选，私有仓库需要" />
</el-form-item>

<el-form-item v-if="remoteForm.authType === 'SSH_KEY'" label="私钥路径">
  <el-input v-model="remoteForm.sshKeyPath" placeholder="如 ~/.ssh/id_rsa 或 C:\Users\xxx\.ssh\id_rsa" />
</el-form-item>

<el-form-item v-if="remoteForm.authType === 'TOKEN'" label="Token">
  <el-input v-model="remoteForm.token" type="password" show-password placeholder="OAuth Token 或 Personal Access Token" />
</el-form-item>
```

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

---

## 实施步骤

### Phase 1: 公共功能对齐（前端）

1. [ ] 远端Tab 操作列新增：提交分析按钮
2. [ ] 远端Tab 操作列新增：GitOperations 组件
3. [ ] 远端Tab 操作列新增：图谱刷新按钮
4. [ ] 远端Tab 表头新增：图谱屏蔽目录按钮
5. [ ] 远端Tab 表头新增：术语配置按钮
6. [ ] 远端Tab 表头新增：跨服务依赖构建按钮
7. [ ] 远端Tab 表头新增：批量生成图谱按钮
8. [ ] 新增辅助方法（术语配置、提交分析、跨服务构建、批量KG、图谱刷新）
9. [ ] 验证：远端项目操作功能与本地一致

### Phase 2: 克隆认证增强（后端）

1. [ ] 数据库 schema 新增 auth_type、ssh_key_path、encrypted_token 字段
2. [ ] RemoteProject 模型新增字段
3. [ ] 创建 AuthType 枚举类
4. [ ] RemoteProjectRepository 新增字段读写
5. [ ] RemoteProjectService 新增认证逻辑分支（PASSWORD/SSH_KEY/TOKEN）
6. [ ] GitCredentialService 复用加密存储 Token
7. [ ] RemoteProjectController 新增认证参数
8. [ ] 单元测试：SSH 密钥认证流程
9. [ ] 验证：三种认证方式均可成功克隆

### Phase 3: 克隆认证增强（前端）

1. [ ] RemoteProject 类型新增 authType、sshKeyPath
2. [ ] CreateRemoteProjectRequest 新增 authType、sshKeyPath、token
3. [ ] 远端项目弹窗新增认证方式下拉
4. [ ] 条件显示认证字段（密码/SSH路径/Token）
5. [ ] handleAuthTypeChange 切换逻辑
6. [ ] 验证：前端添加远端项目支持三种认证方式

---

## 风险与应对

| 风险 | 影响 | 应对策略 |
|------|------|---------|
| SSH 私钥路径跨平台兼容性 | Windows 用户路径格式不同 | 使用 `Paths.get()` 规范化，前端提示用户填写完整路径 |
| JGit SSH 配置复杂 | JSch 配置错误导致克隆失败 | 使用 JschConfigSessionFactory 自定义配置，捕获 JSchException |
| Token 暴露风险 | 安全问题 | 复用 GitCredentialService AES-256-GCM 加密 |
| SSH 认证影响其他克隆 | 全局 SshSessionFactory 状态残留 | 克隆完成后 resetInstance(null) |
| 远端项目本地路径不存在 | 提交分析、GitOperations 失败 | 前端判断 cloneStatus === 'CLONED' 才显示按钮 |

---

## 文件变更清单

| 文件 | 动作 | Phase |
|------|------|-------|
| `ProjectList.vue` | 操作列 + 表头按钮扩展 | 1 |
| `schema.sql` | 新增 auth_type、ssh_key_path、encrypted_token | 2 |
| `RemoteProject.java` | 新增字段 | 2 |
| `AuthType.java` | **新建** | 2 |
| `RemoteProjectRepository.java` | 新增字段读写 | 2 |
| `RemoteProjectService.java` | 认证逻辑分支 + SSH 配置 | 2 |
| `GitCredentialService.java` | 复用加密（无需改动） | 2 |
| `RemoteProjectController.java` | 新增认证参数 | 2 |
| `remote-project.ts` | 类型扩展 | 3 |

---

## 不在范围内

- GPG 认证支持（GPG 用于 commit 签名而非克隆认证）
- SSH 密钥内容存储（后续迭代）
- 华为 CodeHub 特殊 IAM 认证（CodeHub 支持标准 SSH/Token/密码）