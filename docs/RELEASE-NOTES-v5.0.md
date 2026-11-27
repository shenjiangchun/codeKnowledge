# HiSi DevTool v5.0 版本特性变化清单

分支 `feat/workbench-pi-agent-infra`（自 `feat/kg-frontend-backend-joint-build` 拉出）；被分析项目为 v4.0 后端
（`C:\Users\47583\projects\hisi_dev_tool v4.0\hisi-dev-tool`，仅作分析对象，未启动）。

## 一、新增能力

### 首页工作台（技术债看板 + 自动修复）
- **待办聚合**：定时把日志分析 issue 与架构 AI 深度分析问题聚合成待办（来源、位置、严重度、证据、修复建议），按项目筛选、按状态跟踪。
- **自动修复全链路（SDD 八阶段）**：实证 → 方案（确认门：多方案选择、推送开关）→ Spec → 任务拆分 → 迭代开发（行级编辑 + 前后对比）→ 测试回归（失败回开发，上限 3 轮）→ 本地启动 E2E（测试 JVM 隔离 `user.home`）→ 提交（可选推送，全局开关 `workbench.fix.push-enabled`）。
- **运行监视器**：实时进度、阶段面板（请求 / 预期 / 原因）、确认弹窗、随时中止、失败重试。
- **本轮补强**（真机暴露）：
  - 看板状态与运行关联真正落库（此前只 upsert，不写 `status` / `current_run_id`）；
  - develop 阶段编译失败时给模型**确定性提示**：找不到的类型在工作区里定位（给出真实全限定名或名字相近的类型）、private 成员不可直接调用；
  - **偶发编译失败重试**：报错全在本次未改动的文件里时重试一次（真机出现过无改动却因 Lombok 生成成员「找不到符号」而失败）。

### pi agent 基建（统一 agent 底座）
- pi 1.6.0 首次启动自动安装（npm 锁版本）；RPC 会话模式，每任务独立会话目录，可中止、超时强杀。
- **启动自检与补装**：启动时检查 `install-dir` 里的 pi——包版本与锁定版本一致且 CLI 入口存在才算已安装（装到一半被打断不算，会删掉重装），缺就自动 `npm install` 到该目录。启动时安装失败（网络抖动、路径被占等）不会让 pi 在进程内永久失效：下一次有流执行时自动再装，无需重启后端。真机：冷启动自动安装 45 s（`lib/pi` 不存在 → 就绪）；启动安装被阻塞 → 解除后首次对话自动补装并正常回答（49 s）。
- **每流工具注册清单**（`FlowToolManifest`）：READ / WRITE / EXEC 三级；只读流不含写文件或执行命令的工具；git 永不进入清单；`--no-approve` 不信任被分析仓库的项目级文件。
- **通用上下文管理（两层，对所有流透明）**：
  - 提交前预算（`PromptBudget`）：提示词超过 `piagent.context.max-input-tokens`（默认 32000）时保形压缩——结构保留，长列表按深度加权截短并标 `_truncated`，完整原文落盘到会话目录供回看；非 JSON 文本只保留开头。真机：架构分析输入 39.4 万字符 → 8.7 万字符；
  - 运行中压缩：显式写入 pi 的 `settings.json`（保留量按中文场景调到 32000），压缩事件透出到界面，失败原因写入结果说明。
- **架构深度分析已平替为 pi**：真机（v4.0）给出 10–19 个问题、5 个视图、p0–p2 路线图，并正确过滤由测试类触发的 impl-leak 误报。
  - 输出格式并不总合规（首稿漏 `thought`、结论后拖工具调用标记、`evidence_appendix` 给成对象）→ 取第一个配对完整的 JSON、补默认值、字段纠偏，仍不符则带具体契约问题**自动修正**（上限 2 轮）。
  - 稳定性：页面用例连跑 3 次 3/3 通过（其中 2 次靠 1 轮自动修正）。
- **全量平替（本轮）**：所有流的 LLM 入口统一到 pi。
  - 统一入口与编排：`AgentRegistry` 声明 17 个 ReAct agent + 7 个 workflow；ReAct 编排 = 按声明装配（工具清单解析、skill 挂载、契约校验 + 修正轮）；workflow 编排 = `FlowEngine` 有序管道 + 既有 `DagExecutor`（责任链只用于节点内选择器），两种模式共用同一入口（`FlowEngine.resolve` / `ReActSession.run`）。
  - 需求分析五个 LLM 节点（澄清 / 影响面 / 实现 / 验证 / 技术方案）换 pi 会话（`PiClarifyLlmClient` 等 @Primary 实现，旧 Claude 客户端转为抽象基类、只留提示词与回退）。
  - 通用对话（`/api/chat`）与 RAM 对话助手（ram-chat）走 `ReActSession`；对话助手的 5 个动作工具（`act_*`）经 `HISI_SCOPE_TOKEN` → mcp.json 环境引用 → MCP 服务以用户 JWT 调后端，无令牌时明确拒绝（绝不匿名执行）。
  - 一次性结构化调用（知识图谱生成等）走 one-shot 清单（无工具、关思考；`AgentLlmClient` 唯一实现为 `PiAgentLlmClient`）。
  - 13 个 skill 文件随启动同步到 pi 目录（jar 内流式复制）。
  - 保留一个直连客户端：批量 / 向量等非 agent 调用仍直连（GLM 文本、embedding），与 pi 无关。
