# 前端代码实体化 + 前后端跨层关系构建：需求与代码事实简报

## Why

当前知识图谱是纯后端视角：`CodegraphToNeo4jTransformer` 把前端 component/route 抹平成后端 `Method`（`hisi-dev-tool/.../CodegraphToNeo4jTransformer.java:87-104`），前端代码语义全部丢失，图谱里不存在「前端组件 → 后端接口」这条跨层关系。开发者无法在图上回答「这个前端页面调了哪些后端 API」「改这个后端接口会影响哪些前端组件」。本次变更让前端代码成为图谱一等实体，并用静态 URL 匹配自动构建前后端跨层边。

## What Changes

- 新增前端代码实体节点：`Component`（Vue 组件/页面）、`FrontendRoute`（路由）、`ApiClient`（API 调用点）——当前这些语义被抹平为 `Method`
- 新增前端内部依赖边（组件 import/路由跳转等），替代错位的 `Method` 语义
- 新增跨层边 `INVOKES_API`：前端 `ApiClient`/`Component` → 后端 `EntryPoint`，用静态 URL 匹配（解析 axios/fetch 的 URL 模板 ↔ 后端 `@RequestMapping` 已提取的 `entryKey = "<METHOD> <fullPath>"`）
- MCP 层 `language` 枚举从 `java|python` 放开 `typescript`，并新增跨层查询工具（查「哪个前端组件调用了这个接口」）
- 非破坏性：仅新增节点 label / 边类型 / 约束，不改现有后端建图产物语义

## Capabilities

### New Capabilities
- `frontend-code-entities`: 前端代码实体化——把 Vue/TS 前端解析为 Component/FrontendRoute/ApiClient 节点 + 前端内部依赖边
- `frontend-backend-linkage`: 前后端跨层关系——静态 URL 匹配构建「前端 API 调用点 → 后端 EntryPoint」的 INVOKES_API 边

### Modified Capabilities
<!-- 无 spec 级行为变更 -->

## Impact

- 后端：`knowledgegraph/codegraph/CodegraphToNeo4jTransformer.java`（前端节点映射）、`knowledgegraph/service/KnowledgeGraphBuilder.java`（codegraph 分支编排）、`neo4j/model/`（新增节点实体）、`neo4j/config/Neo4jInitializer.java`（新约束/索引）、`link/`（静态 URL 匹配策略，复用/替换空壳 `OpenApiLinkStrategy`）
- MCP：`hisi-mcp-server/src/tools/knowledgeGraphTools.ts`（language 枚举 + 新工具）
- 前端：`hisi-dev-tool-frontend`（跨层关系可视化视图，消费新节点/边）
- 依赖：无新增外部依赖（复用现有 JavaParser/codegraph sidecar 与 axios 源码静态解析）

---

# 需求与代码事实简报

## 意图

### 目标与成功标准
- 目标：让前端代码（Vue/TS）成为知识图谱的一等实体，并自动构建「前端 → 后端」跨层关系，使图谱能够回答「前端组件 ↔ 后端接口/方法」的追溯问题。
- 可观察的成功结果：
  1. 建图后图谱中出现 `Component` / `FrontendRoute` / `ApiClient` 节点（不再是清一色后端 `Method`）
  2. 图谱中出现 `INVOKES_API` 边，连接前端 API 调用点与后端 `EntryPoint`
  3. MCP 检索 `language=typescript` 可返回前端实体；新跨层查询工具能返回「调用某后端接口的前端组件列表」

### 边界与非目标
- 本次范围：前端实体化（Component/FrontendRoute/ApiClient 节点 + 前端内部依赖边）+ 静态 URL 匹配跨层边 + 前端目录自动发现（建图入口靠 `package.json` 定位前端项目）+ MCP language 放开 + 跨层查询工具 + 前端跨层可视化视图
- 非目标：
  - 不构建前端画布手动拖拽建图编辑器
  - 不重写现有 JavaParser/codegraph 建图核心，只扩展 codegraph 分支
  - 不引入新图数据库
  - 本阶段不落地 OpenAPI 契约关联与运行时链路追踪（留作后续校准层）
- 禁止修改路径：后端 `JavaParser` 建图主流程的既有节点/边语义（Method/Class/EntryPoint 现有字段含义不变）

## 代码事实

