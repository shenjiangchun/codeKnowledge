/**
 * Knowledge Graph Tools for MCP Server
 * Provides 15 tools for querying knowledge graphs
 * Implements tools corresponding to the Spring Boot API
 */

import { ApiClient, getApiClient } from '../client/apiClient.js';

// ============================================================================
// Shared Utilities
// ============================================================================

function resolveProjectParams(args: Record<string, unknown>): {
  projectPath?: string;
  projectPaths?: string[];
  language?: string;
} {
  const projectPaths = args.projectPaths as string[] | undefined;
  const projectPath = args.projectPath as string | undefined;
  const language = args.language as string | undefined;
  return {
    projectPath: projectPath || projectPaths?.[0],
    projectPaths: projectPaths || (projectPath ? [projectPath] : undefined),
    language: language || undefined,
  };
}

// Common optional properties added to every tool's inputSchema
const commonOptionalProperties = {
  projectPaths: {
    type: 'array' as const,
    items: { type: 'string' as const },
    description: '项目路径列表，支持多项目同时查询',
  },
  language: {
    type: 'string' as const,
    enum: ['java', 'python'],
    description: '编程语言过滤（不传则查询全部语言）',
  },
};

// ============================================================================
// Tool Definitions (15 tools)
// ============================================================================

export const knowledgeGraphToolDefinitions = [
  // -------------------------
  // Project Discovery (1 tool) — MUST be called first
  // -------------------------
  {
    name: 'kg_list_projects',
    description:
      '【⚠️必须最先调用】列出知识图谱中所有已建图的项目路径。' +
      '在调用任何其他 kg_* 或 hybrid_search 工具之前，必须先用此工具获取可用的 projectPath 列表，' +
      '然后向用户确认应该在哪些项目里搜索/查询，再用用户选定的路径作为后续工具的 projectPath 入参。' +
      '不要自己猜测 projectPath（例如把仓库根目录或工作目录直接当 projectPath），那样会查到 0 条结果。',
    inputSchema: {
      type: 'object' as const,
      properties: {},
      required: [],
    },
  },

  // -------------------------
  // Graph Status (1 tool)
  // -------------------------
  {
    name: 'kg_status',
    description: '获取知识图谱服务状态，检查指定项目的服务是否正常运行',
    inputSchema: {
      type: 'object' as const,
      properties: {
        projectPath: {
          type: 'string',
          description: '项目根目录的绝对路径',
        },
        ...commonOptionalProperties,
      },
      required: ['projectPath'],
    },
  },

  // -------------------------
  // Method Query (4 tools)
  // -------------------------
  {
    name: 'kg_method_detail',
    description: '获取方法详细信息，包括参数、返回值、注解等',
    inputSchema: {
      type: 'object' as const,
      properties: {
        nodeId: {
          type: 'string',
          description: '方法节点ID',
        },
        projectPath: {
          type: 'string',
          description: '项目路径',
        },
        ...commonOptionalProperties,
      },
      required: ['nodeId', 'projectPath'],
    },
  },
  {
    name: 'kg_method_by_class',
    description: '获取类中的所有方法列表',
    inputSchema: {
      type: 'object' as const,
      properties: {
        className: {
          type: 'string',
          description: '类名（全限定名）',
        },
        projectPath: {
          type: 'string',
          description: '项目路径',
        },
        ...commonOptionalProperties,
      },
      required: ['className', 'projectPath'],
    },
  },

  // -------------------------
  // Root Entry Points & Callees Tree (2 tools)
  // -------------------------
  {
    name: 'kg_root_entries',
    description: '查询指定方法的上游信息（合并接口），返回 rootEntries（根入口点：Controller/MQ/Feign/定时任务等）和 directCallers（直接调用方，含 callType/callLine）',
    inputSchema: {
      type: 'object' as const,
      properties: {
        className: {
          type: 'string',
          description: '类名（全限定名）',
        },
        methodName: {
          type: 'string',
          description: '方法名',
        },
        projectPath: {
          type: 'string',
          description: '项目路径',
        },
        ...commonOptionalProperties,
      },
      required: ['className', 'methodName', 'projectPath'],
    },
  },
  {
    name: 'kg_callees_tree',
    description: '获取方法的完整下游调用树（含深度、循环检测），返回带层级的节点和边',
    inputSchema: {
      type: 'object' as const,
      properties: {
        className: {
          type: 'string',
          description: '类名（全限定名）',
        },
        methodName: {
          type: 'string',
          description: '方法名',
        },
        projectPath: {
          type: 'string',
          description: '项目路径',
        },
        maxDepth: {
          type: 'number',
          description: '最大追踪深度，默认10',
        },
        ...commonOptionalProperties,
      },
      required: ['className', 'methodName', 'projectPath'],
    },
  },

  // -------------------------
  // Entry Points (1 tool)
  // -------------------------
  {
    name: 'kg_entry_points',
    description: '获取项目的入口点列表，如Controller、定时任务、消息监听器等',
    inputSchema: {
      type: 'object' as const,
      properties: {
        projectPath: {
          type: 'string',
          description: '项目路径',
        },
        entryType: {
          type: 'string',
          enum: ['CONTROLLER', 'SCHEDULED', 'MQ_LISTENER', 'FEIGN_CLIENT', 'ALL'],
          description: '入口点类型（可选，默认ALL）',
        },
        ...commonOptionalProperties,
      },
      required: ['projectPath'],
    },
  },

  // -------------------------
  // Call Chain Analysis (4 tools)
  // -------------------------
  {
    name: 'kg_downstream',
    description: '获取节点的下游调用链，从指定节点向下游追踪调用关系',
    inputSchema: {
      type: 'object' as const,
      properties: {
        nodeId: {
          type: 'string',
          description: '节点ID',
        },
        projectPath: {
          type: 'string',
          description: '项目路径',
        },
        maxDepth: {
          type: 'number',
          description: '最大追踪深度（可选，默认10）',
        },
        ...commonOptionalProperties,
      },
      required: ['nodeId', 'projectPath'],
    },
  },
  {
    name: 'kg_affecting',
    description: '获取影响指定方法的上游调用链，找出哪些代码会影响到当前节点',
    inputSchema: {
      type: 'object' as const,
      properties: {
        className: {
          type: 'string',
          description: '类名（全限定名）',
        },
        methodName: {
          type: 'string',
          description: '方法名',
        },
        projectPath: {
          type: 'string',
          description: '项目路径',
        },
        ...commonOptionalProperties,
      },
      required: ['className', 'methodName', 'projectPath'],
    },
  },
  {
    name: 'kg_bridges',
    description: '获取节点的桥接点，找出连接不同模块或服务的关键节点',
    inputSchema: {
      type: 'object' as const,
      properties: {
        nodeId: {
          type: 'string',
          description: '节点ID',
        },
        projectPath: {
          type: 'string',
          description: '项目路径',
        },
        ...commonOptionalProperties,
      },
      required: ['nodeId', 'projectPath'],
    },
  },
  {
    name: 'kg_bridge_stats',
    description: '获取项目的桥接点统计信息，包括Feign、MQ等跨服务调用统计',
    inputSchema: {
      type: 'object' as const,
      properties: {
        projectPath: {
          type: 'string',
          description: '项目路径',
        },
        ...commonOptionalProperties,
      },
      required: ['projectPath'],
    },
  },

  // -------------------------
  // Interface Implementation (1 tool)
  // -------------------------
  {
    name: 'kg_implementations',
    description: '获取接口的所有实现类',
    inputSchema: {
      type: 'object' as const,
      properties: {
        interfaceName: {
          type: 'string',
          description: '接口名（全限定名）',
        },
        projectPath: {
          type: 'string',
          description: '项目路径',
        },
        ...commonOptionalProperties,
      },
      required: ['interfaceName', 'projectPath'],
    },
  },

  // -------------------------
  // MyBatis (1 tool)
  // -------------------------
  {
    name: 'kg_mybatis_sql',
    description: '获取MyBatis SQL映射信息',
    inputSchema: {
      type: 'object' as const,
      properties: {
        projectPath: {
          type: 'string',
          description: '项目路径',
        },
        mapperInterface: {
          type: 'string',
          description: 'Mapper接口名（全限定名），可选，不提供则返回所有',
        },
        statementType: {
          type: 'string',
          enum: ['SELECT', 'INSERT', 'UPDATE', 'DELETE'],
          description: 'SQL语句类型过滤，可选',
        },
        ...commonOptionalProperties,
      },
      required: ['projectPath'],
    },
  },

  // -------------------------
  // Cross-Service (2 tools)
  // -------------------------
  {
    name: 'kg_feign_chain',
    description: '获取Feign服务的调用链，追踪微服务间的调用关系',
    inputSchema: {
      type: 'object' as const,
      properties: {
        serviceName: {
          type: 'string',
          description: 'Feign服务名',
        },
        projectPath: {
          type: 'string',
          description: '项目路径',
        },
        ...commonOptionalProperties,
      },
      required: ['serviceName', 'projectPath'],
    },
  },
  {
    name: 'kg_mq_chain',
    description: '获取MQ消息的调用链，追踪消息队列的生产者和消费者',
    inputSchema: {
      type: 'object' as const,
      properties: {
        topic: {
          type: 'string',
          description: 'MQ主题名',
        },
        projectPath: {
          type: 'string',
          description: '项目路径',
        },
        ...commonOptionalProperties,
      },
      required: ['topic', 'projectPath'],
    },
  },
];

