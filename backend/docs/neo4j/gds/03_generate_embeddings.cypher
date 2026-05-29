// ========================================
// GDS Embedding Generation - 生成图嵌入
// ========================================
// 用途: 使用训练好的GraphSAGE模型为节点生成图嵌入
// 前置条件: GraphSAGE模型 'callGraphSage' 已训练完成
// 使用方式: 通过GraphEmbeddingService.generateGraphEmbeddings()调用

// 1. 使用mutate模式生成嵌入（写入图投影内存）
CALL gds.beta.graphSage.mutate(
  'callGraph',
  {
    modelName: 'callGraphSage',
    mutateProperty: 'graphEmbedding'
  }
)
YIELD nodePropertiesWritten, computeMillis
RETURN nodePropertiesWritten, computeMillis;

// 2. 使用write模式写入节点属性到Neo4j数据库
CALL gds.beta.graphSage.write(
  'callGraph',
  {
    modelName: 'callGraphSage',
    writeProperty: 'graphEmbedding'
  }
)
YIELD nodePropertiesWritten, computeMillis, writeMillis
RETURN nodePropertiesWritten, computeMillis, writeMillis;

// 3. 使用stream模式直接返回嵌入结果
CALL gds.beta.graphSage.stream(
  'callGraph',
  {
    modelName: 'callGraphSage'
  }
)
YIELD nodeId, embedding
RETURN gds.util.asNode(nodeId).nodeId AS methodNodeId,
       gds.util.asNode(nodeId).methodName AS methodName,
       embedding AS graphEmbedding
LIMIT 100;

// 4. 批量获取所有节点的图嵌入
CALL gds.beta.graphSage.stream(
  'callGraph',
  {
    modelName: 'callGraphSage'
  }
)
YIELD nodeId, embedding
WITH gds.util.asNode(nodeId) AS node, embedding
RETURN node.nodeId AS nodeId,
       node.className AS className,
       node.methodName AS methodName,
       embedding AS graphEmbedding
ORDER BY node.className, node.methodName;

// ========================================
// 按项目路径筛选生成嵌入
// ========================================
// 如果需要只对特定项目的节点生成嵌入:
//
// MATCH (m:Method)
// WHERE m.projectPath = $projectPath
// WITH collect(id(m)) AS nodeIds
// CALL gds.beta.graphSage.stream(
//   'callGraph',
//   {
//     modelName: 'callGraphSage',
//     nodeLabels: ['Method']
//   }
// )
// YIELD nodeId, embedding
// WHERE nodeId IN nodeIds
// RETURN gds.util.asNode(nodeId).nodeId AS methodNodeId,
//        embedding AS graphEmbedding;

// ========================================
// 嵌入相似度查询
// ========================================
// 计算两个方法的图嵌入相似度:
//
// CALL gds.beta.graphSage.stream('callGraph', {modelName: 'callGraphSage'})
// YIELD nodeId, embedding
// WITH nodeId, embedding
// WHERE gds.util.asNode(nodeId).nodeId IN ['method1.id', 'method2.id']
// WITH collect({nodeId: nodeId, embedding: embedding}) AS nodes
// WITH nodes[0].embedding AS emb1, nodes[1].embedding AS emb2
// RETURN gds.similarity.cosine(emb1, emb2) AS similarity;
