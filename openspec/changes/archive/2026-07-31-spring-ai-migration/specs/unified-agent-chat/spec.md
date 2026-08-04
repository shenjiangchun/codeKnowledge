# Unified Agent Chat

## ADDED Requirements

### Requirement: 统一 Agent REST API

系统 SHALL 提供 `POST /api/chat/{agentType}` 端点，以 Spring AI 原生 SSE 格式替代当前 6 组碎片化 SSE 端点。

#### Scenario: apm-diagnose 替代

**Given** 前端发送 `{ "message": "...", "sessionId": "...", "context": {...} }` 到 `POST /api/chat/apm-diagnose`  
**When** AgentRegistry 根据 agentType 加载 Anthropic ChatClient + SystemPrompt + Tools  
**Then** 响应 SHALL 为 Spring AI 原生 SSE 格式 (`data: {"choices":[...]}`)，功能等价于旧版 `POST /api/claude/universal-chat` + `scenario:APM_DIAGNOSIS`

#### Scenario: SSE 流结束

**Given** LLM 完成响应  
**When** ChatClient.stream() 结束  
**Then** SSE 流 SHALL 终止于 `data: [DONE]`

#### Scenario: 错误处理

**Given** Anthropic API 返回 429 或 5xx  
**When** @CircuitBreaker 状态为 OPEN  
**Then** SHALL 返回 HTTP 503 + `{"error":"AI service temporarily unavailable"}`，不丢失请求

## ADDED Requirements

### Requirement: YAML 声明式 Agent 配置

系统 SHALL 支持通过 `application.yml` 声明式定义 Agent 类型，无需编写 Java 类。

#### Scenario: 新增 Agent 类型

**Given** 在 `hisi.agents` 下新增 YAML 配置块  
**When** Spring ApplicationContext 刷新  
**Then** AgentRegistry 自动注册该类型；`POST /api/chat/{newType}` 立即可用

#### Scenario: 配置缺失

**Given** agentType 未在 hisi.agents 中配置  
**When** `POST /api/chat/unknown-type`  
**Then** SHALL 返回 HTTP 404 + `{"error":"Unknown agent type: unknown-type"}`
