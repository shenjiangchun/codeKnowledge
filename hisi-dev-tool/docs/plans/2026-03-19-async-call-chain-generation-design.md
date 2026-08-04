# 异步调用链生成功能设计文档

> **日期**: 2026-03-19
> **作者**: Claude

## 概述

为项目列表中的每个项目添加调用链生成按钮和状态显示，将现有的同步调用链生成改造为异步任务，支持前端轮询查询任务状态。

## 需求

1. 每个项目独立执行调用链生成
2. 失败的项目不影响其他项目
3. 不需要取消功能
4. 前端每 20 秒轮询状态直到所有任务完成

## 架构

```
┌─────────────────────────────────────────────────────────────────┐
│                         Frontend (Vue 3)                         │
├─────────────────────────────────────────────────────────────────┤
│  ProjectList.vue                                                 │
│  - 每行项目新增 [生成调用链] 按钮                                 │
│  - 状态列: 未生成/生成中/已完成/失败                              │
│  - 轮询逻辑: 当有任务执行中时，每20秒调用 /tasks/status           │
└─────────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌─────────────────────────────────────────────────────────────────┐
│                     Backend (Spring Boot)                        │
├─────────────────────────────────────────────────────────────────┤
│  CallChainTaskController                                         │
│  - POST /api/tasks/generate/{projectName}  启动任务              │
│  - GET  /api/tasks/status?projects=xxx     查询状态              │
│                                                                  │
│  CallChainTaskService                                            │
│  - submitTask(projectName) → 异步执行                            │
│  - getTaskStatus(projectNames) → 批量查询                        │
│                                                                  │
│  @Async 线程池执行，状态写入数据库                                │
└─────────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌─────────────────────────────────────────────────────────────────┐
│                     Database (OpenGauss)                         │
├─────────────────────────────────────────────────────────────────┤
│  call_chain_task 表                                              │
│  - id, project_name, status, start_time, end_time, error_msg    │
└─────────────────────────────────────────────────────────────────┘
```

## 数据库设计

```sql
-- V9__create_call_chain_task_table.sql

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

CREATE INDEX idx_task_project ON call_chain_task(project_name);
CREATE INDEX idx_task_status ON call_chain_task(status);
```

**状态流转:**
```
PENDING → RUNNING → COMPLETED
                  ↘ FAILED
```

## API 设计

### 后端 API

| 方法 | 端点 | 说明 |
|------|------|------|
| POST | `/api/tasks/generate/{projectName}` | 启动调用链生成任务 |
| GET | `/api/tasks/status?projects=xxx` | 批量查询任务状态 |
| GET | `/api/tasks/latest/{projectName}` | 获取单个项目最新任务 |

### 返回数据结构

```java
@Data
@Builder
public class CallChainTaskDTO {
    private Long id;
    private String projectName;
    private String projectPath;
    private String status;        // PENDING/RUNNING/COMPLETED/FAILED
    private LocalDateTime startTime;
    private LocalDateTime endTime;
    private String errorMessage;
    private Integer recordsProcessed;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
```

## 前端改造

### 新增文件

- `src/api/task.ts` - 任务相关 API

### 修改文件

- `src/types/callchain.ts` - 新增 `CallChainTask` 类型
- `src/views/project/ProjectList.vue` - 新增状态列、生成按钮、轮询逻辑

### 轮询逻辑

```typescript
// 启动轮询
const startPolling = () => {
  pollingTimer = setInterval(async () => {
    const runningProjects = Object.values(taskStatusMap)
      .filter(t => t.status === 'PENDING' || t.status === 'RUNNING')
      .map(t => t.projectName)

    if (runningProjects.length === 0) {
      stopPolling()
      return
    }

    const res = await taskApi.getStatus(runningProjects)
    // 更新状态...
  }, 20000)
}
```

## 现有代码改造

### HisiURIMethodChainToDBService

新增重载方法，支持传入项目路径:

```java
void chainGenerator() throws Exception;           // 保留兼容
void chainGenerator(String projectPath) throws Exception;  // 新增
```

### 实现要点

- 保留现有 `/api/method_chain/generate` 端点兼容性
- 新方法使用参数路径替代全局 PROJECT_DIR
- 异步执行时调用新方法

## 文件清单

### 后端新增

```
src/main/java/com/huawei/hisi/
├── controller/
│   └── CallChainTaskController.java
├── model/
│   └── CallChainTaskDTO.java
├── service/
│   ├── CallChainTaskService.java
│   └── CallChainTaskServiceImpl.java
└── repository/
    └── CallChainTaskRepository.java

src/main/resources/db/migration/
└── V9__create_call_chain_task_table.sql
```

### 后端修改

```
src/main/java/com/huawei/hisi/service/
└── HisiURIMethodChainToDBServiceImpl.java
```

### 前端新增

```
src/api/task.ts
```

### 前端修改

```
src/types/callchain.ts
src/views/project/ProjectList.vue
```

## 验证方案

1. 启动后端服务
2. 配置项目目录
3. 扫描仓库，显示项目列表
4. 点击"生成调用链"按钮
5. 验证状态从 PENDING → RUNNING → COMPLETED
6. 验证前端轮询更新状态
7. 模拟失败场景，验证 FAILED 状态