- **部署新增配置**（远端 `application-local.yml` 需补；均有默认值，可不动）：
  - `piagent.*`：`enabled` / `install-dir` / `agent-dir`（默认 `lib/pi`、`lib/pi-agent`，后端项目内、已 gitignore）/ `package-version` / `registry` / `model-base-url` / `model-id` / `max-concurrent-processes` / `deadline-minutes`（`mcp-dist-path` 随 MCP 迁入进程内已移除）；
  - `piagent.context.*`：`max-input-tokens` / `compaction-reserve-tokens` / `compaction-keep-recent-tokens`；
  - `piagent.flows.arch-react` / `one-shot`（其余 per-flow 开关为占位：迁移是 bean 级平替，开关未接线）；
  - hisi-mcp-server 随后端启动自动构建（dist 缺失或源码更新时自动 `npm ci` + `tsc`），远端无需单独启动；真机用「无 node_modules、无 dist」的全新副本实测：18 秒构建完成，拉起后 40 个工具（35 只读 + 5 `act_*`）。**本轮补强**：需要跑 npm 的情况（pi 未装）转后台线程执行，启动不被拖住（远端慢网络卡死问题之一）；就绪标志只在安装与配置全部完成后置位，首个流执行时等待其结束（**此条已被进程内 MCP 取代，见下节**）；
  - **MCP 连接修复（远端真机故障，后被进程内 MCP 取代，见下节）**：`mcp.json` 此前写 `"HISI_SCOPE_TOKEN": "${HISI_SCOPE_TOKEN}"`。pi 把空值也当未设置、且不支持默认值写法，只读流不带令牌时引用解析失败 → 整个 MCP 服务连接失败。架构分析、日志分析、需求分析、通用对话、工作台自引入令牌通道起一直没有任何 MCP 工具（只有带令牌的 ram-act 正常）。改为 `mcp.json` 只写启动命令：实测 pi 会把自身进程环境原样传给 stdio MCP 子进程，`HISI_API_URL` / `HISI_SCOPE_TOKEN` 由 `PiFlowClient` 启动 pi 时按需放入环境。验证：不带令牌 `pi mcp list` → connected, 40 tools；对话流沙箱内可见 19 个 MCP 工具（修复前 0 个）；
  - 对话清单 `CHAT_MCP` 里的 `apm_spans` / `apm_metrics` 在 MCP 服务中不存在（旧工具名残留），换成真实存在的 3 个只读 APM 查询工具；新增守卫测试：每张清单里的 MCP 工具名必须存在于 hisi-mcp-server 源码（缺 APM 工具源文件时测试精确报出那 3 个）；
  - **日志指纹修复（远端真机故障）**：没有错误类型、也没有非框架栈帧的普通日志行（如 WARN）此前都落到同一个默认指纹，互不相同的行被当成重复日志合并进同一份报告（真机 1000 条互不相关的 eureka 告警全部计成同一 report 的出现次数）；改为按内容哈希，且逐行 INFO/WARN 日志降为 DEBUG（每次拉取周期上千行，淹没真实问题）；
  - **向量索引初始化不再拖住启动**：索引创建与上线等待（每个索引最多 120s）从启动路径移到后台线程，就绪前查询走全表扫描回退（与初始化失败行为一致）。远端启动卡住的另一半原因在此；
  - SQLite 结构无需手工建表：全新机器首次启动由各模块 initializer 自动创建（实测空目录首启建出 28 张表，本分支新增的工作台 3 张表 + 5 个索引在工作台接口上可用）；老库升级靠 `CREATE TABLE IF NOT EXISTS` 与幂等补列，本分支没有改既有表的列。顺手修了一处既有的首启顺序问题：KG 队列与架构分析两处启动恢复此前跑在建表之前，全新库首启会报 2 条 `no such table`（功能无害），现以 `@DependsOn` 约束顺序，空库首启 0 ERROR；
  - 移除：`spring.ai.*` 全块与 `chat-models.yml` 引用、`model-provider`（改内部常量）；本地密钥仍放 `application-local.yml` / `.env.local`。

