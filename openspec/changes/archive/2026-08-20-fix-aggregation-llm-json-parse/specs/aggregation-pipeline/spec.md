## ADDED Requirements

### Requirement: 聚合管道 LLM 结构化输出跳过 thinking 块解析
系统 MUST 在聚合管道的领域归纳（`MultiDimensionCommunityDetector.callLlm`）与游离节点层级补全（`LayerRoleLlmService.resolveRoles`）中，通过 `.chatResponse()` 获取完整响应并经 `RobustJsonExtractor.extract` 遍历 generations 跳过 thinking 块后反序列化，而非依赖 `.entity()`（其只取第一个 Generation，拿到的是 thinking 散文）。当 LLM 输出含 thinking 块时，仍 MUST 正确产出领域 / 层级结果。

#### Scenario: 领域归纳含 thinking 块仍产出领域
- **WHEN** `MultiDimensionCommunityDetector.callLlm` 收到含 thinking 块 + text JSON 块的响应
- **THEN** 系统跳过 thinking 块提取出 `DomainGrouping`，`domains` 覆盖输入类，Community Stage 产出领域而非降级

#### Scenario: 层级补全含 thinking 块仍产出层级
- **WHEN** `LayerRoleLlmService.resolveRoles` 收到含 thinking 块 + text JSON 块的响应
- **THEN** 系统跳过 thinking 块提取出 `RoleGrouping`，`items` 覆盖输入节点，`resolved` 计数大于 0

#### Scenario: 提取失败仍走既有降级
- **WHEN** 鲁棒提取失败（返回 null）
- **THEN** 系统保持既有降级行为：领域归纳降级标记 `semantic-degraded;domains=0`，层级补全记录 `批量补全失败` 并继续下一批
