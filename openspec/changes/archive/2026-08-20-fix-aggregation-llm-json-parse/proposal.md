## Why

架构现状分析的聚合管道在大型项目上必定失败：中转服务器把 LLM 的「思考链散文」与「最终 JSON」都拼进 text 块，Spring AI 的 `BeanOutputConverter` 期望输入整体即合法 JSON、不剥离前导散文，于是把「散文 + JSON」当 JSON 解析，首字符非 `{` 直接抛 `JsonParseException`。结果是 Community / DomainName / FreeLayerRole 三个阶段的 LLM 产出全部为 0，前端领域全部未归属、包级依赖大量 UNKNOWN。

这不是模型选型、批大小、thinking 参数或 Neo4j 错库的问题（均已排除）——根因是解析逻辑对「LLM 输出必然是干净 JSON」的假设太严格。必须放弃整体解析，改为从混合文本中鲁棒提取 JSON。

## What Changes

- 新增独立的鲁棒 JSON 提取工具（从 `RamClaudeJsonClient.parseJsonResponse` 的 7 策略中抽取，去 deprecated 依赖、改为泛型返回）。
- `MultiDimensionCommunityDetector.callLlm`：由 `.entity(DomainGrouping.class)` 改为 `.content()` 拿原始文本 + 鲁棒提取 + 反序列化为 `DomainGrouping`。
- `LayerRoleLlmService.resolveRoles`：由 `.entity(RoleGrouping.class)` 改为 `.content()` 拿原始文本 + 鲁棒提取 + 反序列化为 `RoleGrouping`。
- 对模式 A（超限截断）与模式 B（散文前缀污染）均生效。

## Capabilities

### New Capabilities
- `llm-json-extraction`: 从 LLM 混合文本输出（思考散文 / markdown fence / 尾部散文 / 截断）中鲁棒提取 JSON 并反序列化为目标类型的能力。

### Modified Capabilities
- `aggregation-pipeline`: 聚合管道 LLM 结构化输出的解析方式，从「依赖 BeanOutputConverter 整体解析」改为「鲁棒提取」，要求 LLM 输出含思考散文前缀时仍能正确产出领域/层级结果。

## Impact

- 新增文件：`com.huawei.hisi.knowledgegraph.aggregation.llm.RobustJsonExtractor`（或同类名）。
- 修改文件：`MultiDimensionCommunityDetector.java`（`callLlm`）、`LayerRoleLlmService.java`（`resolveRoles`）。
- 参考（不改动）：`RamClaudeJsonClient.parseJsonResponse` 的 7 策略提取逻辑。
- 无 API 变更、无新增依赖（复用 Jackson ObjectMapper）。
- 需补单元测试：覆盖「前导散文 + JSON」「markdown fence 包裹」「尾部散文」「JSON 截断」四类污染场景。
