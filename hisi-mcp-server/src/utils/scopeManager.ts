/**
 * Session-scope manager for KG project selection.
 *
 * Protocol:
 * 1. kg_select_scope — AI calls this once to pick projects → scope is stored
 * 2. Subsequent tools auto-inherit scope if projectPaths is not explicitly passed
 * 3. Explicit projectPaths still override session scope
 *
 * For HTTP mode, scopes are keyed by an opaque scopeToken.
 */

interface ScopeEntry {
  projectPaths: string[];
  setAt: number;
}

// In-memory store: token → scope
const scopes = new Map<string, ScopeEntry>();

/** Default scope (used in stdio mode — single session per process) */
let defaultScope: ScopeEntry | null = null;

export function setScope(token: string | null, projectPaths: string[]): string {
  const entry: ScopeEntry = { projectPaths, setAt: Date.now() };
  if (token) {
    scopes.set(token, entry);
    return token;
  }
  // stdio mode: no token needed
  defaultScope = entry;
  return '';
}

export function getScope(token?: string | null): string[] | null {
  if (token && scopes.has(token)) {
    return scopes.get(token)!.projectPaths;
  }
  if (defaultScope) {
    return defaultScope.projectPaths;
  }
  return null;
}

export function clearScope(token?: string | null): void {
  if (token) {
    scopes.delete(token);
  } else {
    defaultScope = null;
  }
}

/** Generate a unique scope token */
export function generateToken(): string {
  return `scope_${Date.now()}_${Math.random().toString(36).slice(2, 9)}`;
}

/**
 * Tools that require or benefit from project scope.
 * If one of these is called without projectPaths, auto-inject from session scope.
 */
const SCOPED_TOOLS = new Set([
  'kg_status', 'kg_method_detail', 'kg_method_by_class', 'kg_entry_points',
  'kg_root_entries', 'kg_callees_tree', 'kg_downstream', 'kg_affecting',
  'kg_bridges', 'kg_bridge_stats', 'kg_implementations', 'kg_mybatis_sql',
  'kg_feign_chain', 'kg_mq_chain', 'kg_dashboard', 'kg_dsm',
  'kg_hotspots', 'kg_domains', 'kg_service_topology', 'kg_blast_radius',
  'hybrid_search',
  'log_query', 'log_analyze',
]);

/**
 * Tools that ALWAYS require projectPaths and reject if missing.
 */
const STRICT_SCOPED_TOOLS = new Set([
  'kg_status', 'kg_method_detail', 'kg_method_by_class', 'kg_entry_points',
  'kg_root_entries', 'kg_callees_tree', 'kg_downstream', 'kg_affecting',
  'kg_dashboard', 'kg_dsm', 'kg_hotspots', 'kg_domains', 'kg_service_topology',
  'kg_blast_radius', 'hybrid_search',
]);

export function isScopedTool(name: string): boolean {
  return SCOPED_TOOLS.has(name);
}

export function isStrictScopedTool(name: string): boolean {
  return STRICT_SCOPED_TOOLS.has(name);
}
