# HiSi DevTool API 接口文档

**版本**: 1.0.0
**基础URL**: `http://localhost:8080`

---

## 1. 日志分析模块

### 1.1 查询日志

**POST** `/api/log/query`

查询日志云中的错误日志。

**请求体**:
```json
{
  "appId": "string",           // 应用ID（必填）
  "logLevel": "ERROR",         // 日志级别：ERROR/WARN/INFO/DEBUG
  "keyword": "string",         // 关键词（可选）
  "startTime": "2024-01-01T00:00:00",  // 开始时间（可选）
  "endTime": "2024-01-02T00:00:00"     // 结束时间（可选）
}
```

**响应**:
```json
{
  "code": 200,
  "message": "success",
  "data": {
    "total": 10,
    "logs": [
      {
        "id": "string",
        "level": "ERROR",
        "message": "错误消息内容",
        "timestamp": "2024-01-01T12:00:00",
        "appId": "com.huawei.hiapm",
        "traceId": "string"
      }
    ]
  }
}
```

---

### 1.2 提交分析任务

**POST** `/api/log/analyze`

异步提交日志分析任务，返回任务ID。

**请求体**:
```json
{
  "message": "string",         // 日志消息（必填，与 stackTrace 二选一）
  "stackTrace": "string",      // 堆栈信息（必填，与 message 二选一）
  "errorType": "string",       // 错误类型（可选）
  "traceId": "string",         // 追踪ID（可选）
  "serviceName": "string",     // 服务名（可选）
  "userId": "string"           // 用户ID（可选，默认 sys_admin）
}
```

**响应**:
```json
{
  "code": 200,
  "message": "success",
  "data": {
    "reportId": 123456789,
    "status": "pending",
    "createdAt": "2024-01-01T12:00:00"
  }
}
```

---

### 1.3 获取报告列表

**GET** `/api/log/reports`

获取分析报告列表。

**查询参数**:
| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| userId | string | 否 | 用户ID，默认 sys_admin |
| status | string | 否 | 状态过滤：pending/processing/completed/failed |
| page | int | 否 | 页码，默认 1 |
| pageSize | int | 否 | 每页大小，默认 10 |

**响应**:
```json
{
  "code": 200,
  "message": "success",
  "data": {
    "total": 100,
    "page": 1,
    "pageSize": 10,
    "list": [
      {
        "reportId": 123456789,
        "status": "completed",
        "errorType": "NullPointerException",
        "createdAt": "2024-01-01T12:00:00",
        "updatedAt": "2024-01-01T12:05:00"
      }
    ]
  }
}
```

---

### 1.4 获取报告详情

**GET** `/api/log/report/{id}`

获取分析报告的详细内容。

**路径参数**:
| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| id | long | 是 | 报告ID |

**响应**:
```json
{
  "code": 200,
  "message": "success",
  "data": {
    "reportId": 123456789,
    "status": "completed",
    "errorSummary": "空指针异常导致服务崩溃",
    "rootCause": "在 UserService.getUser() 方法中，userId 参数为 null",
    "fixSuggestions": "1. 添加参数校验\n2. 使用 Optional 包装返回值",
    "codeSnippets": "// 建议代码片段...",
    "createdAt": "2024-01-01T12:00:00",
    "updatedAt": "2024-01-01T12:05:00"
  }
}
```

---

### 1.5 查询任务状态

**GET** `/api/log/report/{id}/status`

查询分析任务的处理状态。

**响应**:
```json
{
  "code": 200,
  "message": "success",
  "data": {
    "reportId": 123456789,
    "status": "processing",
    "createdAt": "2024-01-01T12:00:00",
    "updatedAt": "2024-01-01T12:01:00"
  }
}
```

---

## 2. 调用链模块

### 2.1 获取项目列表

**GET** `/api/callchain/projects`

获取可用于调用链分析的项目列表。

**响应**:
```json
{
  "code": 200,
  "message": "success",
  "data": ["project-a", "project-b", "project-c"]
}
```

---

### 2.2 获取 URI 列表

**GET** `/api/callchain/uris`