### 现状摘要
- 建图流水线：`KnowledgeGraphBuilder.buildKnowledgeGraph` → 语言检测（`ProjectLanguageDetector` 已识别 `TYPESCRIPT/JAVASCRIPT`）→ TS/JS/Vue 走 `codegraphSidecarService` → `CodegraphToNeo4jTransformer.transform` 落 Neo4j
- 前端语义丢失：`CodegraphToNeo4jTransformer.java:87-104` 把 `function/method/component` 一律映射为后端 `MethodNode`，`route` 映射为 `EntryPoint(HTTP)`，`file/module/namespace` 节点被跳过
- 后端 HTTP 路径已提取：`KnowledgeGraphBuilder.createEntryPoints` 提取 `entryKey = httpMethod + " " + fullPath`（`KnowledgeGraphBuilder.java:1714-1718`），`fullPath` 含类级 `/api` 前缀
- 前端 API 调用统一封装：`hisi-dev-tool-frontend/src/utils/request.ts` axios 实例 `baseURL: '/api'`，各 `api/*.ts` 用字符串字面量 URL（如 `/v2/knowledge-graph/dashboard`），views 下组件也有直接 request 调用
- 跨层链接空壳：`OpenApiLinkStrategy.java:22-25` 标注 `not yet active`；运行时 `SpanToKgMapper.matchByHttpRoute:200-211` 空实现
- MCP 纯透传：`hisi-mcp-server` 的 `kg_*` 工具只是 HTTP 转发，`language` 枚举只有 `java|python`（`knowledgeGraphTools.ts:44-46`）

### 可复用 / 需扩展 / 冲突
#### 可直接复用
- `ProjectLanguageDetector` 的 TS/JS 识别
- `codegraphSidecarService` + `CodegraphSqliteReader` 的前端 AST 读取链
- 后端 `EntryPointNode.entryKey`（已含 `METHOD fullPath`），无需重新提取后端路由

#### 需要扩展
- `CodegraphToNeo4jTransformer`：新增 Component/FrontendRoute/ApiClient 节点映射，不再把 component 抹平成 Method
- `Neo4jInitializer`：新增节点 label 约束 + `INVOKES_API` 边类型
- 前端 axios 静态解析器：新组件，扫描 `request.get/post/...` 与 `fetch(` 调用点提取 URL 模板

#### 需求与现状冲突
- 现有 `CodegraphToNeo4jTransformer` 把前端 component 写成后端 Method——改变此行为可能影响依赖「前端以 Method 存在」的现有检索/测试；需确认无既有消费者依赖该降级语义（留作设计期验证）

### 挂载点候选
| 优先级 | 路径/符号 | 理由 |
|---|---|---|
| 必选 | `CodegraphToNeo4jTransformer.java`（`transform` / `toMethodNode` / `toEntryPoint`） | 前端实体化的核心落点 |
| 必选 | `KnowledgeGraphBuilder.java`（`buildCodegraphKnowledgeGraph` / `createEntryPoints`） | codegraph 分支编排 + 后端 entryKey 锚点 |
| 必选 | `hisi-dev-tool-frontend/src/utils/request.ts` + `api/*.ts` | 前端 axios 调用点（静态 URL 提取源） |
| 必选 | `EntryPointNode.java` / `Neo4jInitializer.java` | 跨层边目标节点 + 新约束 |
| 备选 | `OpenApiLinkStrategy.java` / `SpanToKgMapper.java` | 后续契约/运行时校准层（本阶段非目标） |

### 波及线索
- 建图流水线 codegraph 分支：新增节点映射，改变前端建图产物形状
- Neo4j schema：`Neo4jInitializer` 加约束（启动时自动执行，无手动迁移）
- MCP 层：`knowledgeGraphTools.ts` language 枚举 + 新工具 schema
- 前端可视化：新增「前后端跨层」Tab + 生成侧「构建跨层关系」按钮 + 统计概览前端计数；现有图谱视图不依赖前端实体故不受影响
- 测试：codegraph 建图相关测试断言可能因「component 不再是 Method」而需更新

### plan 阶段重点验证的关联风险（design 前置要点）
1. **「前端=Method」既有消费者**：现前端 component 被存为 Method，若有语义搜索/调用链依赖「前端方法节点」，改映射会破坏之——设计期第一步 grep 确认
2. **全量重建联动**：后端图全量重建后，跨层 `INVOKES_API` 边会因后端 EntryPoint nodeId 变化而悬空——链接阶段必须支持「后端图重建后重跑」
3. **codegraph 与新 sidecar 职责边界**：两者都扫前端，须明确 codegraph 是否还扫前端（避免 component 被两个 transformer 重复处理）

