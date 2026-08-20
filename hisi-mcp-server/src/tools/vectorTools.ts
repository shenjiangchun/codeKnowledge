/**
 * Hybrid Search Tool for MCP Server
 * Provides semantic code search backed by the Spring Boot search API.
 *
 * hybrid_search → POST /api/search/semantic/v2 (multi-query recall + weighted RRF fusion)
 */

import { ApiClient, getApiClient } from '../client/apiClient.js';

export const vectorToolDefinitions = [
  {
    name: 'hybrid_search',
    description: '三层混合检索代码方法：关键词过滤 + 向量语义匹配 + 调用链图遍历扩展（RRF融合）。' +
                 '【重要】调用本工具前必须先用 kg_list_projects 列出可选项目并和用户确认 projectPath，' +
                 '不要把仓库根目录当 projectPath，会查到 0 条。' +
                 '使用自然语言描述要找的功能，返回最相关的方法列表及其文件位置。' +
                 '示例查询：「查找处理支付回调的方法」、「用户创建逻辑」、「订单状态流转」',
    inputSchema: {
      type: 'object' as const,
      properties: {
        query: {
          type: 'string',
          description: '自然语言查询，描述要查找的功能或行为',
        },
        projectPath: {
          type: 'string',
          description: '项目根目录的绝对路径（与 projectPaths 二选一）',
        },
        projectPaths: {
          type: 'array',
          items: { type: 'string' },
          description: '项目路径列表，支持多项目同时查询',
        },
        limit: {
          type: 'number',
          description: '最多返回结果数量，默认10，建议5~20',
        },
        graphDepth: {
          type: 'number',
          description: '调用链图遍历深度，默认0。注意：多路召回（v2）路径下固定为0不做图扩展，仅查询退化为单路检索时此参数才生效',
        },
        language: {
          type: 'string',
          enum: ['java', 'python'],
          description: '编程语言过滤（不传则查询全部语言）',
        },
      },
      required: ['query'],
    },
  },
];

// ============================================================================
// Type Definitions
// ============================================================================

export interface HybridSearchParams {
  query: string;
  projectPath?: string;
  projectPaths?: string[];
  limit?: number;
  graphDepth?: number;
  language?: 'java' | 'python';
}

// ============================================================================
// VectorTools Class
// ============================================================================

export class VectorTools {
  private client: ApiClient;

  constructor(client?: ApiClient) {
    this.client = client ?? getApiClient();
  }

  /**
   * 三层混合检索（多路召回 + 加权 RRF 融合）
   * POST /api/search/semantic/v2
   */
  async hybridSearch(params: HybridSearchParams): Promise<unknown> {
    const { query, projectPath, projectPaths, limit, graphDepth, language } = params;

    const body: Record<string, unknown> = {
      query,
      projectPath: projectPath || projectPaths?.[0],
      projectPaths: projectPaths || (projectPath ? [projectPath] : undefined),
      limit: limit ?? 10,
      graphDepth: graphDepth ?? 0,
    };

    if (language) {
      body.filters = { language };
    }

    const result = (await this.client.post('/api/search/semantic/v2', body)) as {
      results?: unknown[];
      total?: number;
    } & Record<string, unknown>;

    // If 0 results, attach the list of available projects so Claude can
    // ask the user to pick the correct one and retry.
    const total = Array.isArray(result?.results) ? result.results.length : (result?.total ?? 0);
    if (!total) {
      try {
        const availableProjects = await this.client.get<string[]>(
          '/api/v2/knowledge-graph/projects',
        );
        return {
          ...result,
          _hint:
            '本次搜索 0 条结果。可能是 projectPath 不在已建图的项目里。' +
            '请用 availableProjects 中的某一个路径作为 projectPath 重新搜索，' +
            '或先调用 kg_list_projects 让用户选择。',
          availableProjects,
          requestedProjectPath: body.projectPath,
        };
      } catch {
        // ignore — return original empty result
      }
    }
    return result;
  }
}

// ============================================================================
// Handler Function
// ============================================================================

export const VECTOR_TOOLS = ['hybrid_search'];

let _vecTools: VectorTools | null = null;

export async function handleVectorToolCall(
  toolName: string,
  args: Record<string, unknown>
): Promise<unknown> {
  if (!_vecTools) _vecTools = new VectorTools();
  const tools = _vecTools!

  switch (toolName) {
    case 'hybrid_search':
      return tools.hybridSearch(args as unknown as HybridSearchParams);
    default:
      throw new Error(`Unknown vector tool: ${toolName}`);
  }
}
