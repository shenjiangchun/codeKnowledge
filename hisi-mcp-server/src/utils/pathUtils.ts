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

const BACKSLASH_RE = /\\/g;
const PATH_SUFFIX_RE = /Path$/;
const PATHS_SUFFIX_RE = /Paths$/;

const EXPLICIT_PATH_KEYS = new Set(['projectPath', 'filePath', 'path']);
const EXPLICIT_PATH_ARRAY_KEYS = new Set([
  'projectPaths',
  'filePaths',
  'paths',
]);

function isPathKey(key: string): boolean {
  return EXPLICIT_PATH_KEYS.has(key) || PATH_SUFFIX_RE.test(key);
}

function isPathArrayKey(key: string): boolean {
  return EXPLICIT_PATH_ARRAY_KEYS.has(key) || PATHS_SUFFIX_RE.test(key);
}

function normalizeString(value: string): string {
  return value.replace(BACKSLASH_RE, '/');
}

/**
 * Returns a NEW shallow-copied args object with path-like string values
 * (and string elements of path-like array values) normalized to use forward
 * slashes. Non-string values, unrelated keys, `undefined`, and `null` are
 * passed through unchanged. The input object is never mutated.
 */
export function normalizePathArgs(
  args: Record<string, unknown>,
): Record<string, unknown> {
  const out: Record<string, unknown> = {};
  for (const [key, value] of Object.entries(args)) {
    if (typeof value === 'string' && isPathKey(key)) {
      out[key] = normalizeString(value);
    } else if (Array.isArray(value) && isPathArrayKey(key)) {
      out[key] = value.map((item) =>
        typeof item === 'string' ? normalizeString(item) : item,
      );
    } else {
      out[key] = value;
    }
  }
  return out;
}
