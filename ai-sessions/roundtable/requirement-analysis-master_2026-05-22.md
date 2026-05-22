---
topic: 需求分析大师 — 基于 Claude SDK 封装的核心业务流工具
date: 2026-05-22
mode: 快速
rounds: 4 (提前终止，原定 10 轮)
roles:
  - Agent 系统架构师
  - 产品体验设计师
  - 知识图谱专家
  - 后端 Claude SDK 封装派
  - 质疑者 / 魔鬼代言人
conclusion: 固定四段骨架(Clarify→Impact→Implement→Verify) + 中控 MCP 编排 + 三圈图谱影响分析 + HITL 一等事件 + 事件溯源 + 5 指标 Eval + MVP 三阶段
---

# 圆桌讨论记录：需求分析大师

## 1. 最终结论：一句话

基于 Claude Agent SDK 封装，通过「**固定四段骨架 + 知识图谱三圈影响分析 + 人在回路澄清**」产出**业务/UI/技术三件套需求方案**的后端核心能力，由一个核心 MCP 中控编排。

**核心硬优势**：知识图谱三圈精确影响分析 + 5 个可证伪的 Eval 指标。

---

## 2. 业务方案：固定四段骨架 DAG

```
Clarify ──▶ Impact(3 子节点) ──▶ Implement(3 输出) ──▶ Verify
   ▲                                                       │
   └──── schema 校验失败时回边 ────────────────────────────┘
```

### 节点契约

| 节点 | 输入 | 输出 | 模型 |
|---|---|---|---|
| **Clarify** | `user_request`, `missing_fields` | `intent`, `project_paths`, `target_modules`, `constraints`, `acceptance_criteria` | Sonnet |
| **Impact** | `intent`, `project_paths` | `involved`, `modified`, `affected`, `risk_level` | Haiku + Sonnet |
| **Implement** | `modified`, `affected`, `acceptance_criteria` | `biz_plan`, `ui_plan?`, `tech_plan` | Opus(架构) + Sonnet(编码) |
| **Verify** | `tech_plan`, `acceptance_criteria` | `checks[]`, `pass`, `blockers` | Haiku |

### 两处受控动态分支
1. Impact 探索深度（首轮 query 复杂度判定）
2. Implement 是否拆 3 个 sub-agent 并行（`risk_level=high` 触发）

### 契约驱动 CLARIFY
节点执行前 schema 校验缺字段 → 抛 `ClarifyRequired(missing=[...])` → 回边 Clarify → 反问 → 补齐后 resume（不重跑已完成节点，inputs_hash 命中）。

---

## 3. 知识图谱集成：三圈定义 + Impact 10 步 SOP

### 三圈严格包含关系（涉及 ⊂ 修改 ⊂ 影响）

- **涉及圈** = `hybrid_search` ∪ `kg_entry_points` ∪ `kg_implementations`
- **修改圈** = 涉及圈 ∪ `kg_callees_tree(depth≤2, 同模块)`
- **影响圈** = 修改圈 ∪ `kg_affecting` ∪ `kg_downstream` ∪ `kg_feign_chain` ∪ `kg_mq_chain` ∪ `kg_bridges`

### Impact 节点 SOP（10 步）

| 步骤 | 工具 | 输入 | 输出 | 失败回退 |
|---|---|---|---|---|
| 1 | `kg_list_projects` → 用户确认 | - | `ctx.projectPath` | 空 → 提示先建图 |
| 2 | `hybrid_search`(limit=15, graphDepth=1) | 抽取需求 query 模板 | `involved.seeds[]` | 0命中 → 关键词降级 |
| 3 | `kg_entry_points`(ALL) | ctx.projectPath | `involved.entries[]` | 超时跳过 |
| 4 | `kg_implementations` | seeds 接口 | `involved.impls[]` | 无实现保留 seed |
| 5 | `kg_callees_tree`(maxDepth=2) | seeds | `modified.tree[]` | 单点失败记录继续 |
| 6 | `kg_affecting` + `kg_downstream`(depth=3) | modified 叶子 | `impacted.upstream/downstream` | skip |
| 7 | `kg_feign_chain` + `kg_mq_chain` | 带注解节点 | `impacted.crossService[]` | 空数组 |
| 8 | `kg_bridges` | impacted 全集 | `impacted.bridges[].redFlag=true` | 不标红不阻断 |
| 9 | 本地风险评分 | 三圈节点数+桥接数+跨服务数 | `risk.score`, `risk.level` | - |
| 10 | 3 个 deterministic 校验 | 完整 JSON | `validation.passed` | 二次失败 → needs_human |

**风险评分公式**：`0.5*桥接权重 + 0.3*扇入归一化 + 0.2*深度倒数`

**3 个 deterministic 校验规则**：
- 入口闭包校验：LLM 声称影响 ⊆ `kg_root_entries` 反查集合
- 接口实现完备性：`kg_implementations` 返回的实现类必须全部出现在修改清单
- MyBatis SQL 一致性：`kg_mybatis_sql` 提取 SQL 与 LLM 声称字段集做差集