### MCP 迁入进程内（Spring 原生 MCP 服务端，本轮）
- **删除 Node 版 `hisi-mcp-server` 子项目**（含启动时 `npm ci`+`tsc` 构建链路、`McpController` 的 6 个 `/api/mcp` 安装接口、`mcp-dist-path` 配置）；MCP 不再单独启动、不再随版本漂移失效。
- **进程内服务端**：`spring-ai-starter-mcp-server-webmvc`，streamable HTTP 端点 `/mcp`，服务名 `hisi-mcp-inproc`；40 个工具直接标注在后端接口实现上（`@McpTool`，7 个工具类：KG 作用域/查询/桥接/架构 25 + 日志 4 + APM 6 + `act_*` 5），项目代码改动即时反映到 MCP 契约。
- **令牌通道重做**：动作流用独立 pi 配置目录，其 `mcp.json` 声明请求头 `X-Scope-Token: ${HISI_SCOPE_TOKEN}`（HTTP header 引用，pi 同样要求变量已设置）；`McpEndpointFilter` 把令牌按 `Mcp-Session-Id` 绑定进 `McpRequestContext`（2h TTL），`act_*` 工具以用户 JWT 回调后端；只读流不带令牌，动作工具无令牌时明确拒绝。`/mcp` 只监听回环。
- **契约测试**：`McpToolCatalogTest` 64 条实时断言逐点核对工具名 / 参数 / 必填 / 类型 / 枚举常量（对比迁移前的 Node 服务 dump），全部通过；`FlowToolManifest` 前缀改为 `mcp__hisi_mcp_inproc__`。
- 服务端配置见 `spring.ai.mcp.server.*`（application.yml）；详细机制见 CodeWiki「进程内MCP工具」篇。

### 架构诊断
- 本地源码注入扫描 `injection_scan`（区分接口注入与直接依赖实现类）；ReAct 提示词补充抽样查证规则（`scannedFileCount=0` 不得判误报）。

### 需求分析 / 日志分析 / 对话（修复）
- 人工介入三处修复：批准后已确认结果直接复用（此前续跑输入多一个键导致哈希不符而重算）；确认弹窗「编辑」真正生效（编辑结果沿用原检查点哈希，此前被原始输出盖回）且不再二次确认；多轮澄清携带先前查到的代码定位（`target_methods` 不再丢失）。
- 单轮 LLM 调用默认关闭思考（结构化调用 78 s → 8 s；修复旧客户端重建选项时丢开关的隐蔽缺陷）；模型大白话收尾自动格式化回 JSON；`grep` 工具正则 / 字面量自适应；客户端断开不再打 ERROR、不再把 token 写进日志。

## 二、移除（pi 已平替的老代码）
- 手动 ReAct 循环：`ArchReActAgentNode` 的 v1 bean 注册与 `ArchReActAgentNodeV2`（pi 会话为唯一实现，`piagent.flows.arch-react` 默认开启）。
- `/api/workflow` HTTP 前缀（前端与用例全部走 `/api/v2/workflow`；`WorkflowController` 保留为 v2 控制器基类）。
- `WorkflowRegistry.buildWorkflow`（无引用）；工作流注册改为解析期校验（react 节点由工厂按次创建，注册期无从校验）。

**本轮新增移除**（全量平替）：`RamClaudeJsonClient`、`RamClaudeLlmAdapter`、`AgentTools`、`GrepMatcher`、`ClaudeSessionService(+Impl)`、`LogReportLookupTool`、`ProjectOverviewTool`、`ChatModelProperties`、`LlmJsonAnswer`、`AgentConfig`、`OptionalMemoryAdvisor`、`chat-models.yml` 与全部 `spring.ai` 配置（自动装配按类排除）。**保留**：批量 / 向量直连客户端（GLM 文本、embedding）与 `AgentLlmClient` 接口（唯一实现 `PiAgentLlmClient`）。

## 三、安全整改
- 真实凭据移出追踪文件：智谱 Key（探测脚本改读 `ZHIPU_API_KEY`）、`.claude/settings.local.json` 内嵌的智谱 + 百度千帆 Key（抹除并停止追踪）。
- `jwt.secret` 删除默认值（未配置则每次启动随机生成，需 `JWT_SECRET`）；`neo4j.password` 删除默认值；root 初始口令删除写死值（`hisi.auth.root-password` / `HISI_ROOT_PASSWORD`，未配置则随机生成并打印一次）。
- 管理员口令改从环境变量或未入库的 `hisi-dev-tool/.env.local` 读取（`restart-backend.sh`、15 个前端 e2e 用例、新增 `e2e/helpers/credentials.ts`）。
- 停止追踪：`test-results/`、`playwright-report/`（含真实 JWT 的测试产物）、各子仓 `ai-sessions/*.json`（含开发者账号与主机名）。
- 复审：追踪文件中已无真实密钥；剩余命中为测试里的时间戳 / ID 与口令数字的巧合。