// ============================================================================
// Type Definitions
// ============================================================================

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

// ============================================================================
// Knowledge Graph Tools Class
// ============================================================================

export class KnowledgeGraphTools {
  private client: ApiClient;

  constructor(client?: ApiClient) {
    this.client = client ?? getApiClient();
  }

  private buildQueryParams(
    base: Record<string, string>,
    multi: MultiProjectParams
  ): Record<string, string> {
    const params: Record<string, string> = { ...base };
    if (multi.projectPaths) {
      params.projectPaths = multi.projectPaths.join(',');
    }
    if (multi.language) {
      params.language = multi.language;
    }
    return params;
  }

  async listProjects(): Promise<unknown> {
    return this.client.get('/api/v2/knowledge-graph/projects');
  }

  async status(params: KgStatusParams): Promise<unknown> {
    return this.client.get('/api/v2/knowledge-graph/status', this.buildQueryParams(
      {},
      params
    ));
  }

  async methodDetail(params: KgMethodDetailParams): Promise<unknown> {
    return this.client.get('/api/v2/knowledge-graph/method/detail', this.buildQueryParams(
      { nodeId: params.nodeId },
      params
    ));
  }

  async methodByClass(params: KgMethodByClassParams): Promise<unknown> {
    return this.client.get('/api/v2/knowledge-graph/method/by-class', this.buildQueryParams(
      { className: params.className },
      params
    ));
  }

