# neo4j-index-fixes Specification

## Purpose
TBD - created by archiving change architecture-review-fixes. Update Purpose after archive.
## Requirements
### Requirement: ServiceNode 唯一约束字段修正
系统 SHALL 修正 Neo4jInitializer 中 ServiceNode 的唯一约束，从 `REQUIRE s.name IS UNIQUE` 改为 `REQUIRE s.serviceId IS UNIQUE`，与 ServiceNode 的 @Id 字段一致。

#### Scenario: 约束字段与 @Id 一致
- **WHEN** Neo4j 启动初始化约束
- **THEN** ServiceNode 唯一约束 SHALL 建立在 serviceId 属性上，而非不存在的 name 属性

### Requirement: SqlNode 向量索引标签修正
系统 SHALL 修正 SqlNode 向量索引的标签，从 `FOR (s:SQL)` 改为 `FOR (s:Sql)`，与 SqlNode 的 @Node("Sql") 标签一致，使 SQL 语义检索的向量索引真正生效。

#### Scenario: 向量索引标签对齐
- **WHEN** Neo4j 启动初始化向量索引
- **THEN** SqlNode 的 sqlEmbedding 向量索引 SHALL 建在 `Sql` 标签上（而非大小写错误的 `SQL`），SQL 语义检索能走向量索引

