#!/usr/bin/env node

/**
 * HiSi MCP Server v2.2.0
 *
 * Scope Protocol (mandatory):
 *   Step 1: AI calls kg_select_scope(action="list") -> returns indexed + remote projects
 *   Step 2: AI asks user to pick projects via AskUserQuestion (multiSelect=true)
 *   Step 3: AI calls kg_select_scope(action="set", projectPaths=[...])
 *   Step 4: All subsequent tools auto-inherit scope. Tools reject if no scope set.
 *
 * Transport modes:
 *   - stdio (default): local Claude Code subprocess
 *   - HTTP JSON-RPC: remote clients (HISI_HTTP_PORT=3100)
 */

import { Server } from '@modelcontextprotocol/sdk/server/index.js';
import { StdioServerTransport } from '@modelcontextprotocol/sdk/server/stdio.js';
import {
  CallToolRequestSchema,
  ListToolsRequestSchema,
  ErrorCode,
  McpError,
} from '@modelcontextprotocol/sdk/types.js';
import * as http from 'node:http';

import { allToolDefinitions, handleToolCall } from './tools/index.js';
import { getApiClient } from './client/apiClient.js';
import {
  setScope, getScope, clearScope, generateToken,
  isStrictScopedTool, isScopedTool,
} from './utils/scopeManager.js';

const SERVER_NAME = 'hisi-mcp-server';
const SERVER_VERSION = '2.2.0';
const API_BASE_URL = process.env.HISI_API_URL || 'http://localhost:8080';
const HTTP_PORT = process.env.HISI_HTTP_PORT ? parseInt(process.env.HISI_HTTP_PORT, 10) : null;
const DEBUG = process.env.HISI_DEBUG === 'true';

/** Max request body size: 5 MB */
const MAX_BODY_BYTES = 5 * 1024 * 1024;

// ============================================================
// Scope tool definition
// ============================================================
const SCOPE_TOOL_DEF = {
  name: 'kg_select_scope',
  description:
    '[MANDATORY FIRST CALL] Select KG search scope for this session.\n\n' +
    'Protocol:\n' +
    '1. Call action="list" -> get all indexed KG projects + remote project configs\n' +
    '2. Use AskUserQuestion to ask user "Which projects to search?" (multiSelect=true)\n' +
    '3. Call action="set" with selected projectPaths -> scope is locked\n' +
    '4. All subsequent KG tools auto-use this scope. No need to pass projectPaths again.\n\n' +
    'projectPaths override: pass explicit projectPaths in any tool to temporarily override scope.\n' +
    'Clear scope: action="clear" resets to full-project search.',
  inputSchema: {
    type: 'object' as const,
    properties: {
      action: {
        type: 'string' as const,
        enum: ['list', 'set', 'get', 'clear'],
        description: 'list=show all projects, set=lock scope, get=view current, clear=reset',
      },
      projectPaths: {
        type: 'array' as const,
        items: { type: 'string' as const },
        description: 'Project paths to set as scope (required for action="set")',
      },
    },
    required: ['action'],
  },
};

const allDefsWithScope = [...allToolDefinitions, SCOPE_TOOL_DEF];