### hybrid_search query 模板
```
"{动作动词} {核心业务名词} {限定条件}"
例：需求"用户下单时增加风控校验"→ query="订单创建 风控校验 拦截逻辑"
```

### 三圈合并 JSON
```json
{
  "ctx": {"projectPath": "...", "requirement": "..."},
  "involved":  {"seeds":[], "entries":[], "impls":[]},
  "modified":  {"tree":[], "failed_nodes":[]},
  "impacted":  {
    "upstream":[], "downstream":[],
    "crossService":[{"type":"feign|mq", "topic|service", "peers":[]}],
    "bridges":[{"nodeId", "redFlag":true, "reason"}]
  },
  "risk": {"score":72, "level":"HIGH", "factors":{"bridges":3,"crossSvc":2,"depth":4}},
  "validation": {"passed":true, "violations":[]},
  "summary_nl": "本次修改涉及 X 个方法..."
}
```

---

## 4. UI 交互方案

### 7 屏主流程
1. **输入屏**：自然语言需求框 + 项目选择器 + 历史引用
2. **草稿生成屏**：10s 内出 v0 骨架（流式打字，"假设 X/Y/Z" 高亮）+ 最多 2 个岔路澄清模态
3. **图谱预览屏**：三圈流式渲染（金 → 橙 → 灰），右侧角色 Tab（业务/产品/架构师）
4. **文档编辑屏**：左大纲右正文 + 段落级 AI 重写
5. **待确认清单屏**：异步问题集中处理
6. **导出屏**：Word/Markdown/在线链接/"发群里"卡片
7. **复盘屏**：本次需求与历史相似度 + 复用建议

### 关键交互机制

| 机制 | 设计 |
|---|---|
| 澄清节奏 | 草稿优先 + 关键岔路阻断（≤2 问）+ 异步待确认侧栏 |
| 修正方向收敛 | 1-2 次自由 → 3 次弹 A/B/C → 4 次冷静期+导出 → 5 次锁定 |
| DAG 可视化 | 默认折叠为进度条，点击展开，失败节点"从此 resume" |
| 节点反馈 | 👍/👎/✏️ + 原因 chip → `evaluation_dataset` |
| 成本透明 | 顶部 `已用 12.3k / 预算 50k · ¥0.42` |
| 角色分视图 | 业务方=入口列表；产品=气泡图；架构师=完整三圈 |

### 安全/合规 UI
- 节点三色徽章（绿公开/黄组内/红密）
- 脱敏前置浮层（敏感词实时高亮）
- 首启合规墙（数据出境告知）
- 核心域走本地模型

### 多人协作
- Session 广播条 + git-like 分支（diff 三栏 + cherry-pick）
- 领域词表轻 PR 流
- 发布环节双签

### 治理看板（PM/管理员）
- 数据看板 / 成本看板 / 安全审计看板

---

## 5. 技术方案

### 5.1 分层架构（同 JVM 进程）
```
HTTP/SSE API ── Frontend
       │
ClaudeSessionService（薄封装）
       │
Orchestrator ── EventStore + CheckpointStore + HITL Queue
       │
MCP Server（Spring Bean，stdio+http 双 transport）
       │
AgentRegistry ── CircuitBreaker ── SubAgents | Remote
```

### 5.2 ClaudeSessionService 核心方法
```ts
createSession(userId, plan): SessionId
sendUserMessage(sid, text, opts?): AsyncIterable<SSEEvent>
injectSystemMessage(sid, msg)   // checkpoint/HITL 回填
registerTool(sid, toolDef, handler)
resumeSession(sid, fromEventId?): AsyncIterable
abortSession(sid, reason)
forkSession(sid, atEventId): SessionId
```

### 5.3 中控 MCP 8 个 Tools

| Tool | 功能 |
|---|---|
| `analyze_requirement` | 启动 session，自动选型 + 编排 |
| `get_session_status` | 查询状态/进度/事件 |
| `submit_clarification` | 用户回答澄清 |
| `revise_direction` | 修正方向（rewind/branch） |
| `resume_session` | 中断恢复 |
| `export_artifacts` | 导出半成品 |
| `register_subagent` | Agent 动态注册 |
| `list_capabilities` | 查询可用 capability |

### 5.4 Agent 注册表条目
```json
{
  "agent_id": "planner-v2",
  "version": "2.1.3",
  "capabilities": ["requirement.decompose","plan.generate","risk.identify"],
  "input_contract": {"schema_ref": "schemas/plan_input.json"},
  "model": {"preferred":"sonnet-4.6","fallback":"haiku-4.5"},
  "cost_hint": {"avg_tokens":4000,"p95_latency_ms":12000},
  "supports_hitl": true,
  "circuit_breaker": {"failure_threshold":5,"cooldown_s":30}
}
```

