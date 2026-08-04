/**
 * Tools module - aggregates all MCP tool definitions and routes tool calls
 *
 * Tool groups:
 *   - Knowledge Graph (14 tools, prefix kg_)   → knowledgeGraphTools.ts
 *   - Hybrid Search   (1 tool)                 → vectorTools.ts
 *   - Log Query       (4 tools, prefix log_)   → logTools.ts
 *   - APM Debug       (6 tools, prefix apm_)   → apmTools.ts
 */
export { knowledgeGraphToolDefinitions, handleKnowledgeGraphToolCall, KnowledgeGraphTools, KG_TOOLS, type KgStatusParams, type KgMethodDetailParams, type KgMethodByClassParams, type KgEntryPointsParams, type KgDownstreamParams, type KgAffectingParams, type KgBridgesParams, type KgBridgeStatsParams, type KgImplementationsParams, type KgMybatisSqlParams, type KgFeignChainParams, type KgMqChainParams, type KgRootEntriesParams, type KgCalleesTreeParams, } from './knowledgeGraphTools.js';
export { vectorToolDefinitions, handleVectorToolCall, VectorTools, VECTOR_TOOLS, type HybridSearchParams, } from './vectorTools.js';
export { logToolDefinitions, handleLogToolCall, LogTools, LOG_TOOLS, type LogQueryParams, type LogAnalyzeParams, type LogReportParams, } from './logTools.js';
export { apmToolDefinitions, handleApmToolCall, ApmTools, APM_TOOLS, type ApmStartSessionParams, type ApmExecuteRequestParams, type ApmListTracesParams, type ApmGetTraceParams, type ApmGetReportParams, type ApmStopSessionParams, } from './apmTools.js';
export declare const allToolDefinitions: ({
    name: string;
    description: string;
    inputSchema: {
        type: "object";
        properties: {};
        required: never[];
    };
} | {
    name: string;
    description: string;
    inputSchema: {
        type: "object";
        properties: {
            projectPaths: {
                type: "array";
                items: {
                    type: "string";
                };
                description: string;
            };
            language: {
                type: "string";
                enum: string[];
                description: string;
            };
            projectPath: {
                type: string;
                description: string;
            };
        };
        required: string[];
    };
} | {
    name: string;
    description: string;
    inputSchema: {
        type: "object";
        properties: {
            query: {
                type: string;
                description: string;
            };
            projectPath: {
                type: string;
                description: string;
            };
            projectPaths: {
                type: string;
                items: {
                    type: string;
                };
                description: string;
            };
            limit: {
                type: string;
                description: string;
            };
            graphDepth: {
                type: string;
                description: string;
            };
            language: {
                type: string;
                enum: string[];
                description: string;
            };
        };
        required: string[];
    };
} | {
    name: string;
    description: string;
    inputSchema: {
        type: "object";
        properties: {
            reportId: {
                type: string;
                description: string;
            };
            keyword?: undefined;
            logLevel?: undefined;
            appId?: undefined;
            traceId?: undefined;
            contentContains?: undefined;
            dslQuery?: undefined;
            startTime?: undefined;
            endTime?: undefined;
            size?: undefined;
            errorOnly?: undefined;
            sortOrder?: undefined;
            message?: undefined;
            stackTrace?: undefined;
            serviceName?: undefined;
            errorType?: undefined;
        };
        required: string[];
    };
} | {
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
/**
 * Route a tool call to the appropriate handler.
 * All path arguments are normalized (backslash → forward slash) before dispatch.
 */
export declare function handleToolCall(toolName: string, args: Record<string, unknown>): Promise<unknown>;
//# sourceMappingURL=index.d.ts.map