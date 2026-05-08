package com.huawei.hisi.agent.model;

import lombok.Data;
import lombok.Builder;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

import jakarta.validation.constraints.NotBlank;

/**
 * 诊断请求 DTO
 *
 * @author HiAPM Plugin Team
 * @version 1.0.0
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DiagnosisRequest {

    /**
     * 项目路径（用于代码搜索）
     */
    private String projectPath;

    /**
     * 错误消息
     */
    @NotBlank(message = "错误消息不能为空")
    private String errorMessage;

    /**
     * 堆栈追踪信息
     */
    private String stackTrace;

    /**
     * 日志内容（可选）
     */
    private String logContent;

    /**
     * Trace ID（调用链追踪）
     */
    private String traceId;

    /**
     * 入口点信息
     */
    private String entryPoint;

    /**
     * 工作目录
     */
    private String workingDirectory;

    /**
     * 会话ID（支持多轮对话）
     */
    private String sessionId;
}