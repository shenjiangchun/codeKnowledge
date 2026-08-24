# rerank Specification

## Purpose
TBD - created by archiving change semantic-search-rerank. Update Purpose after archive.
## Requirements
### Requirement: rerank 外部 API 配置
系统 SHALL 支持在 `application.yml` 的 `search.rerank` 段配置外部 rerank 精排服务，包含 `enabled`（开关，默认 false）、`base-url`、`model`、`api-key` 四个配置项。

#### Scenario: 默认关闭
- **WHEN** 配置文件中未显式设置 `search.rerank.enabled`
- **THEN** 系统 SHALL 视为 `enabled=false`，检索行为与无 rerank 时完全一致

#### Scenario: 显式开启
- **WHEN** 配置文件中设置 `search.rerank.enabled=true` 且提供 `base-url` 与 `model`
- **THEN** 系统 SHALL 在检索后启用 rerank 精排

### Requirement: 召回后 rerank 精排
系统 SHALL 在 `MultiQueryHybridSearchService.multiQuerySearch()` 的 RRF 融合完成、返回结果之前，对 top-K 候选调用外部 OpenAI 兼容 `/rerank` 端点进行精排重排序。

#### Scenario: 开启时重排
- **WHEN** `search.rerank.enabled=true` 且 RRF 融合产出 top-K 候选
- **THEN** 系统 SHALL 调用外部 rerank 端点，并按 rerank 分数重排候选顺序后返回

#### Scenario: 关闭时零回归
- **WHEN** `search.rerank.enabled=false`
- **THEN** 系统 SHALL 跳过 rerank 步骤，返回结果与历史逻辑（RRF 融合后直接返回）逐位一致

#### Scenario: rerank 服务异常降级
- **WHEN** `search.rerank.enabled=true` 但 rerank 端点调用失败或超时
- **THEN** 系统 SHALL 降级为不重排（按 RRF 融合原顺序返回），且不中断检索流程

