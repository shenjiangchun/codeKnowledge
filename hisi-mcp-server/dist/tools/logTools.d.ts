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
import { ApiClient } from '../client/apiClient.js';
export declare const logToolDefinitions: ({
    name: string;
    description: string;
    inputSchema: {
        type: "object";
        properties: {
            keyword: {
                type: string;
                description: string;
            };
            logLevel: {
                type: string;
                enum: string[];
                description: string;
            };
            appId: {
                type: string;
                description: string;
            };
            traceId: {
                type: string;
                description: string;
            };
            contentContains: {
                type: string;
                description: string;
            };
            dslQuery: {
                type: string;
                description: string;
            };
            startTime: {
                type: string;
                description: string;
            };
            endTime: {
                type: string;
                description: string;
            };
            size: {
                type: string;
                description: string;
            };
            errorOnly: {
                type: string;
                description: string;
            };
            sortOrder: {
                type: string;
                enum: string[];
                description: string;
            };
            message?: undefined;
            stackTrace?: undefined;
            serviceName?: undefined;
            errorType?: undefined;
            reportId?: undefined;
        };
        required: never[];
    };
} | {
    name: string;
    description: string;
    inputSchema: {
        type: "object";
        properties: {
            message: {
                type: string;
                description: string;
            };
            stackTrace: {
                type: string;
                description: string;
            };
            serviceName: {
                type: string;
                description: string;
            };
            traceId: {
                type: string;
                description: string;
            };
            errorType: {
                type: string;
                description: string;
            };
            keyword?: undefined;
            logLevel?: undefined;
            appId?: undefined;
            contentContains?: undefined;
            dslQuery?: undefined;
            startTime?: undefined;
            endTime?: undefined;
            size?: undefined;
            errorOnly?: undefined;
            sortOrder?: undefined;
            reportId?: undefined;
        };
        required: never[];
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
})[];
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
export declare class LogTools {
    private client;
    constructor(client?: ApiClient);
    /**
     * 查询日志
     * POST /api/log/query
     * Maps to LogQueryDto on backend
     */
    query(params: LogQueryParams): Promise<unknown>;
    /**
     * 提交日志分析任务（异步）
     * POST /api/log/analyze
     */
    analyze(params: LogAnalyzeParams): Promise<unknown>;
    /**
     * 获取分析报告详情
     * GET /api/log/report/{id}
     */
    report(params: LogReportParams): Promise<unknown>;
    /**
     * 查询分析报告状态
     * GET /api/log/report/{id}/status
     */
    reportStatus(params: LogReportParams): Promise<unknown>;
}
export declare const LOG_TOOLS: string[];
export declare function handleLogToolCall(toolName: string, args: Record<string, unknown>): Promise<unknown>;
//# sourceMappingURL=logTools.d.ts.map