## ADDED Requirements

> **阶段**: 设计预留 —— 本次仅创建 `codeHash` 字段 + Neo4j 索引；增量跳过逻辑二期实现。

### Requirement: Code Hash Field on MethodNode
`MethodNode` (Neo4j entity) SHALL have an optional `codeHash` property of type String.
`codeHash` SHALL be a SHA-256 hex digest of `<className>.<methodName>(<signature>)\n<methodBody>`.
Null or missing `codeHash` on existing nodes SHALL be treated as "not yet fingerprinted" (backward compatible).
The field SHALL be populated during vector generation but the skip-if-unchanged logic is deferred to Phase 2.

#### Scenario: New method node gets code hash during generation
- GIVEN a newly parsed method node
- WHEN the code hash is computed from `className.methodName(signature)\nmethodBody`
- THEN the `codeHash` value SHALL be a 64-character lowercase hex string

#### Scenario: Existing node without codeHash is backward compatible
- GIVEN a method node without `codeHash` (legacy data)
- WHEN the vector generation pipeline runs
- THEN the node SHALL be processed normally (no skip)
- AND the `codeHash` SHALL be populated for future use

### Requirement: Phase 2 Delta Skip Deferred
The codeHash-based delta skip logic SHALL NOT be implemented in this change.
The Phase 1 implementation SHALL only create the `codeHash` field and Neo4j index, and SHALL populate `codeHash` during vector generation.
The `codeHash` comparison for method-level incremental skip SHALL be implemented in a subsequent change.

#### Scenario: Phase 1 does not skip based on codeHash
- GIVEN a method node with matching `codeHash` and existing `descriptionEmbedding`
- WHEN Phase 1 vector generation runs
- THEN the method SHALL still be checked against the existing `descriptionEmbedding == null` logic only
- AND `codeHash` comparison SHALL NOT be used for skip decisions
