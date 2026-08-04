# Unified WebSocket Gateway

## ADDED Requirements

### Requirement: 统一 WebSocket 网关

系统 SHALL 提供 `WS /ws/agent` 端点，使用统一协议合并 6 个独立 WS 端点，保留 `/ws/terminal` 和 `/ws/diagnosis`。

#### Scenario: 频道订阅

**Given** WS 连接已打开  
**When** 客户端发送 `{ "type":"subscribe", "channel":"ram-chat", "sessionId":"abc" }`  
**Then** ChannelRegistry 路由到 RamChatChannelHandler；handler 初始化该 session 的 WebSocket 事件推送

#### Scenario: 频道消息

**Given** 已订阅 `ram-chat`  
**When** 客户端发送 `{ "type":"message", "channel":"ram-chat", "payload":{"text":"hello"} }`  
**Then** RamChatChannelHandler 处理消息并返回 `{ "type":"event", "channel":"ram-chat", "eventType":"assistant_delta", "seq":42, ... }`

#### Scenario: 断线重连 with seq

**Given** 客户端断开后重连  
**When** 客户端发送 `subscribe` 带 `payload: { "lastSeq": 42 }`  
**Then** ChannelHandler 回放 seq > 42 的所有遗漏事件

#### Scenario: 未认证连接

**Given** WS 连接无有效 JWT token  
**When** 连接建立  
**Then** SHALL 返回 1008 Policy Violation

#### Scenario: 未知频道

**Given** 客户端订阅 `channel: "nonexistent"`  
**Then** 返回 `{ "type":"error", "eventType":"unknown_channel" }`
