package com.huawei.hisi.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 日志分析任务响应
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AnalyzeTaskResponse {
    /**
     * 报告 ID（雪花 ID）
     */
    private Long reportId;

    /**
     * 任务状态
     */
    private String status;

    /**
     * 创建时间
     */
    private LocalDateTime createdAt;
}