### 证据表
| 类型 | 结论 | 证据 |
|---|---|---|
| 事实 | 前端 component/route 被抹平为后端 Method | `CodegraphToNeo4jTransformer.java:87-104` |
| 事实 | 后端已提取完整 HTTP 路径 `entryKey = METHOD fullPath` | `KnowledgeGraphBuilder.java:1714-1718` |
| 事实 | 前端 axios 统一 `baseURL: '/api'`，URL 为字符串字面量 | `hisi-dev-tool-frontend/src/utils/request.ts:27-38`、`api/knowledgeGraph.ts:413-511` |
| 事实 | MCP language 枚举仅 java/python | `hisi-mcp-server/src/tools/knowledgeGraphTools.ts:44-46` |
| 事实 | OpenAPI 链接空壳、运行时 route 匹配空实现 | `OpenApiLinkStrategy.java:22-25`、`SpanToKgMapper.java:200-211` |
| 推断 | 静态 URL 匹配可行（前端 `/api` 前缀与后端 fullPath 对齐） | 上述 request.ts + entryKey 事实；待设计期确认 URL 模板动态拼接占比 |
| 推断 | 改变 component→Method 映射可能影响现有消费者 | 需设计期 grep 既有对「前端 Method 节点」的依赖 |

## 消歧与闸门

### 开放问题清单
（产品决策权威台账；阻塞项未决前不得进入规格闸门）

| 优先级 | 问题 | 代码事实背景 | 选项与影响（摘要） | 建议 | 状态 | 最终决策 |
|---|---|---|---|---|---|---|
| 必选 | 前端项目路径如何指定给建图流水线？ | 前端是独立目录 `hisi-dev-tool-frontend`，不在后端 projectPath 扫描范围内；现有建图入口只收单个 projectPath | A 显式指定前端路径（新增建图参数）；B 自动发现兄弟目录 `*-frontend`；C 前端作为独立建图目标走单独流水线 | B 自动发现——业界 dependency-cruiser/monorepo 范式，靠 package.json 定位，免显式指定 | decided | B 自动发现前端目录（建图入口收根目录/前端路径，靠 `package.json` 自动探测） |
| 必选 | 前端实体化粒度做到多深？ | 核心诉求是「前后端跨层关系」；前端实体化是为此服务 | A 完整实体化（Component/Page/FrontendRoute/ApiClient 节点 + 前端内部边 + 跨层边）；B 最小闭环（只提取 API 调用点建跨层边，不建 Component/Page 全量实体） | A 完整实体化——贴合「前端图谱构建」的原始诉求 | decided | A 完整实体化 |
| 必选（grilling） | ApiClient 数据源从哪提取？ | codegraph sidecar 不产出 HTTP 调用点（`CodegraphToNeo4jTransformer.java:86-155` 节点 kind 仅 function/method/component/route，无边 kind 表达 http 调用） | A 新建前端 AST 解析器；B 跨层边延期；C 约定式轻量提取 | A 新建前端 AST 解析器——「完整实体化」的必经路径 | decided | A 新建前端 AST 解析器 |
| 必选（grilling） | 前端 AST 解析器技术栈？ | 后端仅 JavaParser+ANTLR，不解析 TS/Vue；codegraph sidecar 已是 Node 实现 | A TS 侧 sidecar（babel/@vue/compiler-sfc）；B tree-sitter native；C 扩展 codegraph 本体 | A TS 侧 sidecar——与 codegraph 同栈、解析 Vue/TS 最准 | decided | A TS 侧 sidecar |
| 必选（grilling） | 跨层边何时建立？ | 前端图与后端图是两套建图产物，跨层边需两端数据都在 | A 独立链接阶段（同构 CrossServiceLinker）；B 同事务内；C 查询时动态算 | A 独立链接阶段——可增量重跑 | decided | A 独立链接阶段 |
| 必选（grilling） | 前端图 projectPath 存什么值？ | cleanProjectData 按 projectPath 精确删（`Neo4jStorageService.java:520-568`） | A 前端实际目录（天然隔离）；B 挂靠后端 projectPath | A 前端实际目录——隔离成立、不改清理逻辑 | decided | A 前端实际目录 |
| 必选（grilling） | 自动发现定位 vs 实际目录存储是否共存？ | 「自动发现」是定位方式、「实际目录」是存储标识，两者不冲突 | A 两者共存；B 改为显式指定 | A 两者共存 | decided | A 两者共存 |
| 必选（grilling） | Component→ApiClient 边由谁建？ | codegraph imports 边不保证捕获组件内具体 request 调用 | A 新解析器一并产出（跨层链完整 Component→ApiClient→EntryPoint）；B 靠 imports 近似 | A 新解析器一并产出 | decided | A 新解析器一并产出 |
| 必选 | ApiClient 数据源从哪提取？（grilling） | codegraph 节点 kind 仅 function/method/component/route/class/file 等，无 HTTP 调用点语义 | A 新建前端 AST 解析器；B 跨层边延期；C 约定式正则轻量提取 | A 新建前端 AST 解析器——最准，是「完整实体化」隐含必经路径 | decided | A 新建前端 AST 解析器 |
| 必选 | 前端 AST 解析器技术栈？（grilling） | 后端仅 JavaParser+ANTLR（不解析 TS/Vue）；codegraph sidecar 已是 Node 实现 | A TS 侧 sidecar；B tree-sitter native；C 扩展 codegraph 本体 | A TS 侧 sidecar——与 codegraph 同栈、解析 Vue/TS 最准 | decided | A TS 侧 sidecar |
| 必选 | 跨层边何时建立？（grilling） | 前端图与后端图是两套建图产物，跨层边需两端数据都在 | A 独立链接阶段；B 同事务内建边；C 查询时动态计算 | A 独立链接阶段——同构 CrossServiceLinker、可增量重跑 | decided | A 独立链接阶段 |
| 必选 | 前端图 projectPath 存什么值？（grilling） | cleanProjectData 按 projectPath 精确删除 | A 前端实际目录（天然隔离）；B 挂靠后端 projectPath | A 前端实际目录——隔离天然成立、不改清理逻辑 | decided | A 前端实际目录 |
| 必选 | Component→ApiClient 边由谁建？（grilling） | codegraph imports 边不保证捕获组件内具体 request 调用 | A 新解析器一并产出（链完整 Component→ApiClient→EntryPoint）；B 靠 imports 近似 | A 新解析器一并产出 | decided | A 新解析器一并产出 |

