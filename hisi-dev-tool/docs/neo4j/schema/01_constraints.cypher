// ============================================================
// Neo4j 约束和索引初始化脚本
// 用于创建知识图谱所需的节点约束、向量索引和全文索引
// ============================================================
// 执行顺序: 此脚本应在 Neo4j 容器启动后执行
// 执行方式: 在 Neo4j Browser 中执行，或通过 cypher-shell 执行
// ============================================================

// ------------------------------------------------------------
// 1. 节点唯一性约束
// ------------------------------------------------------------

// Method 节点约束 - nodeId 唯一
CREATE CONSTRAINT method_nodeId_unique IF NOT EXISTS
FOR (m:Method)
REQUIRE m.nodeId IS UNIQUE;

// EntryPoint 节点约束 - id 唯一
CREATE CONSTRAINT entryPoint_id_unique IF NOT EXISTS
FOR (e:EntryPoint)
REQUIRE e.id IS UNIQUE;

// Interface 节点约束 - interfaceName 唯一
CREATE CONSTRAINT interface_interfaceName_unique IF NOT EXISTS
FOR (i:Interface)
REQUIRE i.interfaceName IS UNIQUE;

// Implementation 节点约束 - className 唯一
CREATE CONSTRAINT implementation_className_unique IF NOT EXISTS
FOR (impl:Implementation)
REQUIRE impl.className IS UNIQUE;

// Mapper 节点约束 - mapperInterface 唯一
CREATE CONSTRAINT mapper_mapperInterface_unique IF NOT EXISTS
FOR (m:Mapper)
REQUIRE m.mapperInterface IS UNIQUE;

// SqlStatement 节点约束 - sqlId 唯一
CREATE CONSTRAINT sqlStatement_sqlId_unique IF NOT EXISTS
FOR (s:SqlStatement)
REQUIRE s.sqlId IS UNIQUE;

// Service 节点约束 - name 唯一
CREATE CONSTRAINT service_name_unique IF NOT EXISTS
FOR (s:Service)
REQUIRE s.name IS UNIQUE;

// Project 节点约束 - projectPath 唯一
CREATE CONSTRAINT project_projectPath_unique IF NOT EXISTS
FOR (p:Project)
REQUIRE p.projectPath IS UNIQUE;

// ------------------------------------------------------------
// 2. 存在性约束（可选，确保关键字段不为空）
// ------------------------------------------------------------

// Method 节点关键字段存在性约束
CREATE CONSTRAINT method_className_exists IF NOT EXISTS
FOR (m:Method)
REQUIRE m.className IS NOT NULL;

CREATE CONSTRAINT method_methodName_exists IF NOT EXISTS
FOR (m:Method)
REQUIRE m.methodName IS NOT NULL;

// ------------------------------------------------------------
// 3. 向量索引（用于 GraphRAG 语义检索）
// ------------------------------------------------------------
// 384维向量索引，使用 cosine 相似度
// 注意：向量索引需要在数据导入后才能看到效果

// Method 节点向量索引
CREATE VECTOR INDEX method_vector_index IF NOT EXISTS
FOR (m:Method)
ON m.embedding
OPTIONS {
  indexConfig: {
    `vector.dimensions`: 384,
    `vector.similarity_function`: 'cosine'
  }
};

// Interface 节点向量索引（用于接口描述检索）
CREATE VECTOR INDEX interface_vector_index IF NOT EXISTS
FOR (i:Interface)
ON i.embedding
OPTIONS {
  indexConfig: {
    `vector.dimensions`: 384,
    `vector.similarity_function`: 'cosine'
  }
};

// SqlStatement 节点向量索引（用于 SQL 语义检索）
CREATE VECTOR INDEX sql_vector_index IF NOT EXISTS
FOR (s:SqlStatement)
ON s.embedding
OPTIONS {
  indexConfig: {
    `vector.dimensions`: 384,
    `vector.similarity_function`: 'cosine'
  }
};

// ------------------------------------------------------------
// 4. 全文索引（用于关键词检索）
// ------------------------------------------------------------

// Method 节点全文索引
CREATE FULLTEXT INDEX method_fulltext_index IF NOT EXISTS
FOR (m:Method)
ON EACH [m.className, m.methodName, m.signature, m.commentSummary];

// EntryPoint 节点全文索引
CREATE FULLTEXT INDEX entry_point_fulltext_index IF NOT EXISTS
FOR (e:EntryPoint)
ON EACH [e.entryKey, e.entryType];

// Interface 节点全文索引
CREATE FULLTEXT INDEX interface_fulltext_index IF NOT EXISTS
FOR (i:Interface)
ON EACH [i.interfaceName, i.description];

// SqlStatement 节点全文索引
CREATE FULLTEXT INDEX sql_fulltext_index IF NOT EXISTS
FOR (s:SqlStatement)
ON EACH [s.sqlId, s.sqlContent, s.description];

// Service 节点全文索引
CREATE FULLTEXT INDEX service_fulltext_index IF NOT EXISTS
FOR (s:Service)
ON EACH [s.name, s.description];

// ------------------------------------------------------------
// 5. 范围索引（用于数值查询优化）
// ------------------------------------------------------------

// Method 节点行号范围索引
CREATE INDEX method_lineNumber_index IF NOT EXISTS
FOR (m:Method)
ON (m.lineNumber);

// Method 节点复杂度索引
CREATE INDEX method_complexity_index IF NOT EXISTS
FOR (m:Method)
ON (m.cyclomaticComplexity);

// ------------------------------------------------------------
// 6. 验证创建结果
// ------------------------------------------------------------

// 显示所有约束
SHOW CONSTRAINTS
YIELD name, type, labelsOrTypes, properties
RETURN name, type, labelsOrTypes, properties
ORDER BY name;

// 显示所有索引
SHOW INDEXES
YIELD name, type, labelsOrTypes, properties
RETURN name, type, labelsOrTypes, properties
ORDER BY name;
