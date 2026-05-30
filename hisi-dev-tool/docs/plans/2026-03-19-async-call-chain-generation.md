# 异步调用链生成功能实现计划

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** 为项目列表中的每个项目添加调用链生成按钮和状态显示，将同步调用链生成改造为异步任务。

**Architecture:** 数据库任务表 + Spring @Async 异步执行 + 前端轮询查询状态。每个项目独立执行，失败不影响其他项目。

**Tech Stack:** Spring Boot 3.2.0 + Java 17 + Vue 3 + TypeScript + Element Plus + OpenGauss

---

## Task 1: 创建数据库迁移脚本

**Files:**
- Create: `src/main/resources/db/migration/V9__create_call_chain_task_table.sql`

**Step 1: 创建迁移脚本文件**

```sql
-- V9__create_call_chain_task_table.sql
-- 调用链生成任务状态表

CREATE TABLE IF NOT EXISTS call_chain_task (
    id              BIGSERIAL PRIMARY KEY,
    project_name    VARCHAR(256) NOT NULL,
    project_path    VARCHAR(512),
    status          VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    start_time      TIMESTAMP,
    end_time        TIMESTAMP,
    error_message   TEXT,
    records_processed INTEGER DEFAULT 0,
    created_at      TIMESTAMP DEFAULT NOW(),
    updated_at      TIMESTAMP DEFAULT NOW()
);

-- 索引
CREATE INDEX IF NOT EXISTS idx_task_project ON call_chain_task(project_name);
CREATE INDEX IF NOT EXISTS idx_task_status ON call_chain_task(status);

-- 注释
COMMENT ON TABLE call_chain_task IS '调用链生成任务表';
COMMENT ON COLUMN call_chain_task.project_name IS '项目名称';
COMMENT ON COLUMN call_chain_task.project_path IS '项目完整路径';
COMMENT ON COLUMN call_chain_task.status IS '任务状态: PENDING/RUNNING/COMPLETED/FAILED';
COMMENT ON COLUMN call_chain_task.start_time IS '任务开始时间';
COMMENT ON COLUMN call_chain_task.end_time IS '任务结束时间';
COMMENT ON COLUMN call_chain_task.error_message IS '失败时的错误信息';
COMMENT ON COLUMN call_chain_task.records_processed IS '已处理的记录数';
```

**Step 2: 验证脚本语法**

检查 SQL 语法是否符合 OpenGauss 规范。

**Step 3: Commit**

```bash
git add src/main/resources/db/migration/V9__create_call_chain_task_table.sql
git commit -m "feat(db): add call_chain_task table for async task tracking"
```

---

## Task 2: 创建任务模型类

**Files:**
- Create: `src/main/java/com/huawei/hisi/model/CallChainTask.java`

**Step 1: 创建模型类**

```java
package com.huawei.hisi.model;

import lombok.Data;
import java.time.LocalDateTime;

/**
 * 调用链生成任务模型类
 * 对应 call_chain_task 数据库表
 */
@Data
public class CallChainTask {
    /**
     * 任务ID
     */
    private Long id;

    /**
     * 项目名称
     */
    private String projectName;

    /**
     * 项目完整路径
     */
    private String projectPath;

    /**
     * 任务状态: PENDING/RUNNING/COMPLETED/FAILED
     */
    private String status;

    /**
     * 任务开始时间
     */
    private LocalDateTime startTime;

    /**
     * 任务结束时间
     */
    private LocalDateTime endTime;

    /**
     * 失败时的错误信息
     */
    private String errorMessage;

    /**
     * 已处理的记录数
     */
    private Integer recordsProcessed;

    /**
     * 创建时间
     */
    private LocalDateTime createdAt;

    /**
     * 更新时间
     */
    private LocalDateTime updatedAt;
}
```

**Step 2: Commit**

```bash
git add src/main/java/com/huawei/hisi/model/CallChainTask.java
git commit -m "feat(model): add CallChainTask model class"
```

---

## Task 3: 创建任务 Repository

**Files:**
- Create: `src/main/java/com/huawei/hisi/repository/CallChainTaskRepository.java`

**Step 1: 创建 Repository 类**

