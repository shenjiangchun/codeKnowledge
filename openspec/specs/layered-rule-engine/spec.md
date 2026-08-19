# layered-rule-engine Specification

## Purpose
TBD - created by archiving change architecture-review-fixes. Update Purpose after archive.
## Requirements
### Requirement: 分层偏序定义
系统 SHALL 内置通用 Spring 分层偏序：`controller → service → repository → model → util`，其中 util 是叶子层（可被任意层依赖，不依赖业务层）。违规 SHALL 定义为依赖方向逆偏序（如下层依赖上层）或跨层跳过（如 controller 直接依赖 repository）。

#### Scenario: 反向依赖判违规
- **WHEN** service 层模块依赖 controller 层模块（逆偏序）
- **THEN** 系统 SHALL 将该依赖判定为分层违规

#### Scenario: 跨层依赖判违规
- **WHEN** controller 层模块直接依赖 repository 层模块（跳过 service）
- **THEN** 系统 SHALL 将该依赖判定为分层违规

#### Scenario: util 叶子层不违规
- **WHEN** 任意层依赖 util 层模块
- **THEN** 系统 SHALL 不判定为违规（util 是叶子层，被所有层依赖）

### Requirement: 分层违规清单
系统 SHALL 基于现成的 `layerRole` 字段 + `DEPENDS_ON` 边，枚举所有违反分层偏序的依赖，填入 `/dashboard` 的 `layeredViolations`（从写死的 0 改为真违规清单）。

#### Scenario: dashboard 返回真违规
- **WHEN** 项目存在分层违规依赖
- **THEN** `/dashboard` 的 layeredViolations SHALL 返回违规清单（含 source/target/message），而非恒为 0

#### Scenario: 无违规时返回空
- **WHEN** 项目无分层违规
- **THEN** layeredViolations SHALL 返回空列表

### Requirement: 非 Spring 项目门控
系统 SHALL 按 `layerRole` 非 UNKNOWN 的模块占比门控分层检测：占比低于阈值（默认 30%）时跳过分层检测，前端显示「非分层架构，不适用」。

#### Scenario: 非分层架构跳过
- **WHEN** 项目 layerRole 非 UNKNOWN 占比 <30%（如 Python/Go/Node 项目）
- **THEN** 系统 SHALL 跳过分层违规检测，前端显示「非分层架构，不适用」，不误报

#### Scenario: Spring 分层架构正常检测
- **WHEN** 项目 layerRole 非 UNKNOWN 占比 ≥30%（标准 Spring 分层）
- **THEN** 系统 SHALL 正常执行分层违规检测