  async entryPoints(params: KgEntryPointsParams): Promise<unknown> {
    const base: Record<string, string> = {};
    if (params.entryType && params.entryType !== 'ALL') {
      base.entryType = params.entryType;
    }
    return this.client.get('/api/v2/knowledge-graph/entry-points', this.buildQueryParams(base, params));
  }

  async downstream(params: KgDownstreamParams): Promise<unknown> {
    const base: Record<string, string> = { nodeId: params.nodeId };
    if (params.maxDepth !== undefined) {
      base.maxDepth = String(params.maxDepth);
    }
    return this.client.get('/api/v2/knowledge-graph/call-chain/downstream', this.buildQueryParams(base, params));
  }

  async affecting(params: KgAffectingParams): Promise<unknown> {
    return this.client.get('/api/v2/knowledge-graph/call-chain/affecting', this.buildQueryParams(
      { className: params.className, methodName: params.methodName },
      params
    ));
  }

  async bridges(params: KgBridgesParams): Promise<unknown> {
    return this.client.get(`/api/v2/knowledge-graph/call-chain/${params.nodeId}/bridges`, this.buildQueryParams(
      {},
      params
    ));
  }

  async bridgeStats(params: KgBridgeStatsParams): Promise<unknown> {
    return this.client.get('/api/v2/knowledge-graph/bridge-stats', this.buildQueryParams(
      {},
      params
    ));
  }

  async implementations(params: KgImplementationsParams): Promise<unknown> {
    return this.client.get('/api/v2/knowledge-graph/implementations', this.buildQueryParams(
      { interfaceName: params.interfaceName },
      params
    ));
  }

