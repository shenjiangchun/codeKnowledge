#!/usr/bin/env node

/**
 * HiSi MCP Server v2.1.0 — with Session Scope Protocol + Remote Project Support
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
const SERVER_VERSION = '2.1.0';
const API_BASE_URL = process.env.HISI_API_URL || 'http://localhost:8080';
const HTTP_PORT = process.env.HISI_HTTP_PORT ? parseInt(process.env.HISI_HTTP_PORT, 10) : null;
const DEBUG = process.env.HISI_DEBUG === 'true';

// ============================================================
// Remote project type (matches backend RemoteProject model)
// ============================================================
interface RemoteProjectInfo {
  id: number;
  name: string;
  gitUrl: string;
  username?: string;
  branch?: string;
  localPath?: string;
  fullPath?: string;
  cloneStatus: string;
  cloneError?: string;
  lastSyncAt?: number;
  authType?: string;
  groupId?: number;
  groupName?: string;
}

// ============================================================
// kg_select_scope tool definition
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
// handleScopeTool: merged KG + remote project listing
// ============================================================
async function handleScopeTool(args: Record<string, unknown>): Promise<unknown> {
  const action = (args.action as string) || 'list';
  const client = getApiClient();

  switch (action) {
    case 'list': {
      // Query BOTH Neo4j (indexed KG projects) AND SQLite (remote project configs)
      const [kgResp, remoteResp] = await Promise.allSettled([
        client.get('/api/v2/knowledge-graph/projects').catch(() => ({ data: [] })),
        client.get('/api/remote-projects').catch(() => ({ data: [] })),
      ]);

      const kgPaths: string[] = (kgResp.status === 'fulfilled' ? (kgResp.value as any)?.data : []) || [];
      const remoteProjects: RemoteProjectInfo[] = (remoteResp.status === 'fulfilled' ? (remoteResp.value as any)?.data : []) || [];

      // Normalize for matching
      const norm = (s: string) => (s || '').replace(/\\/g, '/').toLowerCase();
      const kgNormSet = new Set(kgPaths.map(norm));

      // Categorize remote projects
      const indexedRemote: RemoteProjectInfo[] = [];
      const clonedNotIndexed: RemoteProjectInfo[] = [];
      const notCloned: RemoteProjectInfo[] = [];

      for (const rp of remoteProjects) {
        const fp = norm(rp.fullPath || rp.localPath || '');
        if (rp.cloneStatus === 'CLONED' && ([...kgNormSet].some(kp => kp.includes(fp) || fp.includes(kp)))) {
          indexedRemote.push(rp);
        } else if (rp.cloneStatus === 'CLONED') {
          clonedNotIndexed.push(rp);
        } else {
          notCloned.push(rp);
        }
      }

      // Pure local = in KG but not matched to any remote
      const remoteNormPaths = new Set(remoteProjects.map(r => norm(r.fullPath || r.localPath || '')));
      const pureLocal = kgPaths.filter(p =>
        ![...remoteNormPaths].some(rf => rf.length > 0 && norm(p).includes(rf))
      );

      // Sample stats for KG projects (limit to avoid timeout)
      const stats: Record<string, unknown> = {};
      for (const p of kgPaths.slice(0, 15)) {
        try {
          const sr = await client.get('/api/v2/knowledge-graph/status', { projectPaths: p });
          if ((sr as any)?.success && (sr as any)?.data) stats[p] = (sr as any).data;
        } catch { stats[p] = { status: 'error' }; }
      }

      const currentScope = getScope();
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
      // Use null token → stores in defaultScope (correct for single-user HTTP + stdio)
      setScope(null, paths);
      return {
        success: true,
        projectPaths: paths,
        projectCount: paths.length,
        message: `✅ Scope locked: ${paths.length} projects. All subsequent tools auto-use this scope.`,
      };
    }

    case 'get': {
      const current = getScope();
      return {
        success: true,
        scoped: current !== null,
        projectPaths: current || [],
        projectCount: current ? current.length : 0,
      };
    }

    case 'clear':
      clearScope();
      return { success: true, message: 'Scope cleared. Full project search restored.' };

    default:
      return { success: false, error: `Unknown action: ${action}` };
  }
}

// ============================================================
// Scope auto-injection
// ============================================================
function injectScope(toolName: string, args: Record<string, unknown>): Record<string, unknown> {
  if (toolName === 'kg_select_scope' || toolName === 'kg_list_projects' || toolName === 'apm_start_session') {
    return args;
  }
  if (!isScopedTool(toolName)) return args;

  const hasExplicit = args.projectPaths || args.projectPath;
  if (hasExplicit) return args;

  const scope = getScope();
  if (!scope) {
    if (isStrictScopedTool(toolName)) return { ...args, _scopeError: true };
    return args;
  }

  return { ...args, projectPaths: scope, projectPath: scope[0] };
}

function noScopeResponse() {
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
// MCP Server configuration (stdio mode)
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

    const args = (rawArgs || {}) as Record<string, unknown>;

    if (name === 'kg_select_scope') {
      try {
        const result = await handleScopeTool(args);
        return { content: [{ type: 'text', text: JSON.stringify(result, null, 2) }] };
      } catch (e) {
        const msg = e instanceof Error ? e.message : 'Unknown error';
        return { content: [{ type: 'text', text: JSON.stringify({ success: false, error: msg }) }], isError: true };
      }
    }

    const scopedArgs = injectScope(name, args);
    if (scopedArgs._scopeError) return noScopeResponse();

    try {
      const result = await handleToolCall(name, scopedArgs);
      return { content: [{ type: 'text', text: JSON.stringify(result, null, 2) }] };
    } catch (error) {
      const msg = error instanceof Error ? error.message : 'Unknown error';
      return { content: [{ type: 'text', text: JSON.stringify({ success: false, error: msg, tool: name }) }], isError: true };
    }
  });
}

// ============================================================
// JSON-RPC HTTP bridge
// ============================================================
async function jsonRpcHandler(body: string): Promise<string> {
  let req: { jsonrpc?: string; id?: unknown; method?: string; params?: Record<string, unknown> };
  try { req = JSON.parse(body); } catch {
    return JSON.stringify({ jsonrpc: '2.0', error: { code: -32700, message: 'Parse error' }, id: null });
  }
  const { method, params = {}, id } = req;

  if (method === 'initialize') {
    return JSON.stringify({ jsonrpc: '2.0', result: { protocolVersion: '2025-03-26', capabilities: { tools: {} }, serverInfo: { name: SERVER_NAME, version: SERVER_VERSION } }, id });
  }
  if (method === 'tools/list') {
    return JSON.stringify({ jsonrpc: '2.0', result: { tools: allDefsWithScope.map(t => ({ name: t.name, description: t.description, inputSchema: t.inputSchema })) }, id });
  }
  if (method === 'tools/call') {
    const { name, arguments: rawArgs = {} } = params as { name: string; arguments?: Record<string, unknown> };
    if (!allDefsWithScope.some(t => t.name === name)) {
      return JSON.stringify({ jsonrpc: '2.0', error: { code: -32601, message: `Tool not found: ${name}` }, id });
    }

    if (name === 'kg_select_scope') {
      try {
        const result = await handleScopeTool(rawArgs);
        return JSON.stringify({ jsonrpc: '2.0', result: { content: [{ type: 'text', text: JSON.stringify(result, null, 2) }] }, id });
      } catch (e) {
        const msg = e instanceof Error ? e.message : 'Unknown error';
        return JSON.stringify({ jsonrpc: '2.0', result: { content: [{ type: 'text', text: JSON.stringify({ success: false, error: msg }) }], isError: true }, id });
      }
    }

    const scopedArgs = injectScope(name, rawArgs);
    if (scopedArgs._scopeError) {
      return JSON.stringify({
        jsonrpc: '2.0',
        result: { content: [{ type: 'text', text: JSON.stringify({ success: false, error: 'SCOPE_NOT_SET', message: 'Call kg_select_scope first.' }) }], isError: true },
        id,
      });
    }

    try {
      const result = await handleToolCall(name, scopedArgs);
      return JSON.stringify({ jsonrpc: '2.0', result: { content: [{ type: 'text', text: JSON.stringify(result, null, 2) }] }, id });
    } catch (error) {
      const msg = error instanceof Error ? error.message : 'Unknown error';
      return JSON.stringify({ jsonrpc: '2.0', result: { content: [{ type: 'text', text: JSON.stringify({ success: false, error: msg }) }], isError: true }, id });
    }
  }
  if (method === 'ping') return JSON.stringify({ jsonrpc: '2.0', result: {}, id });

  return JSON.stringify({ jsonrpc: '2.0', error: { code: -32601, message: `Method not found: ${method}` }, id });
}

// ============================================================
// HTTP server
// ============================================================
async function startHttpServer(port: number): Promise<void> {
  const httpServer = http.createServer(async (req, res) => {
    res.setHeader('Access-Control-Allow-Origin', '*');
    res.setHeader('Access-Control-Allow-Methods', 'GET, POST, OPTIONS');
    res.setHeader('Access-Control-Allow-Headers', 'Content-Type');

    if (req.method === 'OPTIONS') { res.writeHead(204); res.end(); return; }
    if (req.method === 'GET' && req.url === '/health') {
      res.writeHead(200, { 'Content-Type': 'application/json' });
      res.end(JSON.stringify({ status: 'ok', server: SERVER_NAME, version: SERVER_VERSION, apiBackend: API_BASE_URL, tools: allDefsWithScope.length, scopeActive: getScope() !== null }));
      return;
    }
    if (req.method !== 'POST') { res.writeHead(405).end('POST only'); return; }

    const chunks: Buffer[] = [];
    for await (const chunk of req) chunks.push(chunk);
    try {
      const result = await jsonRpcHandler(Buffer.concat(chunks).toString());
      res.writeHead(200, { 'Content-Type': 'application/json' });
      res.end(result);
    } catch (err) {
      console.error('[hisi-mcp-server] HTTP error:', err);
      res.writeHead(500, { 'Content-Type': 'application/json' });
      res.end(JSON.stringify({ jsonrpc: '2.0', error: { code: -32603, message: 'Internal error' }, id: null }));
    }
  });

  return new Promise<void>((resolve, reject) => {
    httpServer.on('error', reject);
    httpServer.listen(port, '0.0.0.0', () => {
      console.error(`[hisi-mcp-server v${SERVER_VERSION}] HTTP+Scope on http://0.0.0.0:${port}/mcp`);
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

['SIGINT','SIGTERM'].forEach(s => process.on(s, () => process.exit(0)));
process.on('uncaughtException', e => { console.error('Uncaught:', e); process.exit(1); });
process.on('unhandledRejection', r => { console.error('Unhandled:', r); process.exit(1); });

main().catch(e => { console.error('Start failed:', e); process.exit(1); });
