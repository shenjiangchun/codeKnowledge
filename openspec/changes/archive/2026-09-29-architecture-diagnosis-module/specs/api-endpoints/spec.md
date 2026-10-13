# api-endpoints Delta Specification

## MODIFIED Requirements

### Requirement: 爆炸半径 API
系统 MUST 提供 GET /api/v2/knowledge-graph/blast-radius/{nodeId} 端点，返回指定方法的完整影响面分析。

#### Scenario: 查询 OrderService.placeOrder 的影响面
- 前提：nodeId="com.hisi.order.OrderService.placeOrder"
- 当：GET /api/v2/knowledge-graph/blast-radius/{nodeId}?projectPaths=hisi-dev-tool&maxDepth=5
- 则：响应含 downstream（totalAffectedMethods/maxDepth/bridgeCount/affectedServices/criticalPath）
- 并且：upstream（totalEntryPoints/entryTypes/affectedAPIs）
- 并且：riskSummary（overallRisk/reasons）
- 并且：suggestedTests（受影响入口点的测试建议列表，非空，每项指向受影响入口点或受影响方法）

#### Scenario: 测试建议非空
- 当：影响面分析返回 `affectedEntryPoints > 0` 或存在受影响方法
- 则：`suggestedTests` SHALL 返回基于受影响入口点/方法推导的测试建议列表（如「为入口 X 补充回归测试」「覆盖受影响方法 Y 的调用路径」），而非恒空数组

#### Scenario: 无受影响入口点时的建议
- 当：上游调用者与下游方法均为空（影响面为 0）
- 则：`suggestedTests` 返回空数组（无建议可给），不抛错