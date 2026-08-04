# MCP 工具使用指南

## 概述

本文档描述如何安装、配置和使用 HiSi 知识图谱 MCP Server，以及 4 个业务流 Skill 的使用方法。

---

## 一、MCP Server 安装配置

### 1.1 环境要求

- Node.js 18+
- npm 或 yarn
- Spring Boot 服务运行在 http://localhost:8080
- Python 向量服务运行在 http://localhost:5000 (可选)

### 1.2 安装步骤

```bash
cd hisi-mcp-server
npm install
npm run build
```

### 1.3 配置

环境变量:
- `HISI_API_URL` - Spring Boot API 地址，默认 http://localhost:8080
- `HISI_DEBUG` - 启用调试模式，设置为 true 开启

### 1.4 启动

```bash
npm start
# 或
HISI_API_URL=http://localhost:8080 npm start
```

---

## 二、MCP 工具列表 (29个)

### 2.1 知识图谱工具 (25个)

#### 图谱管理
| 工具名 | 描述 | 必要参数 |
|--------|------|----------|
| `kg_generate` | 同步生成知识图谱 | projectPath |
| `kg_status` | 获取图谱状态 | projectPath |
| `kg_task_status` | 查询任务状态 | projectPaths |

#### 方法查询
| 工具名 | 描述 | 必要参数 |
|--------|------|----------|
| `kg_callers` | 查询调用者 | className, methodName, projectPath |
| `kg_callees` | 查询被调用者 | className, methodName, projectPath |
| `kg_method_detail` | 查询方法详情 | nodeId, projectPath |
| `kg_method_by_class` | 按类查询方法 | className, projectPath |

#### 入口点查询
| 工具名 | 描述 | 必要参数 |
|--------|------|----------|
| `kg_entry_points` | 查询入口点列表 | projectPath |
| `kg_call_chain_by_key` | 按入口键查询调用链 | entryKey, projectPath |
| `kg_call_chain_by_type` | 按入口类型查询调用链 | entryType, projectPath |

#### 调用链分析
| 工具名 | 描述 | 必要参数 |
|--------|------|----------|
| `kg_downstream` | 向下调用链追踪 | nodeId, projectPath |
| `kg_call_chain_graph` | DAG 图数据 | entryKey, projectPath |
| `kg_affecting` | 影响分析 | nodeId, projectPath |
| `kg_bridges` | 方法桥接信息 | nodeId, projectPath |

#### 接口实现
| 工具名 | 描述 | 必要参数 |
|--------|------|----------|
| `kg_implementations` | 查询接口实现 | interfaceName, projectPath |
| `kg_interfaces` | 查询所有接口 | projectPath |

#### 环检测
| 工具名 | 描述 | 必要参数 |
|--------|------|----------|
| `kg_cycles_detect` | 检测调用环 | projectPath |

#### MyBatis
| 工具名 | 描述 | 必要参数 |
|--------|------|----------|
| `kg_mybatis_mappers` | Mapper 列表 | projectPath |
| `kg_mybatis_sql` | SQL 列表 | projectPath |
| `kg_mapper_sql` | Mapper 的 SQL | mapperInterface, projectPath |

#### 桥接查询
| 工具名 | 描述 | 必要参数 |
|--------|------|----------|
| `kg_feign_chain` | Feign 调用链 | serviceName, projectPath |
| `kg_mq_chain` | MQ 调用链 | topic, projectPath |
| `kg_bridge_stats` | 桥接统计 | projectPath |

#### 业务流程生成
| 工具名 | 描述 | 必要参数 |
|--------|------|----------|
| `kg_business_flow` | 生成业务流程文档 | entryKey, projectPath |
| `kg_unit_test` | 生成单元测试 | nodeId, projectPath |

### 2.2 向量搜索工具 (3个)

| 工具名 | 描述 | 必要参数 |
|--------|------|----------|
| `vector_search` | 向量相似度搜索 | query |
| `vector_sync` | 手动触发向量化 | projectName, projectPath |
| `vector_status` | 向量库状态 | 无 |

### 2.3 日志查询工具 (1个)

| 工具名 | 描述 | 必要参数 |
|--------|------|----------|
| `log_query` | DSL 日志查询 | dsl |

---

## 三、Skill 使用指南

### 3.1 Git 代码审查 (`/git-review`)

**用途**: 审查 Git 提交的代码变更，分析影响范围。

**输入**: commit SHA 或 commit 范围

**示例**:
```
/git-review HEAD~1
/git-review abc123..def456
```

**输出**:
- 变更摘要
- 影响范围分析
- 测试回归范围
- 技术规范检查
- 业务流变化

---

### 3.2 错误日志分析 (`/error-analysis`)

**用途**: 分析错误日志，定位问题根因。

**输入**: 错误日志内容（含时间戳、容器、堆栈）

**示例**:
```
/error-analysis
粘贴错误日志...
```

**输出**:
- 错误定位
- 调用链上下文
- 相关变量/参数
- 根因分析
- 修复建议

---

### 3.3 接口业务流分析 (`/interface-flow`)

**用途**: 分析接口的完整业务流程。

**输入**: 接口 URI 或方法签名

**示例**:
```
/interface-flow POST /api/log/analyze
/interface-flow LogAnalysisController.analyze
```

**输出**:
- 业务流程文档
- 用户选择:
  - A. 测试代码生成
  - B. 安全重构

---

### 3.4 技术方案设计 (`/tech-design`)

**用途**: 根据需求生成完整技术方案。

**输入**: 需求描述（自然语言）

**示例**:
```
/tech-design
实现一个用户登录功能，支持用户名密码登录和手机验证码登录
```

**输出**:
- 需求理解
- 现有代码分析
- 设计方案
- 影响范围
- 测试回归范围
- 实施步骤

---

## 四、Python 向量服务

### 4.1 启动

```bash
cd hisi-vector-service
pip install -r requirements.txt
python app.py
```

### 4.2 API 端点

| 方法 | 端点 | 描述 |
|------|------|------|
| GET | /api/vector/status | 服务状态 |
| POST | /api/vector/search | 向量搜索 |
| POST | /api/vector/add | 添加向量 |
| DELETE | /api/vector/delete | 删除向量 |
| POST | /api/vector/sync | 同步源代码 |