```java
package com.huawei.hisi.repository;

import com.huawei.hisi.model.CallChainTask;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 调用链生成任务数据访问层
 */
@Repository
public class CallChainTaskRepository {

    private static final Logger LOG = LoggerFactory.getLogger(CallChainTaskRepository.class);

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final RowMapper<CallChainTask> rowMapper = (rs, rowNum) -> {
        CallChainTask task = new CallChainTask();
        task.setId(rs.getLong("id"));
        task.setProjectName(rs.getString("project_name"));
        task.setProjectPath(rs.getString("project_path"));
        task.setStatus(rs.getString("status"));
        task.setStartTime(rs.getTimestamp("start_time") != null ?
            rs.getTimestamp("start_time").toLocalDateTime() : null);
        task.setEndTime(rs.getTimestamp("end_time") != null ?
            rs.getTimestamp("end_time").toLocalDateTime() : null);
        task.setErrorMessage(rs.getString("error_message"));
        task.setRecordsProcessed(rs.getInt("records_processed"));
        task.setCreatedAt(rs.getTimestamp("created_at") != null ?
            rs.getTimestamp("created_at").toLocalDateTime() : null);
        task.setUpdatedAt(rs.getTimestamp("updated_at") != null ?
            rs.getTimestamp("updated_at").toLocalDateTime() : null);
        return task;
    };

    /**
     * 插入新任务
     */
    public CallChainTask insert(CallChainTask task) {
        String sql = "INSERT INTO call_chain_task (project_name, project_path, status) VALUES (?, ?, ?)";
        KeyHolder keyHolder = new GeneratedKeyHolder();

        jdbcTemplate.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, task.getProjectName());
            ps.setString(2, task.getProjectPath());
            ps.setString(3, task.getStatus() != null ? task.getStatus() : "PENDING");
            return ps;
        }, keyHolder);

        if (keyHolder.getKey() != null) {
            task.setId(keyHolder.getKey().longValue());
        }
        return task;
    }

    /**
     * 更新任务状态
     */
    public int updateStatus(Long id, String status, String errorMessage) {
        String sql = "UPDATE call_chain_task SET status = ?, error_message = ?, updated_at = CURRENT_TIMESTAMP WHERE id = ?";
        return jdbcTemplate.update(sql, status, errorMessage, id);
    }

    /**
     * 更新任务开始时间
     */
    public int updateStartTime(Long id, LocalDateTime startTime) {
        String sql = "UPDATE call_chain_task SET status = 'RUNNING', start_time = ?, updated_at = CURRENT_TIMESTAMP WHERE id = ?";
        return jdbcTemplate.update(sql, Timestamp.valueOf(startTime), id);
    }

    /**
     * 更新任务完成
     */
    public int updateCompleted(Long id, LocalDateTime endTime, int recordsProcessed) {
        String sql = "UPDATE call_chain_task SET status = 'COMPLETED', end_time = ?, records_processed = ?, updated_at = CURRENT_TIMESTAMP WHERE id = ?";
        return jdbcTemplate.update(sql, Timestamp.valueOf(endTime), recordsProcessed, id);
    }

    /**
     * 更新任务失败
     */
    public int updateFailed(Long id, LocalDateTime endTime, String errorMessage) {
        String sql = "UPDATE call_chain_task SET status = 'FAILED', end_time = ?, error_message = ?, updated_at = CURRENT_TIMESTAMP WHERE id = ?";
        return jdbcTemplate.update(sql, Timestamp.valueOf(endTime), errorMessage, id);
    }

    /**
     * 根据ID查询任务
     */
    public Optional<CallChainTask> findById(Long id) {
        String sql = "SELECT * FROM call_chain_task WHERE id = ?";
        try {
            CallChainTask task = jdbcTemplate.queryForObject(sql, rowMapper, id);
            return Optional.ofNullable(task);
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    /**
     * 获取项目的最新任务
     */
    public Optional<CallChainTask> findLatestByProjectName(String projectName) {
        String sql = "SELECT * FROM call_chain_task WHERE project_name = ? ORDER BY created_at DESC LIMIT 1";
        try {
            CallChainTask task = jdbcTemplate.queryForObject(sql, rowMapper, projectName);
            return Optional.ofNullable(task);
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    /**
     * 批量获取多个项目的最新任务状态
     */
    public List<CallChainTask> findLatestByProjectNames(List<String> projectNames) {
        if (projectNames == null || projectNames.isEmpty()) {
            return List.of();
        }

        // 使用子查询获取每个项目的最新任务
        String placeholders = String.join(",", projectNames.stream().map(s -> "?").toArray(String[]::new));
        String sql = "SELECT t.* FROM call_chain_task t " +
            "INNER JOIN (" +
            "  SELECT project_name, MAX(id) as max_id FROM call_chain_task " +
            "  WHERE project_name IN (" + placeholders + ") GROUP BY project_name" +
            ") latest ON t.id = latest.max_id";

        return jdbcTemplate.query(sql, rowMapper, projectNames.toArray());
    }

    /**
     * 获取所有正在运行或待执行的任务
     */
    public List<CallChainTask> findRunningOrPending() {
        String sql = "SELECT * FROM call_chain_task WHERE status IN ('PENDING', 'RUNNING') ORDER BY created_at DESC";
        return jdbcTemplate.query(sql, rowMapper);
    }
}
```

