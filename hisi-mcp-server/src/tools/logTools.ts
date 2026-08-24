/**
 * Log Query Tools for MCP Server
 * Provides tools for querying logs backed by LogAnalysisController
 *
 * Endpoint: POST /api/log/query
 * Backend DTO: LogQueryDto
 *   - keyword: string         关键字搜索
 *   - logLevel: string        日志级别（ERROR/WARN/INFO）
 *   - appId: string           应用ID
 *   - traceId: string         追踪ID
 *   - contentContains: string 内容包含
 *   - dslQuery: string        原始DSL查询（优先级最高，覆盖其他条件）
 *   - startTime: LocalDateTime 开始时间（ISO格式）
 *   - endTime: LocalDateTime   结束时间（ISO格式）
 *   - size: number            返回条数，默认100
 *   - errorOnly: boolean      仅返回错误日志，默认true
 *   - sortBy: string          排序字段，默认 @timestamp
 *   - sortOrder: string       排序方向（asc/desc），默认desc
 */

import { ApiClient, getApiClient } from '../client/apiClient.js';

export const logToolDefinitions = [
  {
    name: 'log_query',
    description: '查询日志，支持关键字、日志级别、TraceId、时间范围等过滤条件。' +
                 '如需精确控制可提供 dslQuery（原始DSL，优先级最高）。' +
                 '示例：查询某TraceId的所有ERROR日志 → logLevel:"ERROR", traceId:"abc123"',
    inputSchema: {
      type: 'object' as const,
      properties: {
        keyword: {
          type: 'string',
          description: '关键字搜索，模糊匹配日志内容，可选',
        },
        logLevel: {
          type: 'string',
          enum: ['ERROR', 'WARN', 'INFO', 'DEBUG'],
          description: '日志级别过滤，可选',
        },
        appId: {
          type: 'string',
          description: '应用ID，可选',
        },
        traceId: {
          type: 'string',
          description: '分布式追踪ID，可选',
        },
        contentContains: {
          type: 'string',
          description: '日志内容精确包含的文本，可选',
        },
        dslQuery: {
          type: 'string',
          description: '原始DSL查询字符串，优先级最高，设置后覆盖其他过滤条件。可选',
        },
        startTime: {
          type: 'string',
          description: '查询开始时间，ISO格式，如 "2024-01-01T00:00:00"，可选',
        },
        endTime: {
          type: 'string',
          description: '查询结束时间，ISO格式，如 "2024-01-01T23:59:59"，可选',
        },
        size: {
          type: 'number',
          description: '返回条数，默认100，最大1000',
        },
        errorOnly: {
          type: 'boolean',
          description: '是否仅返回错误日志，默认true。设为false可查询所有级别',
        },
        sortOrder: {
          type: 'string',
          enum: ['asc', 'desc'],
          description: '按时间排序方向，默认desc（最新优先）',
        },
      },
      required: [],
    },
  },
  {
    name: 'log_analyze',
    description: '提交日志异步分析任务，使用AI分析错误日志的根因并给出修复建议。' +
                 '提交后返回reportId，可通过 log_report_status 查询进度，通过 log_report 获取分析结果',
    inputSchema: {
      type: 'object' as const,
      properties: {
        message: {
          type: 'string',
          description: '错误消息文本，与 stackTrace 至少提供一个',
        },
        stackTrace: {
          type: 'string',
          description: '异常堆栈信息，与 message 至少提供一个',
        },
        serviceName: {
          type: 'string',
          description: '发生错误的服务名，可选',
        },
        traceId: {
          type: 'string',
          description: '分布式追踪ID，可选',
        },
        errorType: {
          type: 'string',
          description: '错误类型，可选',
        },
      },
      required: [],
    },
  },
  {
    name: 'log_report',
    description: '获取日志分析报告详情，包含根因分析、修复建议和代码片段。传入 log_analyze 返回的 reportId。若报告仍在处理中，先调用 log_report_status 确认完成。',
    inputSchema: {
      type: 'object' as const,
      properties: {
        reportId: {
          type: 'number',
          description: '由 log_analyze 返回的报告ID',
        },
      },
      required: ['reportId'],
    },
  },
  {
    name: 'log_report_status',
    description: '查询日志分析报告状态（pending/processing/completed/failed）',
    inputSchema: {
      type: 'object' as const,
      properties: {
        reportId: {
          type: 'number',
          description: '由 log_analyze 返回的报告ID',
        },
      },
      required: ['reportId'],
    },
  },
];

