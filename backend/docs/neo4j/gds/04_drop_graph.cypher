// ========================================
// GDS Graph Cleanup - 清理图投影和模型
// ========================================
// 用途: 释放GDS内存资源，删除图投影和模型
// 使用方式: 通过GraphEmbeddingService.dropGraph()调用

// 1. 删除图投影
CALL gds.graph.drop('callGraph', false)
YIELD graphName
RETURN graphName AS droppedGraph;

// 2. 删除GraphSAGE模型
CALL gds.beta.model.drop('callGraphSage')
YIELD modelInfo
RETURN modelInfo.modelName AS droppedModel;

// 3. 删除所有图投影（谨慎使用）
CALL gds.graph.list()
YIELD graphName
WITH graphName
CALL gds.graph.drop(graphName, false)
YIELD graphName AS droppedGraph
RETURN droppedGraph;

// 4. 删除所有GraphSAGE模型（谨慎使用）
CALL gds.beta.model.list()
YIELD modelInfo
WHERE modelInfo.modelType = 'graphSage'
WITH modelInfo.modelName AS modelName
CALL gds.beta.model.drop(modelName)
YIELD modelInfo
RETURN modelInfo.modelName AS droppedModel;

// 5. 检查图投影状态
CALL gds.graph.list()
YIELD graphName, nodeCount, relationshipCount, database
RETURN graphName, nodeCount, relationshipCount, database;

// 6. 检查内存使用情况
CALL gds.graph.list()
YIELD graphName, memoryUsage
RETURN graphName, memoryUsage;

// ========================================
// 完整清理脚本
// ========================================
// 按顺序执行清理，先删除模型，再删除图:
//
// // Step 1: 删除模型
// CALL gds.beta.model.drop('callGraphSage', false)
// YIELD modelInfo
// WITH modelInfo
// // Step 2: 删除图投影
// CALL gds.graph.drop('callGraph', false)
// YIELD graphName
// RETURN graphName AS cleanupComplete;

// ========================================
// 重置脚本 - 清理所有资源
// ========================================
// 用于完全重置GDS状态:
//
// // 清理所有图投影
// CALL gds.graph.list()
// YIELD graphName
// CALL gds.graph.drop(graphName, true)
// YIELD graphName AS droppedGraph
// RETURN droppedGraph;
//
// // 清理所有模型
// CALL gds.beta.model.list()
// YIELD modelInfo
// CALL gds.beta.model.drop(modelInfo.modelName, true)
// YIELD modelInfo AS droppedModel
// RETURN droppedModel.modelName;
