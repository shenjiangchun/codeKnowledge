# 技术决策(ADR)

> 本目录记录 hisi-mcp-server 的关键技术决策。每条 ADR 采用 **Context / Decision / Consequences** 三段式。

---

## ADR-001:采用 stdio 传输

- **背景**:MCP 支持 stdio、SSE、HTTP 等多种传输。我们的目标读者是 IDE 内的 AI 客户端 (Claude Code 等)。
- **决策**:使用 `StdioServerTransport`,本服务作为子进程被客户端 spawn。
- **取舍**:
  - ✅ 零端口、零鉴权、零网络配置
  - ✅ 客户端进程退出时自动回收
  - ❌ 不能跨主机访问;不能并发被多个客户端连接
- **影响**:任何 `console.log` 都会破坏 stdout 上的 JSON-RPC,本项目统一使用 `console.error`(见 ADR-005 关联约定)。

---

## ADR-002:TypeScript strict + ES2022 + NodeNext ESM

- **背景**:工具入参 schema 与运行时类型需要保持一致,避免漂移。
- **决策**:`tsconfig.json` 使用 `strict: true`、`target: ES2022`、`module/moduleResolution: NodeNext`,并在 `package.json` 设置 `"type": "module"`。
- **取舍**:
  - ✅ 严格模式抓出所有 `unknown` 处理疏漏
  - ✅ 与 MCP SDK 的 ESM 形态一致
  - ❌ 所有内部 import 必须带 `.js` 后缀(可见 `tools/index.ts` 中 `'./knowledgeGraphTools.js'`)
- **影响**:迁移到 CommonJS 不可行。

---

## ADR-003:HTTP 客户端使用原生 fetch

- **背景**:后端有 ~20 个 REST 接口需要调用。
- **决策**:不引入 axios / undici / got,使用 Node 18+ 原生 `fetch` + `AbortController`。
- **取舍**:
  - ✅ 零运行时依赖
  - ✅ 与 Web 平台一致的 API
  - ❌ 缺乏拦截器、重试、并发控制等高级功能
- **影响**:超时统一 30 秒,实现见 `ApiClient.request`。

---

## ADR-004:ApiClient 单例模式

- **背景**:每个工具调用都需要 ApiClient,但 baseUrl 全局唯一。
- **决策**:`getApiClient(baseUrl?)` 第一次调用创建并缓存,后续调用若提供 baseUrl 则更新。
- **取舍**:
  - ✅ 减少对象创建
  - ✅ 配置集中
  - ❌ 测试时需要重置单例(目前无测试)
- **影响**:`src/index.ts` 启动时调用一次注入 `HISI_API_URL`。

---

## ADR-005:工具错误以 `isError: true` 返回,不抛 McpError

- **背景**:LLM 看到错误时往往能根据错误内容自纠(例如换 projectPath 重试)。
- **决策**:
  - 协议级错误 (未知工具名) 抛 `McpError(MethodNotFound)`
  - 工具内任何 `Error` -> 包装为 `{ content:[text(JSON)], isError:true }`,JSON 含 `success:false / error / tool`
- **取舍**:
  - ✅ LLM 可读、可重试
  - ❌ 客户端无法用 JSON-RPC 错误码做精细处理
- **影响**:统一错误形态见 [05-接口文档 §4](../05-接口文档/index.md#4-错误码--错误形态)。

---

## ADR-006:工具按"能力域"分文件

- **背景**:20 个工具放一文件不利于演进。
- **决策**:三个文件 + 一个聚合 (`tools/index.ts`):
  - `knowledgeGraphTools.ts` (15)
  - `vectorTools.ts` (1)
  - `logTools.ts` (4)
- **取舍**:
  - ✅ 域内并行迭代
  - ✅ 每文件 <700 行(符合编码规范)
  - ❌ 跨域共享逻辑(如 `MultiProjectParams`)目前重复在 KG 内部
- **影响**:新增能力域时复制范式即可。

---

## ADR-007:hybrid_search 0 结果自动回退到 `kg_list_projects`

- **背景**:LLM 经常错填 `projectPath`(例如把仓库根目录当成已建图路径),导致 0 结果。
- **决策**:`VectorTools.hybridSearch` 在 `total === 0` 时,主动 `GET /api/knowledge-graph/projects`,把 `availableProjects` 与 `_hint` 写入响应。
- **取舍**:
  - ✅ 显著降低 LLM 自纠的轮次
  - ❌ 多一次 HTTP(仅在 0 结果时)
- **影响**:KG 工具暂未做同等回退,因为 KG 工具入参更严格、错误率更低。

---

## ADR-008:路径分隔符在路由层归一化

- **背景**:Windows 用户传 `C:\projects\foo`,后端期望 `C:/projects/foo`。
- **决策**:`tools/index.ts` 的 `handleToolCall` 在分发前调用 `normalizePathArgs(args)`,把所有字符串字段中的 `\` 替换为 `/`。
- **取舍**:
  - ✅ 工具实现层无需关心
  - ❌ 若将来有字段确实需要保留反斜杠(如正则),需要白名单
- **影响**:目前所有工具入参都不存在此场景,安全。

---

## 未来可考虑的决策(待定)

| 主题 | 触发条件 |
|------|---------|
| 引入 zod 校验入参 | 工具增多、入参更复杂 |
| 引入重试 / 指数退避 | 后端不稳定 |
| 引入 Resources / Prompts capability | 需要把 `skills/` 文档作为 MCP 资源暴露 |
| 引入 metrics 埋点 | 需要观测 LLM 调用偏好 |

---

> **延伸阅读**:
> - 架构总览 -> [02-架构设计](../02-架构设计/index.md)
