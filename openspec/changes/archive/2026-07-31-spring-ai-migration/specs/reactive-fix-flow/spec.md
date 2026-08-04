# Reactive Fix Flow

## ADDED Requirements

### Requirement: ReAct 修复流程

FixFlowRunner SHALL 将 Step 6 (ai_fix) 升级为 ReAct Agent 循环，使用 ToolCallingAdvisor 自动调用 writeFix/compileCheck/runTests 工具，最大 5 轮。

#### Scenario: 修复成功

**Given** 异常日志确定了 throwPointSig 和 exceptionType  
**And** Step 4 (generate_test) 已生成 ReproTest 且编译通过  
**When** ReAct 循环开始  
**Then** LLM 调用 writeFix → compileCheck → 编译失败 → writeFix(修正) → compileCheck → 通过 → runTests → 通过 → 返回修复代码

#### Scenario: 最大轮次耗尽

**Given** ReAct 循环已执行 5 轮  
**And** 测试仍未通过  
**Then** 系统 SHALL 返回 UNVERIFIED 状态 + 最后版本代码 + 失败测试输出；推送给前端供人工审核

#### Scenario: HITL 确认

**Given** ReAct 循环成功（测试通过）  
**When** HitlGateAdvisor 触发 HITL gate  
**Then** 前端展示 diff + 测试结果；用户 approve → commit → 流程结束；用户 reject → 丢弃修复 → 标记 REJECTED

#### Scenario: HITL 超时

**Given** HITL gate 处于 WAITING 状态  
**And** 用户 30 分钟内未响应  
**When** @Scheduled 清理任务运行  
**Then** 标记 EXPIRED；删除 worktree；前端显示 "Approval timeout"

#### Scenario: REPRO 阶段失败

**Given** Step 2-4 的 repro test 编译失败或 KG 搜索无结果  
**When** Step 6 ReAct 循环检查前置条件  
**Then** SHALL 跳过 ReAct；标记 UNVERIFIABLE；将异常栈 + 源码直接推送前端供人工修复
