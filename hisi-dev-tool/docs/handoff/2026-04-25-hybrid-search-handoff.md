# 混合检索会话移交文档

> 日期: 2026-04-25
> 来源: python-support worktree
> 目标: semantic-search worktree

## 概述

python-support 分支新增了 `publicProjectPath` 字段和公共知识图谱能力。本文档说明该字段在 Neo4j 节点中的位置、取值规则、以及对混合检索的影响。

## `publicProjectPath` 字段

### 出现位置

所有 Neo4j 节点类型均已添加该字段：
- `MethodNode.publicProjectPath`
- `EntryPointNode.publicProjectPath`
- `SqlNode.publicProjectPath`
- `ServiceNode.publicProjectPath`
- `GenerationCheckpointNode.publicProjectPath`

### 取值规则

| 场景 | `projectPath` | `publicProjectPath` |
|------|---------------|---------------------|
| 单项目模式 | `/path/to/project` | `null`（或等于 projectPath） |
| 公共图谱模式 | `/workspace/svc-a` | `/workspace`（用户选定的 rootPath） |

### 向后兼容

旧节点的 `publicProjectPath` 为 `null`。范围查询统一使用：
```cypher
WHERE coalesce(n.publicProjectPath, n.projectPath) = $scope
```

### 索引

`Neo4jInitializer` 在启动时自动创建索引并 backfill：
- 索引名: `idx_method_publicProjectPath`, `idx_entrypoint_publicProjectPath`, etc.
- Backfill: `WHERE n.publicProjectPath IS NULL SET n.publicProjectPath = n.projectPath`

## 属性命名约定

所有 Neo4j 节点属性使用 **camelCase**：
- `projectPath` (非 `project_path`)
- `publicProjectPath`
- `descriptionEmbedding`
- `codeEmbedding`
- `sqlEmbedding`

## `language` 字段

新增 `language` 属性：`"java"` / `"python"`。旧节点 `null` 视作 `"java"`。

查询示例（按语言过滤）：
```cypher
MATCH (m:Method)
WHERE coalesce(m.publicProjectPath, m.projectPath) = $scope
  AND coalesce(m.language, 'java') = $language
RETURN m
```

## 对混合检索的影响

1. **范围过滤**: HybridSearchService 的 scope 参数对应 `publicProjectPath`
2. **多语言**: 需支持按 `language` 字段过滤搜索结果
3. **向量索引**: 现有向量索引不变，新增节点自动被索引
4. **跨服务链接**: `EXTERNAL_CALL` 关系类型用于跨服务调用，可在图扩展搜索中利用

## 示例 Cypher

### 范围内全部方法
```cypher
MATCH (m:Method)
WHERE coalesce(m.publicProjectPath, m.projectPath) = '/workspace'
RETURN m.className, m.methodName, m.language
ORDER BY m.className
```

### 跨服务调用链
```cypher
MATCH (caller:Method)-[c:CALLS {callType: 'EXTERNAL_CALL'}]->(callee:Method)
WHERE coalesce(caller.publicProjectPath, caller.projectPath) = '/workspace'
RETURN caller.className + '.' + caller.methodName AS from,
       callee.className + '.' + callee.methodName AS to,
       c.bridgeType
```

### 向量搜索（带范围）
```cypher
CALL db.index.vector.queryNodes('idx_method_description_embedding', 10, $queryVector)
YIELD node, score
WHERE coalesce(node.publicProjectPath, node.projectPath) = $scope
RETURN node.className, node.methodName, score
```
