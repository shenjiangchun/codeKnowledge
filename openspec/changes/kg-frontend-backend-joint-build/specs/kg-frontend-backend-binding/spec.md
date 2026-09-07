# kg-frontend-backend-binding Specification

## Purpose
定义「前后端绑定」关系：把前端目录与后端目录显式绑定为一对，作为「构建前后端完整图谱」与「检索/总览范围展开」的基础。

## ADDED Requirements

### Requirement: 绑定关系持久化
系统 SHALL 将「后端目录 ↔ 前端目录」绑定关系持久化到 SQLite（本地元数据，与现有 `glossary_term` 同构），每条绑定记录包含 `id`、`backendPath`、`frontendPath`、`createdAt`、`updatedAt`，并对 `(backendPath, frontendPath)` 建唯一约束，防止重复绑定。

#### Scenario: 创建绑定
- **WHEN** 用户为未绑定过的「后端目录 A + 前端目录 B」发起绑定
- **THEN** 系统写入一条新绑定记录，`backendPath=A`、`frontendPath=B`
- **并且** 重复绑定同一对时返回冲突（409）而非写入第二条

#### Scenario: 绑定关系可增删改查
- **WHEN** 用户查询绑定列表 / 删除某条绑定
- **THEN** 系统分别返回全部绑定记录 / 物理删除指定绑定记录

### Requirement: 构建前后端完整图谱端点
系统 SHALL 提供「构建前后端完整图谱」端点：入参为后端目录与前端目录，端点 SHALL 先建立（或复用）绑定关系，再依次执行前端实体化、前端节点向量化、跨层链接，最终返回结构化结果（发现的实体数、生成的向量数、命中的跨层边数）。

#### Scenario: 完整构建成功
- **WHEN** 用户选择后端目录 A 与前端目录 B 触发完整图谱构建，且 B 内含 `package.json`
- **THEN** 系统建立 A↔B 绑定，解析 B 生成 Component/FrontendRoute/ApiClient 节点，为前端节点生成 embedding，并执行静态 URL 匹配构建 INVOKES_API 跨层边
- **并且** 返回结构化结果（含前端实体数、向量数、跨层边命中数）

#### Scenario: 前端目录无效
- **WHEN** 前端目录 B 不存在或不含 `package.json`
- **THEN** 系统返回明确错误（非静默成功），且不建立绑定、不写脏数据

#### Scenario: 任一目录未建图
- **WHEN** 后端目录 A 尚未建图（Neo4j 中无 A 的 Method/EntryPoint 节点）
- **THEN** 系统返回明确错误，提示先对后端建图，再执行完整图谱构建

### Requirement: 检索/总览入口绑定范围展开
系统 SHALL 在检索与总览入口，将用户给定的任一（前端或后端）目录自动展开为「该目录及其所有绑定对中的对侧目录」的路径集合，作为检索/总览范围。

#### Scenario: 选后端目录命中前端
- **WHEN** 用户在检索/总览处选择后端目录 A，且存在绑定 (A, B)
- **THEN** 检索/总览范围 SHALL 自动包含 A 与 B 两个目录，结果可同时返回后端方法节点与前端 Component/ApiClient/FrontendRoute 节点

#### Scenario: 选前端目录命中后端
- **WHEN** 用户在检索/总览处选择前端目录 B，且存在绑定 (A, B)
- **THEN** 检索/总览范围 SHALL 自动包含 B 与 A 两个目录，结果可同时返回前端节点与后端节点

#### Scenario: 无绑定的目录
- **WHEN** 用户选择的目录不存在任何绑定记录
- **THEN** 检索/总览范围退化为仅该目录（单项目模式，向后兼容）
