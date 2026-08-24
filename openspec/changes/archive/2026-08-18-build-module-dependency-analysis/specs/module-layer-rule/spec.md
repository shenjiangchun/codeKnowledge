# Spec: module-layer-rule

## ADDED Requirements

### Requirement: 按命名约定识别 module 职责

系统 SHALL 按 build-module 的 `artifactId` 命名后缀约定识别其职责层级。识别规则：后缀为 `model`/`dto`/`po`/`entity`/`common`/`util` → L1；`client`/`rpc`/`sdk`/`feign` → L2；`service`/`core`/`biz`/`domain` → L3；`api`/`controller`/`web`/`facade`/`app` → L4；`gw`/`gateway`/`edge`/`portal` → L5。无法匹配任何后缀的 module SHALL 标记为 `UNKNOWN` 职责，不阻碍环检测。

#### Scenario: 命名约定识别职责
- **WHEN** build-module 的 artifactId 为 `user-model` 或 `user-client` 或 `user-service`
- **THEN** 系统分别识别其职责为 L1、L2、L3

#### Scenario: 无法识别记为 UNKNOWN
- **WHEN** build-module 的 artifactId 后缀不匹配任何约定（如 `shared-utils-core`）
- **THEN** 系统标记职责为 `UNKNOWN`，该 module 仍参与环检测

### Requirement: 检测已知职责 module 的分层违规

系统 SHALL 在查询时内存拼出的构建模块依赖图上，对职责已知的依赖边检测分层违规。分层偏序为 L1 < L2 < L3 < L4 < L5（层级越大越靠上层，只能依赖下层或同层）。当 `src` 依赖了层级更高的 `tgt`（`src` 层级 < `tgt` 层级）时，系统 SHALL 判定为反向依赖违规（如 `model → client`）。

#### Scenario: 检测反向依赖违规
- **WHEN** module `user-model`（L1）依赖 module `user-client`（L2）
- **THEN** 系统判定为反向依赖违规，输出违规边 `user-model → user-client` 及违规类型「反向依赖」

#### Scenario: 正常分层不误报
- **WHEN** module `user-client`（L2）依赖 module `user-model`（L1）
- **THEN** 系统不判定违规

#### Scenario: 网关依赖下游不违规
- **WHEN** module `api-gw`（L5）依赖 module `user-client`（L2）
- **THEN** 系统不判定违规（网关是最顶层，可依赖所有下层）

### Requirement: 用相对层级约束检测职责未知 module 的层级问题

系统 SHALL 对职责未知的 module X，用其已知职责的邻居推导层级区间 `[下界, 上界]`：下界 = X 依赖的所有已知 module 的最大层级；上界 = 依赖 X 的所有已知 module 的最小层级。当 **下界 > 上界** 时，系统 SHALL 判定为层级矛盾违规。

#### Scenario: 相对层级约束矛盾
- **WHEN** 职责未知的 module X 依赖 `client`（L2），且被 `model`（L1）依赖
- **THEN** 系统推导下界 = 2、上界 = 1，判定层级矛盾违规（下界 > 上界）

#### Scenario: 相对层级约束一致不误报
- **WHEN** 职责未知的 module X 依赖 `model`（L1），且被 `service`（L3）依赖
- **THEN** 系统推导下界 = 1、上界 = 3，不判定违规（层级区间一致）

### Requirement: 提供分层违规查询 API

系统 SHALL 提供查询端点，返回 module 级分层违规清单。每项违规 SHALL 包含：违规边（`source → target`）、违规类型（反向依赖 / 跨层 / 层级矛盾）、source 与 target 的职责（未知为 `UNKNOWN`）、严重度。

#### Scenario: 查询分层违规清单
- **WHEN** 前端请求分层违规端点（携带 `projectPaths`）
- **THEN** 系统返回违规清单，每项含违规边、类型、职责与严重度
