package com.huawei.hisi.model;

import lombok.Data;

import java.util.Map;

/**
 * 日志条目 DTO
 */
@Data
public class LogEntry {
    /**
     * 日志 ID
     */
    private Long id;

    /**
     * 时间戳
     */
    private String timestamp;

    /**
     * 日志级别
     */
    private String level;

    /**
     * 日志消息
     */
    private String message;

    /**
     * 原始日志内容
     */
    private String rawContent;

    /**
     * 错误堆栈
     */
    private String stackTrace;

    /**
     * Trace ID
     */
    private String traceId;

    /**
     * 服务名称
     */
    private String serviceName;

    /**
     * Pod 名称
     */
    private String podName;

    /**
     * 主机名
     */
    private String hostname;

    /**
     * 容器名称
     */
    private String containerName;

    /**
     * 命名空间
     */
    private String namespace;

    /**
     * 日志源/文件
     */
    private String logSource;

    /**
     * 原始字段映射（存储 API 返回的原始字段）
     */
    private Map<String, Object> rawFields;

    /**
     * 错误类型（提取自日志内容）
     */
    private String errorType;

    /**
     * 是否包含堆栈信息
     */
    private boolean hasStackTrace;

    /**
     * 日志内容行数
     */
    private int lineCount;
}