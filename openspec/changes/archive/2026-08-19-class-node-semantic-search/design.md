# ClassNode 实体节点 + 语义检索类型化 — 设计文档

## Context

上一个 change（architecture-status-analysis）已落地领域划分（LLM 全局归纳 + BELONGS_TO 边），并为 ClassNode 预留扩展点：
- 领域下钻虚拟类节点用 `projectPath + ":" + className` 作稳定标识
- 下钻 DTO 统一（className/methodCount/description 占位）
- LLM 输入留了「类注释优先」接口（当前类注释未提取）

现状约束：
- 领域归属 `Domain -[:BELONGS_TO]-> Method`（边指向方法节点）
- 类只是 `MethodNode.className` 属性，无实体节点、无类级描述/向量
- 语义检索 `QueryTypeDetector.detect(query)` 自动推断类型，调用方不能显式传参
- SqlNode（sqlEmbedding）、EntryPoint（brief/detailedEmbedding）已有向量索引

## Goals / Non-Goals

**Goals:**
- 新增实体 ClassNode（classId、签名、类注释、类描述、类向量）。
- 领域归属迁移为三层：`Domain -[:BELONGS_TO]-> ClassNode -[:HAS_METHOD]-> Method`。
- 类描述 = 类注释优先，无注释则 LLM 汇总方法描述；类描述向量化。
- 语义检索 searchType 显式传参优先，缺省回退自动检测。
- 类级语义检索（CLASS 类型走 ClassNode descriptionEmbedding）。
- MCP 检索工具同步 searchType。

**Non-Goals:**
- 不改 QueryTypeDetector 的自动检测逻辑本身（保留作为缺省回退）。
- 不改调用链查询（仍只走 Method/CALLS）。
- 不重构 SqlNode/EntryPoint 既有检索逻辑（只加 searchType 路由入口）。

## Decisions

### D1: ClassNode 实体字段
**选择**：`classId`（projectPath:className 唯一）、`className`、`packageName`、`signature`（类声明签名）、`classComment`（Javadoc）、`description`（类描述）、`descriptionEmbedding`（类向量）、`methodCount`、`projectPath`、`language`、`framework`。
**理由**：classId 复用上一个 change 的虚拟类节点标识规则，零迁移；descriptionEmbedding 维度 2048（与 MethodNode 一致，复用智谱 embedding-3）。

### D2: 三层领域归属
**选择**：`Domain -[:BELONGS_TO]-> ClassNode -[:HAS_METHOD]-> Method`，领域归属边 BELONGS_TO 指向 ClassNode。
**理由**：领域是「类的集合」，连类语义最准确；HAS_METHOD 表达「类包含方法」。领域下钻直接读 ClassNode，不再聚合。
**迁移**：重跑架构现状分析即重建（领域是派生数据），旧 `Domain -[:BELONGS_TO]-> Method` 边随 DomainNode DETACH DELETE 清理。

### D3: 类描述生成（类注释优先，无则汇总）
**选择**：`CommentExtractor` 补类注释（Javadoc）提取；有类注释直接用，无则 LLM 汇总该类所有方法的 description。
**理由**：类 Javadoc 是开发者写的类级语义，质量高于汇总；无注释时 LLM 汇总保证覆盖。

### D4: 语义检索 searchType 传参（显式优先，缺省回退）
**选择**：`SearchRequest` 新增 `searchType` 枚举（METHOD/CLASS/SQL/ENTRY/ALL）；传了就走对应检索，没传走现有 `QueryTypeDetector.detect` 自动推断。
**理由**：向后兼容（现有调用方不传 searchType 行为不变），同时让 MCP/前端能显式指定"只搜类/只搜方法"。

### D5: 类级检索实现
**选择**：CLASS 类型走 `ClassNode.descriptionEmbedding` 向量索引（新增 idx）；METHOD 走现有 MethodNode.descriptionEmbedding；SQL 走 SqlNode.sqlEmbedding；ENTRY 走 EntryPoint.briefEmbedding；ALL 合并多路。
**理由**：每类实体用各自向量索引，ALL 用 RRF 融合。

## Risks / Trade-offs

- **[BELONGS_TO 迁移破坏性]** → 领域是派生数据，重跑架构现状即重建；旧边随 DETACH DELETE 清理，无需手工迁移。
- **[类描述 LLM 汇总成本]** → 每类一次 LLM 调用（汇总方法描述），类数多时成本高；缓解：类注释优先可跳过大部分 LLM 调用。
- **[类向量索引内存]** → 新增 ClassNode 向量索引，Neo4j 内存略增；缓解：与 MethodNode 同维度、同 embedding 服务。
- **[searchType 枚举扩展]** → 未来新增类型需改枚举；缓解：枚举设计为可扩展。

## Open Questions

- 无。所有关键决策已与用户确认。

## 补充决策（grill 收敛）

### D6: 类描述/向量两段式生成
**选择**：ClassNode 结构字段（classId/packageName/signature/classComment）在图谱构建阶段写入；类描述 + descriptionEmbedding 在「语义&向量」阶段生成（与 MethodNode 描述同批处理）。
**理由**：类描述需汇总方法描述，而方法描述在语义阶段才生成。两段式保证「只选架构、不选语义」时 ClassNode 有结构无描述，选语义时描述+向量同批生成。

### D7: 全量重建 = 全删重算，增量 = 只处理变更类
**选择**：全量重建时 DETACH DELETE 旧 ClassNode 并重建（类描述+向量重算）；增量时只对变更类维护 ClassNode。
**理由**：用户明确要数据新鲜度优先于成本（全量重算），增量保持高效（只处理变更类）。

### D8: 类级检索复用 SearchResultItem
**选择**：SearchResultItem.nodeType 增加 "Class"；类级结果 nodeId=classId、className=类名、description=类描述、methodName/signature 留空。
**理由**：复用统一检索返回契约，前端按 nodeType 分支渲染，不引入第二套 DTO。

### D9: 领域下钻 DTO 加类描述
**选择**：领域下钻 DTO 增加 description 字段（className/methodCount/description）。
**理由**：类描述是 ClassNode 核心价值，领域下钻是最自然的展示入口。
