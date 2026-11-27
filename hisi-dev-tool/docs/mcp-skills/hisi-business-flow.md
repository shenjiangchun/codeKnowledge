# HiSi 业务流分析 Skill

> 工具由后端进程内 MCP 服务（`hisi-mcp-inproc`，streamable HTTP `/mcp`）提供，见 CodeWiki「进程内MCP工具」篇。

## 目的
分析接口的完整业务调用流程

## 使用场景
用户提供一个URI或接口名，需要了解完整的调用链路

## 步骤
1. 使用 `hybrid_search` 定位 URI 对应的入口方法（或先 `kg_entry_points` 按 entryType 过滤找入口）
2. 使用 `kg_root_entries` 获取根入口点与直接调用方
3. 使用 `kg_callees_tree` 获取下游调用树（含深度与循环检测，适合生成链路图）
4. 使用 `kg_method_detail` 获取关键方法的详细信息
5. 组织成完整的业务流程图

## 输出格式
- **入口方法**: Controller层的接口定义
- **调用链路**: 完整的方法调用序列（含层级缩进）
- **关键节点**: 重要的分支和决策点
- **外部依赖**: 数据库、MQ、HTTP调用
- **流程图**: Mermaid格式的时序图
