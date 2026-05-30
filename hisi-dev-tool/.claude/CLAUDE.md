# 🔍 Worktree Mission: semantic-search

> **本 worktree 职责**：适配 `HybridSearchService` / `VectorSearchController` / `QueryTypeDetector` 以支持多语言（Java + Python）+ 公共图谱范围（`publicProjectPath`）检索。不修改 KG 生成侧代码。

---

# HiSi DevTool Backend

## Project Overview

**Version**: 4.x (主线进入 Python 支持 + 公共知识图谱阶段，目标 tag `v4.1.0-python-support`)
**Tech Stack**: Spring Boot 3.2.0 + Java 17 + Spring Data Neo4j 7.x + Neo4j 5.11+ + 智谱 AI（embedding-3 / glm-4-flash）+ SQLite（本地会话/任务存储）
**Purpose**: 面向开发者的代码理解与运维平台 —— 知识图谱（Java/Python）、语义/混合检索、调用链与影响分析、日志诊断、批量 Git 仓库操作。

> **重要更正**：CLAUDE.md 历史版本写的 "OpenGauss/MySQL" 已作废。当前架构以 **Neo4j 5.11+（图 + 原生 VECTOR INDEX）** 为主存储，本地元数据走 SQLite；不再依赖关系型数据库。

## 核心架构事实（防误判，必读）

| 项 | 值 |
|---|---|
| 主存储 | **Neo4j 5.11+**（节点 + 关系 + **原生 VECTOR INDEX，cosine**） |
| 本地存储 | SQLite（位于 `~/.hisi-devtool/devtool.db`，存会话/任务/缓存元数据） |
| 向量生成 | `EmbeddingService` → `ZhipuService` → 智谱 **`embedding-3` (2048d)** |
| 描述生成 | `ZhipuService` → 智谱 **`glm-4-flash`** |
| 向量存储 | Neo4j 节点属性：`descriptionEmbedding` / `codeEmbedding` / `sqlEmbedding`（**不是 ChromaDB**） |
| 向量检索 | Neo4j 原生 VECTOR INDEX（`HybridSearchService` + `Neo4jMethodNodeRepository.find*ByVectorIndex`） |
| ChromaDB / `hisi-vector-service` | **已与 Java 后端解耦的遗留组件，主链路不调用** |
| Neo4j 启用方式 | `@ConditionalOnProperty(name = "neo4j.uri")`，未配置则 KG/检索功能不启用 |
| Neo4j 节点字段命名 | **camelCase**（`projectPath` / `publicProjectPath` / `language` / `framework` / `descriptionEmbedding` …），Cypher 中也写 camelCase |
| `publicProjectPath` 字段 | 范围分区主键 —— 单项目 = `projectPath`；公共图谱 = 用户选定 rootPath |
| `language` 字段 | `"java"` / `"python"`；旧节点 null 视作 java（向后兼容） |
| 索引/迁移 | 由 `Neo4jInitializer` 在 `ApplicationReadyEvent` 启动时自动执行（含 `publicProjectPath` 索引 + backfill），**无需手动跑脚本** |

## 本 worktree 改动范围

| 文件 | 改动 |
|---|---|
| `neo4j/repository/Neo4jMethodNodeRepository.java` | 新增 `*ByScope` 重载（`coalesce(m.publicProjectPath, m.projectPath) = $scope`） |
| `neo4j/service/HybridSearchService.java` | 新增 `scope` / `language` 参数重载；旧重载委托新重载；language 后过滤 |
| `neo4j/service/QueryTypeDetector.java` | 新增 Python FQN pattern + 放宽 ANNOTATION pattern |
| `neo4j/controller/VectorSearchController.java` | `SearchRequest` DTO 追加 `scope` / `language` 可选字段 |
| `neo4j/model/MethodNode.java` | 已有 `publicProjectPath` / `language` / `framework` 字段（Phase 1 已完成） |
| `neo4j/model/EntryPointNode.java` | 已有 `publicProjectPath` 字段 |

## Code Conventions（同主仓 CLAUDE.md）

- **Neo4j 命名**: 节点字段 camelCase，Cypher 中保持一致
- **范围查询**: `coalesce(n.publicProjectPath, n.projectPath) = $scope`
- **向量检索**: `db.index.vector.queryNodes('idx_name', $topK, $vec)`
- **Embedding 命名**: `descriptionEmbedding` / `codeEmbedding` / `sqlEmbedding`；遗留 `embedding` 字段不再写
- **language 后过滤**: null 视为 java（兼容旧数据）

## Testing

```bash
mvn test -Dtest='HybridSearchServiceTest,QueryTypeDetectorTest,VectorSearchControllerTest'
```

零回归：`mvn -pl hisi-dev-tool test` 全绿。