获取指定项目下的所有 URI 列表。

**查询参数**:
| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| project | string | 是 | 项目名称 |

**响应**:
```json
{
  "code": 200,
  "message": "success",
  "data": [
    "/api/user/list",
    "/api/user/detail",
    "/api/order/create"
  ]
}
```

---

### 2.3 获取调用链数据

**GET** `/api/callchain/calls`

获取指定 URI 的完整调用链数据。

**查询参数**:
| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| uri | string | 是 | URI 路径 |

**响应**:
```json
{
  "code": 200,
  "message": "success",
  "data": [
    {
      "name": "UserController.list()",
      "type": "method",
      "children": [
        {
          "name": "UserService.list()",
          "type": "method"
        }
      ]
    }
  ]
}
```

---

### 2.4 搜索方法或类

**GET** `/api/callchain/search`

在代码库中搜索方法或类。

**查询参数**:
| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| q | string | 是 | 搜索关键词 |
| project | string | 否 | 项目名称（可选） |

**响应**:
```json
{
  "code": 200,
  "message": "success",
  "data": [
    {
      "name": "UserService",
      "type": "class",
      "file": "src/main/java/.../UserService.java",
      "line": 15
    }
  ]
}
```

---

## 3. 项目管理模块

### 3.1 获取项目列表

**GET** `/api/projects/list`

获取已克隆的项目列表。

**响应**:
```json
{
  "code": 200,
  "message": "success",
  "data": ["project-a", "project-b"]
}
```

---

### 3.2 克隆项目

**POST** `/api/projects/clone`

从 Git 仓库克隆项目。

**请求体**:
```json
{
  "repository": "git@codehub.huawei.com:group/project.git",
  "branch": "master"
}
```

**响应**:
```json
{
  "code": 200,
  "message": "success",
  "data": {
    "success": true,
    "project": "project-name",
    "path": "/path/to/project"
  }
}
```

---

### 3.3 获取项目状态

**GET** `/api/projects/status`

获取项目的克隆状态和分析进度。

**查询参数**:
| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| project | string | 是 | 项目名称 |

**响应**:
```json
{
  "code": 200,
  "message": "success",
  "data": {
    "name": "project-name",
    "status": "ready",
    "branch": "master",
    "lastCommit": "abc123",
    "analysisProgress": 100
  }
}
```

---

## 4. 运维监控模块

### 4.1 健康检查

**GET** `/api/ops/health`

检查服务健康状态。

**响应**:
```json
{
  "code": 200,
  "message": "success",
  "data": {
    "status": "UP",
    "services": [
      {
        "name": "database",
        "healthy": true,
        "responseTime": 5
      },
      {
        "name": "logCloud",
        "healthy": true,
        "responseTime": 120
      }
    ],
    "resources": {
      "cpu": 45.5,
      "memory": 62.3,
      "disk": 35.8
    }
  }
}
```

---

### 4.2 影响范围分析

**POST** `/api/ops/analysis/impact`

分析代码变更的影响范围。

**请求体**:
```json
{
  "project": "string",       // 项目名称（必填）
  "type": "class",           // 分析类型：class/method/interface
  "targets": ["UserService", "OrderService"]  // 目标对象列表
}
```

**响应**:
```json
{
  "code": 200,
  "message": "success",
  "data": {
    "results": [
      {
        "target": "UserService",
        "type": "class",
        "impactCount": 15,
        "impactedFiles": [
          "UserController.java",
          "OrderService.java"
        ]
      }
    ]
  }
}
```

---

### 4.3 生成接口文档

**GET** `/api/ops/docs/interface`

为指定接口生成文档。

**查询参数**:
| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| uri | string | 是 | 接口 URI |

**响应**:
```json
{
  "code": 200,
  "message": "success",
  "data": {
    "uri": "/api/log/analyze",
    "method": "POST",
    "description": "提交日志分析任务",
    "requestBody": {...},
    "responseBody": {...}
  }
}
```

---

### 4.4 下载错误日志

**POST** `/api/ops/logs/download`

