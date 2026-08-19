# class-node Specification

## Purpose
TBD - created by archiving change class-node-semantic-search. Update Purpose after archive.
## Requirements
### Requirement: ClassNode 实体节点
系统 SHALL 新增 ClassNode 实体节点，以 `classId = projectPath + ":" + className` 作为唯一标识，承载 className、packageName、signature（类声明签名）、classComment（Javadoc 注释）、description（类描述）、descriptionEmbedding（类向量）、methodCount、projectPath、language、framework 属性。

#### Scenario: classId 稳定标识
- **WHEN** 为某个类创建 ClassNode
- **THEN** classId SHALL 等于 `projectPath + ":" + className`，与上一个 change 的虚拟类节点标识规则一致

#### Scenario: 类节点承载完整信息
- **WHEN** ClassNode 落库
- **THEN** 节点 SHALL 含 className、classComment、description、descriptionEmbedding、methodCount 等字段

### Requirement: 三层领域归属
系统 SHALL 将领域归属从 `Domain -[:BELONGS_TO]-> Method` 迁移为 `Domain -[:BELONGS_TO]-> ClassNode -[:HAS_METHOD]-> Method`。BELONGS_TO 边 SHALL 指向 ClassNode，HAS_METHOD 边 SHALL 表达「类包含方法」。

#### Scenario: 领域连类
- **WHEN** 领域划分完成后
- **THEN** DomainNode 通过 BELONGS_TO 边关联 ClassNode，ClassNode 通过 HAS_METHOD 边关联该类所有 MethodNode

#### Scenario: 旧边清理
- **WHEN** 重跑架构现状分析
- **THEN** 旧 DomainNode 及其边 SHALL 被 DETACH DELETE 清理，重新建立三层结构

### Requirement: 类注释提取
系统 SHALL 扩展 CommentExtractor 提取类级 Javadoc 注释，写入 ClassNode.classComment。

#### Scenario: 提取类 Javadoc
- **WHEN** Java 类声明有 Javadoc 注释
- **THEN** 系统 SHALL 提取该 Javadoc 描述写入 classComment

#### Scenario: 无类注释
- **WHEN** Java 类无 Javadoc 注释
- **THEN** classComment SHALL 为空字符串或 null

### Requirement: 类描述生成（类注释优先，无则汇总）
系统 SHALL 生成类描述：类注释（classComment）非空时直接用类注释；为空时用 LLM 汇总该类所有方法的 description 生成类描述。

#### Scenario: 类注释优先
- **WHEN** 类的 classComment 非空
- **THEN** 类描述 SHALL 直接采用 classComment

#### Scenario: 无注释则汇总
- **WHEN** 类的 classComment 为空
- **THEN** 系统 SHALL 用 LLM 汇总该类所有方法的 description 生成类描述

### Requirement: 类向量化
系统 SHALL 对类描述生成向量，写入 ClassNode.descriptionEmbedding（维度 2048，cosine），并建立向量索引。

#### Scenario: 类描述向量索引
- **WHEN** 类描述生成后
- **THEN** 系统 SHALL 生成 descriptionEmbedding 并写入 ClassNode，Neo4jInitializer 建立对应向量索引

### Requirement: 类描述两段式生成
系统 SHALL 分两段生成 ClassNode：结构字段（classId/packageName/signature/classComment）在图谱构建阶段写入；类描述与 descriptionEmbedding 在「语义&向量」阶段生成（与 MethodNode 描述同批处理）。

#### Scenario: 图谱阶段写结构
- **WHEN** 图谱构建阶段解析到类
- **THEN** 系统 SHALL 写入 ClassNode 的结构字段（classId/className/signature/classComment），不生成类描述和向量

#### Scenario: 语义阶段写描述与向量
- **WHEN** 「语义&向量」阶段执行
- **THEN** 系统 SHALL 生成类描述（类注释优先，无则 LLM 汇总方法描述）并向量化，写入 ClassNode.description 和 descriptionEmbedding

### Requirement: ClassNode 重建策略
系统 SHALL 在全量重建时 DETACH DELETE 旧 ClassNode 并重建（类描述+向量重算）；在增量生成时仅对变更类维护 ClassNode。

#### Scenario: 全量重建全删重算
- **WHEN** 全量生成图谱
- **THEN** 系统 SHALL 删除旧 ClassNode 并重建，所有类的描述+向量重新生成

#### Scenario: 增量只处理变更类
- **WHEN** 增量生成图谱
- **THEN** 系统 SHALL 仅对新增/修改的类维护 ClassNode，未变更类的 ClassNode 保持不变

### Requirement: 领域下钻读实体 ClassNode
系统 SHALL 将领域下钻从「查询时聚合 className 虚拟类节点」改为「读实体 ClassNode」，下钻 DTO 结构保持不变（className/methodCount/description）。

#### Scenario: 领域下钻读实体类节点
- **WHEN** 用户请求领域下的类列表
- **THEN** 系统 SHALL 通过 `Domain -[:BELONGS_TO]-> ClassNode` 遍历返回实体类节点，而非聚合虚拟类节点

#### Scenario: 下钻 DTO 契约不变
- **WHEN** 领域下钻接口返回
- **THEN** 返回 DTO SHALL 保持 className/methodCount/description 结构不变

