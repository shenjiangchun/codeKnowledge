# 调用链状态按项目路径去重实现计划

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** 前后端统一使用 projectPath（项目完整路径）作为调用链任务的唯一标识，解决同名项目在不同目录下共享状态的问题。

**Architecture:** 修改后端 Controller/Service/Repository 接收 projectPath 参数，按 project_path 字段查询数据库；前端 API 和组件改为传递项目路径。

**Tech Stack:** Spring Boot 3.2 + Java 17 + Vue 3 + TypeScript + Pinia

---

## Task 1: 后端 Repository 添加按路径查询方法

**Files:**
- Modify: `hisi-dev-tool/src/main/java/com/huawei/hisi/repository/CallChainTaskRepository.java`

**Step 1: 添加索引**

在 `createTable()` 方法中添加 project_path 索引：

```java
databaseHelper.createIndexWithDialect("idx_task_project_path", "call_chain_task", "project_path");
```

位置：在第 85 行 `databaseHelper.createIndexWithDialect("idx_task_status"...)` 之后。

**Step 2: 添加 findLatestByProjectPath 方法**

在第 165 行 `findLatestByProjectName` 方法后添加：

```java
/**
 * 根据项目路径获取最新任务
 */
public Optional<CallChainTask> findLatestByProjectPath(String projectPath) {
    String sql = "SELECT * FROM call_chain_task WHERE project_path = ? ORDER BY id DESC LIMIT 1";
    try {
        CallChainTask task = jdbcTemplate.queryForObject(sql, rowMapper, projectPath);
        return Optional.ofNullable(task);
    } catch (Exception e) {
        LOG.warn("findLatestByProjectPath failed for path {}: {}", projectPath, e.getMessage());
        return Optional.empty();
    }
}
```

**Step 3: 添加 findLatestByProjectPaths 方法**

在 `findLatestByProjectNames` 方法后添加：

```java
/**
 * 批量获取多个项目路径的最新任务状态
 */
public List<CallChainTask> findLatestByProjectPaths(List<String> projectPaths) {
    if (projectPaths == null || projectPaths.isEmpty()) {
        return List.of();
    }

    // 使用子查询获取每个项目路径的最新任务
    String placeholders = String.join(",", projectPaths.stream().map(s -> "?").toArray(String[]::new));
    String sql = "SELECT t.* FROM call_chain_task t " +
        "INNER JOIN (" +
        "  SELECT project_path, MAX(id) as max_id FROM call_chain_task " +
        "  WHERE project_path IN (" + placeholders + ") GROUP BY project_path" +
        ") latest ON t.id = latest.max_id";

    try {
        return jdbcTemplate.query(sql, rowMapper, projectPaths.toArray());
    } catch (Exception e) {
        LOG.warn("findLatestByProjectPaths failed: {}", e.getMessage());
        return List.of();
    }
}
```

**Step 4: 编译验证**

Run: `cd hisi-dev-tool && mvn compile -q`
Expected: 编译成功，无错误

**Step 5: Commit**

```bash
git add src/main/java/com/huawei/hisi/repository/CallChainTaskRepository.java
git commit -m "feat: add findLatestByProjectPath methods to repository"
```

---

## Task 2: 后端 Service 修改方法签名

**Files:**
- Modify: `hisi-dev-tool/src/main/java/com/huawei/hisi/service/CallChainTaskService.java`
- Modify: `hisi-dev-tool/src/main/java/com/huawei/hisi/service/CallChainTaskServiceImpl.java`

**Step 1: 修改 Service 接口**

将 `CallChainTaskService.java` 的方法签名改为按路径查询：

```java
/**
 * 启动调用链生成任务
 * @param projectPath 项目完整路径
 * @return 创建的任务对象
 */
CallChainTask startTask(String projectPath);

/**
 * 获取单个项目的最新任务状态
 * @param projectPath 项目完整路径
 * @return 任务对象
 */
CallChainTask getLatestTask(String projectPath);

/**
 * 批量获取多个项目的最新任务状态
 * @param projectPaths 项目完整路径列表
 * @return 任务列表
 */
List<CallChainTask> getTaskStatus(List<String> projectPaths);
```

**Step 2: 修改 ServiceImpl 实现**

修改 `CallChainTaskServiceImpl.java`：

1. `startTask` 方法签名和实现：

