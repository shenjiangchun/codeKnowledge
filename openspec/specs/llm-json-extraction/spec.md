# llm-json-extraction Specification

## Purpose
从 LLM 响应中跳过 thinking 块、鲁棒提取结构化 JSON 的通用能力。Spring AI 1.1.8 的 AnthropicChatModel 把 thinking 块和 text 块各建一个 Generation（thinking 排第一），而 ChatClient.content()/.entity() 只取第一个 Generation，因此需要遍历 generations 跳过 thinking 块从 text 块提取 JSON。

## Requirements
### Requirement: 从 LLM 响应中跳过 thinking 块鲁棒提取 JSON
系统 SHALL 提供 `RobustJsonExtractor.extract(ChatResponse, Class<T>)` 能力，遍历响应中的所有 Generation，跳过思考散文块（thinking），对 text 块做 fence 剥离 + 平衡括号提取 + 严格/宽松两级解析，反序列化为目标类型 `T`。不得依赖 `ChatClient.content()` / `.entity()`（二者只取 `getResult()` = 第一个 Generation，拿到的是 thinking 散文）。

#### Scenario: thinking 块在前、text 块在后
- **WHEN** LLM 响应的 generations 为 `[thinking 散文 Generation, text JSON Generation]`（Spring AI 1.1.8 AnthropicChatModel 的真实行为）
- **THEN** 系统跳过 thinking 块，从 text 块提取 JSON 并成功反序列化为目标类型

#### Scenario: 前导思考散文 + JSON 同在一个 text 块
- **WHEN** text 块内容以思考散文开头、随后紧跟合法 JSON 对象（首字符非 `{`）
- **THEN** 系统扫描平衡花括号提取出 JSON 并成功反序列化

#### Scenario: markdown fence 包裹
- **WHEN** text 块为 ```json\n{...}\n``` 形式
- **THEN** 系统剥离 fence 后提取 JSON 并成功反序列化

#### Scenario: 宽松语法（尾逗号/单引号/注释）
- **WHEN** JSON 含尾随逗号、单引号、注释或无引号字段名
- **THEN** 系统在严格解析失败后回退宽松 ObjectMapper 解析成功

#### Scenario: 提取失败降级
- **WHEN** 所有 Generation 均无法提取出目标类型
- **THEN** 系统返回 null，由调用方走既有降级路径（不抛异常、不打印完整 raw）
