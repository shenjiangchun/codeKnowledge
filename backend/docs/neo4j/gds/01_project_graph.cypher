// ========================================
// GDS Graph Projection - 调用图投影
// ========================================
// 用途: 将Neo4j中的方法调用图投影到GDS图内存中
// 前置条件: Neo4j GDS插件已安装，调用图数据已存在
// 使用方式: 通过GraphEmbeddingService.projectGraph()调用

// 1. 检查是否已存在同名图投影，如果存在则删除
CALL gds.graph.exists('callGraph')
YIELD exists
WITH exists
CALL gds.graph.drop('callGraph', false) YIELD graphName
RETURN graphName AS droppedGraph;

// 2. 创建调用图投影
// 使用CORA投影，包含Method节点和CALLS关系
// 节点属性: className, methodName, signature, comment, serviceName
CALL gds.graph.project(
  'callGraph',
  'Method',
  {
    CALLS: {
      orientation: 'NATURAL',
      aggregation: 'SINGLE'
    }
  },
  {
    nodeProperties: ['className', 'methodName', 'signature', 'comment', 'serviceName'],
    relationshipProperties: []
  }
)
YIELD graphName, nodeCount, relationshipCount
RETURN graphName AS projectedGraph, nodeCount, relationshipCount;

// 3. 验证图投影是否成功
CALL gds.graph.exists('callGraph')
YIELD exists, graphName
RETURN exists AS graphExists, graphName;

// ========================================
// 替代方案: 使用匿名图投影 (适用于临时分析)
// ========================================
// 在CYPHER查询中直接使用匿名图:
// CALL gds.graph.project.cypher(
//   'callGraph',
//   'MATCH (m:Method) RETURN id(m) AS id, m.className AS className',
//   'MATCH (m1:Method)-[:CALLS]->(m2:Method) RETURN id(m1) AS source, id(m2) AS target'
// )