> **待用户处理**：仓库为公开仓库，上述密钥与口令此前已随提交推到远端，需在对应平台**作废重置**；清理 git 历史涉及强推，未执行。

## 四、验证结果（2026-11-26，真实后端 + 真实模型）
- 页面 E2E（`hisi-dev-tool-frontend/e2e-real/`，v4.0 为被分析项目）：见文末「验证记录」。
- 后端单测：1655 个，唯一失败为 `Phi3OnnxInferenceDemo`（依赖本地 ONNX 模型文件，仓库不收录，任何干净环境都失败）。
- 前端：`vue-tsc --noEmit` 0 错误；Vitest 全过。

## 五、架构分析可靠性审查（架构师视角）
- **可信**：结构腐化类结论由确定性规则 + 图谱证据产出，AI 只做带证据的查证；真机实证能识别并过滤测试类导致的误报。
- **要打折**：`confirmed` 的语义是「证据一致」而非人工确认；同类候选按 1–2 条代表外推；阈值是 P95 相对值；域划分含 LLM 推断且未标置信度；单快照无趋势；无 ground truth 回归集。
- **覆盖缺口**：变化热点（churn × 复杂度）、稳定性（I/A 主序距离）、死代码可达性、API 面稳定性、重复代码、异常 / 事务边界、跨服务契约。
- **建议**：P0 把可信度写进报告（verdict 措辞、抽样外推标注、阈值绝对化、域划分来源）；P1 补 churn × 复杂度与 I/A；P1 把陷阱场景固化为回归基准；P2 趋势快照与修复前后架构分对照；P2 死代码可达性与 API 稳定性。

## 六、存量问题（已记录，未在本次改动）
- 13 对功能包互相依赖（最大 knowledgegraph ↔ neo4j 62 处 import）；5 处 service → controller 反向引用；model / dto / entity / domain 四种命名并存；42 个 `.disabled` 历史测试与 28 个 `.bak` 测试备份。
- `GitCredentialService` 默认加密键是公开常量（未配置 `HISI_ENCRYPT_KEY` 时加密形同虚设）。
- 自动修复对「拆包循环」这类结构性重构在 3 轮内不收敛（run 16），看板如实标失败；建议结构类待办走人工拆解工单。
- v4.0 仓库遗留 8 个 `fix/wb-r*` 分支与 worktree（自动修复验证产物），未清理。

## 验证记录（2026-11-26，真实后端 + 真实模型，被分析项目为 v4.0 后端）
| 页面流程 | 结果 |
|---|---|
| 架构诊断（AI 深度分析，pi） | 连跑 3 次 3/3 通过（25.4 分钟；其中 2 次靠 1 轮自动修正） |
| 技术债看板 + 自动修复 | 通过（6.0 分钟；run 18 首轮迭代 DONE，提交 `82ecc640`） |
| 日志分析（重新分析 → 报告 → 详情） | 通过 |
| RAM 对话 | 通过 |
| 需求分析大师（澄清 → 三轮确认 → 影响面 → 实现 → 验证 → 技术方案） | 通过（4.9 分钟） |
| 项目现状分析 | 通过 |

在线后端日志：两次启动窗口内 ERROR 均为 0；WARN 为 3 条已知启动噪声与 3 条「模型回答不是 JSON、已格式化转换」。

### 已知限制
- 真机故障（已修，2026-11-26）：需求分析影响面节点 0 结果——GLM 把分解结果按行美化返回，旧兜底按行切分把 `"query": "xxx",` 这类 JSON 碎片当成子查询送检索（15 个碎片、0 结果、节点失败）。已重写 `QueryDecomposer` 响应解析（容忍尾随散文 / 裸数组 / 丢外层大括号；JSON 语法行永不进检索）+ 提示词要求单行输出；新增 8 例单测锁死各格式。
- 需求分析的技术方案偏薄：会话 191 中影响面识别出 2 个待改方法，但技术方案的 `target_methods_detail`、时序图、流程图为空——模型首稿写成散文时经格式化转换（`LlmJsonAnswer`）细节易丢失，待单独治理。
- 自动修复对「拆包循环」类结构性重构在 3 轮迭代内不收敛（run 16），看板如实标失败。
- 工作台自动修复的 E2E 服务启动分支（`workbench.fix.e2e.start-service=true` 的接口用例）未在真实环境验证；本轮以 `start-service=false` 运行（v4.0 按约定不启动）。