**Step 2: Commit**

```bash
git add src/main/java/com/huawei/hisi/repository/CallChainTaskRepository.java
git commit -m "feat(repo): add CallChainTaskRepository for task persistence"
```

---

## Task 4: 创建任务服务接口和实现

**Files:**
- Create: `src/main/java/com/huawei/hisi/service/CallChainTaskService.java`
- Create: `src/main/java/com/huawei/hisi/service/CallChainTaskServiceImpl.java`

**Step 1: 创建服务接口**

```java
package com.huawei.hisi.service;

import com.huawei.hisi.model.CallChainTask;
import java.util.List;

/**
 * 调用链生成任务服务接口
 */
public interface CallChainTaskService {

    /**
     * 启动调用链生成任务
     * @param projectName 项目名称
     * @param projectPath 项目路径
     * @return 创建的任务对象
     */
    CallChainTask startTask(String projectName, String projectPath);

    /**
     * 获取单个项目的最新任务状态
     * @param projectName 项目名称
     * @return 任务对象
     */
    CallChainTask getLatestTask(String projectName);

    /**
     * 批量获取多个项目的最新任务状态
     * @param projectNames 项目名称列表
     * @return 任务列表
     */
    List<CallChainTask> getTaskStatus(List<String> projectNames);

    /**
     * 获取所有正在运行的任务
     * @return 任务列表
     */
    List<CallChainTask> getRunningTasks();
}
```

**Step 2: 创建服务实现**

```java
package com.huawei.hisi.service;

import com.huawei.hisi.model.CallChainTask;
import com.huawei.hisi.repository.CallChainTaskRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 调用链生成任务服务实现
 */
@Service
public class CallChainTaskServiceImpl implements CallChainTaskService {

    private static final Logger LOG = LoggerFactory.getLogger(CallChainTaskServiceImpl.class);

    @Autowired
    private CallChainTaskRepository taskRepository;

    @Autowired
    private HisiURIMethodChainToDBService chainService;

    @Override
    public CallChainTask startTask(String projectName, String projectPath) {
        // 创建任务记录
        CallChainTask task = new CallChainTask();
        task.setProjectName(projectName);
        task.setProjectPath(projectPath);
        task.setStatus("PENDING");

        task = taskRepository.insert(task);
        LOG.info("Created call chain task: id={}, project={}", task.getId(), projectName);

        // 异步执行任务
        executeTaskAsync(task.getId(), projectPath);

        return task;
    }

    @Async("analysisTaskExecutor")
    public void executeTaskAsync(Long taskId, String projectPath) {
        LOG.info("Starting async call chain generation for task: {}", taskId);

        // 更新状态为 RUNNING
        taskRepository.updateStartTime(taskId, LocalDateTime.now());

        try {
            // 调用现有的调用链生成逻辑
            chainService.chainGenerator(projectPath);

            // 更新状态为 COMPLETED
            taskRepository.updateCompleted(taskId, LocalDateTime.now(), 0);
            LOG.info("Call chain task completed: id={}", taskId);

        } catch (Exception e) {
            LOG.error("Call chain task failed: id={}, error={}", taskId, e.getMessage(), e);
            // 更新状态为 FAILED
            taskRepository.updateFailed(taskId, LocalDateTime.now(), e.getMessage());
        }
    }

    @Override
    public CallChainTask getLatestTask(String projectName) {
        return taskRepository.findLatestByProjectName(projectName).orElse(null);
    }

    @Override
    public List<CallChainTask> getTaskStatus(List<String> projectNames) {
        if (projectNames == null || projectNames.isEmpty()) {
            return taskRepository.findRunningOrPending();
        }
        return taskRepository.findLatestByProjectNames(projectNames);
    }

    @Override
    public List<CallChainTask> getRunningTasks() {
        return taskRepository.findRunningOrPending();
    }
}
```

**Step 3: Commit**

