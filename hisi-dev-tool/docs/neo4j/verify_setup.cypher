// ============================================================
// Neo4j 环境验证脚本
// 用于验证 Neo4j 环境是否正确配置
// ============================================================

// ------------------------------------------------------------
// 1. 验证 APOC 插件
// ------------------------------------------------------------
// 检查 APOC 插件是否正确安装
RETURN apoc.version() AS apocVersion;

// 测试 APOC 基本功能
CALL apoc.help('apoc') YIELD name
RETURN count(name) AS apocFunctionsCount;

// ------------------------------------------------------------
// 2. 验证 GDS 插件
// ------------------------------------------------------------
// 检查 GDS 插件是否正确安装
RETURN gds.version() AS gdsVersion;

// 列出可用的 GDS 算法
CALL gds.list() YIELD name, type
RETURN count(name) AS gdsAlgorithmsCount;

// ------------------------------------------------------------
// 3. 验证约束
// ------------------------------------------------------------
// 查看所有约束
SHOW CONSTRAINTS;

// 验证特定约束是否存在
// 方法节点约束
DO $$
DECLARE constraintExists BOOLEAN;
SET constraintExists = EXISTS {
  SHOW CONSTRAINTS YIELD name
  WHERE name = 'method_nodeId_unique'
};
RETURN constraintExists AS methodNodeIdConstraintExists;
$$;

// ------------------------------------------------------------
// 4. 验证索引
// ------------------------------------------------------------
// 查看所有索引
SHOW INDEXES;

// 验证向量索引是否存在
DO $$
DECLARE indexExists BOOLEAN;
SET indexExists = EXISTS {
  SHOW INDEXES YIELD name
  WHERE name = 'method_vector_index'
};
RETURN indexExists AS vectorIndexExists;
$$;

// 验证全文索引是否存在
DO $$
DECLARE fulltextIndexExists BOOLEAN;
SET fulltextIndexExists = EXISTS {
  SHOW INDEXES YIELD name
  WHERE name = 'method_fulltext_index'
};
RETURN fulltextIndexExists AS fulltextIndexExists;
$$;

// ------------------------------------------------------------
// 5. 验证数据库连接
// ------------------------------------------------------------
// 测试基本查询
RETURN 1 AS testValue;

// 检查数据库版本
CALL dbms.components() YIELD name, versions, edition
RETURN name, versions, edition;

// ------------------------------------------------------------
// 6. 验证内存配置
// ------------------------------------------------------------
// 查看当前内存设置
CALL dbms.listConfig() YIELD name, value
WHERE name CONTAINS 'memory'
RETURN name, value
ORDER BY name;

// ------------------------------------------------------------
// 7. 综合验证结果
// ------------------------------------------------------------
// 执行所有验证并返回汇总
CALL {
  // APOC 验证
  CALL apoc.version() YIELD apocVersion

  // GDS 验证
  CALL gds.version() YIELD gdsVersion

  RETURN apocVersion, gdsVersion
}
RETURN
  'Neo4j Environment Verification Complete' AS status,
  apocVersion,
  gdsVersion;
