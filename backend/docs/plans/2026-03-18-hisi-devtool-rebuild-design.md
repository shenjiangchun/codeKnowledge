# HiSi DevTool 项目重建与前端开发设计方案

**设计日期**: 2026-03-18
**设计版本**: 1.0
**设计状态**: 已确认

## 一、项目概述

### 1.1 项目背景

基于 `codeanalyser.txt` 文档中的 Spring 项目代码导出，逆向还原完整后端项目，并开发配套的前端应用。

### 1.2 项目目标

1. 完整还原后端 Spring Boot 项目
2. 补充关键功能单元测试
3. 生成完整 API 接口文档
4. 开发 Vue 3 前端应用（四大功能模块）
5. 生成项目 `.claude` 说明文件

### 1.3 技术选型

| 层级 | 技术栈 |
|------|--------|
| 后端框架 | Spring Boot 3.2.0 + Java 17 |
| 数据库 | OpenGauss (兼容 PostgreSQL) |
| ORM | JdbcTemplate (原生 JDBC) |
| 前端框架 | Vue 3.4+ + TypeScript 5.x |
| UI组件库 | Element Plus |
| 构建工具 | Vite 5.x |
| 状态管理 | Pinia |
| HTTP客户端 | Axios |

## 二、后端项目结构

```
hisi-dev-tool/
├── pom.xml
├── src/
│   ├── main/
│   │   ├── java/com/huawei/hisi/
│   │   │   ├── DevToolApplication.java
│   │   │   ├── config/
│   │   │   ├── controller/
│   │   │   ├── model/
│   │   │   ├── repository/
│   │   │   ├── service/
│   │   │   └── utils/
│   │   └── resources/
│   │       ├── application.yml
│   │       ├── application-prod.yml
│   │       └── db/migration/
│   └── test/
└── start.sh
```

### 2.1 核心模块说明

| 模块 | 功能描述 |
|------|----------|
| **config** | 异步配置、CORS配置、数据源配置、LLM配置、日志云配置 |
| **controller** | REST API 控制器（8个控制器） |
| **service** | 业务服务层（13个服务接口及实现） |
| **model** | 数据模型/DTO（17个模型类） |
| **repository** | 数据访问层 |
| **utils** | 工具类（代码分析、雪花ID、堆栈过滤等） |

## 三、前端项目结构

```
hisi-dev-tool-frontend/
├── package.json
├── vite.config.ts
├── tsconfig.json
├── src/
│   ├── main.ts
│   ├── App.vue
│   ├── api/                    # API 接口封装
│   ├── views/                  # 页面组件
│   │   ├── log-analysis/       # 日志分析模块
│   │   ├── call-chain/         # 调用链分析模块
│   │   ├── project/            # 项目管理模块
│   │   └── ops/                # 运维监控模块
│   ├── components/             # 公共组件
│   ├── stores/                 # Pinia 状态管理
│   ├── router/                 # 路由配置
│   ├── styles/                 # 样式文件
│   └── utils/                  # 工具函数
└── .env.development
```

### 3.1 四大功能模块

| 模块 | 页面 | 功能 |
|------|------|------|
| **日志分析** | LogQuery.vue | 日志查询 |
| | AnalyzeTask.vue | 提交分析任务 |
| | ReportDetail.vue | 查看分析报告 |
| **调用链分析** | ProjectList.vue | 项目列表选择 |
| | UriList.vue | URI列表 |
| | CallChainGraph.vue | 调用链可视化 |
| **项目管理** | ProjectList.vue | 项目列表 |
| | CloneProject.vue | 克隆Git仓库 |
| **运维监控** | HealthCheck.vue | 服务健康状态 |
| | ImpactAnalysis.vue | 影响范围分析 |
| | ApiDocs.vue | 接口文档查看 |

## 四、API 接口清单

### 4.1 日志分析模块

| 端点 | 方法 | 功能 |
|------|------|------|
| `/api/log/query` | POST | 查询日志 |
| `/api/log/analyze` | POST | 提交分析任务（异步） |
| `/api/log/reports` | GET | 获取报告列表 |
| `/api/log/report/{id}` | GET | 获取报告详情 |
| `/api/log/report/{id}/status` | GET | 查询任务状态 |

### 4.2 调用链模块

| 端点 | 方法 | 功能 |
|------|------|------|
| `/api/callchain/projects` | GET | 获取项目列表 |
| `/api/callchain/uris` | GET | 获取URI列表 |
| `/api/callchain/calls` | GET | 获取调用链数据 |
| `/api/callchain/search` | GET | 搜索方法或类 |

### 4.3 项目管理模块

| 端点 | 方法 | 功能 |
|------|------|------|
| `/api/projects/list` | GET | 获取项目列表 |
| `/api/projects/clone` | POST | 克隆项目 |
| `/api/projects/status` | GET | 获取项目状态 |

### 4.4 运维模块

| 端点 | 方法 | 功能 |
|------|------|------|
| `/api/ops/health` | GET | 健康检查 |
| `/api/ops/analysis/impact` | POST | 影响范围分析 |
| `/api/ops/docs/interface` | GET | 生成接口文档 |
| `/api/ops/logs/download` | POST | 下载错误日志 |

### 4.5 其他模块

| 端点 | 方法 | 功能 |
|------|------|------|
| `/api/llm/generate` | POST | LLM文本生成 |
| `/api/git/fetch` | POST | 批量克隆仓库 |
| `/api/method_chain/generate` | GET | 生成调用链落表 |
| `/api/description/generate` | GET | 生成调用链描述 |

## 五、实施计划

### 阶段一：后端还原与重构（预计 2-3 小时）

1. **解析脚本开发**
   - 编写 Python 脚本解析 codeanalyser.txt
   - 提取文件路径和内容
   - 按原始目录结构写入文件

2. **项目骨架创建**
   - 创建 hisi-dev-tool 目录
   - 写入 pom.xml（用户提供）
   - 创建标准 Maven 目录结构

3. **源码还原**
   - 逐个还原 Java 源文件（70个文件）
   - 还原配置文件
   - 还原 SQL 迁移脚本
   - 还原测试文件

4. **代码梳理与重构**
   - 分析代码逻辑和结构
   - 提高可读性的适度重构
   - 保持业务逻辑不变

### 阶段二：测试与验证（预计 1 小时）

1. 补充关键功能单元测试
2. 运行 `mvn test` 全量回归
3. 修复发现的问题

### 阶段三：文档生成（预计 30 分钟）

1. 分析所有 Controller 提取接口信息
2. 生成 Markdown 格式 API 文档
3. 生成 .claude 项目说明文件

### 阶段四：前端开发（预计 3-4 小时）

1. 创建 Vue 3 + TypeScript 项目
2. 实现四大功能模块
3. 集成测试与后端联调

## 六、交付物清单

1. **后端项目**
   - `hisi-dev-tool/` 完整 Spring Boot 项目

2. **前端项目**
   - `hisi-dev-tool-frontend/` 完整 Vue 3 项目

3. **文档**
   - `.claude/CLAUDE.md` 项目说明
   - `docs/api-document.md` API 接口文档
   - `docs/frontend-design.md` 前端技术方案

## 七、风险与注意事项

1. **数据库兼容性**：项目使用 OpenGauss，需要确保数据库驱动和 SQL 语法兼容
2. **敏感信息**：配置文件中的密码、API Key 等需要脱敏处理
3. **测试环境**：部分服务依赖外部系统（LLM、日志云），测试时可能需要 Mock
4. **代码重构原则**：不改变业务逻辑，只做可读性优化，不过度拆分

---

**设计确认人**: 用户
**确认时间**: 2026-03-18