下载指定时间范围的错误日志。

**请求体**:
```json
{
  "service": "string",       // 服务名称
  "timeRange": "24h",        // 时间范围
  "level": "ERROR"           // 日志级别
}
```

---

## 5. LLM 模块

### 5.1 文本生成

**POST** `/api/llm/generate`

调用大语言模型生成文本。

**查询参数**:
| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| userInput | string | 是 | 用户输入文本 |

**响应**:
```json
{
  "code": 200,
  "message": "success",
  "data": "大模型生成的回复内容..."
}
```

---

## 6. Git 批量操作模块

### 6.1 批量克隆仓库

**POST** `/api/git/fetch`

批量克隆多个 Git 仓库。

**请求体**:
```json
[
  {
    "repository": "git@codehub.huawei.com:group/project-a.git",
    "branch": "master",
    "directory": "project-a"
  },
  {
    "repository": "git@codehub.huawei.com:group/project-b.git",
    "branch": "develop",
    "directory": "project-b"
  }
]
```

**响应**:
```json
{
  "success": true,
  "results": [
    {
      "repository": "git@codehub.huawei.com:group/project-a.git",
      "status": "cloned",
      "path": "/path/to/project-a"
    }
  ]
}
```

---

## 7. 公共知识图谱模块

### 7.1 扫描工作区服务

**POST** `/api/knowledge-graph/public/scan`

扫描工作区根路径，识别服务清单（语言、框架、构建文件）。

**请求体**:
```json
{ "rootPath": "/path/to/workspace" }
```

**响应**:
```json
{
  "success": true,
  "data": [
    { "path": "/path/to/workspace/svc-a", "language": "java", "framework": "spring", "buildFile": "pom.xml" },
    { "path": "/path/to/workspace/svc-b", "language": "python", "framework": "fastapi", "buildFile": "pyproject.toml" }
  ]
}
```

---

### 7.2 生成公共知识图谱

**POST** `/api/knowledge-graph/public/generate`

为选定的服务生成公共知识图谱。

**请求体**:
```json
{
  "rootPath": "/path/to/workspace",
  "selectedServicePaths": ["/path/to/workspace/svc-a", "/path/to/workspace/svc-b"]
}
```

**响应**:
```json
{ "success": true, "data": { "taskId": 42 } }
```

---

### 7.3 增量刷新知识图谱

**POST** `/api/knowledge-graph/refresh`

基于 Git 变更增量刷新知识图谱。

**请求体**:
```json
{
  "projectPath": "/path/to/project",
  "publicProjectPath": "/path/to/workspace"
}
```

**响应（成功）**:
```json
{
  "success": true,
  "data": { "noop": false, "changedFiles": 3, "deleted": 3, "rebuilt": 3 }
}
```

**错误码**:
| 错误码 | 说明 |
|--------|------|
| 400 | 缺少 projectPath 参数 |
| 409 | 未找到检查点（需先执行完整生成） |
| 412 | 工作目录存在未提交的变更 |

---

### 7.4 跨服务链接策略

公共知识图谱生成完成后，系统自动执行以下 4 种链接策略：

1. **HttpRestLinkStrategy** — 匹配 HTTP 出站调用与跨服务 HTTP 入口点
2. **MqLinkStrategy** — 按 topic 匹配 MQ 生产者与消费者
3. **OpenApiLinkStrategy** — （预留：未来 OpenAPI 规范链接）
4. **GrpcLinkStrategy** — （预留：未来 gRPC 服务链接）

---

## 通用响应格式

所有接口均采用统一的响应格式：

```json
{
  "code": 200,           // 状态码：200 成功，其他为错误
  "message": "success",  // 响应消息
  "data": {}             // 响应数据（成功时）
}
```

**错误响应示例**:
```json
{
  "code": 400,
  "message": "请求参数不能为空",
  "data": null
}
```

---

## 错误码说明

| 错误码 | 说明 |
|--------|------|
| 200 | 成功 |
| 400 | 请求参数错误 |
| 404 | 资源不存在 |
| 500 | 服务器内部错误 |