# HiSi DevTool Backend

> Last verified against commit `0b82da1` on 2026-05-08. If architecture changes, re-verify.

开发者工具后端服务，提供日志分析、调用链追踪、知识图谱和运维监控功能。

## 技术栈

- **Java 17** + **Spring Boot 3.2.0**
- **Neo4j 5.11+** — 知识图谱 + 原生向量索引（VECTOR INDEX, cosine）
- **SQLite** — 本地会话/任务元数据（`~/.hisi-devtool/devtool.db`）
- **智谱 AI** — embedding-3 (2048d) 向量生成 + glm-4-flash 描述生成
- **Spring Data Neo4j 7.x** — Neo4j 集成
- **ANTLR4** — Java/Python 源码 AST 解析（知识图谱构建）
- **PTY4J** — 嵌入式伪终端（Claude Terminal）

## 快速开始

### 1. 环境准备

#### 1.1 安装 Neo4j

**推荐使用 Neo4j Desktop**:

1. 下载安装 [Neo4j Desktop](https://neo4j.com/download/)
2. 创建本地数据库，设置密码
3. **安装必需插件**:
   - 打开数据库 → 点击 **"Plugins"** 选项卡
   - 安装 **APOC** 插件
   - 安装 **Graph Data Science (GDS)** 插件
4. 启动数据库

#### 1.2 配置数据库连接

编辑 `src/main/resources/application.yml`:

```yaml
# Neo4j 配置
neo4j:
  uri: neo4j://127.0.0.1:7687
  username: neo4j
  password: your_password
```

或使用环境变量:

```powershell
# PowerShell
$env:NEO4J_PASSWORD = "your_neo4j_password"
```

### 2. 编译运行

```bash
# 编译
mvn clean package -DskipTests

# 运行
java -jar target/devTools-1.0.0.jar

# 或使用 Maven
mvn spring-boot:run
```

**默认端口**: 8080

### 3. 验证 Neo4j 初始化

应用启动时会自动初始化 Neo4j 约束和索引，查看日志确认:

```
开始初始化 Neo4j 约束和索引...
创建唯一性约束...
创建存在性约束...
创建向量索引...
创建全文索引...
创建范围索引...
Neo4j 初始化完成: 成功=20, 失败=0
APOC 版本: 5.x.x
GDS 版本: 2.x.x
```

也可手动在 Neo4j Browser (http://localhost:7474) 中验证:

```cypher
// 验证插件
RETURN apoc.version(), gds.version();

// 查看约束
SHOW CONSTRAINTS;

// 查看索引
SHOW INDEXES;
```

---

## 项目结构

```
src/main/java/com/huawei/hisi/
├── config/              # 配置类 (CORS, DataSource, Neo4j)
├── controller/          # REST API 端点
├── model/               # DTO 和实体类
├── repository/          # 数据访问层
├── service/             # 业务逻辑
├── neo4j/               # Neo4j 知识图谱模块
│   ├── config/          # Neo4j 配置和初始化
│   ├── controller/      # 图谱 API
│   ├── model/           # 图谱节点实体
│   ├── repository/      # 图谱 Repository
│   └── service/         # 图谱服务
├── knowledgegraph/      # 知识图谱扫描模块
├── callchain/           # 调用链分析模块
└── utils/               # 工具类
```

---

## API 模块

### 1. 知识图谱模块 (`/api/knowledge-graph/`)

| 端点 | 方法 | 说明 |
|------|------|------|
| `/scan` | POST | 扫描项目生成知识图谱 |
| `/status/{taskId}` | GET | 查询扫描任务状态 |
| `/call-chain/upstream` | GET | 向上调用链查询 |
| `/call-chain/downstream` | GET | 向下调用链查询 |
| `/call-chain/graph` | GET | DAG 图数据 |
| `/cycles/detect` | GET | 环检测 |

### 2. 向量检索模块 (`/api/neo4j/`)

| 端点 | 方法 | 说明 |
|------|------|------|
| `/search` | POST | 混合检索 (向量+全文+图) |
| `/migrate` | POST | 数据迁移到 Neo4j |
| `/migrate/status` | GET | 迁移状态查询 |
| `/embeddings/generate` | POST | 生成代理向量 |
| `/embeddings/graph` | POST | 生成图嵌入 |

### 3. 调用链模块 (`/api/callchain/`)

| 端点 | 方法 | 说明 |
|------|------|------|
| `/analyze` | POST | 分析项目调用链 |
| `/search` | GET | 搜索方法 |
| `/uri-chain` | GET | URI 调用链查询 |

### 4. 日志分析模块 (`/api/log/`)

| 端点 | 方法 | 说明 |
|------|------|------|
| `/query` | POST | 查询日志 |
| `/analyze` | POST | LLM 日志分析 |

---

## Neo4j 初始化

### 自动初始化

应用启动时 `Neo4jInitializer` 会自动执行:

1. 创建 10 个约束 (唯一性 + 存在性)
2. 创建 10 个索引 (向量 + 全文 + 范围)
3. 验证 APOC 和 GDS 插件

### 手动初始化

如需手动初始化，执行脚本:

```bash
# 脚本位置
docs/neo4j/schema/01_constraints.cypher
docs/neo4j/init_neo4j.cypher
```

在 Neo4j Browser 中复制粘贴执行即可。

---

## 开发指南

### 添加新 API

1. 创建 DTO (如需要)
2. 添加 Service 接口和实现
3. 添加 Controller 方法
4. 编写单元测试

### 运行测试

```bash
# 全部测试
mvn test

# 指定测试类
mvn test -Dtest=KnowledgeGraphControllerTest

# 跳过集成测试 (需要 Neo4j)
mvn test -Dtest='!*IntegrationTest'
```

### 代码规范

- Service 层使用接口 + impl 模式
- DTO 使用 `@Data`, `@Builder`
- API 响应使用 `ApiResponse<T>` 包装
- 异常由 `GlobalExceptionHandler` 统一处理

---

## 相关项目

- **前端**: `../hisi-dev-tool-frontend` — Vue 3 + TypeScript + Element Plus
- **MCP 服务**: `../hisi-mcp-server` — MCP 协议服务（知识图谱/混合检索/日志查询工具）

---

## 文档

- [API 文档](./docs/api-document.md)
- [Neo4j 配置指南](./docs/neo4j/README.md)
- [Neo4j 本地部署](./docs/neo4j/README-local.md)
- [GraphRAG 设计文档](./docs/plans/2026-04-17-neo4j-graphrag-design.md)

---

## 架构演进

| 版本 | 变更 |
|------|------|
| v3.x | ChromaDB + hisi-vector-service (Python FastAPI) 做向量存储与检索 |
| v4.0 | 迁移至 Neo4j 5.11+ 原生 VECTOR INDEX；废弃 ChromaDB 和 hisi-vector-service |
| v4.1 | 新增 Python 源码解析（ANTLR4）；知识图谱支持 Java + Python 双语言 |
| v4.4 | 新增公共知识图谱（`publicProjectPath` 分区）；混合检索支持 scope/language 过滤 |

> 本 README 最后一次与代码对齐：commit `0b82da1` (2026-05-08)

---

## 常见问题

### Q: Neo4j 连接失败

检查:
1. Neo4j 服务是否运行
2. 端口 7687 是否开放
3. 密码是否正确

### Q: 插件安装失败 (内网)

手动下载 JAR 包:
- APOC: https://github.com/neo4j-contrib/neo4j-apoc-procedures/releases
- GDS: https://github.com/neo4j/graph-data-science/releases

放入数据库 `plugins` 目录后重启。

### Q: 向量索引创建失败

需要 Neo4j 5.11+ 版本支持向量索引。
