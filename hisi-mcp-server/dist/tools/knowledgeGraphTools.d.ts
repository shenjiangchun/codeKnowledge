/**
 * Knowledge Graph Tools for MCP Server
 * Provides 15 tools for querying knowledge graphs
 * Implements tools corresponding to the Spring Boot API
 */
import { ApiClient } from '../client/apiClient.js';
export declare const knowledgeGraphToolDefinitions: ({
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
            nodeId: {
                type: string;
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
            className: {
                type: string;
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
            className: {
                type: string;
                description: string;
            };
            methodName: {
                type: string;
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
            className: {
                type: string;
                description: string;
            };
            methodName: {
                type: string;
                description: string;
            };
            projectPath: {
                type: string;
                description: string;
            };
            maxDepth: {
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
            entryType: {
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
            nodeId: {
                type: string;
                description: string;
            };
            projectPath: {
                type: string;
                description: string;
            };
            maxDepth: {
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
            interfaceName: {
                type: string;
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
            mapperInterface: {
                type: string;
                description: string;
            };
            statementType: {
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
            serviceName: {
                type: string;
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
            topic: {
                type: string;
                description: string;
            };
            projectPath: {
                type: string;
                description: string;
            };
        };
        required: string[];
    };
})[];
interface MultiProjectParams {
    projectPath?: string;
    projectPaths?: string[];
    language?: string;
}
export interface KgStatusParams extends MultiProjectParams {
    projectPath: string;
}
export interface KgMethodDetailParams extends MultiProjectParams {
    nodeId: string;
    projectPath: string;
}
export interface KgMethodByClassParams extends MultiProjectParams {
    className: string;
    projectPath: string;
}
export interface KgEntryPointsParams extends MultiProjectParams {
    projectPath: string;
    entryType?: 'CONTROLLER' | 'SCHEDULED' | 'MQ_LISTENER' | 'FEIGN_CLIENT' | 'ALL';
}
export interface KgDownstreamParams extends MultiProjectParams {
    nodeId: string;
    projectPath: string;
    maxDepth?: number;
}
export interface KgAffectingParams extends MultiProjectParams {
    className: string;
    methodName: string;
    projectPath: string;
}
export interface KgBridgesParams extends MultiProjectParams {
    nodeId: string;
    projectPath: string;
}
export interface KgBridgeStatsParams extends MultiProjectParams {
    projectPath: string;
}
export interface KgImplementationsParams extends MultiProjectParams {
    interfaceName: string;
    projectPath: string;
}
export interface KgMybatisSqlParams extends MultiProjectParams {
    projectPath: string;
    mapperInterface?: string;
    statementType?: 'SELECT' | 'INSERT' | 'UPDATE' | 'DELETE';
}
export interface KgFeignChainParams extends MultiProjectParams {
    serviceName: string;
    projectPath: string;
}
export interface KgMqChainParams extends MultiProjectParams {
    topic: string;
    projectPath: string;
}
export interface KgRootEntriesParams extends MultiProjectParams {
    className: string;
    methodName: string;
    projectPath: string;
}
export interface KgCalleesTreeParams extends MultiProjectParams {
    className: string;
    methodName: string;
    projectPath: string;
    maxDepth?: number;
}
export declare class KnowledgeGraphTools {
    private client;
    constructor(client?: ApiClient);
    private buildQueryParams;
    listProjects(): Promise<unknown>;
    status(params: KgStatusParams): Promise<unknown>;
    methodDetail(params: KgMethodDetailParams): Promise<unknown>;
    methodByClass(params: KgMethodByClassParams): Promise<unknown>;
    entryPoints(params: KgEntryPointsParams): Promise<unknown>;
    downstream(params: KgDownstreamParams): Promise<unknown>;
    affecting(params: KgAffectingParams): Promise<unknown>;
    bridges(params: KgBridgesParams): Promise<unknown>;
    bridgeStats(params: KgBridgeStatsParams): Promise<unknown>;
    implementations(params: KgImplementationsParams): Promise<unknown>;
    mybatisSql(params: KgMybatisSqlParams): Promise<unknown>;
    feignChain(params: KgFeignChainParams): Promise<unknown>;
    mqChain(params: KgMqChainParams): Promise<unknown>;
    rootEntries(params: KgRootEntriesParams): Promise<unknown>;
    calleesTree(params: KgCalleesTreeParams): Promise<unknown>;
}
export declare const KG_TOOLS: string[];
/**
 * Handler function for MCP tool calls
 */
export declare function handleKnowledgeGraphToolCall(toolName: string, args: Record<string, unknown>): Promise<unknown>;
export {};
//# sourceMappingURL=knowledgeGraphTools.d.ts.map