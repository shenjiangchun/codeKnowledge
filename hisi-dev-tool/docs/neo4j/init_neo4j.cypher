// ============================================================
// Neo4j 知识图谱初始化脚本
// 执行方式: 在 Neo4j Browser (http://localhost:7474) 中复制粘贴执行
// ============================================================

// ------------------------------------------------------------
// 1. 节点唯一性约束
// ------------------------------------------------------------

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

// ------------------------------------------------------------
// 2. 存在性约束
// ------------------------------------------------------------

CREATE CONSTRAINT method_className_exists IF NOT EXISTS
FOR (m:Method)
REQUIRE m.className IS NOT NULL;

CREATE CONSTRAINT method_methodName_exists IF NOT EXISTS
FOR (m:Method)
REQUIRE m.methodName IS NOT NULL;

// ------------------------------------------------------------
// 3. 向量索引
// ------------------------------------------------------------

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

// ------------------------------------------------------------
// 4. 全文索引
// ------------------------------------------------------------

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

// ------------------------------------------------------------
// 5. 范围索引
// ------------------------------------------------------------

CREATE INDEX method_lineNumber_index IF NOT EXISTS
FOR (m:Method)
ON (m.lineNumber);

CREATE INDEX method_complexity_index IF NOT EXISTS
FOR (m:Method)
ON (m.cyclomaticComplexity);