```java
@Override
public CallChainTask startTask(String projectPath) {
    // 防呆设计1：检查项目路径是否存在
    File projectDir = new File(projectPath);
    if (!projectDir.exists() || !projectDir.isDirectory()) {
        throw new IllegalArgumentException("项目路径不存在: " + projectPath);
    }

    // 提取项目名称（路径最后一段）
    String projectName = new File(projectPath).getName();

    // 防呆设计2：检查该项目路径是否已有运行中或待处理的任务
    CallChainTask existingTask = taskRepository.findLatestByProjectPath(projectPath).orElse(null);
    if (existingTask != null) {
        String status = existingTask.getStatus();
        if ("PENDING".equals(status) || "RUNNING".equals(status)) {
            LOG.warn("Project path {} already has a running task: id={}, status={}",
                projectPath, existingTask.getId(), status);
            throw new IllegalStateException("该项目已有调用链生成任务正在执行中，请等待完成后再试");
        }
    }

    // 创建任务记录
    CallChainTask task = new CallChainTask();
    task.setProjectName(projectName);
    task.setProjectPath(projectPath);
    task.setStatus("PENDING");

    task = taskRepository.insert(task);
    LOG.info("Created call chain task: id={}, projectPath={}", task.getId(), projectPath);

    // 异步执行任务
    self.executeTaskAsync(task.getId(), projectPath);

    return task;
}
```

2. `getLatestTask` 方法：

```java
@Override
public CallChainTask getLatestTask(String projectPath) {
    return taskRepository.findLatestByProjectPath(projectPath).orElse(null);
}
```

3. `getTaskStatus` 方法：

```java
@Override
public List<CallChainTask> getTaskStatus(List<String> projectPaths) {
    if (projectPaths == null || projectPaths.isEmpty()) {
        return taskRepository.findRunningOrPending();
    }
    return taskRepository.findLatestByProjectPaths(projectPaths);
}
```

**Step 3: 编译验证**

Run: `cd hisi-dev-tool && mvn compile -q`
Expected: 编译成功，无错误

**Step 4: Commit**

```bash
git add src/main/java/com/huawei/hisi/service/CallChainTaskService.java
git add src/main/java/com/huawei/hisi/service/CallChainTaskServiceImpl.java
git commit -m "feat: change service methods to use projectPath as identifier"
```

---

## Task 3: 后端 Controller 修改接口参数

**Files:**
- Modify: `hisi-dev-tool/src/main/java/com/huawei/hisi/controller/CallChainTaskController.java`

**Step 1: 修改 startTask 接口**

将接口从路径参数改为查询参数：

```java
/**
 * 启动调用链生成任务
 * POST /api/tasks/generate?projectPath=D:/path/to/project
 */
@PostMapping("/generate")
public ResponseEntity<?> startTask(@RequestParam String projectPath) {
    try {
        CallChainTask task = taskService.startTask(projectPath);
        return ResponseEntity.ok(task);

    } catch (IllegalArgumentException e) {
        Map<String, Object> error = new HashMap<>();
        error.put("error", "INVALID_PROJECT");
        error.put("message", e.getMessage());
        return ResponseEntity.badRequest().body(error);

    } catch (IllegalStateException e) {
        Map<String, Object> error = new HashMap<>();
        error.put("error", "TASK_RUNNING");
        error.put("message", e.getMessage());
        CallChainTask existingTask = taskService.getLatestTask(projectPath);
        if (existingTask != null) {
            error.put("runningTask", existingTask);
        }
        return ResponseEntity.status(409).body(error);
    }
}
```

**Step 2: 修改 getTaskStatus 接口**

将参数名从 `projects` 改为 `projectPaths`：

```java
/**
 * 批量查询任务状态
 * GET /api/tasks/status?projectPaths=D:/path1,D:/path2
 */
@GetMapping("/status")
public ResponseEntity<List<CallChainTask>> getTaskStatus(
        @RequestParam(required = false) String projectPaths) {

    List<String> pathList = null;
    if (projectPaths != null && !projectPaths.trim().isEmpty()) {
        pathList = Arrays.asList(projectPaths.split(","));
    }

    List<CallChainTask> tasks = taskService.getTaskStatus(pathList);
    return ResponseEntity.ok(tasks);
}
```

**Step 3: 修改 getLatestTask 接口**

改为查询参数：

```java
/**
 * 获取单个项目的最新任务
 * GET /api/tasks/latest?projectPath=D:/path/to/project
 */
@GetMapping("/latest")
public ResponseEntity<CallChainTask> getLatestTask(@RequestParam String projectPath) {
    CallChainTask task = taskService.getLatestTask(projectPath);
    if (task == null) {
        return ResponseEntity.notFound().build();
    }
    return ResponseEntity.ok(task);
}
```

**Step 4: 移除不再需要的依赖和类**

