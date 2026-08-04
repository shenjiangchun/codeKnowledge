/**
 * Path normalization utilities for MCP tool arguments.
 *
 * Windows users frequently paste backslash paths (e.g. `C:\foo\bar`) into MCP
 * clients, but the knowledge graph's Neo4j store keys nodes by forward-slash
 * paths. `normalizePathArgs` is invoked once per tool dispatch in
 * `src/tools/index.ts` to coerce path-shaped arguments before they reach the
 * downstream services.
 *
 * Assumption: all known consumers only inspect TOP-LEVEL keys of the args
 * object, so a shallow copy is sufficient — nested objects are passed through
 * by reference.
 */
/**
 * Returns a NEW shallow-copied args object with path-like string values
 * (and string elements of path-like array values) normalized to use forward
 * slashes. Non-string values, unrelated keys, `undefined`, and `null` are
 * passed through unchanged. The input object is never mutated.
 */
export declare function normalizePathArgs(args: Record<string, unknown>): Record<string, unknown>;
//# sourceMappingURL=pathUtils.d.ts.map