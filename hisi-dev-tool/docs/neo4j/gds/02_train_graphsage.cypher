// ========================================
// GDS GraphSAGE Training - 训练GraphSAGE模型
// ========================================
// 用途: 在调用图上训练GraphSAGE模型以学习节点嵌入
// 前置条件: 图投影 'callGraph' 已创建
// 使用方式: 通过GraphEmbeddingService.trainGraphSageModel()调用

// 1. 训练GraphSAGE模型
// model: 模型名称，唯一标识训练好的模型
// graphName: 图投影名称
// featureProperties: 用于训练的节点属性（嵌入向量维度需要兼容）
// embeddingDimension: 输出嵌入向量的维度（默认384，与代理向量一致）
// aggregator: 聚合器类型 (mean, sum, max)
// sampleSizes: 每层采样邻居数量
// epochs: 训练轮数
// learningRate: 学习率

// 基础训练配置
CALL gds.beta.graphSage.train(
  'callGraph',
  {
    modelName: 'callGraphSage',
    embeddingDimension: 384,
    aggregator: 'mean',
    sampleSizes: [25, 10],
    epochs: 10,
    learningRate: 0.1,
    maxIterations: 100,
    tolerance: 1.0E-4,
    activationFunction: 'sigmoid',
    batchSize: 100,
    logTrainingProgress: true
  }
)
YIELD modelInfo
RETURN modelInfo;

// 2. 检查模型是否存在
CALL gds.beta.model.exists('callGraphSage')
YIELD exists, modelInfo
RETURN exists AS modelExists, modelInfo.modelName AS modelName, modelInfo.modelType AS modelType;

// 3. 列出所有GraphSAGE模型
CALL gds.beta.model.list()
YIELD modelInfo
WHERE modelInfo.modelType = 'graphSage'
RETURN modelInfo.modelName AS modelName,
       modelInfo.modelType AS modelType,
       modelInfo.creationTime AS creationTime,
       modelInfo.storedTrue AS stored;

// ========================================
// 高级配置选项
// ========================================
// 如果图数据量较大，可以使用以下优化配置:
//
// CALL gds.beta.graphSage.train(
//   'callGraph',
//   {
//     modelName: 'callGraphSageLarge',
//     embeddingDimension: 384,
//     aggregator: 'mean',
//     sampleSizes: [10, 5],          // 减少采样邻居数以提升性能
//     epochs: 5,                      // 减少训练轮数
//     learningRate: 0.05,
//     batchSize: 500,                 // 增大batch大小
//     maxIterations: 50,
//     degreeAsProperty: true          // 使用节点度数作为特征
//   }
// )
// YIELD modelInfo
// RETURN modelInfo;

// ========================================
// 使用预计算特征训练
// ========================================
// 如果节点已有嵌入向量属性，可以使用其作为特征:
//
// CALL gds.beta.graphSage.train(
//   'callGraph',
//   {
//     modelName: 'callGraphSageWithFeatures',
//     embeddingDimension: 384,
//     featureProperties: ['embedding'],
//     aggregator: 'mean',
//     sampleSizes: [25, 10],
//     epochs: 10
//   }
// )
// YIELD modelInfo
// RETURN modelInfo;
