# Spring AI 1.0.8 迁移：技术设计文档

> **版本**: Spring Boot 3.4.5 + Spring AI 1.0.8 + Java 17  
> **风险**: High  
> **关联**: proposal.md, 审计 00-reflact-overall-assessment.md

---

## 1. 决策记录

### 1.1 版本决策

| 决策 | 值 | 理由 |
|------|-----|------|
| Spring Boot | 3.4.5 | Spring AI 1.0.x 最低; Java 17 兼容; 保守升级三跳(3.2→3.4) |
| Spring AI | 1.0.8 | 最新 1.0.x patch; ChatClient/@Tool/ToolContext/ChatMemory 全可用 |
| Java | 17 | 维持现状; AI 1.0.x 运行时最低只需 17 |
| Anthropic SDK | spring-ai-starter-model-anthropic 1.0.8 | 对话 Agent |
| Zhipu | spring-ai-starter-model-openai 1.0.8 (compat) | KG 批处理描述生成 |

### 1.2 架构决策

| # | 决策 |
|---|------|
| D1 | JSON→Markdown 输出用于 RamChatOrchestrator 对话流; Phase2 DAG 节点改用 BeanOutputConverter + 类型化 record |
| D2 | LifecycleAdvisor 替代 onProgress; 框架级自动发射, 覆盖全部 9 agentType |
| D3 | PromptCacheAdvisor 实现 L1(Spring AI 自动) + L2(project context) + L3(session recent) |
| D4 | resilience4j @CircuitBreaker per-model 粒度; Step 1 接入 |
| D5 | HITL 30min 超时 → @Scheduled 清理 EXPIRED session + worktree |
| D6 | Step 10 拆为 10a(删旧 LLM 客户端) + 10b(Unified WS 网关) |
| D7 | Step 6 拆为 6a(RamChatOrchestrator 迁移) + 6b(Phase2 节点迁移 + 删 ram/sdk) |
| D8 | 前端 composable Phase 0 mock 测试, 后端 Step 1 才提供真实端点 |
| D9 | AgentOrchestrator + DiagnosticAgent 完整保留; 仅内部 LLM 调用切 ChatClient |
| D10 | FixFlowRunner Step 4 (generate_test) 独立保留; Step 6 (ai_fix) 升级 ReAct + max 5 轮 ToolCallingAdvisor |
| D11 | WS 合并 6 频道 → /ws/agent; /ws/diagnosis 保留不动 (AgentOrchestrator 专用); /ws/terminal 保留不动 |
| D12 | ChannelHandler 接口 + Spring DI 自动注入; 复用 DiagnosticAgent 的同构模式 |
| D13 | JSON 输出格式仅 Phase2 DAG 保留(结构化消费); RamChatOrchestrator 对话流改 Markdown; parseJsonResponse 7 策略 ~300 行随 RamClaudeJsonClient 删除 |

### 1.3 HITL 设计

```java
// ReAct 结束后一次性 HITL
@Component
public class HitlGateAdvisor implements ResponseAdvisor {
    @Override
    public AdvisedResponse adviseResponse(AdvisedResponse response) {
        if (isFixFlowAgent(response.context())) {
            String diff = worktreeService.getDiff(session.getBranch());
            session.setConfirmationState(WAITING);
            eventPublisher.pushHitlRequest(sessionId, turnId, diff);
            return response; // 不阻塞 Thread; confirmation 走 REST endpoint
        }
        return response;
    }
}

// 超时清理
@Scheduled(fixedRate = 900000) // 15min
public void cleanupExpiredHITL() {
    fixSessionRepository.findByStateAndOlderThan(WAITING, 30, MINUTES)
        .forEach(s -> {
            worktreeService.remove(s.getWorktreePath());
            s.setConfirmationState(EXPIRED);
            fixSessionRepository.save(s);
        });
}
```

### 1.4 恢复策略

| 失败场景 | 恢复方式 |
|---------|---------|
| Step N 后端改出编译错误 | `git checkout` 还原该 Step 改动 |
| Step N agent-browser 验证失败 | 在当前分支修 bug, 重新 agent-browser |
| Step N 前端改出 `npm run build` 报错 | TypeScript 编译器精确指出文件+行号 |
| mvn test 回归失败 | 查看失败的测试类 → 修新的 ChatClient mock |
| jaCoCo 覆盖率不达标 | 补写新 ChatClient 路径的测试覆盖 |
| Step 1 `mvn dependency:tree` 发现版本冲突 | `<exclusions>` 排除冲突传递依赖 |

---

## 2. 系统架构

```
POST /api/chat/{agentType}
    │
    ▼
AgentChatController
    │
    ▼
AgentRegistry (YAML → AgentConfig)
    │
    ├─→ ChatClient (Spring AI Anthropic)
    │     ├─ Advisor Chain:
    │     │   ChatMemoryAdvisor
    │     │   ToolCallingAdvisor (ReAct)
    │     │   LifecycleAdvisor (progress)
    │     │   ChatEventPublisher (WS push)
    │     │   PromptCacheAdvisor (L1/L2/L3)
    │     │   RateLimitingAdvisor (concurrency)
    │     │   HitlGateAdvisor (fix flow only)
    │     │   ReasoningAdvisor (debug trace)
    │     └─ Tools (@Tool beans):
    │         AgentTools (kgSearch, readFile, ...)
    │
    ├─→ ChatClient (Spring AI OpenAI → Zhipu compat)
    │     └─ KG 描述/向量生成 (裸调用, 无 Advisor)
    │
    └─→ AgentOrchestrator (保留, 多Agent诊断)

WS /ws/agent (替代 6 个旧 WS 端点)
    │
    ▼
UnifiedWebSocketHandler → ChannelHandler 接口
    ├─ RamChatChannelHandler
    ├─ DialogChannelHandler
    ├─ DiagnosisChannelHandler? → 否, /ws/diagnosis 保留
    ├─ ApmChannelHandler
    ├─ LogAnalysisChannelHandler
    └─ LogFollowupChannelHandler

WS /ws/diagnosis (AgentOrchestrator 事件推送, 保留)
WS /ws/terminal (PTY, 保留)
```

