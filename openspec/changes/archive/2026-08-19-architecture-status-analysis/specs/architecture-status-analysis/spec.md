# 架构现状分析能力

## ADDED Requirements

### Requirement: LLM 全局归纳领域划分
系统 SHALL 使用 LLM 对项目全部类名进行全局业务语义归纳，生成业务领域，而非按 Louvain 社区逐社区命名。领域名 SHALL 是业务名词（如「订单」「代码扫描」），MUST NOT 使用技术分层词（controller/service/repository/model/util 等）作为领域名。

#### Scenario: 全局归纳出业务领域
- **WHEN** 项目有 139 个类，包含 `CodeScanner`、`KnowledgeGraphBuilder`、`LogAnalyzer` 等跨技术分层的类
- **THEN** LLM 归纳出 ≤15 个业务领域，领域名如「代码扫描」「知识图谱」「日志分析」，且不出现「控制器域」「服务域」这类技术分层名

#### Scenario: 领域数由 LLM 自定
- **WHEN** 不同规模的项目分别执行领域划分
- **THEN** 领域数由 LLM 按业务语义自定，不按类数硬编码上限

#### Scenario: 大项目分块归纳
- **WHEN** 项目类数超过阈值（约 120 类/块）
- **THEN** 系统 SHALL 分块归纳，每块输出本块类的归属，代码侧累积合并领域

#### Scenario: 分块跨块领域一致
- **WHEN** 分块归纳时，后续块的类属于已有领域
- **THEN** 系统 SHALL 把前序块的「领域名 + 代表类」传给下一轮，使新类并入已有领域而非新建重复领域

### Requirement: 领域归属以 BELONGS_TO 边为唯一真相
系统 SHALL 以 `DomainNode -[:BELONGS_TO]-> MethodNode` 关系边承载领域归属，MUST NOT 依赖 `MethodNode.businessNoun` 属性（该属性 SHALL 被删除）。领域→方法的聚合、领域间 INTERACTS_WITH 计算 SHALL 均走图边遍历。

#### Scenario: 领域与方法通过边关联
- **WHEN** 一个类 `OrderService` 被归纳到「订单」领域
- **THEN** 系统创建 `DomainNode{domainName:'订单'}`，并建立 `DomainNode -[:BELONGS_TO]-> 该类所有 MethodNode` 的边

#### Scenario: 删除 businessNoun 后领域数据完整
- **WHEN** 领域聚合完成后查询领域下方法数
- **THEN** 通过 `BELONGS_TO` 边遍历得到正确方法数，不依赖 `businessNoun` 属性

### Requirement: 领域下钻虚拟类节点
系统 SHALL 支持从领域节点下钻展开类，通过查询时聚合 `MethodNode.className` 实时构建虚拟类节点（不落库）。下钻接口 SHALL 返回统一的类节点 DTO（含 className、methodCount、description 占位字段）。

#### Scenario: 领域下钻到类
- **WHEN** 用户请求某领域下的类列表
- **THEN** 系统聚合该领域 `BELONGS_TO` 关联的所有方法的 `className`，返回去重后的虚拟类节点列表

#### Scenario: 类下钻到方法
- **WHEN** 用户请求某虚拟类下的方法列表
- **THEN** 系统返回该 `className` 对应的所有方法节点

### Requirement: 领域节点隔离于语义检索与调用链
领域节点 SHALL 仅承载抽象领域属性，MUST NOT 出现在语义检索结果或调用链查询路径中。语义检索 SHALL 只检索 `MethodNode`（及既有 SqlNode/EntryPoint），调用链上游查询 SHALL 只遍历 `CALLS` 边。

#### Scenario: 语义检索不含领域节点
- **WHEN** 用户执行语义检索
- **THEN** 结果 SHALL 只含方法/SQL/入口节点，不含 DomainNode

#### Scenario: 调用链查询不经过领域边
- **WHEN** 用户查询某方法的上游调用链
- **THEN** 查询路径 SHALL 只遍历 Method 节点和 CALLS 边，不经过 BELONGS_TO 边或 DomainNode

### Requirement: 架构现状输入分层降级
系统 SHALL 在架构现状分析时，逐方法使用「自然语言描述优先、方法签名降级」策略：`description` 非空用描述，否则用方法签名。本次 LLM 输入 SHALL 为「类名 + 方法描述/签名」，不含类注释（类注释未来随 ClassNode 一并加入）。

