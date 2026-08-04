# Pi Agent 架构接入可行性分析

> 调研日期：2026-07-31
> 调研范围：两个主流 Pi Agent 框架 + 与当前 hisi-dev-tool 架构的适配分析

---

## 一、Pi Agent 生态系统

存在两套独立的 Pi Agent 框架，设计哲学差异显著：

### 1. Pi by Mario Zechner (TypeScript/Node.js)
- **仓库**: `github.com/badlogic/pi-mono`，MIT，5100+ commits，v0.80.x
- **设计哲学**: 极致极简 —— 仅 4 个内置工具（read/write/edit/bash），拒绝 MCP
- **核心能力**: 统一 LLM API (20+ provider)、agent loop 状态机、事件流、TUI/WebUI/headless 多模式
- **排名**: TerminalBench #2（Claude Opus 4.5，82 tasks）

```
pi-coding-agent (CLI) → pi-agent-core (agent loop) → pi-ai (LLM API) → pi-tui (UI)
```

### 2. pi-coding-agent by Ashutosh Sharma (Python)
- **安装**: `pip install pi-coding-agent`，MIT
- **设计哲学**: 精简但功能完备 —— 14 工具 + 18 技能 + MCP + 子 agent + 持久记忆
- **核心能力**: 多 provider 自动检测、skill 自动路由、沙箱工作区、自我审查

---

## 二、当前 hisi-dev-tool 能力矩阵 vs Pi Agent 可增强项

| 能力维度 | 当前 hisi-dev-tool | Pi Agent 提供的增强 | 差距评估 |
|---------|-------------------|-------------------|---------|
| **Agent 循环** | RamChatOrchestrator（单轮 tool-use）、DagExecutor（状态机 DAG） | Pi agent-core 的通用 agent loop + event streaming（10 种事件类型） | 🔴 可增强 |
| **Skill 系统** | 无 skill 路由/routing | pi-coding-agent 18 个 skills + 自动评分匹配 | 🔴 缺失 |
| **持久记忆** | AgentEvent 持久化到 SQLite（仅存档） | `.pi/memory.md` 跨会话记忆 + BM25 本地检索 | 🟡 可增强 |
| **多 Provider** | Spring AI ChatClient（单 Anthropic） | 20+ provider 统一 API + 跨 provider 上下文切换 | 🟡 可增强 |
| **子 Agent** | 无 | pi-coding-agent delegate 子 agent + pi-mono 上下文树分支 | 🟡 可增强 |
| **工具系统** | AgentTools 10 个 @Tool 方法 | TypeBox schema 验证 + tool hooks + 并行/串行执行 | 🟢 已有 |
| **守护/安全** | AdminOnlyInterceptor JWT | 内建 secret 防泄露 + 破坏性命令确认 + 路径沙箱 | 🟢 已有 |
| **TUI/交互** | Element Plus Web UI + Vue 3 | pi-tui 保留模式终端 UI + pi-coding-agent Streamlit | 🟢 已有（Web UI 更强） |

---

## 三、推荐接入方案

### 方案 A: TypeScript Pi 作为 Agent Sidecar（推荐 ⭐）

```
┌─────────────────────────────────────┐
│         hisi-dev-tool 前端          │
│         Vue 3 + Element Plus        │
└──────────────┬──────────────────────┘
               │ REST + WebSocket
┌──────────────▼──────────────────────┐
│         hisi-dev-tool 后端          │
│         Spring Boot 3.5.16          │
│         ChatClient + AgentTools     │
└──────────────┬──────────────────────┘
               │ HTTP/gRPC sidecar
┌──────────────▼──────────────────────┐
│         Pi Agent Sidecar            │
│         Node.js (pi-agent-core)     │
│    ┌──────────────────────────┐     │
│    │ Agent Loop + Skill Route │     │
│    │ 20+ Provider API         │     │
│    │ Tool Hooks + Steering    │     │
│    └──────────────────────────┘     │
└─────────────────────────────────────┘
```