### 澄清完整性扫描
- 已检查的适用维度：使用者/权限、正常+边界状态、失败/重试、数据保存/删除/迁移、既有调用方/公共契约、性能/安全/可观测/验收
- 由证据解决的缺失事实：建图权限沿用现有入口（事实）；跨层边非破坏性新增、前端图独立 projectPath 天然隔离于后端 cleanProjectData（事实）；MCP language 枚举为加法扩展、向后兼容（事实）
- 新增开放问题及处理状态：见「开放问题清单」9 项（2 项原始必选 + 7 项 grilling 追加），全部已决
- 明确不适用 / 不在范围的维度：支付、鉴权策略、隐私、破坏性删除
- 结论：无实质阻塞项（9 项决策已决：自动发现前端目录 + 完整实体化 + 7 项 grilling）

### 风险定级与闸门建议
- 建议车道/风险：High
- 命中的风险特征：
  - 公共契约扩展（MCP language 枚举、图谱 schema 新增节点/边类型——非破坏但属公开契约面）
  - 数据 schema 新增（Neo4j 新节点 label + 边类型 + 约束，由 Neo4jInitializer 启动执行）
  - 跨模块/服务/进程边界（前端 Vue + 后端 Spring + MCP TS 三个子项目）
  - 核心建图流水线扩展（codegraph 分支）
- 未命中的高风险特征：鉴权/权限、支付、隐私、破坏性删除、核心业务主路径行为变更（本次仅新增，不改主路径语义）
- 不确定点：改变 component→Method 映射是否破坏既有消费者（设计期需验证）；前端 URL 模板动态拼接占比（决定静态匹配漏配率）
- 闸门建议：规格闸门（单题放行）→ 计划阶段实现闸门（单题放行 + 代价/风险/回滚摘要）
- 可用验证：codegraph 建图单测；前端 axios 静态解析器单测；静态 URL 匹配单元测试；MCP 工具 schema 校验
- 缺失验证：真实前端项目建图的端到端回归（需 test-projects 样例）

### Explore 交接消费
- [x] `chosen_direction` → 已写入「意图」（前端实体化 + 静态 URL 匹配跨层关系）
- [x] `non_goals` → 已写入「意图」边界（不建手动编辑器、不重写 JavaParser 核心、不引入新图库、不落地契约/运行时校准层）
- [x] `code_anchors` → 已驱动「代码事实」检查（CodegraphToNeo4jTransformer / OpenApiLinkStrategy / SpanToKgMapper / request.ts / knowledgeGraphTools.ts 均已引用）
- [x] `risk_signal` → 仅作线索；「风险定级与闸门建议」已按代码事实重算为 High（命中公共契约 + schema 新增 + 跨进程边界 + 核心流水线扩展）
- [x] `unknowns` → 已写入「开放问题清单」/ 不确定点（后端 OpenAPI 产出、URL 动态拼接占比、增量刷新语义）

落点摘要：意图=前端实体化+静态URL跨层边；挂载=CodegraphToNeo4jTransformer + KnowledgeGraphBuilder + request.ts；Risk=High；开放问题=2项必选

### 状态源与工件位置
- 后端：OpenSpec change
- 路径：`openspec/changes/kg-frontend-backend-graph/`
- 设计前置要点：交互图 + 架构交互方案 + 关联影响分析已产出并经用户确认（见「plan 阶段重点验证的关联风险」3 项）
- 闸门记录：规格批准状态 = 已批准 / 批准人 = 用户（2026-08-20）/ 附加约束 = 无（9 项开放问题已决，风险 High，交互/架构/影响分析已确认）