// ============================================================
// Scope tool handler
// ============================================================
async function handleScopeTool(args: Record<string, unknown>, scopeToken?: string): Promise<unknown> {
  const action = (args.action as string) || 'list';
  const client = getApiClient();

  switch (action) {
    case 'list': {
      const [kgResp, remoteResp] = await Promise.allSettled([
        client.get('/api/v2/knowledge-graph/projects').catch(() => ({ data: [] })),
        client.get('/api/remote-projects').catch(() => ({ data: [] })),
      ]);

      const kgPaths: string[] = (kgResp.status === 'fulfilled' ? (kgResp.value as any)?.data : []) || [];
      const remoteProjects: any[] = (remoteResp.status === 'fulfilled' ? (remoteResp.value as any)?.data : []) || [];

      // Normalize for matching
      const norm = (s: string) => (s || '').replace(/\\/g, '/').toLowerCase();
      const kgNormSet = new Set(kgPaths.map(norm));
      const remoteNormPaths = new Set(remoteProjects.map((r: any) => norm(r.fullPath || r.localPath || '')));

      const indexedRemote: any[] = [];
      const clonedNotIndexed: any[] = [];
      const notCloned: any[] = [];
      for (const rp of remoteProjects) {
        const fp = norm(rp.fullPath || rp.localPath || '');
        if (rp.cloneStatus === 'CLONED' && [...kgNormSet].some(kp => kp.includes(fp) || fp.includes(kp))) {
          indexedRemote.push(rp);
        } else if (rp.cloneStatus === 'CLONED') {
          clonedNotIndexed.push(rp);
        } else {
          notCloned.push(rp);
        }
      }
      const pureLocal = kgPaths.filter(p =>
        ![...remoteNormPaths].some(rf => rf.length > 0 && norm(p).includes(rf))
      );

      // Fetch stats in parallel (limited to avoid backend overload)
      const MAX_STATS = 20;
      const truncated = kgPaths.length > MAX_STATS;
      const samplePaths = kgPaths.slice(0, MAX_STATS);
      const stats: Record<string, unknown> = {};
      const results = await Promise.allSettled(
        samplePaths.map(async p => {
          try {
            const sr = await client.get('/api/v2/knowledge-graph/status', { projectPaths: p });
            if ((sr as any)?.success && (sr as any)?.data) return { path: p, data: (sr as any).data };
            return { path: p, data: { status: 'no_data' } };
          } catch (e: unknown) {
            const msg = e instanceof Error ? e.message : String(e);
            if (DEBUG) console.error(`[DEBUG] kg_status failed for ${p}: ${msg}`);
            return { path: p, data: { status: 'error', detail: msg } };
          }
        })
      );
      for (const r of results) {
        if (r.status === 'fulfilled') stats[r.value.path] = r.value.data;
      }

      const currentScope = getScope(scopeToken);
      return {
        success: true,
        summary: {
          totalIndexed: kgPaths.length,
          totalRemoteConfigs: remoteProjects.length,
          pureLocal: pureLocal.length,
          indexedRemote: indexedRemote.length,
          clonedNotIndexed: clonedNotIndexed.length,
          notCloned: notCloned.length,
        },
        truncated,
        sampleCount: MAX_STATS,
        searchableProjects: kgPaths.map(p => ({
          path: p,
          stats: stats[p] || {},
          source: pureLocal.includes(p) ? 'local' : 'remote-indexed',
        })),
        remoteProjects: {
          indexed: indexedRemote.map(r => ({ name: r.name, gitUrl: r.gitUrl, branch: r.branch, fullPath: r.fullPath })),
          clonedNotIndexed: clonedNotIndexed.map(r => ({
            name: r.name, gitUrl: r.gitUrl, fullPath: r.fullPath,
            action: 'Cloned but not KG-indexed — run KG build first',
          })),
          notCloned: notCloned.map(r => ({
            name: r.name, gitUrl: r.gitUrl, cloneStatus: r.cloneStatus,
            action: 'Not cloned — clone first then KG build',
          })),
        },
        currentScope: currentScope || null,
        instruction: currentScope
          ? `Scope locked: ${currentScope.length} projects. Start searching.`
          : 'No scope set. Ask user to pick projects from searchableProjects, then call action="set".',
      };
    }

    case 'set': {
      const paths = args.projectPaths as string[] | undefined;
      if (!paths || paths.length === 0) {
        return { success: false, error: 'projectPaths is required for action="set"' };
      }
      // In HTTP mode, use token-keyed scope; in stdio, use global
      const token = scopeToken ? scopeToken : null;
      const newToken = setScope(token, paths);
      return {
        success: true,
        scopeToken: newToken || undefined,
        projectPaths: paths,
        projectCount: paths.length,
        message: `Scope locked: ${paths.length} projects. All subsequent tools auto-use this scope.`,
      };
    }

    case 'get': {
      const current = getScope(scopeToken);
      return {
        success: true,
        scoped: current !== null,
        projectPaths: current || [],
        projectCount: current ? current.length : 0,
      };
    }

    case 'clear':
      clearScope(scopeToken);
      return { success: true, message: 'Scope cleared. Full project search restored.' };

    default:
      return { success: false, error: `Unknown action: ${action}` };
  }
}

// ============================================================
// Shared tool dispatch — used by both stdio and HTTP transports
// ============================================================
interface DispatchResult {
  content: Array<{ type: string; text: string }>;
  isError?: boolean;
}