#### Scenario: 有描述用描述
- **WHEN** 某方法已有 `description` 属性（增量场景多数方法）
- **THEN** 架构现状分析的 LLM 输入使用该方法的自然语言描述

#### Scenario: 无描述降级用签名
- **WHEN** 某方法 `description` 为空（如新构建的图谱、只选架构未选语义）
- **THEN** 架构现状分析的 LLM 输入使用该方法的签名（方法名+参数）作为降级

### Requirement: 架构现状编排
系统 SHALL 支持图谱生成、语义&向量、架构现状三者的编排，提供四种组合：
1. 都不选：仅生成图谱
2. 仅选语义：图谱 → 语义&向量
3. 仅选架构：图谱 → 架构现状（语义部分逐方法降级）
4. 都选：图谱 → 语义&向量 → 架构现状（串行）

#### Scenario: 都选时串行执行
- **WHEN** 用户全量生成并勾选「语义&向量」和「架构现状」
- **THEN** 系统按 图谱→语义&向量→架构现状 顺序串行执行，架构现状使用刚生成的描述

#### Scenario: 仅选架构时降级输入
- **WHEN** 用户只勾选「架构现状」，未勾选「语义&向量」
- **THEN** 系统生成图谱后直接执行架构现状，逐方法用签名降级输入

#### Scenario: 都不选时仅生成图谱
- **WHEN** 用户全量生成但不勾选任何附加项
- **THEN** 系统只生成图谱，不触发语义/架构现状

### Requirement: 架构现状独立触发
系统 SHALL 在项目管理页提供独立的「架构现状分析」触发按钮，可在不重新生成图谱的情况下单独重新执行架构现状分析。

#### Scenario: 独立触发架构现状
- **WHEN** 用户点击项目管理页「架构现状分析」按钮
- **THEN** 系统基于现有图谱数据重新执行领域划分，使用「描述优先、签名降级」输入

### Requirement: 增量时领域全量重归域
系统 SHALL 在增量图谱生成后，对全库所有方法重新执行 LLM 领域归域，而非仅归域变更的方法。

#### Scenario: 增量后全量重归域
- **WHEN** 增量图谱生成只更新了少量方法
- **THEN** 领域划分仍对全库所有方法重新归域，保证领域全局一致

### Requirement: DSM 展示可配置 Top N 与分层钻取
系统 SHALL 支持 DSM 矩阵的 Top N 可配置（替换硬编码 20），并支持勾选模块（筛选语义）后下钻展示模块内部类之间的依赖关系（聚焦语义）。

#### Scenario: 可配置 Top N
- **WHEN** 模块数超过默认展示数
- **THEN** 用户可调整 Top N（如 20/50/全部），不被硬截断

#### Scenario: 勾选模块下钻到类依赖
- **WHEN** 用户勾选多个模块并下钻
- **THEN** 系统展示这些模块内部所有类之间的依赖矩阵（类粒度）

### Requirement: ModuleNode 补 CONTAINS 边
系统 SHALL 建立 `ModuleNode -[:CONTAINS]-> MethodNode` 关系边，支撑 DSM 下钻到包内类/方法。

#### Scenario: 模块关联方法
- **WHEN** 模块聚合完成后
- **THEN** 系统建立 `ModuleNode -[:CONTAINS]-> MethodNode` 边，使模块可下钻其包含的方法

### Requirement: 定时任务增强
系统 SHALL 支持定时任务自动 git pull 拉取最新分支代码后触发增量生成，且「是否刷新语义&向量」「是否刷新架构现状」可作为定时任务配置项。定时任务 SHALL 支持可选的 `branch` 配置（为空时跟随仓库当前分支，非空时用 `git pull origin <branch>`）。

#### Scenario: 定时拉代码并增量
- **WHEN** 定时任务触发且配置了 git pull
- **THEN** 系统先 git pull 最新代码，再执行增量生成

#### Scenario: 定时指定分支拉取
- **WHEN** 定时任务配置了 branch 字段
- **THEN** 系统 SHALL 使用 `git pull origin <branch>` 拉取指定分支；未配置则跟随当前 checked-out 分支

#### Scenario: 定时刷新架构现状可配置
- **WHEN** 定时任务配置了「刷新架构现状」
- **THEN** 增量生成后自动执行架构现状分析；未配置则不刷新

#### Scenario: git pull 冲突保护
- **WHEN** git pull 时工作区有未提交改动
- **THEN** 系统跳过该次拉取并告警，不中断增量流程
