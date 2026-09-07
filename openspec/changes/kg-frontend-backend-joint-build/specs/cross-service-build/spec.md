# cross-service-build Specification

## Purpose
为现有「跨服务依赖构建」能力补规范，并修复其静默失败、空壳占位符策略、空选择校验、远端路径未规范化等缺陷。构建结果 SHALL 可验证、不产生「假成功」与「数据静默丢失」。

## ADDED Requirements

### Requirement: 跨服务构建失败显式化
系统 SHALL 在跨服务构建任一步骤失败时，向调用方返回明确的失败状态与原因，不得静默吞掉异常后仍返回 `completed`。

#### Scenario: 链接策略抛异常
- **WHEN** `CrossServiceLinker` 中某链接策略抛异常
- **THEN** 构建端点 SHALL 返回失败（而非 `completed`），并在结果中携带失败的策略名与异常摘要

#### Scenario: 构建成功返回结构化结果
- **WHEN** 跨服务构建成功
- **THEN** 结果 SHALL 包含「删除旧边数」「各策略命中并新建的 EXTERNAL_CALL 边数」等可验证计数，而非仅 `status=completed`

### Requirement: 先建边后清旧边，避免数据静默丢失
系统 SHALL 调整跨服务构建顺序，使旧边清理与新建边不构成「先删后建、建失败即丢数据」的窗口：SHALL 先在内存计算命中关系，确认可建边后再清理旧边并写入新边；若建边失败 SHALL 保留旧边。

#### Scenario: 建边失败保留旧边
- **WHEN** 链接阶段在计算命中关系后、写入新边前发生异常
- **THEN** 系统 SHALL 保留既有 EXTERNAL_CALL 边，不出现「旧边已删、新边未建」的空窗

### Requirement: 移除空壳占位符链接策略
系统 SHALL 移除 `OpenApiLinkStrategy` 与 `GrpcLinkStrategy` 两个未实现（`not yet active`）的占位符策略，使 `CrossServiceLinker` 仅遍历真实生效的链接策略。

#### Scenario: 链接器仅执行生效策略
- **WHEN** 跨服务构建触发链接
- **THEN** `CrossServiceLinker` SHALL 仅执行 HTTP/MQ 等已实现策略，不再遍历并打印 OpenAPI/gRPC 占位策略日志

### Requirement: 空选择校验
系统 SHALL 在跨服务构建端点对入参 `projectPaths` 做校验：至少 2 个项目路径方可构建，否则返回参数错误。

#### Scenario: 少于两个项目路径
- **WHEN** 用户以少于 2 个（含空列表）项目路径触发跨服务构建
- **THEN** 系统 SHALL 返回 400 参数错误，不进入构建流程

#### Scenario: 项目未建图
- **WHEN** 入参中任一项目路径在 Neo4j 中无方法节点
- **THEN** 系统 SHALL 返回明确错误，指明该路径尚未建图

### Requirement: 远端路径规范化
系统 SHALL 对远端项目参与跨服务构建的路径做规范化（统一为绝对路径、分隔符、去尾部斜杠），确保本地项目与远端项目在跨服务匹配时路径口径一致。

#### Scenario: 远端路径规范化后匹配
- **WHEN** 远端项目路径以相对路径或非标准分隔符传入
- **THEN** 系统 SHALL 先规范化该路径再参与「是否有图谱」校验与跨服务链接，避免因路径口径不一致导致误判「未建图」或漏链接
