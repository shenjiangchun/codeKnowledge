# 生成中心后端：需求规格

## 目的
扩展 Spring AI Agent 基础设施，新增 "test-gen" Agent Type，利用 Claude Sonnet 4 的推理能力和图谱工具，为架构师生成测试建议和重构建议。

## 范围
### 本次范围
- 新增 AgentType "test-gen"（在 AgentTypeRegistry 中注册）
- 扩展 AgentTools 新增 4 个 @Tool：`getBlastRadius`, `getHotspotSummary`, `getDsmViolations`, `getDomainBoundaries`
- 复用已有 `agentChatClient` bean（Spring AI ChatClient + AnthropicChatModel + Claude Sonnet 4）
- 新增 MCP 工具：`kg_test_suggestions(nodeId, projectPaths)` 和 `kg_refactor_suggestions(moduleName, projectPaths)`
- LLM 生成的 output 格式：场景描述级别的测试建议（不生成完整 Java 代码）

### 非目标
- 完整测试代码生成（只生成可读的场景描述）
- 实际写入 .java 测试文件到磁盘
- 替换已有的 `UnifiedTextService` GLM 管道（该管道继续用于方法描述批量生成）

## ADDED Requirements
### Requirement: kg_test_suggestions 生成测试场景建议
系统 MUST 提供 MCP 工具 `kg_test_suggestions`，输入 nodeId，输出 LLM 生成的测试场景建议列表。

#### Scenario: 为 OrderService.placeOrder 生成测试建议
- 前提：用户或 AI 助手调用 kg_test_suggestions(nodeId="...OrderService.placeOrder", projectPaths=["hisi-dev-tool"])
- 当：后端先计算爆炸半径（Cypher 查询 upstream+downstream+bridge），然后构建 Claude 的 system prompt（含方法签名、描述、爆炸半径数据、调用链拓扑）
- 则：Claude 返回结构化 JSON，含 testCases 数组（每项含 scenarioDescription/testType NORMAL|EXCEPTION|BOUNDARY|INTEGRATION/priority HIGH|MEDIUM|LOW/affectedAPI）
- 并且：coverage 对象（affectedAPIsCount/coveredAPIsCount/uncoveredAPIs）

#### Scenario: 失败处理
- 当：Claude 调用超时（30s）或返回非 JSON
- 则：返回 HTTP 200，testCases=[], error="LLM 生成超时，请稍后重试"

### Requirement: AgentTools 扩展 4 个新 @Tool
系统 MUST在 Spring AI 的 AgentTools 中新增 4 个 @Tool 方法，供生成中心的 Claude Agent 在推理过程中自动调用 KG 数据。

#### Scenario: Claude 在生成测试建议时查询爆炸半径
- 前提：Claude 正在处理 kg_test_suggestions 请求，需要知道 OrderService.placeOrder 的影响面
- 当：Claude 通过 Spring AI 自动调用 @Tool getBlastRadius(nodeId="...OrderService.placeOrder")
- 则：工具返回 downstream/upstream/bridge 数据，Claude 继续推理并生成更精准的测试建议

### Requirement: 新增 "test-gen" Agent Type
系统 MUST在 AgentTypeRegistry 中注册 "test-gen" Agent Type，使用已配置的 `agentChatClient`（Claude Sonnet 4），配备扩展后的 AgentTools。

#### Scenario: MCP 工具触发 test-gen Agent
- 前提：AI 助手调用 kg_test_suggestions MCP 工具
- 当：后端收到请求 → 创建 test-gen Agent 实例 → 构建 system prompt（架构分析专家角色 + KG 工具说明）
- 则：Agent 使用 Claude Sonnet 4 推理，在需要时自动调用 KG @Tool 查询数据，最终返回结构化测试建议

### Requirement: 生成中心 LLM 响应采用结构化输出（非文本解析）
系统 MUST 在 kg_test_suggestions 和 kg_refactor_suggestions 中，通过 Spring AI 的 `.entity()` 强制结构化输出（对 anthropic 模型走 tool use 强制，对 openai 模型走 response_format），替代 `.content()` + 手写 JSON 正则解析。

#### Scenario: 测试建议返回强类型列表
- 前提：Claude 模型可用（anthropic 中转支持 tool use 强制结构化输出）
- 当：后端调用 `.entity(new ParameterizedTypeReference<List<TestSuggestion>>() {})`
- 则：返回 `List<TestSuggestion>`，每项含 `scenario`(String)/`type`(UNIT|INTEGRATION|EXCEPTION|BOUNDARY)/`priority`(HIGH|MEDIUM|LOW)
- 并且：字段类型由 JSON Schema 强制，LLM 不会返回 markdown 围栏、解释文字或缺失字段

#### Scenario: 重构建议返回强类型列表
- 前提：Claude 模型可用
- 当：后端调用 `.entity(new ParameterizedTypeReference<List<RefactorSuggestion>>() {})`
- 则：返回 `List<RefactorSuggestion>`，每项含 `issue`(String)/`direction`(String)/`impact`(String)/`priority`(HIGH|MEDIUM|LOW)

#### Scenario: 失败处理
- 当：结构化输出转换失败（如模型不支持 tool use 或返回字段类型不匹配）
- 则：抛异常，Controller catch 后返回 `ApiResponse.error("LLM 生成测试建议失败: " + e.getMessage())`，不降级为文本解析

## 兼容性与外部契约
- 复用已有 `agentChatClient` bean（Spring AI ChatClient），不创建新的 ChatClient
- 新增 @Tool 方法与已有 10 个 @Tool 在同一个 AgentTools 类中共存
- AgentTypeRegistry 已有 6 个 agent type（apm-diagnose/call-chain-analysis/log-analysis/code-analysis/dialog/fix），新增 "test-gen" 遵循已有注册模式
- 结构化输出依赖 Spring AI 1.1.8 的 `.entity()` 机制（anthropic 模型走 tool use 强制，已用 curl 实测中转端点支持）

## 验收矩阵
| 需求/场景 | 验证方法 | 可证伪的失败表现 |
|-----------|---------|----------------|
| kg_test_suggestions 返回测试建议 | 集成测试：mock Claude 返回 JSON → 断言 testCases[] 非空 | testCases 为空 |
| getBlastRadius @Tool 可被调用 | 单元测试：Spring AI ToolCallback → 断言返回 BlastRadiusData | @Tool 方法注册失败 |
| test-gen Agent Type 注册 | 单元测试：AgentTypeRegistry.get("test-gen") → 断言非 null | 返回 Optional.empty() |
| Claude 超时降级 | 集成测试：mock 超时 → 断言 error 消息 + testCases=[] | 抛出 500 异常 |
| LLM 非 JSON 响应处理 | 单元测试：mock "这是测试建议" 文本 → 断言 error="LLM 返回格式异常" | 抛出 JSON 解析异常 |

## 已确认决策
| 决策项 | 选择 | 批准人/日期 | 影响的需求 |
|--------|------|------------|-----------|
| 生成中心 LLM 管道 | Claude Sonnet 4 via Spring AI ChatClient（不走 GLM-4） | 用户 / 2026-08-11 | test-gen Agent、kg_test_suggestions |
| 输出格式 | 场景描述级别（不生成完整代码） | 用户 / 2026-08-11 | kg_test_suggestions |
| 前端展示 | 合并为一个上下文感知面板 | 用户 / 2026-08-11 | 前端生成中心面板 |
