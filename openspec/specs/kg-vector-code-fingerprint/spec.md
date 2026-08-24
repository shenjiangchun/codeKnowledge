# KG Vector Code Fingerprint

## Purpose
Provide a `codeHash` (SHA-256) field on `MethodNode` for reuse-skip detection in the `reuse` build mode (full rebuild with node reuse). Populated during knowledge graph build (scan + merge); used for reuse decisions.

## Requirements

### Requirement: Code Hash Field on MethodNode
`MethodNode` (Neo4j entity) SHALL have an optional `codeHash` property of type String.
`codeHash` SHALL be a SHA-256 hex digest of `<className>.<methodName>(<signature>)\n<comment>\n<methodBody>`.
Null or missing `codeHash` on existing nodes SHALL be treated as "not yet fingerprinted" (backward compatible).
The field SHALL be populated during knowledge graph build (scan + merge), and SHALL be used for reuse-skip decisions in the `reuse` build mode.

#### Scenario: New method node gets code hash including comment
- **WHEN** the code hash is computed from `className.methodName(signature)\ncomment\nmethodBody`
- **THEN** the `codeHash` value SHALL be a 64-character lowercase hex string
- **AND** a change to `comment` SHALL produce a different `codeHash` than the same method with unchanged signature/body

#### Scenario: Existing node without codeHash is backward compatible
- **WHEN** a method node without `codeHash` (legacy data) is encountered
- **THEN** the node SHALL be treated as "not yet fingerprinted"
- **AND** in `reuse` build mode it SHALL be re-fingerprinted and its vector regenerated
