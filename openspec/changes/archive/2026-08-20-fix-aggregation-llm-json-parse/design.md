## Context

架构现状分析的聚合管道有两条 LLM 结构化输出调用链，均依赖 Spring AI 的 `BeanOutputConverter`（通过 `.entity(Class)` 触发）：

- `MultiDimensionCommunityDetector.callLlm` → `.entity(DomainGrouping.class)`
- `LayerRoleLlmService.resolveRoles` → `.entity(RoleGrouping.class)`

`BeanOutputConverter` 的契约是「LLM 返回整体即合法 JSON」，不剥离前导/尾部非 JSON 文本。当前中转服务器（OpenAI→Anthropic 格式转换）在复杂结构化输出下，会把模型的思考链散文拼进 text 块、或贴在 JSON 前，导致首字符非 `{` → `JsonParseException`。换模型 / 调批大小 / 关 thinking 均无效（根因报告已逐一排除），唯一稳路是放弃整体解析、改为鲁棒提取。

项目内已有 `RamClaudeJsonClient.parseJsonResponse` 的 7 策略提取实现，但该类已 `@Deprecated(forRemoval=true)`，方法为 `private` 且返回 `Map<String,Object>`（非泛型），不适合直接复用——需抽取为独立、非 deprecated、泛型的工具。

## Goals / Non-Goals

**Goals:**
- 让两条 LLM 调用链在「思考散文前缀 + JSON」污染下仍能正确反序列化出 `DomainGrouping` / `RoleGrouping`。
- 同时覆盖模式 A（超限截断）与模式 B（散文前缀污染）。
- 抽取出可复用的、无 deprecated 依赖的鲁棒 JSON 提取能力，并配单元测试。

**Non-Goals:**
- 不改中转服务器行为、不改模型选型、不调 thinking 参数（均被证明无效）。
- 不重构 `RamClaudeJsonClient` 本体（保持其 deprecated 状态不动，仅从中借鉴逻辑）。
- 不处理根因报告「附：独立问题」里的 `transactionManager` / `ClientAbortException` / Neo4j 错库（已另有处理）。

## Decisions

**D1：新增独立工具类，而非复用 `RamClaudeJsonClient.parseJsonResponse`**

- 备选 A：直接把 `parseJsonResponse` 改成 public/static 复用。否决——该类 deprecated、返回 `Map` 非泛型、且让聚合包反向依赖 RAM 包。
- 决策：新增 `RobustJsonExtractor`（`com.huawei.hisi.knowledgegraph.aggregation.llm`），泛型签名 `public static <T> T extract(ChatResponse response, Class<T> type)`，内部采用从 `parseJsonResponse` 借鉴的策略序列。

**D2：策略序列——遍历 Generation + 每块内鲁棒提取**

1. 遍历 `response.getResults()`，对每个 Generation 取 `getOutput().getText()`。
2. 对每个文本块：trim → 直接解析（纯 JSON）→ 剥 fence 解析 → 扫描每个 `{` 平衡括号提取并解析。
3. 解析用严格 ObjectMapper → 宽松 ObjectMapper（尾逗号/单引号/注释/无引号字段名）两级降级。
4. 返回第一个成功反序列化为目标类型的结果；全部失败返回 null。

**D3：调用点改为 `.chatResponse()` 拿完整 Generation 列表**

- 决策：`extractionChatClient.prompt()...call().chatResponse()` 返回完整 `ChatResponse`，再 `RobustJsonExtractor.extract(resp, DomainGrouping.class)`。
- 关键事实（反编译 Spring AI 1.1.8 证实）：`.content()` 和 `.entity()` 都只调 `getResult()`（= `getResults().get(0)`，即第一个 Generation）。而 `AnthropicChatModel` 把 thinking 块和 text 块各建一个 Generation、thinking 排第一。所以 `.content()` / `.entity()` 拿到的是 thinking 散文，不是 JSON——这是本 bug 的直接机制。
- 因此必须用 `.chatResponse()` 拿全列表，遍历跳过 thinking 块。

## Risks / Trade-offs

- [提取到错误 JSON 片段（多个候选时选错）] → 候选按「包含目标字段」优先评分：`DomainGrouping` 要求含 `domains`，`RoleGrouping` 要求含 `items`；实际由「解析成功 + 非 null」作为通过标准，取第一个成功解析的候选。
- [宽松 mapper 可能接受语义错误但仍能 bind 的 JSON] → 接受；解析成功后仍走既有 `logCoverage` / `normalizeRole` 校验兜底。
- [性能] → 平衡括号扫描 O(n)，四步序列对单次 LLM 输出（K 级字符）毫秒级，可忽略。
- [日志隐私] → 提取失败仅记 `length` + 前 N 字符，不打印完整 raw（对齐业界五级管线隐私约束）。

## Migration Plan

- 无数据迁移、无 schema 变更。仅改两处调用点 + 新增工具类。
- 回滚：git revert 该提交即可，两条调用链恢复 `.entity()` 旧行为。

## Open Questions

- 暂无。根因与修复方向已被日志 + curl 实测 + 业界实践三重印证。
