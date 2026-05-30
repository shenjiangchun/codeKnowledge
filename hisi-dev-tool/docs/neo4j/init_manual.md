# Neo4j 初始化指南

## 您的配置信息

- **URL**: neo4j://127.0.0.1:7687
- **用户名**: neo4j
- **密码**: 12345678
- **Browser**: http://localhost:7474

---

## 步骤 1: 安装插件

### 1.1 打开 Neo4j Desktop

1. 启动 Neo4j Desktop
2. 选择您的数据库项目
3. 点击数据库右侧的 **"Plugins"** 选项卡

### 1.2 安装必需插件

必须安装以下两个插件：

| 插件 | 用途 |
|-----|------|
| **APOC** | 存储过程扩展，用于复杂数据操作 |
| **Graph Data Science (GDS)** | 图算法库，用于 GraphSAGE 嵌入生成 |

**安装步骤**:
1. 找到 **APOC** 插件，点击 **Install** 按钮
2. 找到 **Graph Data Science** 插件，点击 **Install** 按钮
3. 等待下载完成
4. **重启数据库**（点击 Stop 然后 Start）

---

## 步骤 2: 验证插件安装

打开 Neo4j Browser (http://localhost:7474)，执行以下查询：

```cypher
// 验证 APOC 插件
RETURN apoc.version() AS apocVersion;

// 验证 GDS 插件
RETURN gds.version() AS gdsVersion;
```

**预期结果**:
- APOC 版本: 例如 "5.x.x"
- GDS 版本: 例如 "2.x.x"

---

## 步骤 3: 执行初始化脚本

### 方式 A: 通过 Neo4j Browser 手动执行

1. 打开 http://localhost:7474
2. 登录（neo4j / 12345678）
3. 复制以下脚本并执行：

<details>
<summary>点击展开完整脚本</summary>

```cypher
// ============================================================
// 1. 唯一性约束
// ============================================================

CREATE CONSTRAINT method_nodeId_unique IF NOT EXISTS
FOR (m:Method)
REQUIRE m.nodeId IS UNIQUE;

CREATE CONSTRAINT entryPoint_id_unique IF NOT EXISTS
FOR (e:EntryPoint)
REQUIRE e.id IS UNIQUE;

CREATE CONSTRAINT interface_interfaceName_unique IF NOT EXISTS
FOR (i:Interface)
REQUIRE i.interfaceName IS UNIQUE;

CREATE CONSTRAINT implementation_className_unique IF NOT EXISTS
FOR (impl:Implementation)
REQUIRE impl.className IS UNIQUE;

CREATE CONSTRAINT mapper_mapperInterface_unique IF NOT EXISTS
FOR (m:Mapper)
REQUIRE m.mapperInterface IS UNIQUE;

CREATE CONSTRAINT sqlStatement_sqlId_unique IF NOT EXISTS
FOR (s:SqlStatement)
REQUIRE s.sqlId IS UNIQUE;

CREATE CONSTRAINT service_name_unique IF NOT EXISTS
FOR (s:Service)
REQUIRE s.name IS UNIQUE;

CREATE CONSTRAINT project_projectPath_unique IF NOT EXISTS
FOR (p:Project)
REQUIRE p.projectPath IS UNIQUE;

// ============================================================
// 2. 存在性约束
// ============================================================

CREATE CONSTRAINT method_className_exists IF NOT EXISTS
FOR (m:Method)
REQUIRE m.className IS NOT NULL;

CREATE CONSTRAINT method_methodName_exists IF NOT EXISTS
FOR (m:Method)
REQUIRE m.methodName IS NOT NULL;

// ============================================================
// 3. 向量索引
// ============================================================

CREATE VECTOR INDEX method_vector_index IF NOT EXISTS
FOR (m:Method)
ON m.embedding
OPTIONS {
  indexConfig: {
    `vector.dimensions`: 384,
    `vector.similarity_function`: 'cosine'
  }
};

CREATE VECTOR INDEX interface_vector_index IF NOT EXISTS
FOR (i:Interface)
ON i.embedding
OPTIONS {
  indexConfig: {
    `vector.dimensions`: 384,
    `vector.similarity_function`: 'cosine'
  }
};

CREATE VECTOR INDEX sql_vector_index IF NOT EXISTS
FOR (s:SqlStatement)
ON s.embedding
OPTIONS {
  indexConfig: {
    `vector.dimensions`: 384,
    `vector.similarity_function`: 'cosine'
  }
};

// ============================================================
// 4. 全文索引
// ============================================================

CREATE FULLTEXT INDEX method_fulltext_index IF NOT EXISTS
FOR (m:Method)
ON EACH [m.className, m.methodName, m.signature, m.commentSummary];

CREATE FULLTEXT INDEX entry_point_fulltext_index IF NOT EXISTS
FOR (e:EntryPoint)
ON EACH [e.entryKey, e.entryType];

CREATE FULLTEXT INDEX interface_fulltext_index IF NOT EXISTS
FOR (i:Interface)
ON EACH [i.interfaceName, i.description];

CREATE FULLTEXT INDEX sql_fulltext_index IF NOT EXISTS
FOR (s:SqlStatement)
ON EACH [s.sqlId, s.sqlContent, s.description];

CREATE FULLTEXT INDEX service_fulltext_index IF NOT EXISTS
FOR (s:Service)
ON EACH [s.name, s.description];

// ============================================================
// 5. 范围索引
// ============================================================

CREATE INDEX method_lineNumber_index IF NOT EXISTS
FOR (m:Method)
ON (m.lineNumber);

CREATE INDEX method_complexity_index IF NOT EXISTS
FOR (m:Method)
ON (m.cyclomaticComplexity);
```

</details>

### 方式 B: 通过应用自动初始化

我已经创建了 `Neo4jInitializer.java`，当您启动 Spring Boot 应用时，它会自动执行初始化脚本。

```bash
cd "C:\Users\47583\projects\hisi_dev_tool v4.0\hisi-dev-tool"
mvn spring-boot:run
```

应用启动时会看到日志：
```
开始初始化 Neo4j 约束和索引...
创建唯一性约束...
创建存在性约束...
创建向量索引...
创建全文索引...
创建范围索引...
Neo4j 初始化完成: 成功=xx, 失败=xx
APOC 版本: x.x.x
GDS 版本: x.x.x
```

---

## 步骤 4: 验证初始化结果

在 Neo4j Browser 中执行：

```cypher
// 查看所有约束
SHOW CONSTRAINTS;

// 查看所有索引
SHOW INDEXES;
```

**预期结果**:
- 约束数量: 10 个
- 索引数量: 包含 3 个向量索引、5 个全文索引、2 个范围索引

---

## 故障排除

### 问题 1: 插件安装失败

如果插件无法通过 Neo4j Desktop 安装（内网限制），请手动下载：

**APOC 插件**:
1. 访问 https://github.com/neo4j-contrib/neo4j-apoc-procedures/releases
2. 下载与 Neo4j 版本匹配的 JAR 文件
3. 放入数据库的 `plugins` 目录

**GDS 插件**:
1. 访问 https://github.com/neo4j/graph-data-science/releases
2. 下载 JAR 文件
3. 放入数据库的 `plugins` 目录

### 问题 2: 连接失败

检查：
1. Neo4j 服务是否正在运行
2. 端口 7687 是否被占用
3. 防火墙设置

### 问题 3: 向量索引创建失败

向量索引需要 Neo4j 5.11+ 版本。如果版本过低，请升级 Neo4j。

---

## 下一步

初始化完成后：

1. 启动 Spring Boot 应用
2. 调用数据迁移 API 迁移知识图谱数据
3. 测试混合检索功能