// ============================================================================
// Type Definitions
// ============================================================================

export interface LogQueryParams {
  keyword?: string;
  logLevel?: 'ERROR' | 'WARN' | 'INFO' | 'DEBUG';
  appId?: string;
  traceId?: string;
  contentContains?: string;
  dslQuery?: string;
  startTime?: string;
  endTime?: string;
  size?: number;
  errorOnly?: boolean;
  sortOrder?: 'asc' | 'desc';
}

export interface LogAnalyzeParams {
  message?: string;
  stackTrace?: string;
  serviceName?: string;
  traceId?: string;
  errorType?: string;
}

export interface LogReportParams {
  reportId: number;
}

// ============================================================================
// LogTools Class
// ============================================================================

export class LogTools {
  private client: ApiClient;

  constructor(client?: ApiClient) {
    this.client = client ?? getApiClient();
  }

  /**
   * 查询日志
   * POST /api/log/query
   * Maps to LogQueryDto on backend
   */
  async query(params: LogQueryParams): Promise<unknown> {
    const body: Record<string, unknown> = {};

    if (params.keyword !== undefined) body.keyword = params.keyword;
    if (params.logLevel !== undefined) body.logLevel = params.logLevel;
    if (params.appId !== undefined) body.appId = params.appId;
    if (params.traceId !== undefined) body.traceId = params.traceId;
    if (params.contentContains !== undefined) body.contentContains = params.contentContains;
    if (params.dslQuery !== undefined) body.dslQuery = params.dslQuery;
    if (params.startTime !== undefined) body.startTime = params.startTime;
    if (params.endTime !== undefined) body.endTime = params.endTime;
    if (params.size !== undefined) body.size = params.size;
    if (params.errorOnly !== undefined) body.errorOnly = params.errorOnly;
    if (params.sortOrder !== undefined) body.sortOrder = params.sortOrder;

    return this.client.post('/api/log/query', body);
  }

  /**
   * 提交日志分析任务（异步）
   * POST /api/log/analyze
   */
  async analyze(params: LogAnalyzeParams): Promise<unknown> {
    const body: Record<string, unknown> = {};

    if (params.message !== undefined) body.message = params.message;
    if (params.stackTrace !== undefined) body.stackTrace = params.stackTrace;
    if (params.serviceName !== undefined) body.serviceName = params.serviceName;
    if (params.traceId !== undefined) body.traceId = params.traceId;
    if (params.errorType !== undefined) body.errorType = params.errorType;

    return this.client.post('/api/log/analyze', body);
  }

  /**
   * 获取分析报告详情
   * GET /api/log/report/{id}
   */
  async report(params: LogReportParams): Promise<unknown> {
    return this.client.get(`/api/log/report/${params.reportId}`);
  }

  /**
   * 查询分析报告状态
   * GET /api/log/report/{id}/status
   */
  async reportStatus(params: LogReportParams): Promise<unknown> {
    return this.client.get(`/api/log/report/${params.reportId}/status`);
  }
}

// ============================================================================
// Handler Function
// ============================================================================

export const LOG_TOOLS = ['log_query', 'log_analyze', 'log_report', 'log_report_status'];

let _logTools: LogTools | null = null;

export async function handleLogToolCall(
  toolName: string,
  args: Record<string, unknown>
): Promise<unknown> {
  if (!_logTools) _logTools = new LogTools();
  const tools = _logTools!

  switch (toolName) {
    case 'log_query':
      return tools.query(args as unknown as LogQueryParams);
    case 'log_analyze':
      return tools.analyze(args as unknown as LogAnalyzeParams);
    case 'log_report':
      return tools.report(args as unknown as LogReportParams);
    case 'log_report_status':
      return tools.reportStatus(args as unknown as LogReportParams);
    default:
      throw new Error(`Unknown log tool: ${toolName}`);
  }
}
