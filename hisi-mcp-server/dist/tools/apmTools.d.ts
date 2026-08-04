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
import { ApiClient } from '../client/apiClient.js';
export declare const apmToolDefinitions: ({
    name: string;
    description: string;
    inputSchema: {
        type: "object";
        properties: {
            projectPath: {
                type: string;
                description: string;
            };
            targetPort: {
                type: string;
                description: string;
            };
            serviceName: {
                type: string;
                description: string;
            };
            sessionId?: undefined;
            method?: undefined;
            path?: undefined;
            body?: undefined;
            headers?: undefined;
            traceId?: undefined;
        };
        required: string[];
    };
} | {
    name: string;
    description: string;
    inputSchema: {
        type: "object";
        properties: {
            sessionId: {
                type: string;
                description: string;
            };
            method: {
                type: string;
                enum: string[];
                description: string;
            };
            path: {
                type: string;
                description: string;
            };
            body: {
                type: string;
                description: string;
            };
            headers: {
                type: string;
                additionalProperties: {
                    type: string;
                };
                description: string;
            };
            projectPath?: undefined;
            targetPort?: undefined;
            serviceName?: undefined;
            traceId?: undefined;
        };
        required: string[];
    };
} | {
    name: string;
    description: string;
    inputSchema: {
        type: "object";
        properties: {
            sessionId: {
                type: string;
                description: string;
            };
            projectPath?: undefined;
            targetPort?: undefined;
            serviceName?: undefined;
            method?: undefined;
            path?: undefined;
            body?: undefined;
            headers?: undefined;
            traceId?: undefined;
        };
        required: string[];
    };
} | {
    name: string;
    description: string;
    inputSchema: {
        type: "object";
        properties: {
            traceId: {
                type: string;
                description: string;
            };
            projectPath?: undefined;
            targetPort?: undefined;
            serviceName?: undefined;
            sessionId?: undefined;
            method?: undefined;
            path?: undefined;
            body?: undefined;
            headers?: undefined;
        };
        required: string[];
    };
})[];
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
export declare class ApmTools {
    private client;
    constructor(client?: ApiClient);
    /**
     * Start an APM debug session
     * POST /api/apm/launch
     */
    startSession(params: ApmStartSessionParams): Promise<unknown>;
    /**
     * Execute an HTTP request against the target service
     * POST /api/apm/execute
     */
    executeRequest(params: ApmExecuteRequestParams): Promise<unknown>;
    /**
     * List captured traces and spans
     * GET /api/apm/spans/{sessionId}
     */
    listTraces(params: ApmListTracesParams): Promise<unknown>;
    /**
     * Get full span tree for a trace with KG method mapping
     * GET /api/apm/trace/{traceId}
     */
    getTrace(params: ApmGetTraceParams): Promise<unknown>;
    /**
     * Get debug execution report
     * GET /api/apm/report/{sessionId}
     */
    getReport(params: ApmGetReportParams): Promise<unknown>;
    /**
     * Stop the target process and end the session
     * POST /api/apm/stop
     */
    stopSession(params: ApmStopSessionParams): Promise<unknown>;
}
export declare const APM_TOOLS: string[];
export declare function handleApmToolCall(toolName: string, args: Record<string, unknown>): Promise<unknown>;
//# sourceMappingURL=apmTools.d.ts.map