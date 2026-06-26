package com.huawei.hisi.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 日志分析详细报告响应
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DetailedAnalysisReport {
    /**
     * 报告 ID
     */
    private Long reportId;

    /**
     * 任务状态
     */
    private String status;

    /**
     * 错误摘要
     */
    private Map<String, Object> errorSummary;

    /**
     * 根因分析
     */
    private Map<String, Object> rootCause;

    /**
     * 修复建议
     */
    private List<Map<String, Object>> fixSuggestions;

    /**
     * 相关代码段
     */
    private List<Map<String, Object>> codeSnippets;

    /**
     * 创建时间
     */
    private LocalDateTime createdAt;

    /**
     * 更新时间
     */
    private LocalDateTime updatedAt;

    /**
     * 出现次数（同类错误合并计数）
     */
    private Integer occurrenceCount;
}