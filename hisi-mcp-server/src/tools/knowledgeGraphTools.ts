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

// Common required property for multi-project query tools
const commonRequiredProperties = {
  projectPaths: {
    type: 'array' as const,
    items: { type: 'string' as const },
    description: '项目路径列表（必填），支持多项目同时查询',
  },
};

// Common optional properties added to every tool's inputSchema
const commonOptionalProperties = {
  projectPath: {
    type: 'string' as const,
    description: '单项目路径（兼容旧调用，优先使用 projectPaths）',
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
      '在调用任何其他 kg_* 或 hybrid_search 工具之前，必须先用此工具获取可用的项目路径列表，' +
      '然后向用户确认应该在哪些项目里搜索/查询，再用用户选定的路径作为后续工具的 projectPaths 入参。' +
      '不要自己猜测项目路径（例如把仓库根目录或工作目录直接当 projectPath），那样会查到 0 条结果。',
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
    description: '获取知识图谱服务状态，检查指定项目的服务是否正常运行。返回节点数、关系数、入口点数等统计信息。用于确认项目图谱数据是否就绪。',
    inputSchema: {
      type: 'object' as const,
      properties: {
        ...commonRequiredProperties,
        ...commonOptionalProperties,
      },
      required: ['projectPaths'],
    },
  },

  // -------------------------
  // Method Query (4 tools)
  // -------------------------
  {
    name: 'kg_method_detail',
    description: '获取方法详细信息，包括参数、返回值、注解、方法体、复杂度、异常声明等。适合在检索到方法后深入了解其实现细节。需要 nodeId（从其他 KG 工具的结果中获取）。',
    inputSchema: {
      type: 'object' as const,
      properties: {
        nodeId: {
          type: 'string',
          description: '方法节点ID',
        },
        ...commonRequiredProperties,
        ...commonOptionalProperties,
      },
      required: ['nodeId', 'projectPaths'],
    },
  },
  {
    name: 'kg_method_by_class',
    description: '获取类中的所有方法列表（全限定类名）。返回类内所有方法的签名、参数、注解等信息。用于浏览类的 API 表面。',
    inputSchema: {
      type: 'object' as const,
      properties: {
        className: {
          type: 'string',
          description: '类名（全限定名）',
        },
        ...commonRequiredProperties,
        ...commonOptionalProperties,
      },
      required: ['className', 'projectPaths'],
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
        ...commonRequiredProperties,
        ...commonOptionalProperties,
      },
      required: ['className', 'methodName', 'projectPaths'],
    },
  },
  {
    name: 'kg_callees_tree',
    description: '获取方法的完整下游调用树（含深度、循环检测），返回带层级的节点和边。比 kg_downstream 更结构化，适合生成调用链路图。需要 className + methodName。',
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
        maxDepth: {
          type: 'number',
          description: '最大追踪深度，默认10',
        },
        ...commonRequiredProperties,
        ...commonOptionalProperties,
      },
      required: ['className', 'methodName', 'projectPaths'],
    },
  },

  // -------------------------
  // Entry Points (1 tool)
  // -------------------------
  {
    name: 'kg_entry_points',
    description: '获取项目的入口点列表，如Controller、定时任务、消息监听器、Feign Client等。可按 entryType 过滤，用于了解项目的对外 API 面和入口架构。',
    inputSchema: {
      type: 'object' as const,
      properties: {
        entryType: {
          type: 'string',
          enum: ['CONTROLLER', 'SCHEDULED', 'MQ_LISTENER', 'FEIGN_CLIENT', 'ALL'],
          description: '入口点类型（可选，默认ALL）',
        },
        ...commonRequiredProperties,
        ...commonOptionalProperties,
      },
      required: ['projectPaths'],
    },
  },

  // -------------------------
  // Call Chain Analysis (4 tools)
  // -------------------------
  {
    name: 'kg_downstream',
    description: '获取节点的下游调用链，从指定节点向下游追踪调用关系（BFS）。返回层级化的节点和边，含调用类型（DIRECT/LAMBDA）和循环检测。适合问"这个方法调了什么"。需要 nodeId。',
    inputSchema: {
      type: 'object' as const,
      properties: {
        nodeId: {
          type: 'string',
          description: '节点ID',
        },
        maxDepth: {
          type: 'number',
          description: '最大追踪深度（可选，默认10）',
        },
        ...commonRequiredProperties,
        ...commonOptionalProperties,
      },
      required: ['nodeId', 'projectPaths'],
    },
  },
  {
    name: 'kg_affecting',
    description: '获取影响指定方法的上游调用链，找出哪些代码会影响到当前节点。反向追踪调用方，适合问"改了这方法会影响谁"。需要 className + methodName。',
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
        ...commonRequiredProperties,
        ...commonOptionalProperties,
      },
      required: ['className', 'methodName', 'projectPaths'],
    },
  },
  {
    name: 'kg_bridges',
    description: '获取节点的桥接点，找出连接不同模块或服务的关键节点（跨包/跨模块调用）。定位架构中的耦合点，帮助评估重构风险。需要 nodeId。',
    inputSchema: {
      type: 'object' as const,
      properties: {
        nodeId: {
          type: 'string',
          description: '节点ID',
        },
        ...commonRequiredProperties,
        ...commonOptionalProperties,
      },
      required: ['nodeId', 'projectPaths'],
    },
  },
  {
    name: 'kg_bridge_stats',
    description: '获取项目的桥接点统计信息，包括Feign、MQ、HTTP、JPA、MAPPER 等跨服务/跨层调用统计。返回各类型桥接点数量和占比，用于架构健康度评估。',
    inputSchema: {
      type: 'object' as const,
      properties: {
        ...commonRequiredProperties,
        ...commonOptionalProperties,
      },
      required: ['projectPaths'],
    },
  },

  // -------------------------
  // Interface Implementation (1 tool)
  // -------------------------
  {
    name: 'kg_implementations',
    description: '获取接口的所有实现类。输入接口全限定名，返回所有 implements 该接口的类名列表。用于多态分析和架构理解。',
    inputSchema: {
      type: 'object' as const,
      properties: {
        interfaceName: {
          type: 'string',
          description: '接口名（全限定名）',
        },
        ...commonRequiredProperties,
        ...commonOptionalProperties,
      },
      required: ['interfaceName', 'projectPaths'],
    },
  },

  // -------------------------
  // MyBatis (1 tool)
  // -------------------------
  {
    name: 'kg_mybatis_sql',
    description: '获取MyBatis SQL映射信息，包括 SELECT/INSERT/UPDATE/DELETE 语句及对应的 Mapper 接口。用于理解数据访问层的 SQL 逻辑和表结构。可指定 mapperInterface 过滤。',
    inputSchema: {
      type: 'object' as const,
      properties: {
        mapperInterface: {
          type: 'string',
          description: 'Mapper接口名（全限定名），可选，不提供则返回所有',
        },
        statementType: {
          type: 'string',
          enum: ['SELECT', 'INSERT', 'UPDATE', 'DELETE'],
          description: 'SQL语句类型过滤，可选',
        },
        ...commonRequiredProperties,
        ...commonOptionalProperties,
      },
      required: ['projectPaths'],
    },
  },

  // -------------------------
  // Cross-Service (2 tools)
  // -------------------------
  {
    name: 'kg_feign_chain',
    description: '获取Feign服务的调用链，追踪微服务间的调用关系。输入 Feign 服务名，返回服务间的 RPC 调用链。适合分析分布式系统的服务依赖。',
    inputSchema: {
      type: 'object' as const,
      properties: {
        serviceName: {
          type: 'string',
          description: 'Feign服务名',
        },
        ...commonRequiredProperties,
        ...commonOptionalProperties,
      },
      required: ['serviceName', 'projectPaths'],
    },
  },
  {
    name: 'kg_mq_chain',
    description: '获取MQ消息的调用链，追踪消息队列的生产者和消费者。输入 MQ topic 名，返回该消息的完整生产和消费链路。适合异步通信架构分析。',
    inputSchema: {
      type: 'object' as const,
      properties: {
        topic: {
          type: 'string',
          description: 'MQ主题名',
        },
        ...commonRequiredProperties,
        ...commonOptionalProperties,
      },
      required: ['topic', 'projectPaths'],
    },
  },
  // -----------------------
  // Aggregation (6 tools) — multi-perspective platform Phase 3
  // -----------------------
  {
    name: 'kg_dashboard',
    description: '获取项目架构仪表盘聚合数据：模块列表、依赖关系、KPI摘要、风险列表。一次性获取项目全景视图，适合快速了解项目结构和技术债。',
    inputSchema: {
      type: 'object' as const,
      properties: {
        ...commonRequiredProperties,
        ...commonOptionalProperties,
        language: { type: 'string', description: '编程语言过滤: java | python (可选)' },
      },
      required: ['projectPaths'],
    },
  },
  {
    name: 'kg_dsm',
    description: '获取 DSM 依赖结构矩阵，返回 N×N 模块依赖关系和循环依赖/分层违规标记。用于依赖分析和架构治理，可视化模块间的耦合关系。',
    inputSchema: {
      type: 'object' as const,
      properties: {
        ...commonRequiredProperties,
        ...commonOptionalProperties,
        language: { type: 'string', description: '语言过滤 (可选)' },
        level: { type: 'string', description: '粒度: package (默认) | build-module' },
      },
      required: ['projectPaths'],
    },
  },
  {
    name: 'kg_hotspots',
    description: '获取项目热点文件列表，按风险分降序排列（复杂度×变更频率×耦合度）。用于识别需要重构的高风险代码区域和测试优先级排序。',
    inputSchema: {
      type: 'object' as const,
      properties: {
        ...commonRequiredProperties,
        ...commonOptionalProperties,
        language: { type: 'string', description: '语言过滤 (可选)' },
        limit: { type: 'number', description: '返回数量上限 (默认 20)' },
      },
      required: ['projectPaths'],
    },
  },
  {
    name: 'kg_domains',
    description: '获取自动检测的领域/DDD边界及其跨域交互关系。返回项目的领域划分、领域间依赖和违规标记。用于理解模块化结构和架构边界。',
    inputSchema: {
      type: 'object' as const,
      properties: {
        ...commonRequiredProperties,
        ...commonOptionalProperties,
        language: { type: 'string', description: '语言过滤 (可选)' },
      },
      required: ['projectPaths'],
    },
  },
  {
    name: 'kg_service_topology',
    description: '获取微服务拓扑图：服务节点、跨服务依赖边（Feign/MQ/HTTP）、调用频率',
    inputSchema: {
      type: 'object' as const,
      properties: {
        ...commonRequiredProperties,
        ...commonOptionalProperties,
        language: { type: 'string', description: '语言过滤 (可选)' },
      },
      required: ['projectPaths'],
    },
  },
  {
    name: 'kg_blast_radius',
    description: '计算指定方法的爆炸半径：下游影响面、上游调用者、受影响的API入口点、风险摘要。用于评估代码变更的影响范围和安全风险评估。需要 nodeId。',
    inputSchema: {
      type: 'object' as const,
      properties: {
        nodeId: { type: 'string', description: '方法节点ID' },
        ...commonRequiredProperties,
        ...commonOptionalProperties,
        maxDepth: { type: 'number', description: '遍历深度 (默认 5)' },
      },
      required: ['nodeId', 'projectPaths'],
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
  projectPaths: string[];
}

export interface KgMethodDetailParams extends MultiProjectParams {
  nodeId: string;
  projectPaths: string[];
}

export interface KgMethodByClassParams extends MultiProjectParams {
  className: string;
  projectPaths: string[];
}

export interface KgEntryPointsParams extends MultiProjectParams {
  projectPaths: string[];
  entryType?: 'CONTROLLER' | 'SCHEDULED' | 'MQ_LISTENER' | 'FEIGN_CLIENT' | 'ALL';
}

export interface KgDownstreamParams extends MultiProjectParams {
  nodeId: string;
  projectPaths: string[];
  maxDepth?: number;
}

export interface KgAffectingParams extends MultiProjectParams {
  className: string;
  methodName: string;
  projectPaths: string[];
}

export interface KgBridgesParams extends MultiProjectParams {
  nodeId: string;
  projectPaths: string[];
}

export interface KgBridgeStatsParams extends MultiProjectParams {
  projectPaths: string[];
}

export interface KgImplementationsParams extends MultiProjectParams {
  interfaceName: string;
  projectPaths: string[];
}

export interface KgMybatisSqlParams extends MultiProjectParams {
  projectPaths: string[];
  mapperInterface?: string;
  statementType?: 'SELECT' | 'INSERT' | 'UPDATE' | 'DELETE';
}

export interface KgFeignChainParams extends MultiProjectParams {
  serviceName: string;
  projectPaths: string[];
}

export interface KgMqChainParams extends MultiProjectParams {
  topic: string;
  projectPaths: string[];
}

export interface KgRootEntriesParams extends MultiProjectParams {
  className: string;
  methodName: string;
  projectPaths: string[];
}

export interface KgCalleesTreeParams extends MultiProjectParams {
  className: string;
  methodName: string;
  projectPaths: string[];
  maxDepth?: number;
}

// Aggregation params (Phase 3)
export interface KgDashboardParams extends MultiProjectParams { language?: string; }
export interface KgDsmParams extends MultiProjectParams { language?: string; level?: string; }
export interface KgHotspotsParams extends MultiProjectParams { language?: string; limit?: number; }
export interface KgDomainsParams extends MultiProjectParams { language?: string; }
export interface KgServiceTopologyParams extends MultiProjectParams { language?: string; }
export interface KgBlastRadiusParams extends MultiProjectParams { nodeId: string; maxDepth?: number; }

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

  // --- Aggregation methods (Phase 3) ---
  async dashboard(params: KgDashboardParams): Promise<unknown> {
    return this.client.get('/api/v2/knowledge-graph/dashboard', this.buildQueryParams({}, params));
  }
  async dsm(params: KgDsmParams): Promise<unknown> {
    const base: Record<string, string> = {};
    if (params.level) base.level = params.level;
    return this.client.get('/api/v2/knowledge-graph/dsm', this.buildQueryParams(base, params));
  }
  async hotspots(params: KgHotspotsParams): Promise<unknown> {
    const base: Record<string, string> = {};
    if (params.limit) base.limit = String(params.limit);
    return this.client.get('/api/v2/knowledge-graph/hotspots', this.buildQueryParams(base, params));
  }
  async domains(params: KgDomainsParams): Promise<unknown> {
    return this.client.get('/api/v2/knowledge-graph/domains', this.buildQueryParams({}, params));
  }
  async serviceTopology(params: KgServiceTopologyParams): Promise<unknown> {
    return this.client.get('/api/v2/knowledge-graph/service-topology', this.buildQueryParams({}, params));
  }
  async blastRadius(params: KgBlastRadiusParams): Promise<unknown> {
    const base: Record<string, string> = {};
    if (params.maxDepth) base.maxDepth = String(params.maxDepth);
    return this.client.get(
      `/api/v2/knowledge-graph/blast-radius/${encodeURIComponent(params.nodeId)}`,
      this.buildQueryParams(base, params)
    );
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
  'kg_dashboard',
  'kg_dsm',
  'kg_hotspots',
  'kg_domains',
  'kg_service_topology',
  'kg_blast_radius',
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
    case 'kg_dashboard':
      return tools.dashboard(mergedArgs as unknown as KgDashboardParams);
    case 'kg_dsm':
      return tools.dsm(mergedArgs as unknown as KgDsmParams);
    case 'kg_hotspots':
      return tools.hotspots(mergedArgs as unknown as KgHotspotsParams);
    case 'kg_domains':
      return tools.domains(mergedArgs as unknown as KgDomainsParams);
    case 'kg_service_topology':
      return tools.serviceTopology(mergedArgs as unknown as KgServiceTopologyParams);
    case 'kg_blast_radius':
      return tools.blastRadius(mergedArgs as unknown as KgBlastRadiusParams);
    default:
      throw new Error(`Unknown knowledge graph tool: ${toolName}`);
  }
}
