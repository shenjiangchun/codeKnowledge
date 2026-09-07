# frontend-code-entities Specification (Delta)

## Purpose
调整前端目录的指定方式：从「后端建图时自动探测 `<后端名>-frontend`」改为「由用户在绑定构建时显式选择前端目录」。

## REMOVED Requirements

### Requirement: 前端目录自动发现
**Reason**: 自动探测（`<后端名>-frontend` 命名约定 + `package.json` 物理定位）过于隐晦且脆弱，目录改名即失效；改为绑定驱动的显式选择。
**Migration**: 使用「构建前后端完整图谱」绑定端点（`kg-frontend-backend-binding`）显式选择前端目录后执行实体化。

## ADDED Requirements

### Requirement: 前端目录由绑定显式指定
系统 SHALL 通过「构建前后端完整图谱」绑定端点，由用户显式指定前端项目目录，并对该目录执行前端实体化（生成 Component/FrontendRoute/ApiClient 节点）；系统 SHALL NOT 在后端全量建图过程中自动探测前端目录。

#### Scenario: 显式选择前端目录执行实体化
- **WHEN** 用户在绑定构建端点选择后端目录 A 与前端目录 B，且 B 内含 `package.json`
- **THEN** 系统对 B 执行前端实体化，生成 Component/FrontendRoute/ApiClient 节点

#### Scenario: 后端建图不自动触发前端实体化
- **WHEN** 用户仅执行后端全量建图（未通过绑定构建端点）
- **THEN** 系统 SHALL NOT 自动探测或实体化任何前端目录

#### Scenario: 显式目录无 package.json
- **WHEN** 用户显式选择的前端目录不含 `package.json`
- **THEN** 系统 SHALL 返回明确错误，不产生前端节点，不建立绑定
