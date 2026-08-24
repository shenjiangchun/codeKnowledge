# ClassNode 实体节点预案（本次不实现）

## ADDED Requirements

### Requirement: 虚拟类节点预留扩展性
系统 SHALL 在本次实现的「虚拟类节点」（查询时聚合 `className`）中，预留未来实体 ClassNode 的扩展点：虚拟类节点 SHALL 使用 `projectPath + className` 作为稳定标识，与未来实体 ClassNode 的 `classId` 一致，使未来实体化时查询层语义不变、仅后端数据源从「聚合生成」切换为「读实体节点」。

#### Scenario: 虚拟类节点标识稳定
- **WHEN** 本次通过聚合 `className` 生成虚拟类节点
- **THEN** 节点标识 SHALL 为 `projectPath + ":" + className`，与未来 ClassNode 的 classId 规则一致

### Requirement: 下钻接口返回统一 DTO
系统 SHALL 定义统一的类节点 DTO（className、methodCount、description 占位字段），本次虚拟类节点与未来实体 ClassNode 下钻接口 SHALL 使用同一 DTO，避免未来前端契约变更。

#### Scenario: DTO 契约不变
- **WHEN** 未来引入实体 ClassNode 后
- **THEN** 领域/DSM 下钻接口的返回 DTO 结构 SHALL 保持不变，前端无需改动

### Requirement: 领域归属边可平滑扩展到类层级
系统 SHALL 将本次 `BELONGS_TO` 边连到 MethodNode 的决策，设计为可平滑扩展到「领域→类→方法」两级：下钻查询层 SHALL 统一走「先聚合 className 得类，再关联方法」的逻辑，使未来实体化时只需把「聚合 className」替换为「读 ClassNode」。

#### Scenario: 下钻逻辑可复用
- **WHEN** 未来引入实体 ClassNode
- **THEN** 领域下钻的查询逻辑 SHALL 复用「类→方法」的关联语义，仅数据源从聚合切换为实体节点读取

### Requirement: 语义检索与 MCP 改造留待后续
本次 SHALL NOT 修改语义检索（不加 searchType 字段、不改 MCP 适配）。实体 ClassNode 及其语义检索（类/SQL/入口/方法多类型检索）、MCP 适配 SHALL 作为独立后续 spec 一并完整实现。

#### Scenario: 本次不改语义检索
- **WHEN** 本次架构现状分析交付后
- **THEN** 语义检索接口契约 SHALL 保持不变，无 searchType 字段

### Requirement: ClassNode 落地时补类注释到领域输入
本次架构现状分析的 LLM 输入 SHALL 为「类名 + 方法自然语言描述/签名」（不含类注释，因类注释当前未提取）。未来实体 ClassNode 落地时，领域划分的 LLM 输入 SHALL 增加「类注释」维度（类注释作为 ClassNode 的自然属性），并同步修改输入组装逻辑。

#### Scenario: 未来加入类注释
- **WHEN** 实体 ClassNode 落地后重新执行领域划分
- **THEN** LLM 输入 SHALL 包含类注释，输入组装逻辑 SHALL 从「类名+方法描述」升级为「类名+类注释+方法描述」
