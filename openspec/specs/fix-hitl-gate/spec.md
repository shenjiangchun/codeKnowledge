# fix-hitl-gate Specification

## Purpose
TBD - created by archiving change architecture-review-fixes. Update Purpose after archive.
## Requirements
### Requirement: commit 前人工闸门
系统 SHALL 在 fixengine 修复流中，当修复未通过测试验证时，停在 worktree 不 commit，把 diff + 测试结果推给人，人确认后才提交。

#### Scenario: 测试未通过不 commit
- **WHEN** fixengine 生成的修复未通过复现/验证测试
- **THEN** 系统 SHALL 停在 worktree，不自动 commit，将 diff 和测试结果呈现给用户等待确认

#### Scenario: 人确认后提交
- **WHEN** 用户确认修复后
- **THEN** 系统 SHALL 才执行 commit

#### Scenario: 测试通过可自动提交
- **WHEN** 修复通过了测试验证
- **THEN** 系统 SHALL 可自动 commit（无需人工闸门）

