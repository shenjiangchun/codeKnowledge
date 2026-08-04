# HiSi DevTool Backend CodeWiki

> 自动生成于 2026-05-08，基于 `main` 分支当前 HEAD 版本
> 本手册由 codewiki-generator 技能自动产出，如需更新请重新运行该技能

---

## 项目简介

HiSi DevTool Backend 是面向开发者的**代码理解与运维平台**后端服务，核心能力包括：基于 Neo4j 的代码知识图谱（Java + Python）、向量 + 全文 + 图三层混合检索、调用链与影响分析、Claude CLI 终端会话编排、日志诊断与根因分析。是 hisi 开发工具套件（前端 `hisi-dev-tool-frontend`、MCP 服务 `hisi-mcp-server`）的核心后端。

---

## 项目速览

| 维度 | 信息 |
|------|------|
| **项目类型** | Spring Boot 单体后端服务（多功能 DevTool 平台） |
| **技术栈** | Java 17 + Spring Boot 3.2.0 + Spring Data Neo4j + Neo4j 5.11+ + SQLite + WebSocket + PTY4J + Playwright + ANTLR4 + JavaParser |
| **架构风格** | 分层单体 + 模块化包（`controller / service / scanner / knowledgegraph / neo4j / agent / handler`） |
| **代码规模** | 约 278 个 Java 源文件（`src/main/java`） |
| **模块数量** | 10 个核心功能模块 |
| **API 数量** | 约 162 个 REST 端点（23 个 Controller）+ Terminal WebSocket |
| **存储** | Neo4j 5.11+（图 + 原生 VECTOR INDEX）+ SQLite（本地会话/任务/缓存） |
| **AI 能力** | OpenAI 兼容 `/embeddings` + `/chat/completions`，默认使用 Qwen3-VL-Embedding-8B（4096 维）+ glm-4-flash |
| **部署方式** | 可执行 jar（`mvn package` + `java -jar`），默认端口 8080 |

---

## 目录

### 理解项目（入门）

| 章节 | 说明 | 适合谁 |
|------|------|--------|
| [项目概览](01-项目概览/index.md) | 使命、技术栈、快速启动、核心概念 | 新成员 |
| [术语表](09-术语表/index.md) | 项目专有名词、领域概念 | 所有读者 |

### 理解设计（深入）

| 章节 | 说明 | 适合谁 |
|------|------|--------|
| [架构设计](02-架构设计/index.md) | 分层、C4 图、技术选型、质量属性 | 架构师 |
| [技术决策](08-技术决策/index.md) | ADR 决策记录、方案对比 | 架构师、负责人 |

### 开发参考（实操）

| 章节 | 说明 | 适合谁 |
|------|------|--------|
| [模块说明](03-模块说明/) | 各功能模块的职责、接口、内部结构 | 开发者 |
| [数据流程](04-数据流程/index.md) | 知识图谱构建/混合检索/终端会话/日志诊断 端到端流 | 开发者、测试 |
| [接口文档](05-接口文档/index.md) | 23 个 Controller 的 REST + WebSocket 接口 | 前后端开发者 |
| [数据模型](06-数据模型/index.md) | Neo4j 图节点、SQLite 表、DTO 定义 | 开发者 |

### 运维部署（运营）

| 章节 | 说明 | 适合谁 |
|------|------|--------|
| [部署运维](07-部署运维/index.md) | 环境变量、Neo4j 准备、构建启动 | 运维、DevOps |

---

## 模块列表

| # | 模块 | 文件 | 关键词 | 层级 |
|---|------|------|--------|------|
| 1 | 应用启动与全局配置 | [应用启动与全局配置.md](03-模块说明/应用启动与全局配置.md) | Spring Boot 入口、CORS、Neo4j、代理、WebSocket、异常处理 | 基础设施 |
| 2 | REST 接口层 | [REST接口层.md](03-模块说明/REST接口层.md) | 23 个 Controller、162 个端点、ApiResponse 包装 | 表现层 |
| 3 | 知识图谱构建 | [知识图谱构建.md](03-模块说明/知识图谱构建.md) | JavaParser、Python ANTLR4、扫描器、增量更新 | 应用层 |
| 4 | Neo4j 图存储与检索 | [Neo4j图存储与检索.md](03-模块说明/Neo4j图存储与检索.md) | MethodNode、混合检索、向量索引、RRF | 服务层 |
| 5 | 终端与 WebSocket | [终端与WebSocket.md](03-模块说明/终端与WebSocket.md) | PTY4J、Claude CLI、TerminalWebSocketHandler | 表现层 |
| 6 | 智能诊断与对话 | [智能诊断与对话.md](03-模块说明/智能诊断与对话.md) | DiagnosticAgent、Intent、DialogContext | 应用层 |
| 7 | 影响分析与风险评估 | [影响分析与风险评估.md](03-模块说明/影响分析与风险评估.md) | impact / risk / suggestion / semantic | 服务层 |
| 8 | 会话与工作区 | [会话与工作区.md](03-模块说明/会话与工作区.md) | ClaudeSession、WorkspaceSession、SQLite 持久化 | 服务层 |
| 9 | 日志云与运维 | [日志云与运维.md](03-模块说明/日志云与运维.md) | LogCloudService、Playwright、根因分析 | 服务层 |
| 10 | 技能市场与提示词 | [技能市场与提示词.md](03-模块说明/技能市场与提示词.md) | SkillService、PromptService、codeai-skills 资源 | 应用层 |

---

## 推荐阅读路径

| 角色 | 路径 |
|------|------|
| 新成员入职 | 01-项目概览 → 09-术语表 → 02-架构设计 → 03-模块说明（按所属包） |
| 知识图谱开发 | 03-模块说明/知识图谱构建 → 03-模块说明/Neo4j图存储与检索 → 04-数据流程 |
| 接入新 LLM | 03-模块说明/应用启动与全局配置（embedding / text-model 段） → 08-技术决策（OpenAI 兼容协议） |
| Bug 修复 | 04-数据流程 → 03-模块说明（相关模块） → 07-部署运维（日志） |
| 接口对接 | 05-接口文档 → 06-数据模型 |

---

## 技术栈标签云

```
Java 17 · Spring Boot 3.2.0 · Spring Data Neo4j · Neo4j 5.11+ · SQLite · Lombok ·
JavaParser · ANTLR4 · PTY4J · Playwright · Jsoup · OkHttp · Caffeine · Reactor ·
ONNX Runtime · DJL Tokenizers · WebSocket · JaCoCo · TestContainers
```

---

## 变更记录

| 日期 | 版本 | 变更内容 |
|------|------|---------|
| 2026-05-08 | v1.0 | 初始生成（基于 codewiki-generator 技能） |

---

> **延伸阅读**：
> - 快速了解项目 → [01-项目概览](01-项目概览/index.md)
> - 理解系统设计 → [02-架构设计](02-架构设计/index.md)
> - 查阅接口定义 → [05-接口文档](05-接口文档/index.md)