```bash
git add src/main/java/com/huawei/hisi/service/CallChainTaskService.java
git add src/main/java/com/huawei/hisi/service/CallChainTaskServiceImpl.java
git commit -m "feat(service): add CallChainTaskService for async task management"
```

---

## Task 5: 修改 HisiURIMethodChainToDBService 支持指定路径

**Files:**
- Modify: `src/main/java/com/huawei/hisi/service/HisiURIMethodChainToDBService.java`
- Modify: `src/main/java/com/huawei/hisi/service/HisiURIMethodChainToDBServiceImpl.java`

**Step 1: 修改接口添加新方法**

在 `HisiURIMethodChainToDBService.java` 添加:

```java
package com.huawei.hisi.service;

public interface HisiURIMethodChainToDBService {

    /**
     * 入口类 - 扫描配置的项目目录
     * @throws Exception 分析异常
     */
    void chainGenerator() throws Exception;

    /**
     * 入口类 - 扫描指定的项目路径
     * @param projectPath 项目完整路径
     * @throws Exception 分析异常
     */
    void chainGenerator(String projectPath) throws Exception;
}
```

**Step 2: 修改实现类**

在 `HisiURIMethodChainToDBServiceImpl.java` 中：

1. 重构现有的 `chainGenerator()` 方法，提取核心逻辑到私有方法
2. 新增 `chainGenerator(String projectPath)` 方法

核心改动:

```java
// 保留原方法（兼容现有调用）
@Override
public void chainGenerator() throws Exception {
    String currentProjectDir = appConfigService.getProjectDir();
    if (currentProjectDir == null || currentProjectDir.isEmpty()) {
        throw new IllegalStateException("PROJECT_DIR is not configured. Please configure it in project management.");
    }
    chainGenerator(currentProjectDir);
}

// 新增方法 - 接受项目路径参数
@Override
public void chainGenerator(String projectPath) throws Exception {
    if (projectPath == null || projectPath.isEmpty()) {
        throw new IllegalArgumentException("Project path cannot be null or empty");
    }

    LOG.info("Starting call chain generation for: " + projectPath);

    List<Path> sourceRoots = findSourceRoots(Paths.get(projectPath));

    // ... 其余逻辑使用 projectPath 替代 currentProjectDir
    // 清除该项目的现有数据
    clearProjectData(projectPath);

    // ... 继续现有逻辑
}
```

**Step 3: Commit**

```bash
git add src/main/java/com/huawei/hisi/service/HisiURIMethodChainToDBService.java
git add src/main/java/com/huawei/hisi/service/HisiURIMethodChainToDBServiceImpl.java
git commit -m "feat(service): add chainGenerator with projectPath parameter"
```

---

## Task 6: 创建任务控制器

**Files:**
- Create: `src/main/java/com/huawei/hisi/controller/CallChainTaskController.java`

**Step 1: 创建控制器**

```java
package com.huawei.hisi.controller;

import com.huawei.hisi.model.CallChainTask;
import com.huawei.hisi.service.CallChainTaskService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * 调用链生成任务控制器
 */
@RestController
@RequestMapping("/api/tasks")
public class CallChainTaskController {

    @Autowired
    private CallChainTaskService taskService;

    @Autowired
    private AppConfigService appConfigService;

    /**
     * 启动调用链生成任务
     * POST /api/tasks/generate/{projectName}
     */
    @PostMapping("/generate/{projectName}")
    public ResponseEntity<CallChainTask> startTask(
            @PathVariable String projectName,
            @RequestBody(required = false) TaskRequest request) {

        // 构建项目完整路径
        String baseDir = appConfigService.getProjectDir();
        String projectPath = baseDir + "/" + projectName;

        CallChainTask task = taskService.startTask(projectName, projectPath);
        return ResponseEntity.ok(task);
    }

    /**
     * 批量查询任务状态
     * GET /api/tasks/status?projects=proj1,proj2,proj3
     */
    @GetMapping("/status")
    public ResponseEntity<List<CallChainTask>> getTaskStatus(
            @RequestParam(required = false) String projects) {

        List<String> projectList = null;
        if (projects != null && !projects.trim().isEmpty()) {
            projectList = Arrays.asList(projects.split(","));
        }

        List<CallChainTask> tasks = taskService.getTaskStatus(projectList);
        return ResponseEntity.ok(tasks);
    }

    /**
     * 获取单个项目的最新任务
     * GET /api/tasks/latest/{projectName}
     */
    @GetMapping("/latest/{projectName}")
    public ResponseEntity<CallChainTask> getLatestTask(@PathVariable String projectName) {
        CallChainTask task = taskService.getLatestTask(projectName);
        if (task == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(task);
    }

    /**
     * 请求体模型
     */
    public static class TaskRequest {
        private String projectPath;

        public String getProjectPath() {
            return projectPath;
        }

        public void setProjectPath(String projectPath) {
            this.projectPath = projectPath;
        }
    }
}
```