  async mybatisSql(params: KgMybatisSqlParams): Promise<unknown> {
    const base: Record<string, string> = {};
    if (params.mapperInterface) {
      base.mapperInterface = params.mapperInterface;
    }
    if (params.statementType) {
      base.statementType = params.statementType;
    }
    return this.client.get('/api/v2/knowledge-graph/mybatis/sql', this.buildQueryParams(base, params));
  }

  async feignChain(params: KgFeignChainParams): Promise<unknown> {
    return this.client.get(
      `/api/v2/knowledge-graph/feign/${encodeURIComponent(params.serviceName)}/call-chain`,
      this.buildQueryParams({}, params)
    );
  }

  async mqChain(params: KgMqChainParams): Promise<unknown> {
    return this.client.get(
      `/api/v2/knowledge-graph/mq/${encodeURIComponent(params.topic)}/call-chain`,
      this.buildQueryParams({}, params)
    );
  }

  async rootEntries(params: KgRootEntriesParams): Promise<unknown> {
    return this.client.get('/api/v2/knowledge-graph/root-entries', this.buildQueryParams(
      { className: params.className, methodName: params.methodName },
      params
    ));
  }

  async calleesTree(params: KgCalleesTreeParams): Promise<unknown> {
    const base: Record<string, string> = {
      className: params.className,
      methodName: params.methodName,
    };
    if (params.maxDepth !== undefined) {
      base.maxDepth = String(params.maxDepth);
    }
    return this.client.get('/api/v2/knowledge-graph/callees-tree', this.buildQueryParams(base, params));
  }
}

// ============================================================================
// Handler Function
// ============================================================================

// Tool name list for routing
export const KG_TOOLS = [
  'kg_list_projects',
  'kg_status',
  'kg_method_detail',
  'kg_method_by_class',
  'kg_entry_points',
  'kg_downstream',
  'kg_affecting',
  'kg_bridges',
  'kg_bridge_stats',
  'kg_implementations',
  'kg_mybatis_sql',
  'kg_feign_chain',
  'kg_mq_chain',
  'kg_root_entries',
  'kg_callees_tree',
];

/**
 * Handler function for MCP tool calls
 */
export async function handleKnowledgeGraphToolCall(
  toolName: string,
  args: Record<string, unknown>
): Promise<unknown> {
  const tools = new KnowledgeGraphTools();
  const resolved = resolveProjectParams(args);
  const mergedArgs = { ...args, ...resolved };

  switch (toolName) {
    case 'kg_list_projects':
      return tools.listProjects();
    case 'kg_status':
      return tools.status(mergedArgs as unknown as KgStatusParams);
    case 'kg_method_detail':
      return tools.methodDetail(mergedArgs as unknown as KgMethodDetailParams);
    case 'kg_method_by_class':
      return tools.methodByClass(mergedArgs as unknown as KgMethodByClassParams);
    case 'kg_entry_points':
      return tools.entryPoints(mergedArgs as unknown as KgEntryPointsParams);
    case 'kg_downstream':
      return tools.downstream(mergedArgs as unknown as KgDownstreamParams);
    case 'kg_affecting':
      return tools.affecting(mergedArgs as unknown as KgAffectingParams);
    case 'kg_bridges':
      return tools.bridges(mergedArgs as unknown as KgBridgesParams);
    case 'kg_bridge_stats':
      return tools.bridgeStats(mergedArgs as unknown as KgBridgeStatsParams);
    case 'kg_implementations':
      return tools.implementations(mergedArgs as unknown as KgImplementationsParams);
    case 'kg_mybatis_sql':
      return tools.mybatisSql(mergedArgs as unknown as KgMybatisSqlParams);
    case 'kg_feign_chain':
      return tools.feignChain(mergedArgs as unknown as KgFeignChainParams);
    case 'kg_mq_chain':
      return tools.mqChain(mergedArgs as unknown as KgMqChainParams);
    case 'kg_root_entries':
      return tools.rootEntries(mergedArgs as unknown as KgRootEntriesParams);
    case 'kg_callees_tree':
      return tools.calleesTree(mergedArgs as unknown as KgCalleesTreeParams);
    default:
      throw new Error(`Unknown knowledge graph tool: ${toolName}`);
  }
}