async function dispatchToolCall(
  name: string,
  rawArgs: Record<string, unknown>,
  scopeToken?: string
): Promise<DispatchResult> {
  // Handle scope tool
  if (name === 'kg_select_scope') {
    try {
      const result = await handleScopeTool(rawArgs, scopeToken);
      return { content: [{ type: 'text', text: JSON.stringify(result, null, 2) }] };
    } catch (e) {
      const msg = e instanceof Error ? e.message : 'Unknown error';
      return { content: [{ type: 'text', text: JSON.stringify({ success: false, error: msg }) }], isError: true };
    }
  }

  // Auto-inject scope
  const scopedArgs = injectScope(name, rawArgs, scopeToken);
  if (scopedArgs._scopeError) return noScopeResponse();

  try {
    const result = await handleToolCall(name, scopedArgs);
    return { content: [{ type: 'text', text: JSON.stringify(result, null, 2) }] };
  } catch (error) {
    const msg = error instanceof Error ? error.message : 'Unknown error';
    return { content: [{ type: 'text', text: JSON.stringify({ success: false, error: msg, tool: name }) }], isError: true };
  }
}

// ============================================================
// Scope auto-injection
// ============================================================
function injectScope(
  toolName: string,
  args: Record<string, unknown>,
  scopeToken?: string
): Record<string, unknown> {
  // Tools explicitly exempt from scope injection:
  // - kg_select_scope: is the scope manager itself
  // - kg_list_projects: lists everything (before scope is set)
  // - apm_start_session: uses appPath, not KG projectPaths
  if (toolName === 'kg_select_scope' || toolName === 'kg_list_projects' || toolName === 'apm_start_session') {
    return args;
  }
  if (!isScopedTool(toolName)) return args;

  const hasExplicit = args.projectPaths || args.projectPath;
  if (hasExplicit) return args;

  const scope = getScope(scopeToken);
  if (!scope) {
    if (isStrictScopedTool(toolName)) return { ...args, _scopeError: true };
    return args;
  }

  return { ...args, projectPaths: scope, projectPath: scope[0] };
}

function noScopeResponse(): DispatchResult {
  return {
    content: [{
      type: 'text',
      text: JSON.stringify({
        success: false,
        error: 'SCOPE_NOT_SET',
        message: 'Scope not set. Call kg_select_scope(action="list") -> pick projects -> kg_select_scope(action="set").',
      }, null, 2),
    }],
    isError: true,
  };
}

// ============================================================
// MCP Server (stdio mode)
// ============================================================
function configureServer(server: Server): void {
  server.setRequestHandler(ListToolsRequestSchema, async () => ({
    tools: allDefsWithScope.map(t => ({
      name: t.name, description: t.description, inputSchema: t.inputSchema,
    })),
  }));

  server.setRequestHandler(CallToolRequestSchema, async (request) => {
    const { name, arguments: rawArgs } = request.params;
    if (!allDefsWithScope.some(t => t.name === name)) {
      throw new McpError(ErrorCode.MethodNotFound, `Unknown tool: ${name}`);
    }
    // Reuse shared dispatch; unwrap DispatchResult into MCP SDK format
    const { content, isError } = await dispatchToolCall(name, (rawArgs || {}) as Record<string, unknown>);
    return isError ? { content, isError } : { content };
  });
}

// ============================================================
// JSON-RPC HTTP handler
// ============================================================
function jsonRpcReply(id: unknown, result: unknown): string {
  return JSON.stringify({ jsonrpc: '2.0', result, id });
}
function jsonRpcError(id: unknown, code: number, message: string): string {
  return JSON.stringify({ jsonrpc: '2.0', error: { code, message }, id });
}

async function jsonRpcHandler(body: string, scopeToken?: string): Promise<string> {
  let req: { jsonrpc?: string; id?: unknown; method?: string; params?: Record<string, unknown> };
  try { req = JSON.parse(body); } catch {
    return jsonRpcError(null, -32700, 'Parse error');
  }
  const { method, params = {}, id } = req;

  switch (method) {
    case 'initialize':
      return jsonRpcReply(id, {
        protocolVersion: '2025-03-26',
        capabilities: { tools: {} },
        serverInfo: { name: SERVER_NAME, version: SERVER_VERSION },
      });
    case 'tools/list':
      return jsonRpcReply(id, {
        tools: allDefsWithScope.map(t => ({ name: t.name, description: t.description, inputSchema: t.inputSchema })),
      });
    case 'tools/call': {
      const { name, arguments: rawArgs = {} } = params as { name: string; arguments?: Record<string, unknown> };
      if (!allDefsWithScope.some(t => t.name === name)) {
        return jsonRpcError(id, -32601, `Tool not found: ${name}`);
      }
      const result = await dispatchToolCall(name, rawArgs, scopeToken);
      return jsonRpcReply(id, result);
    }
    case 'ping':
      return jsonRpcReply(id, {});
    default:
      return jsonRpcError(id, -32601, `Method not found: ${method}`);
  }
}

