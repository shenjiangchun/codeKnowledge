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

### Requirement: 超限截断时压缩上下文重试
当 LLM 因 `max_tokens` 被思考链耗尽导致 JSON 输出残缺（模式 A）时，系统 SHALL 逐级压缩 prompt 上下文并重试，而非直接降级。领域归纳与层级补全两条链路均 MUST 实现：每降一级（减少传给 LLM 的细节）重试一次，全部级别失败才走既有降级路径。

#### Scenario: 领域归纳截断后压缩方法描述重试
- **WHEN** `MultiDimensionCommunityDetector` 首次归纳返回空（JSON 截断致提取失败）
- **THEN** 系统按「每类最多 5 个方法描述 → 2 → 0（仅类名）」逐级压缩重试，任一级成功即产出领域；全部失败才降级

#### Scenario: 层级补全截断后压缩依赖描述重试
- **WHEN** `LayerRoleLlmService` 首次补全返回空（JSON 截断致提取失败）
- **THEN** 系统按「依赖不截断 → 依赖最多 200 字符 → 省略依赖」逐级压缩重试，任一级成功即返回 `resolved` 结果；全部失败才记 `批量补全失败`

#### Scenario: 全部级别失败仍降级
- **WHEN** 压缩重试所有级别均提取失败
- **THEN** 系统走既有降级路径（领域归纳 `semantic-degraded`，层级补全继续下一批），不抛异常、不无限重试
