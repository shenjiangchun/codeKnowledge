package com.huawei.hisi.model;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 日志查询参数 DTO
 */
@Data
public class LogQueryDto {
    /**
     * 应用 ID
     */
    private String appId;

    /**
     * 开始时间
     */
    private LocalDateTime startTime;

    /**
     * 结束时间
     */
    private LocalDateTime endTime;

    /**
     * 日志级别 (ERROR/WARN/INFO)
     */
    private String logLevel;

    /**
     * 关键词
     */
    private String keyword;

    /**
     * Trace ID（可选）
     */
    private String traceId;

    /**
     * 日志内容包含的文本（用于 DSL 查询）
     */
    private String contentContains;

    /**
     * 是否只查询错误日志
     */
    private boolean errorOnly = true;

    /**
     * 分页大小
     */
    private Integer size = 100;

    /**
     * 排序字段
     */
    private String sortBy = "@timestamp";

    /**
     * 排序方向 (asc/desc)
     */
    private String sortOrder = "desc";

    /**
     * 自定义 DSL 查询（JSON 字符串，如果提供则覆盖自动构建的 DSL）
     */
    private String dslQuery;
}