// ============================================================
// HTTP server (no CORS — this is an API, not a browser service)
// ============================================================
function readBody(req: http.IncomingMessage): Promise<string> {
  return new Promise((resolve, reject) => {
    const chunks: Buffer[] = [];
    let total = 0;
    req.on('data', (chunk: Buffer) => {
      total += chunk.length;
      if (total > MAX_BODY_BYTES) {
        req.destroy();
        reject(new Error('Request body too large'));
        return;
      }
      chunks.push(chunk);
    });
    req.on('end', () => resolve(Buffer.concat(chunks).toString()));
    req.on('error', reject);
  });
}

async function startHttpServer(port: number): Promise<void> {
  const httpServer = http.createServer(async (req, res) => {
    // Health check
    if (req.method === 'GET' && req.url === '/health') {
      res.writeHead(200, { 'Content-Type': 'application/json' });
      res.end(JSON.stringify({
        status: 'ok', server: SERVER_NAME, version: SERVER_VERSION,
        apiBackend: API_BASE_URL, tools: allDefsWithScope.length,
        scopeActive: getScope() !== null,
      }));
      return;
    }

    if (req.method !== 'POST') {
      res.writeHead(405, { 'Content-Type': 'application/json' });
      res.end(jsonRpcError(null, -32600, 'POST only'));
      return;
    }

    const ct = req.headers['content-type'] || '';
    if (!ct.includes('application/json')) {
      res.writeHead(415, { 'Content-Type': 'application/json' });
      res.end(jsonRpcError(null, -32600, 'Content-Type must be application/json'));
      return;
    }

    try {
      const body = await readBody(req);
      // Extract scope token from custom header (HTTP clients pass token here)
      const scopeToken = (req.headers['x-scope-token'] as string) || undefined;
      const result = await jsonRpcHandler(body, scopeToken);
      res.writeHead(200, { 'Content-Type': 'application/json' });
      res.end(result);
    } catch (err: unknown) {
      if ((err as Error).message === 'Request body too large') {
        res.writeHead(413, { 'Content-Type': 'application/json' });
        res.end(jsonRpcError(null, -32600, 'Request body exceeds 5 MB limit'));
        return;
      }
      console.error('[hisi-mcp-server] HTTP error:', err);
      if (!res.headersSent) {
        res.writeHead(500, { 'Content-Type': 'application/json' });
        res.end(jsonRpcError(null, -32603, 'Internal error'));
      }
    }
  });

  return new Promise<void>((resolve, reject) => {
    httpServer.on('error', reject);
    httpServer.listen(port, '0.0.0.0', () => {
      console.error(`[hisi-mcp-server v${SERVER_VERSION}] HTTP JSON-RPC → http://0.0.0.0:${port}/mcp`);
      console.error(`[hisi-mcp-server] Health: http://localhost:${port}/health`);
      resolve();
    });
  });
}

async function main(): Promise<void> {
  if (DEBUG) console.error(`[DEBUG] ${SERVER_NAME} v${SERVER_VERSION}, backend: ${API_BASE_URL}`);
  getApiClient(API_BASE_URL);

  if (HTTP_PORT) {
    await startHttpServer(HTTP_PORT);
    return;
  }

  const server = new Server({ name: SERVER_NAME, version: SERVER_VERSION }, { capabilities: { tools: {} } });
  configureServer(server);
  await server.connect(new StdioServerTransport());
  if (DEBUG) console.error('[DEBUG] stdio connected');
}

['SIGINT', 'SIGTERM'].forEach(s => process.on(s, () => process.exit(0)));
process.on('uncaughtException', e => { console.error('Uncaught:', e); process.exit(1); });
process.on('unhandledRejection', r => { console.error('Unhandled:', r); process.exit(1); });

main().catch(e => { console.error('Start failed:', e); process.exit(1); });
