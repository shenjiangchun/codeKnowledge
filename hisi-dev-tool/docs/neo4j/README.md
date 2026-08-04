# Neo4j 环境配置指南

本文档说明如何在 Windows 本地部署 Neo4j 环境。

## 部署方式选择

| 方式 | 适用场景 | 说明 |
|------|---------|------|
| **Neo4j Desktop** | 推荐，图形化管理，适合开发环境 | 见下方详细步骤 |
| **Community Server (ZIP)** | 无需安装，解压即用 | 见 [README-local.md](./README-local.md) |
| **Docker** | 需要 Docker 环境 | 内网环境不建议使用 |

---

## Neo4j Desktop 安装（推荐）

### 1. 下载安装

1. 访问下载页面：https://neo4j.com/download/
2. 下载 **Neo4j Desktop** (Windows 版本)
3. 双击安装程序，按向导完成安装
4. 启动 Neo4j Desktop

### 2. 创建数据库

1. 点击左侧 **"New"** 按钮
2. 选择 **"Create Database"**
3. 选择 **"Local DBMS"**
4. 输入名称：`hisi-dev-tool`
5. 设置密码（记住此密码，后续配置需要）
6. 点击 **"Create"**

### 3. 安装插件

**必须安装以下插件**：

1. 点击创建的数据库 `hisi-dev-tool`
2. 选择右侧 **"Plugins"** 选项卡
3. 安装以下插件：
   - **APOC** - 点击 "Install" 按钮
   - **Graph Data Science (GDS)** - 点击 "Install" 按钮
4. 等待插件下载安装完成

### 4. 启动数据库

点击 **"Start"** 按钮启动数据库，等待状态变为绿色 "Running"

### 5. 访问 Neo4j Browser

- 点击 **"Open"** → **"Browser"**
- 或直接浏览器访问：http://localhost:7474
- 用户名：`neo4j`
- 密码：创建时设置的密码

---

## 初始化数据库

### 1. 执行约束和索引脚本

在 Neo4j Browser 中：

1. 打开文件 `docs/neo4j/schema/01_constraints.cypher`
2. 复制所有内容
3. 粘贴到查询框
4. 点击执行按钮（或按 Ctrl+Enter）

### 2. 验证初始化结果

在 Neo4j Browser 中执行：

```cypher
// 验证 APOC 插件
RETURN apoc.version() AS apocVersion;

// 验证 GDS 插件
RETURN gds.version() AS gdsVersion;

// 验证约束
SHOW CONSTRAINTS;

// 验证索引
SHOW INDEXES;
```

### 预期结果

| 验证项 | 预期结果 |
|--------|----------|
| APOC 版本 | 返回版本号 (如 "5.15.0") |
| GDS 版本 | 返回版本号 (如 "2.5.0") |
| 约束数量 | 10+ 个约束 |
| 索引数量 | 包含向量索引和全文索引 |

---

## Spring Boot 连接配置

### 1. 设置环境变量

```powershell
# PowerShell
$env:NEO4J_PASSWORD = "your_password"
```

或在 `application.yml` 中直接配置：

```yaml
neo4j:
  uri: bolt://localhost:7687
  username: neo4j
  password: your_password
```

### 2. 启动 Spring Boot 应用

确保 Neo4j 数据库已启动，然后启动 Spring Boot 应用。

---

## GDS 图嵌入脚本执行

初始化完成后，如需生成图嵌入，执行以下脚本：

### 1. 投影图结构

打开 `docs/neo4j/gds/01_project_graph.cypher`，在 Neo4j Browser 中执行

### 2. 训练 GraphSAGE 模型

打开 `docs/neo4j/gds/02_train_graphsage.cypher`，执行

### 3. 生成图嵌入

打开 `docs/neo4j/gds/03_generate_embeddings.cypher`，执行

### 4. 清理（可选）

完成图嵌入后，可执行 `docs/neo4j/gds/04_drop_graph.cypher` 清理图投影

---

## 端口说明

| 端口 | 用途 |
|------|------|
| 7474 | HTTP API 和 Neo4j Browser |
| 7687 | Bolt 协议 (应用连接) |

---

## 常见问题

### 问题 1: 插件安装失败

如果插件下载失败（内网环境）：

1. 手动下载插件 JAR 包：
   - APOC: https://github.com/neo4j-contrib/neo4j-apoc-procedures/releases
   - GDS: https://github.com/neo4j/graph-data-science/releases
2. 将 JAR 文件放入数据库的 `plugins` 目录
3. 重启数据库

### 问题 2: 无法连接

1. 确认数据库状态为 "Running"
2. 检查端口是否被占用：
   ```powershell
   netstat -ano | findstr "7474"
   netstat -ano | findstr "7687"
   ```
3. 检查防火墙设置

### 问题 3: 密码错误

在 Neo4j Desktop 中：
1. 停止数据库
2. 点击数据库右侧设置图标
3. 选择 "Reset Password"

---

## 文件结构

```
docs/neo4j/
|-- README.md                    # 本文档
|-- README-local.md              # ZIP 安装方式说明
|-- schema/
|   |-- 01_constraints.cypher   # 约束和索引脚本
|-- gds/
|   |-- 01_project_graph.cypher # 图投影脚本
|   |-- 02_train_graphsage.cypher# GraphSAGE 训练脚本
|   |-- 03_generate_embeddings.cypher # 生成嵌入脚本
|   |-- 04_drop_graph.cypher    # 清理脚本
|-- verify_setup.cypher          # 环境验证脚本
```

---

## 下一步

环境搭建完成后：

1. 配置 Spring Boot 连接 Neo4j
2. 运行数据迁移服务
3. 生成代理向量和图嵌入
4. 测试混合检索功能

---

## 参考资料

- [Neo4j 官方文档](https://neo4j.com/docs/)
- [APOC 用户指南](https://neo4j.com/docs/apoc/current/)
- [GDS 文档](https://neo4j.com/docs/graph-data-science/current/)
