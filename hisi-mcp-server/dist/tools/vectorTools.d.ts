/**
 * Hybrid Search Tool for MCP Server
 * Provides semantic code search backed by the Spring Boot search API.
 *
 * hybrid_search → POST /api/search/semantic (keyword + vector + graph traversal, RRF fusion)
 */
import { ApiClient } from '../client/apiClient.js';
export declare const vectorToolDefinitions: {
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
}[];
export interface HybridSearchParams {
    query: string;
    projectPath?: string;
    projectPaths?: string[];
    limit?: number;
    graphDepth?: number;
    language?: 'java' | 'python';
}
export declare class VectorTools {
    private client;
    constructor(client?: ApiClient);
    /**
     * 三层混合检索
     * POST /api/search/semantic
     */
    hybridSearch(params: HybridSearchParams): Promise<unknown>;
}
export declare const VECTOR_TOOLS: string[];
export declare function handleVectorToolCall(toolName: string, args: Record<string, unknown>): Promise<unknown>;
//# sourceMappingURL=vectorTools.d.ts.map