**Step 2: Commit**

```bash
git add src/main/java/com/huawei/hisi/controller/CallChainTaskController.java
git commit -m "feat(controller): add CallChainTaskController for task API endpoints"
```

---

## Task 7: 前端添加类型定义

**Files:**
- Modify: `src/types/callchain.ts`

**Step 1: 添加 CallChainTask 类型**

在文件末尾追加:

```typescript
/**
 * 调用链生成任务状态
 */
export interface CallChainTask {
  /** 任务ID */
  id: number
  /** 项目名称 */
  projectName: string
  /** 项目完整路径 */
  projectPath: string
  /** 任务状态: PENDING/RUNNING/COMPLETED/FAILED */
  status: 'PENDING' | 'RUNNING' | 'COMPLETED' | 'FAILED'
  /** 任务开始时间 */
  startTime?: string
  /** 任务结束时间 */
  endTime?: string
  /** 失败时的错误信息 */
  errorMessage?: string
  /** 已处理的记录数 */
  recordsProcessed: number
  /** 创建时间 */
  createdAt: string
  /** 更新时间 */
  updatedAt: string
}
```

**Step 2: Commit**

```bash
git add src/types/callchain.ts
git commit -m "feat(types): add CallChainTask interface"
```

---

## Task 8: 前端添加任务 API

**Files:**
- Create: `src/api/task.ts`

**Step 1: 创建 API 模块**

```typescript
import request from '@/utils/request'
import type { CallChainTask } from '@/types/callchain'

export const taskApi = {
  /**
   * 启动调用链生成任务
   */
  startGenerate(projectName: string) {
    return request.post<CallChainTask>(`/tasks/generate/${encodeURIComponent(projectName)}`)
  },

  /**
   * 批量查询任务状态
   * @param projects 项目名称列表，为空则查询所有运行中任务
   */
  getStatus(projects?: string[]) {
    const params = projects && projects.length > 0
      ? { projects: projects.join(',') }
      : {}
    return request.get<CallChainTask[]>('/tasks/status', { params })
  },

  /**
   * 获取单个项目最新任务
   */
  getLatest(projectName: string) {
    return request.get<CallChainTask>(`/tasks/latest/${encodeURIComponent(projectName)}`)
  }
}
```

**Step 2: Commit**

```bash
git add src/api/task.ts
git commit -m "feat(api): add taskApi for call chain task management"
```

---

## Task 9: 前端修改 ProjectList.vue 添加状态列和按钮

**Files:**
- Modify: `src/views/project/ProjectList.vue`

**Step 1: 添加导入和状态**

在 `<script setup>` 部分添加:

```typescript
import { taskApi } from '@/api/task'
import type { CallChainTask } from '@/types/callchain'
import { Connection } from '@element-plus/icons-vue'

// 任务状态映射
const taskStatusMap = ref<Record<string, CallChainTask>>({})
let pollingTimer: ReturnType<typeof setInterval> | null = null

// 状态样式
const getTaskStatusType = (status?: string) => {
  const types: Record<string, string> = {
    PENDING: 'info',
    RUNNING: 'warning',
    COMPLETED: 'success',
    FAILED: 'danger'
  }
  return types[status || ''] || 'info'
}

// 状态文本
const getTaskStatusText = (status?: string) => {
  const texts: Record<string, string> = {
    PENDING: '等待中',
    RUNNING: '生成中',
    COMPLETED: '已完成',
    FAILED: '失败'
  }
  return texts[status || ''] || '未生成'
}

// 启动轮询
const startPolling = () => {
  if (pollingTimer) return
  pollingTimer = setInterval(async () => {
    const runningProjects = Object.values(taskStatusMap.value)
      .filter(t => t.status === 'PENDING' || t.status === 'RUNNING')
      .map(t => t.projectName)

    if (runningProjects.length === 0) {
      stopPolling()
      return
    }

    try {
      const res = await taskApi.getStatus(runningProjects)
      if (res.data) {
        res.data.forEach(task => {
          taskStatusMap.value[task.projectName] = task
        })
      }
    } catch (e) {
      console.error('Failed to poll task status:', e)
    }
  }, 20000)
}

// 停止轮询
const stopPolling = () => {
  if (pollingTimer) {
    clearInterval(pollingTimer)
    pollingTimer = null
  }
}

// 生成调用链
const handleGenerateChain = async (row: GitRepositoryInfo) => {
  if (!appStore.projectDirConfigured) {
    ElMessage.warning('请先配置项目目录')
    return
  }

  try {
    const res = await taskApi.startGenerate(row.name)
    if (res.data) {
      taskStatusMap.value[row.name] = res.data
      ElMessage.success('已开始生成调用链')
      startPolling()
    }
  } catch (e) {
    ElMessage.error('启动任务失败')
  }
}

// 获取项目的任务状态
const getProjectTaskStatus = (projectName: string) => {
  return taskStatusMap.value[projectName]?.status
}

// 组件卸载时清理
onUnmounted(() => {
  stopPolling()
})
```