---

## 3. 接口契约

### 3.1 POST /api/chat/{agentType}

```
Request: { "message": "...", "sessionId": "...", "context": {...} }
Response: text/event-stream (Spring AI 原生格式)

data: {"id":"1","choices":[{"delta":{"content":"..."}}],"usage":null}
data: {"id":"2","choices":[{"delta":{"tool_calls":[{"function":{"name":"hybrid_search","arguments":"..."}}]}}],"usage":null}
data: {"id":"3","choices":[{"finish_reason":"stop"}],"usage":{"prompt_tokens":1234,"completion_tokens":567}}
data: [DONE]
```

### 3.2 GET /api/chat/{agentType}/{sessionId}/stream?afterSeq=N

```
事件溯源投影 (ram/merge-analysis/workflow)
SSE 格式不变: data: {"seq":42,"type":"CHECKPOINT","payload":{...}}
```

### 3.3 WS /ws/agent

```
Client → Server:
  { "type": "subscribe", "channel": "ram-chat", "sessionId": "sid", "payload": {} }
  { "type": "message", "channel": "ram-chat", "sessionId": "sid", "payload": {...} }
  { "type": "unsubscribe", "channel": "ram-chat", "sessionId": "sid" }

Server → Client:
  { "type": "event", "channel": "ram-chat", "eventType": "assistant_delta",
    "sessionId": "sid", "seq": 42, "payload": {...}, "timestamp": 123 }
  { "type": "error", "channel": "ram-chat", "eventType": "error",
    "sessionId": "sid", "seq": 43, "payload": {"message": "..."} }
```

---

## 4. 需求→设计→任务→验证可追溯矩阵

| Requirement | Design | Task | Validation |
|-------------|--------|------|------------|
| 统一 ChatClient bean 注入 | AgentConfig ChatClient Bean + Advisor 链 | Step 1 | mvn test ChatClient Bean 注入成功 |
| Anthropic 对话 LLM 调用 | spring-ai-starter-model-anthropic | Step 2-9 | agent-browser 各页面功能正常 |
| Zhipu KG 描述生成 | spring-ai-starter-model-openai compat | Step 1 | mvn test ZhipuServiceTest |
| 9 agentType 路由 | AgentRegistry YAML 配置 | Step 2 | agent-browser 切换 agentType 验证 |
| SSE 流式输出 | Spring AI ChatClient.stream() | Step 2-5 | agent-browser 验证 token 逐字输出 |
| WS 频道合并 | ChannelHandler 接口 + UnifiedWSHandler | Step 10b | agent-browser 检查 WS 连接+消息 |
| HITL fix 流程 | HitlGateAdvisor + @Scheduled 清理 | Step 9 | agent-browser 修复完成→确认弹窗→超时清理 |
| ReAct fix 循环 | ToolCallingAdvisor max 5 轮 | Step 9 | mvn test FixFlowRunnerReActTest |
| Prompt Caching | PromptCacheAdvisor L1/L2/L3 | Step 1 | 观察 Anthropic API usage.prompt_tokens cache hit |
| resilience4j CB | @CircuitBreaker per-model | Step 1 | 模拟 API 故障→fallback 触发 |

---

## 5. 执行计划

```
feat/spring-ai-migration
  Phase 0: 前端 composable (mock 测试)
    └─ useChatStream + useAgentWebSocket + 单元测试

  Phase 1: 基础设施
    └─ Step 1: pom.xml (Boot 3.4.5 + AI 1.0.8) + ChatClient Bean + resilience4j

  Phase 2: 逐模块迁移
    ├─ Step 2: apm-diagnose 迁移
    ├─ Step 3: call-chain-analysis 迁移
    ├─ Step 4: log-analysis 迁移
    ├─ Step 5: code-analysis 迁移
    ├─ Step 6: dialog 迁移 + naturalLanguageStore 重构
    ├─ Step 7a: RAM Chat 迁移 (RamChatOrchestrator)
    ├─ Step 7b: RAM Phase2 节点 + 删除 ram/sdk
    ├─ Step 8: MergeAnalysis + Workflow URL 迁移
    └─ Step 9: FixFlowRunner ReAct + HITL gate

  Phase 3: 清理
    ├─ Step 10a: 删除所有旧 LLM 客户端 + @Deprecated 类型
    ├─ Step 10b: Unified WebSocket 网关 (ChannelHandler)
    └─ Step 11: agent-browser 全链路回归 (10 页)
```

---

## 6. 风险与回滚

| 风险 | 缓解 | 回滚 |
|------|------|------|
| Boot 3.4.5 升级破坏现有功能 | `mvn test` 全量回归 | git revert Step 1 |
| Spring AI 1.0.8 ChatClient 行为与旧客户端不一致 | 逐模块 agent-browser 验证 | 该 Step revert |
| Prompt Caching 实际收益低于预期 | 配置化可选; 不依赖它实现核心功能 | N/A |
| jaCoCo 覆盖率跌破 80% | 新测试先通过再删旧 | 补测试 |
| ReAct 循环 token 费用暴增 | max 5 轮上限; CostTrackingAdvisor 监控 | 调低 maxIterations |