**接入点**:
1. **Skill 路由层**: 前端的 6 种 agent type（apm-diagnose, call-chain-analysis, etc.）可映射为 Pi skills
2. **Agent Loop 增强**: 用 pi-agent-core 的事件流替代当前的 SSE text/event-stream，获得 10 种结构化事件
3. **多 Provider 路由**: 用 pi-ai 的 20+ provider 来降低单 provider 对 Anthropic 的依赖

**代价**: 引入 Node.js 依赖、增加部署复杂度

### 方案 B: Python pi-coding-agent 作为 MCP Server

```
hisi-dev-tool (Java) → MCP → pi-coding-agent (Python)
                              ├── Skill Router (18 skills)
                              ├── Memory (.pi/memory.md)
                              └── Sub-agent delegation
```

**接入点**:
1. **MCP 集成**: pi-coding-agent 原生支持 MCP，直接作为 MCP server 注册
2. **Skill 自动路由**: 前端的 user prompt 可通过 pi-coding-agent 自动评分匹配最相关的 3 个 skills
3. **持久记忆**: `.pi/memory.md` 可用作跨会话的 RAM 记忆

**代价**: Python 环境依赖、MCP 调用额外延迟

### 方案 C: 借鉴设计、内部实现（最轻量）

不是兼容 Pi Agent，而是将其架构设计引入当前系统：

1. **Skill 自动路由** → 在 AgentTypeRegistry 中新增 skill 评分匹配逻辑（纯 Java）
2. **Agent Loop 事件流** → 对 RamChatOrchestrator 做 ReAct 循环升级（P2-1 已有计划）
3. **内存管理优化** → 借鉴 pi-agent-core 的 JSON 序列化/反序列化，替代当前的 AgentEvent payload 字符串
4. **多 Provider** → 扩展 Spring AI ChatClient 配置，支持 model routing（P2-2 已有计划）

---

## 四、投入产出评估

| 方案 | 工作量 | 核心收益 | 风险 |
|------|--------|---------|------|
| A (TS Sidecar) | 3-5 周 | 20+ provider、结构化事件流、skill 系统 | Node.js 运维、跨进程通信延迟 |
| B (Python MCP) | 2-3 周 | 18 skills 开箱可用、MCP 标准协议 | Python 环境、MCP 协议开销 |
| **C (借鉴设计)** | **1-2 周** | 渐进式升级、零新依赖、与现有 P0-P2 计划完全对齐 | 需要手动实现 skill router |

---

## 五、建议

**推荐方案 C —— 借鉴 Pi Agent 设计理念，在现有 Spring Boot 架构中内部实现**：

1. **Skill 自动路由** 是最直接的增强：在前端 `mapSceneToAgentType()` 之前插入一层 skill 评分匹配
2. **ReAct 循环**（P2-1）天然适合引入 Pi 的事件流模式
3. **多 Provider 路由**（P2-2）天然对齐 pi-ai 的设计
4. 以上改动均可在 1-2 周内完成，且不引入任何新依赖

如果需要快速验证 Pi Agent 的能力，可先部署方案 B（Python MCP）作为旁路实验，验证 Skill 路由和持久记忆的效果，再决定是否深化。

---

## 六、参考

- [Pi by Mario Zechner (TypeScript/Node.js)](https://github.com/earendil-works/pi)
- [pi-coding-agent by Ashutosh Sharma (Python)](https://pypi.org/project/pi-coding-agent/)
- [Pi framework analysis](https://github.com/larsderidder/framework-analysis/blob/main/tier-2/pi.md)
- [Building Pi: A Minimal, Extensible Coding Agent Framework - ZenML](https://www.zenml.io/llmops-database/building-pi-a-minimal-extensible-coding-agent-framework)
- [Pi monorepo (CloudEngineHub mirror)](https://github.com/CloudEngineHub/pi-mono)
