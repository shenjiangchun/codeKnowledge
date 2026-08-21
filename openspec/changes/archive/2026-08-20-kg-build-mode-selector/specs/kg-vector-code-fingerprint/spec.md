## MODIFIED Requirements

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

## REMOVED Requirements

### Requirement: Phase 2 Delta Skip Deferred
**Reason**: The deferred Phase 2 (codeHash-based delta skip) is now implemented by the `kg-build-mode-selector` change; the "SHALL NOT implement" constraint no longer holds.
**Migration**: Reuse-skip behavior is specified in `specs/kg-build-mode-selector/spec.md` (Requirement: 全量-复用模式的节点复用判定). The `codeHash` field is now populated during KG build and used for reuse decisions.