移除 `AppConfigService` 引用（不再需要获取 projectDir）和 `TaskRequest` 类。

删除第 26-27 行的 `@Autowired AppConfigService`。
删除第 107-117 行的 `TaskRequest` 类。

**Step 5: 编译验证**

Run: `cd hisi-dev-tool && mvn compile -q`
Expected: 编译成功，无错误

**Step 6: Commit**

```bash
git add src/main/java/com/huawei/hisi/controller/CallChainTaskController.java
git commit -m "feat: change API endpoints to use projectPath parameter"
```

---

## Task 4: 前端 API 修改

**Files:**
- Modify: `hisi-dev-tool-frontend/src/api/task.ts`

**Step 1: 修改 API 方法签名**

```typescript
import request from '@/utils/request'
import type { CallChainTask } from '@/types/callchain'

type ApiTaskResponse = CallChainTask
type ApiTaskListResponse = CallChainTask[]

export const taskApi = {
  /**
   * 启动调用链生成任务
   * @param projectPath 项目完整路径
   */
  startGenerate(projectPath: string): Promise<ApiTaskResponse> {
    return request.post(`/tasks/generate?projectPath=${encodeURIComponent(projectPath)}`)
  },

  /**
   * 批量查询任务状态
   * @param projectPaths 项目完整路径列表，为空则查询所有运行中任务
   */
  getStatus(projectPaths?: string[]): Promise<ApiTaskListResponse> {
    const params = projectPaths && projectPaths.length > 0
      ? { projectPaths: projectPaths.join(',') }
      : {}
    return request.get('/tasks/status', { params })
  },

  /**
   * 获取单个项目最新任务
   * @param projectPath 项目完整路径
   */
  getLatest(projectPath: string): Promise<ApiTaskResponse> {
    return request.get(`/tasks/latest?projectPath=${encodeURIComponent(projectPath)}`)
  }
}
```

**Step 2: TypeScript 编译验证**

Run: `cd hisi-dev-tool-frontend && npm run type-check`
Expected: 无类型错误

**Step 3: Commit**

```bash
git add src/api/task.ts
git commit -m "feat: change task API to use projectPath parameter"
```

---

## Task 5: 前端 ProjectList 组件修改

**Files:**
- Modify: `hisi-dev-tool-frontend/src/views/project/ProjectList.vue`

**Step 1: 修改 taskStatusMap 存储键**

找到第 401-404 行的存储逻辑，改为：

```typescript
taskStatusMap.value = {
  ...taskStatusMap.value,
  [row.path]: task  // 使用项目路径作为键
}
```

同样修改第 417-419 行：

```typescript
[row.path]: errorData.runningTask
```

**Step 2: 修改 getProjectTaskStatus 函数**

找到第 437-438 行，改为：

```typescript
const getProjectTaskStatus = (projectPath: string) => {
  return taskStatusMap.value[projectPath]?.status
}
```

**Step 3: 修改模板中的调用**

找到第 96-99 行，改为：

```vue
:class="getTaskStatusClass(getProjectTaskStatus(row.path))"
:title="getTaskStatusTooltip(getProjectTaskStatus(row.path))"
<span class="status-text">{{ getTaskStatusText(getProjectTaskStatus(row.path)) }}</span>
```

找到第 134 行，改为：

```vue
:disabled="!appStore.projectDirConfigured || isTaskRunning(getProjectTaskStatus(row.path))"
```

**Step 4: 修改 handleGenerateChain**

找到第 388-434 行，改为：

```typescript
const handleGenerateChain = async (row: GitRepositoryInfo) => {
  if (!appStore.projectDirConfigured) {
    ElMessage.warning('请先配置项目目录')
    return
  }

  // Mark as generating - 使用项目路径
  generatingProjects.value.add(row.path)

  try {
    const task = await taskApi.startGenerate(row.path)  // 传递项目路径
    if (task) {
      taskStatusMap.value = {
        ...taskStatusMap.value,
        [row.path]: task  // 使用项目路径存储
      }
      ElMessage.success('已开始生成调用链')
      startPolling()
    }
  } catch (error: any) {
    if (error.response?.status === 409) {
      const errorData = error.response.data
      ElMessage.warning(errorData?.message || '该项目已有任务在执行中')
      if (errorData?.runningTask) {
        taskStatusMap.value = {
          ...taskStatusMap.value,
          [row.path]: errorData.runningTask  // 使用项目路径存储
        }
        startPolling()
      }
    } else if (error.response?.status === 400) {
      const errorData = error.response.data
      ElMessage.error(errorData?.message || '项目路径不存在')
    } else {
      ElMessage.error('启动任务失败')
    }
  } finally {
    generatingProjects.value.delete(row.path)  // 使用项目路径删除
  }
}
```

