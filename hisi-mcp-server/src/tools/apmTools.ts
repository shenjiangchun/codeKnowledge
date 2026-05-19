/**
 * APM Debug Tools for MCP Server
 * Provides tools for the APM debug workflow backed by ApmController
 *
 * Endpoints:
 *   POST /api/apm/launch         Start an APM debug session
 *   POST /api/apm/execute        Execute an HTTP request against the target service
 *   GET  /api/apm/spans/{id}     List captured traces and spans
 *   GET  /api/apm/trace/{id}     Get full span tree for a trace
 *   GET  /api/apm/report/{id}    Get debug execution report
 *   POST /api/apm/stop           Stop session and target process
 */

import { ApiClient, getApiClient } from '../client/apiClient.js';

// ============================================================================
// Tool Definitions
// ============================================================================

export const apmToolDefinitions = [
  {
    name: 'apm_start_session',
    description:
      'Start an APM debug session — launches a Spring Boot project with the OpenTelemetry agent attached for trace collection. Returns a sessionId to use with other APM tools.',
    inputSchema: {
      type: 'object' as const,
      properties: {
        projectPath: {
          type: 'string',
          description: 'Absolute path to the Spring Boot project root',
        },
        targetPort: {
          type: 'number',
          description: 'Port for the target app (0 = auto-assign). Optional, default 0',
        },
        serviceName: {
          type: 'string',
          description: 'OTel service name. Optional, derived from project directory if omitted',
        },
      },
      required: ['projectPath'],
    },
  },
  {
    name: 'apm_execute_request',
    description:
      'Execute an HTTP request against the target service and trigger trace collection. The session must be in READY or RUNNING state.',
    inputSchema: {
      type: 'object' as const,
      properties: {
        sessionId: {
          type: 'string',
          description: 'APM session ID from apm_start_session',
        },
        method: {
          type: 'string',
          enum: ['GET', 'POST', 'PUT', 'DELETE', 'PATCH'],
          description: 'HTTP method',
        },
        path: {
          type: 'string',
          description: 'Request path (e.g., /api/users)',
        },
        body: {
          type: 'string',
          description: 'Request body (JSON string). Optional for GET/DELETE',
        },
        headers: {
          type: 'object',
          additionalProperties: { type: 'string' },
          description: 'Request headers as key-value pairs. Optional',
        },
      },
      required: ['sessionId', 'method', 'path'],
    },
  },
  {
    name: 'apm_list_traces',
    description: 'List captured traces and spans for an APM session.',
    inputSchema: {
      type: 'object' as const,
      properties: {
        sessionId: {
          type: 'string',
          description: 'APM session ID',
        },
      },
      required: ['sessionId'],
    },
  },
  {
    name: 'apm_get_trace',
    description: 'Get the full span tree for a specific trace with KG (knowledge graph) method mapping.',
    inputSchema: {
      type: 'object' as const,
      properties: {
        traceId: {
          type: 'string',
          description: 'Trace ID',
        },
      },
      required: ['traceId'],
    },
  },
  {
    name: 'apm_get_report',
    description:
      'Get a debug execution report for an APM session, including span tree, performance hotspots, and error points.',
    inputSchema: {
      type: 'object' as const,
      properties: {
        sessionId: {
          type: 'string',
          description: 'APM session ID',
        },
      },
      required: ['sessionId'],
    },
  },
  {
    name: 'apm_stop_session',
    description: 'Stop the target process and end the APM debug session.',
    inputSchema: {
      type: 'object' as const,
      properties: {
        sessionId: {
          type: 'string',
          description: 'APM session ID to stop',
        },
      },
      required: ['sessionId'],
    },
  },
];

// ============================================================================
// Type Definitions
// ============================================================================

export interface ApmStartSessionParams {
  projectPath: string;
  targetPort?: number;
  serviceName?: string;
}

export interface ApmExecuteRequestParams {
  sessionId: string;
  method: string;
  path: string;
  body?: string;
  headers?: Record<string, string>;
}

export interface ApmListTracesParams {
  sessionId: string;
}

export interface ApmGetTraceParams {
  traceId: string;
}

export interface ApmGetReportParams {
  sessionId: string;
}

export interface ApmStopSessionParams {
  sessionId: string;
}

// ============================================================================
// ApmTools Class
// ============================================================================

export class ApmTools {
  private client: ApiClient;

  constructor(client?: ApiClient) {
    this.client = client ?? getApiClient();
  }

  /**
   * Start an APM debug session
   * POST /api/apm/launch
   */
  async startSession(params: ApmStartSessionParams): Promise<unknown> {
    const body: Record<string, unknown> = {
      projectPath: params.projectPath,
    };
    if (params.targetPort !== undefined) body.targetPort = params.targetPort;
    if (params.serviceName !== undefined) body.serviceName = params.serviceName;
    return this.client.post('/api/apm/launch', body);
  }

  /**
   * Execute an HTTP request against the target service
   * POST /api/apm/execute
   */
  async executeRequest(params: ApmExecuteRequestParams): Promise<unknown> {
    return this.client.post('/api/apm/execute', params);
  }

  /**
   * List captured traces and spans
   * GET /api/apm/spans/{sessionId}
   */
  async listTraces(params: ApmListTracesParams): Promise<unknown> {
    return this.client.get(`/api/apm/spans/${params.sessionId}`);
  }

  /**
   * Get full span tree for a trace with KG method mapping
   * GET /api/apm/trace/{traceId}
   */
  async getTrace(params: ApmGetTraceParams): Promise<unknown> {
    return this.client.get(`/api/apm/trace/${params.traceId}`);
  }

  /**
   * Get debug execution report
   * GET /api/apm/report/{sessionId}
   */
  async getReport(params: ApmGetReportParams): Promise<unknown> {
    return this.client.get(`/api/apm/report/${params.sessionId}`);
  }

  /**
   * Stop the target process and end the session
   * POST /api/apm/stop
   */
  async stopSession(params: ApmStopSessionParams): Promise<unknown> {
    return this.client.post('/api/apm/stop', { sessionId: params.sessionId });
  }
}

// ============================================================================
// Handler Function
// ============================================================================

export const APM_TOOLS = [
  'apm_start_session',
  'apm_execute_request',
  'apm_list_traces',
  'apm_get_trace',
  'apm_get_report',
  'apm_stop_session',
];

export async function handleApmToolCall(
  toolName: string,
  args: Record<string, unknown>,
): Promise<unknown> {
  const tools = new ApmTools();

  switch (toolName) {
    case 'apm_start_session':
      return tools.startSession(args as unknown as ApmStartSessionParams);
    case 'apm_execute_request':
      return tools.executeRequest(args as unknown as ApmExecuteRequestParams);
    case 'apm_list_traces':
      return tools.listTraces(args as unknown as ApmListTracesParams);
    case 'apm_get_trace':
      return tools.getTrace(args as unknown as ApmGetTraceParams);
    case 'apm_get_report':
      return tools.getReport(args as unknown as ApmGetReportParams);
    case 'apm_stop_session':
      return tools.stopSession(args as unknown as ApmStopSessionParams);
    default:
      throw new Error(`Unknown APM tool: ${toolName}`);
  }
}
