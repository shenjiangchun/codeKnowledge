# frontend-node-vectorization Specification

## Purpose
为前端代码实体节点（Component / FrontendRoute / ApiClient）生成向量 embedding 并建立向量索引，使语义检索能够命中前端节点，实现「前后端节点统一语义检索」。

## ADDED Requirements

### Requirement: 前端节点向量字段
系统 SHALL 为 `Component`、`FrontendRoute`、`ApiClient` 三类前端节点新增向量属性 `descriptionEmbedding`（与后端 `MethodNode.descriptionEmbedding` 同构），用于语义检索。

#### Scenario: 前端节点携带向量
- **WHEN** 前端实体化完成且向量生成成功
- **THEN** 每类前端节点 SHALL 带有 `descriptionEmbedding` 向量属性（维度与后端方法一致）

### Requirement: 前端节点描述生成与向量化
系统 SHALL 在「构建前后端完整图谱」流程中，为前端节点生成描述文本并调用 embedding 服务生成向量：Component 用组件名 + 描述，FrontendRoute 用路由路径 + 目标组件，ApiClient 用 method + URL + 源文件 + 组件名。

#### Scenario: 完整图谱构建触发前端向量化
- **WHEN** 用户触发「构建前后端完整图谱」
- **THEN** 系统在解析出前端节点后，为三类前端节点生成描述并写入 `descriptionEmbedding`
- **并且** 返回「已向量化前端节点数」

#### Scenario: 向量生成失败不阻断实体化
- **WHEN** 某前端节点的向量生成失败（如 embedding 服务超时）
- **THEN** 前端节点本身 SHALL 保留（不因向量失败而丢失节点），并在结果中报告向量失败数

### Requirement: 前端节点向量索引
系统 SHALL 在 Neo4j 初始化时为前端节点的 `descriptionEmbedding` 建立 VECTOR INDEX（cosine），与后端方法向量索引并存。

#### Scenario: 索引就绪
- **WHEN** 应用启动且 Neo4j 已启用
- **THEN** 前端节点向量索引 SHALL 存在，可被向量查询命中

### Requirement: 语义检索命中前端节点
系统 SHALL 使语义检索在给定范围（含绑定展开后的前端目录）内，能够命中并返回前端节点，且结果携带节点类型标识以区分前端/后端。

#### Scenario: 检索返回前端节点
- **WHEN** 用户语义检索且检索范围包含某前端目录（经绑定展开）
- **THEN** 检索结果 SHALL 包含命中的 Component / FrontendRoute / ApiClient 节点
- **并且** 每个结果 SHALL 携带 `nodeType`（如 `Component` / `FrontendRoute` / `ApiClient` / `Method`）以区分前端/后端
