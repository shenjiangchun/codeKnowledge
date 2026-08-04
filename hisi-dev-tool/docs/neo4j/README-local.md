# Neo4j Windows 本地部署指南（非 Docker）

## 方式一：Neo4j Desktop（推荐）

### 1. 下载安装

1. 访问下载页面：https://neo4j.com/download/
2. 下载 **Neo4j Desktop** (Windows 版本)
3. 双击安装程序，按向导完成安装
4. 启动 Neo4j Desktop

### 2. 创建数据库

1. 点击 **"New"** → **"Create Database"**
2. 选择 **"Local DBMS"**
3. 输入名称：`hisi-dev-tool`
4. 设置密码：自定义密码
5. 点击 **"Create"**

### 3. 安装插件

1. 点击创建的数据库
2. 选择右侧 **"Plugins"** 选项卡
3. 安装以下插件：
   - **APOC** - 点击 "Install"
   - **Graph Data Science (GDS)** - 点击 "Install"

### 4. 启动数据库

点击 **"Start"** 按钮启动数据库

### 5. 访问 Neo4j Browser

- 点击 **"Open"** → **"Browser"**
- 或浏览器访问：http://localhost:7474
- 用户名：`neo4j`
- 密码：创建时设置的密码

---

## 方式二：Neo4j Community Server（ZIP 安装）

### 1. 下载

1. 访问：https://neo4j.com/download-center/
2. 下载 **Neo4j Community Edition** (Windows ZIP)
3. 解压到目标目录，如：`C:\neo4j`

### 2. 配置

编辑 `conf\neo4j.conf`：

```properties
# 数据库路径
server.default_database=hisi-dev-tool

# 内存配置
server.memory.heap.initial_size=512M
server.memory.heap.max_size=1G
server.memory.pagecache.size=512M

# 允许远程连接
server.default_listen_address=0.0.0.0

# 开启 APOC（需要先下载插件）
dbms.security.procedures.unrestricted=apoc.*
dbms.security.procedures.allowlist=apoc.*
```

### 3. 安装插件

手动下载插件 JAR 包：

**APOC**:
- 下载：https://github.com/neo4j-contrib/neo4j-apoc-procedures/releases
- 选择与 Neo4j 版本匹配的版本
- 将 `apoc-5.x.x-core.jar` 放入 `plugins` 目录

**GDS**:
- 下载：https://github.com/neo4j/graph-data-science/releases
- 将 `neo4j-graph-data-science-xxx.jar` 放入 `plugins` 目录

### 4. 启动服务

```powershell
# PowerShell
cd C:\neo4j
.\bin\neo4j console

# 或安装为 Windows 服务
.\bin\neo4j windows-service install
.\bin\neo4j start
```

### 5. 验证

浏览器访问：http://localhost:7474

---

## 初始化数据库

### 1. 执行约束脚本

在 Neo4j Browser 中：

1. 打开文件 `docs/neo4j/schema/01_constraints.cypher`
2. 复制所有内容
3. 粘贴到查询框
4. 点击执行

### 2. 验证安装

在 Neo4j Browser 中执行：

```cypher
// 验证 APOC
RETURN apoc.version();

// 验证 GDS
RETURN gds.version();

// 查看约束
SHOW CONSTRAINTS;

// 查看索引
SHOW INDEXES;
```

---

## 连接配置

更新 `application.yml`：

```yaml
neo4j:
  uri: bolt://localhost:7687
  username: neo4j
  password: ${NEO4J_PASSWORD:your_password}
```

或设置环境变量：

```powershell
$env:NEO4J_PASSWORD = "your_password"
```

---

## 端口说明

| 端口 | 用途 |
|------|------|
| 7474 | HTTP API 和 Neo4j Browser |
| 7687 | Bolt 协议 (应用连接) |

---

## 常见问题

### 问题 1: 无法启动服务

检查端口是否被占用：
```powershell
netstat -ano | findstr "7474"
netstat -ano | findstr "7687"
```

### 问题 2: APOC/GDS 插件不生效

1. 确认 JAR 文件在 `plugins` 目录
2. 确认 `neo4j.conf` 中有相应配置
3. 重启 Neo4j 服务

### 问题 3: 内存不足

编辑 `conf\neo4j.conf` 调整内存配置：
```properties
server.memory.heap.initial_size=256M
server.memory.heap.max_size=512M
```

---

## 内网环境离线安装

如果无法访问外网：

1. 提前下载好 Neo4j Desktop 安装包或 ZIP 包
2. 提前下载 APOC 和 GDS 插件 JAR 包
3. 使用 U 盘或内网传输工具拷贝到目标机器
4. 按上述步骤安装
