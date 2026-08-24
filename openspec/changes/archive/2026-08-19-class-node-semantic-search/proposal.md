# ClassNode 实体节点 + 语义检索类型化

## Why

当前「类」只是 `MethodNode.className` 属性，没有实体 ClassNode。这导致：① 领域下钻只能用"查询时聚合 className"的虚拟类节点（无类级描述/向量）；② 语义检索只能检索方法，无法直接检索「类」这个更自然的理解单元；③ 类级信息（签名、Javadoc 注释、聚合描述）无处承载。上一个 change 已为此预留扩展点（虚拟类节点用 `projectPath:className` 作稳定标识、下钻 DTO 统一、LLM 输入留了类注释接口），现在落地实体 ClassNode。

## What Changes

- **新增实体 ClassNode**：`classId = projectPath + ":" + className`，承载类签名、类注释（Javadoc）、类描述、类向量（descriptionEmbedding）。
- **三层领域归属**：领域归属从 `Domain -[:BELONGS_TO]-> Method` 迁移为 `Domain -[:BELONGS_TO]-> ClassNode -[:HAS_METHOD]-> Method`（**BREAKING**：BELONGS_TO 边指向从 Method 改为 ClassNode）。
- **类描述与向量生成**：类注释（Javadoc）优先；无类注释时 LLM 汇总类内方法描述生成类描述；类描述向量化（descriptionEmbedding）。
- **语义检索类型化**：`SearchRequest` 新增 `searchType` 字段（METHOD/CLASS/SQL/ENTRY/ALL），显式传参优先；缺省时回退到现有 `QueryTypeDetector` 自动推断（向后兼容）。
- **类级语义检索**：CLASS 类型检索走 ClassNode 的 descriptionEmbedding 向量索引。
- **MCP 适配**：对外提供的 KG 检索 MCP 工具同步支持 searchType 传参。

## Capabilities

### New Capabilities

- `class-node`: ClassNode 实体节点（classId、签名、注释、描述、向量），以及三层领域归属（Domain→ClassNode→Method）。
- `semantic-search-type`: 语义检索类型化（searchType 传参 + 类级检索 + MCP 适配）。

### Modified Capabilities

（无。现有 openspec/specs/ 下无语义检索/ClassNode 能力。）

## Impact

- **后端**：
  - `neo4j/model/ClassNode.java`：新增实体（含 descriptionEmbedding）。
  - `MultiDimensionCommunityDetector`：BELONGS_TO 边从 Method 改为 ClassNode；LLM 输入加入类注释（类注释优先）。
  - `CommentExtractor`：补类注释（Javadoc）提取。
  - `DomainNameGenerator` / 领域下钻端点：改走三层结构。
  - `Neo4jInitializer`：ClassNode 约束 + descriptionEmbedding 向量索引。
  - `HybridSearchService` / `VectorSearchController`：searchType 传参 + 类级检索。
  - `Neo4jMethodNodeRepository` 或新增 `Neo4jClassNodeRepository`：类节点 CRUD + HAS_METHOD 边。
- **前端**：领域下钻从「聚合虚拟类」改为「读 ClassNode」；语义搜索面板加类型选择（可选）。
- **MCP**：KG 检索工具加 searchType 参数。

## 关联影响（从上一个 change 预留点落地）

- 虚拟类节点 `projectPath:className` 标识 → 实体 ClassNode `classId` 一致，下钻 DTO 不变。
- LLM 输入「类注释优先」接口 → 本次实现类注释提取 + 注入。
