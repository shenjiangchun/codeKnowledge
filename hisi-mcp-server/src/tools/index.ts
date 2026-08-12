/**
 * Tools module - aggregates all MCP tool definitions and routes tool calls
 *
 * Tool groups:
 *   - Knowledge Graph (22 tools, prefix kg_)   → knowledgeGraphTools.ts  (+ kg_select_scope in index.ts)
 *   - Hybrid Search   (1 tool)                 → vectorTools.ts
 *   - Log Query       (4 tools, prefix log_)   → logTools.ts
 *   - APM Debug       (6 tools, prefix apm_)   → apmTools.ts
 *   ─────────────────────────────────────────
 *   Total: 33 tools registered in index.ts
 */

export {
  knowledgeGraphToolDefinitions,
  handleKnowledgeGraphToolCall,
  KnowledgeGraphTools,
  KG_TOOLS,
  type KgStatusParams,
  type KgMethodDetailParams,
  type KgMethodByClassParams,
  type KgEntryPointsParams,
  type KgDownstreamParams,
  type KgAffectingParams,
  type KgBridgesParams,
  type KgBridgeStatsParams,
  type KgImplementationsParams,
  type KgMybatisSqlParams,
  type KgFeignChainParams,
  type KgMqChainParams,
  type KgRootEntriesParams,
  type KgCalleesTreeParams,
  type KgDashboardParams,
  type KgDsmParams,
  type KgHotspotsParams,
  type KgDomainsParams,
  type KgServiceTopologyParams,
  type KgBlastRadiusParams,
} from './knowledgeGraphTools.js';

export {
  vectorToolDefinitions,
  handleVectorToolCall,
  VectorTools,
  VECTOR_TOOLS,
  type HybridSearchParams,
} from './vectorTools.js';

export {
  logToolDefinitions,
  handleLogToolCall,
  LogTools,
  LOG_TOOLS,
  type LogQueryParams,
  type LogAnalyzeParams,
  type LogReportParams,
} from './logTools.js';

export {
  apmToolDefinitions,
  handleApmToolCall,
  ApmTools,
  APM_TOOLS,
  type ApmStartSessionParams,
  type ApmExecuteRequestParams,
  type ApmListTracesParams,
  type ApmGetTraceParams,
  type ApmGetReportParams,
  type ApmStopSessionParams,
} from './apmTools.js';

// ============================================================================
// Aggregate
// ============================================================================

import { knowledgeGraphToolDefinitions, KG_TOOLS } from './knowledgeGraphTools.js';
import { vectorToolDefinitions, VECTOR_TOOLS } from './vectorTools.js';
import { logToolDefinitions, LOG_TOOLS } from './logTools.js';
import { apmToolDefinitions, APM_TOOLS } from './apmTools.js';

import { handleKnowledgeGraphToolCall } from './knowledgeGraphTools.js';
import { handleVectorToolCall } from './vectorTools.js';
import { handleLogToolCall } from './logTools.js';
import { handleApmToolCall } from './apmTools.js';
import { normalizePathArgs } from '../utils/pathUtils.js';

export const allToolDefinitions = [
  ...knowledgeGraphToolDefinitions,
  ...vectorToolDefinitions,
  ...logToolDefinitions,
  ...apmToolDefinitions,
];

/**
 * Route a tool call to the appropriate handler.
 * All path arguments are normalized (backslash → forward slash) before dispatch.
 */
export async function handleToolCall(
  toolName: string,
  args: Record<string, unknown>
): Promise<unknown> {
  args = normalizePathArgs(args);
  if (KG_TOOLS.includes(toolName)) {
    return handleKnowledgeGraphToolCall(toolName, args);
  }

  if (VECTOR_TOOLS.includes(toolName)) {
    return handleVectorToolCall(toolName, args);
  }

  if (LOG_TOOLS.includes(toolName)) {
    return handleLogToolCall(toolName, args);
  }

  if (APM_TOOLS.includes(toolName)) {
    return handleApmToolCall(toolName, args);
  }

  throw new Error(`Unknown tool: ${toolName}`);
}
