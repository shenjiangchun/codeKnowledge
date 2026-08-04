package com.huawei.hisi.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 日志分析请求
 * 前端传递完整的日志条目信息
 */
@Data
public class LogAnalyzeRequest {
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
     * 日志消息（包含完整错误信息）
     * 与 stackTrace 至少提供一个
     */
    @Size(max = 50000, message = "日志消息长度不能超过50000字符")
    private String message;

    /**
     * 错误堆栈
     * 与 message 至少提供一个
     */
    @Size(max = 100000, message = "堆栈信息长度不能超过100000字符")
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
     * 错误类型
     */
    private String errorType;

    /**
     * 用户 ID（可选，默认 sys_admin）
     */
    private String userId;

    /**
     * 项目路径（可选，用于 KG 检索增强分析）
     */
    private String projectPath;
}