**Step 5: 修改 loadAllTaskStatuses**

找到第 442-468 行，改为：

```typescript
const loadAllTaskStatuses = async () => {
  if (projects.value.length === 0) return

  // 传递项目路径列表
  const projectPaths = projects.value.map(p => p.path)
  try {
    const tasks = await taskApi.getStatus(projectPaths)
    if (tasks && Array.isArray(tasks) && tasks.length > 0) {
      const newMap: Record<string, CallChainTask> = {}
      let hasRunning = false
      tasks.forEach(task => {
        // 按路径存储
        newMap[task.projectPath] = task
        if (task.status === 'PENDING' || task.status === 'RUNNING') {
          hasRunning = true
        }
      })
      taskStatusMap.value = newMap
      if (hasRunning) {
        startPolling()
      }
    }
  } catch (e) {
    console.error('Failed to load task statuses:', e)
  }
}
```

**Step 6: 修改轮询逻辑**

找到第 341-374 行的 `startPolling`，改为：

```typescript
const startPolling = () => {
  if (pollingTimer) {
    console.log('Polling already running, skip')
    return
  }
  console.log('Starting polling for task status updates...')
  pollingTimer = setInterval(async () => {
    const runningTasks = Object.values(taskStatusMap.value)
      .filter(t => t.status === 'PENDING' || t.status === 'RUNNING')

    if (runningTasks.length === 0) {
      console.log('No running tasks, stopping polling')
      stopPolling()
      return
    }

    // 用项目路径查询
    const runningProjectPaths = runningTasks.map(t => t.projectPath)
    console.log('Polling status for project paths:', runningProjectPaths)

    try {
      const tasks = await taskApi.getStatus(runningProjectPaths)
      if (tasks && Array.isArray(tasks)) {
        const newMap = { ...taskStatusMap.value }
        tasks.forEach(task => {
          newMap[task.projectPath] = task  // 按路径存储
          console.log(`Task ${task.projectPath} status: ${task.status}`)
        })
        taskStatusMap.value = newMap

        const stillRunning = tasks.some(t => t.status === 'PENDING' || t.status === 'RUNNING')
        if (!stillRunning) {
          console.log('All tasks completed, stopping polling')
          stopPolling()
        }
      }
    } catch (e) {
      console.error('Failed to poll task status:', e)
    }
  }, 20000)
}
```

**Step 7: TypeScript 编译验证**

Run: `cd hisi-dev-tool-frontend && npm run type-check`
Expected: 无类型错误

**Step 8: Commit**

```bash
git add src/views/project/ProjectList.vue
git commit -m "feat: change ProjectList to use projectPath for task status"
```

---

## Task 6: 集成测试验证

**Step 1: 启动后端服务**

Run: `cd hisi-dev-tool && mvn spring-boot:run`
Expected: 服务启动成功，端口 8080

**Step 2: 启动前端开发服务器**

Run: `cd hisi-dev-tool-frontend && npm run dev`
Expected: 前端启动成功，端口 5173

**Step 3: 手动验证流程**

1. 打开浏览器访问 http://localhost:5173
2. 进入项目管理页面
3. 配置两个不同的项目目录，每个目录下有同名项目（如 `my-project`）
4. 在第一个目录的项目点击"生成调用链"
5. 等待任务完成，状态变为"已完成"
6. 切换 projectDir 配置到第二个目录
7. 扫描仓库，检查同名项目的调用链状态是否为"未生成"
8. 点击第二个目录项目的"生成调用链"，验证状态独立更新

**Step 4: 最终 Commit**

```bash
git add docs/plans/2026-04-13-call-chain-status-by-path-design.md
git commit -m "docs: add call chain status by path design"
```

---

## Files Changed Summary

| 项目 | 文件 | 改动内容 |
|------|------|----------|
| 后端 | `CallChainTaskRepository.java` | 添加按路径查询方法 |
| 后端 | `CallChainTaskService.java` | 方法签名改为 projectPath |
| 后端 | `CallChainTaskServiceImpl.java` | 实现按路径查询 |
| 后端 | `CallChainTaskController.java` | API参数改为 projectPath |
| 前端 | `src/api/task.ts` | API方法改为传递 projectPath |
| 前端 | `src/views/project/ProjectList.vue` | taskStatusMap 用路径存储 |
| 文档 | `docs/plans/...-design.md` | 设计文档 |