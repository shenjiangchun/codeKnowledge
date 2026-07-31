# Agent Tools Registry

## ADDED Requirements

### Requirement: @Tool 注解工具注册

系统 SHALL 通过 Spring AI `@Tool` 注解替代 `KgToolRegistry.buildToolDefinitions()` 动态注册方式。

#### Scenario: ToolContext 注入 projectPath

**Given** ChatClient 调用时传入 `toolContext: {"projectPath": "/path/to/project"}`  
**When** LLM 调用 `@Tool hybridSearch(String query, ToolContext ctx)`  
**Then** `ctx.get("projectPath")` 返回当前 projectPath；LLM 请求中不包含 projectPath 参数

#### Scenario: 工具列表全量覆盖

**Given** AgentTools Bean 被 Spring 管理  
**When** ChatClient 构建时注册 AgentTools  
**Then** KG 工具 (hybrid_search, load_method_bodies, callees_tree, root_entries, entry_points) 和文件系统工具 (grep_project, read_file, list_files) 全部可用

#### Scenario: 工具执行失败

**Given** hybridSearch 调用时 KG 服务不可达  
**When** ToolCallingAdvisor 执行工具  
**Then** 工具返回 error map `{"error": "KG service unavailable"}`；LLM 收到错误结果并可以尝试其他工具或报告给用户