### 5.5 DB Schema
```sql
CREATE TABLE agent_session (
  id BIGINT PRIMARY KEY,
  user_id VARCHAR(64),
  plan_id VARCHAR(64),
  status ENUM('RUNNING','WAITING_CLARIFY','WAITING_HITL','PAUSED','DONE','FAILED','ABORTED'),
  current_node_id VARCHAR(64),
  step_count INT,
  last_checkpoint_event_id BIGINT,
  cache_key VARCHAR(64),
  version INT,
  created_at TIMESTAMP,
  updated_at TIMESTAMP
);

CREATE TABLE agent_event (
  id BIGINT PRIMARY KEY,
  session_id BIGINT,
  seq INT,
  type ENUM('USER_MSG','ASSISTANT_DELTA','TOOL_USE','TOOL_RESULT','CHECKPOINT',
            'CLARIFY_REQ','CLARIFY_RES','HITL_REQ','HITL_RES','ERROR'),
  payload JSON,
  tool_use_id VARCHAR(64),
  parent_event_id BIGINT,
  idempotency_key VARCHAR(64) UNIQUE,
  cumulative_tokens BIGINT,
  retry_count INT DEFAULT 0,
  clarify_round_no INT,
  inputs_hash CHAR(64),
  circuit_state ENUM('OK','TRIPPED','HUMAN_TAKEOVER'),
  cost_usd_cents INT,
  validator_status ENUM('OK','REJECTED','SKIPPED'),
  created_at TIMESTAMP,
  UNIQUE(session_id, seq)
);
```

### 5.6 防雪崩四件套
1. Prompt Caching 三级布点（L1 系统/L2 项目/L3 会话）→ 命中率 ≥70%
2. 强类型 tool 出参（JSON Schema + Ajv 校验）
3. 子任务上下文隔离（独立子会话，回灌 summary）
4. Deterministic Checkpoint（每 3 步 `verify_plan`）

### 5.7 熔断+幂等
```yaml
global: {max_tokens:200000, max_duration_min:30, max_clarify_rounds:5}
node:   {max_retries:3, backoff:exp(2^n), timeout_per_call_s:120}
user:   {max_sessions_per_day:20, max_concurrent:3}
idempotency_key: sha(session_id + node_id + attempt_seq + inputs_hash)
```

### 5.8 模型路由 + 成本

| 节点 | 模型 |
|---|---|
| Intake/槽位抽取 | Haiku |
| CLARIFY 问题生成 | Sonnet |
| Impact/KG 总结 | Haiku |
| 架构决策 | **Opus** |
| Implement 编码 | Sonnet |
| Verify 测试 | Haiku |
| Critic | Sonnet |

**预估成本**：复杂需求 ¥4-6/次；轻量 ¥0.8-1.5/次；日均 50 ≈ ¥3.6-5.4k/月（较 ¥3 万降 82%）。

---

## 6. Evaluation 框架

| 指标 | 公式 | 目标 |
|---|---|---|
| Recall@PR | \|预测∩PR实际\|/\|PR实际\| | ≥0.85 |
| Precision@PR | \|预测∩实际\|/\|预测\| | ≥0.6 |
| Jaccard | \|交\|/\|并\| | ≥0.5 |
| 回归 bug 率 | 4周内方案遗漏导致bug | ≤5% |
| 校验一次通过率 | 3规则全PASS | ≥0.9 |

**Ground Truth**：扫近 6 个月 master 已合并 PR（n≈300）。
**A/B**：Shadow mode 优先，6 周后切 50/50。

---

## 7. MVP 三阶段切分

| 阶段 | 周期 | 范围 |
|---|---|---|
| Phase 1 | 4 周 | **只读 Copilot**：hybrid_search + Impact 报告 + Markdown 输出 |
| Phase 2 | 4 周 | **半自动化**：MCP 编排 + Plan/Apply 分离 + 人工 approve gate |
| Phase 3 | 8 周 | **多 Agent**：角色化子 Agent + 并行编排 + 团队共享 + RBAC |

**永远不做**：
1. 全自动改生产代码
2. Agent 直接执行 DB migration / 线上发布
3. 跨租户共享 embedding/KG
4. 自研 LLM 网关替代成熟方案
5. AI 自动写测试并自动判定

**上线前红蓝军演练**：
1. 投毒（KG 注入恶意调用边）
2. 越权（跨项目读 / 高危 MCP）
3. 幻觉链（虚构 API）

---

## 8. 立即可启动的 4 件事

1. ✅ 建立 Agent 注册表（JSON Schema 化 capability）
2. ✅ 实现 ClaudeSessionService 薄封装
3. ✅ 写 8 个 MCP Tool（先实现 analyze_requirement / submit_clarification / resume_session 3 个最小集）
4. ✅ 采集 Ground Truth + 跑 Shadow mode

---

## 9. 各角色立场最终更新

| 角色 | 起始立场 | 最终立场 |
|---|---|---|
| 架构师 | 全动态 DAG Planner | 固定四段骨架 + 受控动态分支 + 契约驱动 CLARIFY |
| 体验师 | 前置集中澄清 | 草稿优先 + 岔路澄清 + 异步清单 |
| 图谱专家 | 图谱第一公民 | 图谱作为校验器+摘要源，非主 UI |
| SDK 派 | 同进程 subagent 默认 | 接受架构师严格规则（权限/审计/语言边界拆分） |
| 质疑者 | 5 大炮轰 | 建设性 MVP 三阶段切分，"先 Copilot 再 Autopilot" |
