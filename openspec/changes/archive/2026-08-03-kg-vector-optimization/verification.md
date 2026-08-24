# KG 向量生成效率优化：验证报告

## 验证时间
2026-08-03

## 单元测试
- 命令：`cd hisi-dev-tool && mvn test`
- 结果：1072 tests, 0 failures, 0 errors, 15 skipped
- 基线对比：1069 baseline → 1072 after (+3 new UnifiedEmbeddingServiceBatchTest)
- 零回归

## OpenSpec 结构验证
- 命令：`openspec validate kg-vector-optimization`
- 结果：Change 'kg-vector-optimization' is valid

## 变更范围
| 文件 | 变更类型 | 行数 |
|------|---------|------|
| `EmbeddingModelConfig.java` | +batchSize 字段 | +3 |
| `TextModelConfig.java` | +jsonOutputStrategy 字段 | +3 |
| `UnifiedEmbeddingService.java` | +generateEmbeddings(List) + 批量解析 | +153 |
| `EmbeddingService.java` | batchGenerateEmbeddings 改为委托批量 | ~+3/-6 |
| `UnifiedTextService.java` | +generateDescriptionsBatch + buildBatchPrompt | +212 |
| `AdaptiveBatchController.java` | 新文件 (Chiron v3 算法) | +200 |
| `LLMDescriptionService.java` | +generateDescriptionsBatch 协调方法 | +44 |
| `VectorGenerationService.java` | 批量主流程 + 自适应 + Sql批量 | +137/-34 |
| `MethodNode.java` | +codeHash 字段 | +8 |
| `Neo4jInitializer.java` | +method_codeHash_index | +1 |
| `UnifiedEmbeddingServiceBatchTest.java` | 新测试 | +85 |
| **合计** | | **~849 行** |

## 残留项
- T5 集成测试：需要 Neo4j + API key 环境，在目标环境中执行
  - 验证命令：`grep '[性能报告]' logs/local-model/vector-generation.log` 对比 total_time
  - 验证命令：`grep '[ADAPTIVE]' logs/local-model/vector-generation.log` 确认 batch_size 轨迹
- Qwen3-embedding-8b 批量 API 未经实测（KNOWN 表标为 JSON_MODE，基于文档推断）

## 代码审查
中风险 Standard 变更，待独立审查（SubAgent 或人工）
