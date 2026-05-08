# 调用链状态按项目路径去重设计

## Context

**问题描述**：同一个项目在不同文件目录下，在其中一个项目生成调用链后，扫描另一个目录下的同名项目时，发现其调用链状态也是"已生成"。调用链状态应该按项目路径去重，而不是按项目名称。

**根本原因**：
- 前端 `taskStatusMap` 使用 `projectName` 作为存储键
- 后端API也按 `projectName` 查询任务状态
- `CallChainTask` 表已有 `projectPath` 字段，但未被用作唯一标识

**影响**：两个同名项目存在于不同目录时（如 `D:\path1\my-project` 和 `D:\path2\my-project`），它们共享调用链状态。

## Solution

前后端统一使用 `projectPath`（项目完整路径）作为唯一标识。

## Backend Changes

### API Interface

**改动文件**：`TaskController.java`

**当前接口**：
```
POST /tasks/generate/{projectName}
GET  /tasks/status?projects=name1,name2
GET  /tasks/latest/{projectName}
```

**改为**：
```
POST /tasks/generate?projectPath=D:\path\my-project
GET  /tasks/status?projectPaths=D:\path1\proj1,D:\path2\proj2
GET  /tasks/latest?projectPath=D:\path\my-project
```

### Service Layer

**改动文件**：`TaskService.java`

- `findByProjectPath(String projectPath)` - 按路径查询
- `startGenerate(String projectPath)` - 按路径创建任务

### Database

CallChainTask 表已有 `project_path` 字段，无需修改表结构。

```sql
SELECT * FROM call_chain_task WHERE project_path = ? ORDER BY created_at DESC LIMIT 1
```

## Frontend Changes

### API Module

**改动文件**：`src/api/task.ts`

```typescript
// 当前
startGenerate(projectName: string)
getStatus(projects?: string[])
getLatest(projectName: string)

// 改为
startGenerate(projectPath: string)
getStatus(projectPaths?: string[])
getLatest(projectPath: string)
```

### ProjectList Component

**改动文件**：`src/views/project/ProjectList.vue`

**改动点**：

1. taskStatusMap 存储键：`row.name` → `row.path`
2. API调用：`taskApi.startGenerate(row.path)`
3. 状态查询：`getProjectTaskStatus(row.path)`
4. 轮询匹配：用 `task.projectPath` 匹配更新

**关键代码改动**：
```typescript
// 存储任务状态
taskStatusMap.value[row.path] = task

// 查询任务状态
const getProjectTaskStatus = (projectPath: string) => {
  return taskStatusMap.value[projectPath]?.status
}

// API调用
const task = await taskApi.startGenerate(row.path)
```

## Data Flow

```
用户点击"生成调用链"
    ↓
前端调用 taskApi.startGenerate(row.path)
    ↓
后端按 projectPath 创建任务，存入 call_chain_task 表
    ↓
前端轮询 taskApi.getStatus(projectPaths)
    ↓
后端按 projectPath 查询任务状态返回
    ↓
前端按 row.path 匹配更新 taskStatusMap
    ↓
UI 显示对应项目的调用链状态
```

## Verification

1. 在两个不同目录下准备同名项目
2. 在第一个目录的项目生成调用链
3. 切换 projectDir 配置，扫描第二个目录
4. 验证同名项目的调用链状态为"未生成"
5. 在第二个目录生成调用链，验证状态独立更新

## Files Changed

| 层 | 文件 | 改动内容 |
|----|------|----------|
| 前端API | `src/api/task.ts` | 参数改为 projectPath |
| 前端组件 | `src/views/project/ProjectList.vue` | 存储键改为 row.path |
| 后端Controller | `TaskController.java` | 接口参数改为 projectPath |
| 后端Service | `TaskService.java` | 查询方法按路径查询 |