**Step 2: 修改模板添加状态列和按钮**

在 `<el-table>` 中添加状态列:

```vue
<el-table-column label="调用链状态" width="100">
  <template #default="{ row }">
    <el-tag :type="getTaskStatusType(getProjectTaskStatus(row.name))" size="small">
      {{ getTaskStatusText(getProjectTaskStatus(row.name)) }}
    </el-tag>
  </template>
</el-table-column>
```

在操作列中添加生成按钮:

```vue
<el-button
  type="warning"
  link
  @click="handleGenerateChain(row)"
  :loading="getProjectTaskStatus(row.name) === 'RUNNING'"
  :disabled="!appStore.projectDirConfigured"
>
  <el-icon><Connection /></el-icon>
  生成调用链
</el-button>
```

**Step 3: 更新 imports**

添加 `onUnmounted` 到 Vue imports:

```typescript
import { ref, reactive, onMounted, onUnmounted } from 'vue'
```

**Step 4: Commit**

```bash
git add src/views/project/ProjectList.vue
git commit -m "feat(ui): add call chain generation button and status column"
```

---

## Task 10: 集成测试和验证

**Step 1: 构建后端**

```bash
cd hisi-dev-tool
mvn clean package -DskipTests
```

Expected: BUILD SUCCESS

**Step 2: 启动后端服务**

```bash
java -jar target/devTools-1.0.0.jar
```

Expected: 服务启动成功，端口 8080

**Step 3: 启动前端**

```bash
cd hisi-dev-tool-frontend
npm run dev
```

Expected: 前端启动成功

**Step 4: 功能验证**

1. 打开浏览器访问 http://localhost:5173
2. 配置项目目录
3. 点击"扫描仓库"
4. 验证表格显示项目列表
5. 验证"调用链状态"列显示"未生成"
6. 点击"生成调用链"按钮
7. 验证状态变为"生成中"
8. 等待任务完成，验证状态变为"已完成"
9. 验证前端停止轮询

**Step 5: Commit**

```bash
git add -A
git commit -m "test: verify async call chain generation feature"
```

---

## 文件清单总结

### 新增文件

| 文件路径 | 说明 |
|---------|------|
| `src/main/resources/db/migration/V9__create_call_chain_task_table.sql` | 数据库迁移脚本 |
| `src/main/java/com/huawei/hisi/model/CallChainTask.java` | 任务模型类 |
| `src/main/java/com/huawei/hisi/repository/CallChainTaskRepository.java` | 任务数据访问层 |
| `src/main/java/com/huawei/hisi/service/CallChainTaskService.java` | 任务服务接口 |
| `src/main/java/com/huawei/hisi/service/CallChainTaskServiceImpl.java` | 任务服务实现 |
| `src/main/java/com/huawei/hisi/controller/CallChainTaskController.java` | 任务控制器 |
| `src/api/task.ts` | 前端任务 API |

### 修改文件

| 文件路径 | 修改内容 |
|---------|---------|
| `src/main/java/com/huawei/hisi/service/HisiURIMethodChainToDBService.java` | 添加 `chainGenerator(String projectPath)` 方法 |
| `src/main/java/com/huawei/hisi/service/HisiURIMethodChainToDBServiceImpl.java` | 实现新方法 |
| `src/types/callchain.ts` | 添加 `CallChainTask` 类型 |
| `src/views/project/ProjectList.vue` | 添加状态列、生成按钮、轮询逻辑 |