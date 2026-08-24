/**
 * Session-scope manager for KG project selection.
 *
 * Protocol:
 * 1. kg_select_scope(action="list") -> enumerate projects
 * 2. AI uses AskUserQuestion to pick projects
 * 3. kg_select_scope(action="set", projectPaths=[...]) -> scope locked
 * 4. Subsequent tools auto-inherit scope (no need to repeat projectPaths)
 *
 * For stdio mode: single session, defaults to global scope.
 * For HTTP mode: token-keyed per-client scope with TTL eviction.
 */

import * as crypto from 'node:crypto';

interface ScopeEntry {
  projectPaths: string[];
  setAt: number;
}

const scopes = new Map<string, ScopeEntry>();
let defaultScope: ScopeEntry | null = null;

/** Maximum scope lifetime: 30 minutes */
const SCOPE_TTL_MS = 30 * 60 * 1000;

/** Cleanup stale scopes every 5 minutes */
const CLEANUP_INTERVAL_MS = 5 * 60 * 1000;

/** Start periodic eviction of expired scope tokens */
let cleanupTimer: ReturnType<typeof setInterval> | null = null;
function ensureCleanup(): void {
  if (cleanupTimer) return;
  cleanupTimer = setInterval(() => {
    const now = Date.now();
    for (const [token, entry] of scopes) {
      if (now - entry.setAt > SCOPE_TTL_MS) scopes.delete(token);
    }
  }, CLEANUP_INTERVAL_MS);
  cleanupTimer.unref(); // don't block process exit
}

export function setScope(token: string | null, projectPaths: string[]): string {
  const entry: ScopeEntry = { projectPaths, setAt: Date.now() };
  if (token) {
    ensureCleanup();
    scopes.set(token, entry);
    return token;
  }
  defaultScope = entry;
  return '';
}

export function getScope(token?: string | null): string[] | null {
  if (token && scopes.has(token)) {
    const entry = scopes.get(token)!;
    if (Date.now() - entry.setAt > SCOPE_TTL_MS) {
      scopes.delete(token);
      return null;
    }
    return entry.projectPaths;
  }
  if (defaultScope) return defaultScope.projectPaths;
  return null;
}

export function clearScope(token?: string | null): void {
  if (token) { scopes.delete(token); }
  else { defaultScope = null; }
}

/** Generate a cryptographically random scope token */
export function generateToken(): string {
  return `scope_${crypto.randomUUID()}`;
}

// ================================================================
// Tool registry — single source of truth for scope awareness
// ================================================================
interface ToolScopeConfig {
  /** Auto-inject scope if available */
  scoped: boolean;
  /** Reject call if no scope set */
  strict: boolean;
}

const TOOL_SCOPE_REGISTRY: Record<string, ToolScopeConfig> = {
  // KG core — strict: search/trace tools MUST have project scope
  kg_status:              { scoped: true, strict: true },
  kg_method_detail:       { scoped: true, strict: true },
  kg_method_by_class:     { scoped: true, strict: true },
  kg_entry_points:        { scoped: true, strict: true },
  kg_root_entries:        { scoped: true, strict: true },
  kg_callees_tree:        { scoped: true, strict: true },
  kg_downstream:          { scoped: true, strict: true },
  kg_affecting:           { scoped: true, strict: true },
  kg_blast_radius:        { scoped: true, strict: true },
  // Architecture tools — strict
  kg_dashboard:           { scoped: true, strict: true },
  kg_dsm:                 { scoped: true, strict: true },
  kg_hotspots:            { scoped: true, strict: true },
  kg_domains:             { scoped: true, strict: true },
  kg_service_topology:    { scoped: true, strict: true },
  // Bridge tools — soft scoped (can work without if explicit projectPath)
  kg_bridges:             { scoped: true, strict: false },
  kg_bridge_stats:        { scoped: true, strict: false },
  kg_implementations:     { scoped: true, strict: false },
  kg_mybatis_sql:         { scoped: true, strict: false },
  kg_feign_chain:         { scoped: true, strict: false },
  kg_mq_chain:            { scoped: true, strict: false },
  // Hybrid search — strict
  hybrid_search:          { scoped: true, strict: true },
  // Log — soft scoped
  log_query:              { scoped: true, strict: false },
  log_analyze:            { scoped: true, strict: false },
};

export function isScopedTool(name: string): boolean {
  return TOOL_SCOPE_REGISTRY[name]?.scoped ?? false;
}

export function isStrictScopedTool(name: string): boolean {
  return TOOL_SCOPE_REGISTRY[name]?.strict ?? false;
}
