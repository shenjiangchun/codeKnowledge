package com.huawei.hisi.model;

import com.huawei.hisi.repository.LogAnalysisRepository.LogAnalysisReportEntity;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 报告列表响应
 *
 * @author HiAPM Plugin Team
 * @version 1.0.0
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ReportListResponse {
    /**
     * 总数量
     */
    private Integer total;

    /**
     * 当前页码
     */
    private Integer page;

    /**
     * 每页大小
     */
    private Integer pageSize;

    /**
     * 报告列表
     */
    private List<ReportSummary> list;

    /**
     * 报告摘要
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ReportSummary {
        /**
         * 报告 ID
         */
        private Long reportId;

        /**
         * 任务状态
         */
        private String status;

        /**
         * 错误类型
         */
        private String errorType;

        /**
         * 服务名
         */
        private String serviceName;

        /**
         * 出现次数（合并后）
         */
        private Integer occurrenceCount;

        /**
         * 创建时间
         */
        private LocalDateTime createdAt;

        /**
         * 更新时间
         */
        private LocalDateTime updatedAt;
    }

    /**
     * 从实体列表转换为报告摘要列表
     *
     * @param reports 报告实体列表
     * @return 报告摘要列表
     */
    public static List<ReportSummary> fromEntities(List<LogAnalysisReportEntity> reports) {
        List<ReportSummary> summaries = new ArrayList<>();
        for (LogAnalysisReportEntity report : reports) {
            summaries.add(ReportSummary.builder()
                    .reportId(report.getReportId())
                    .status(report.getStatus())
                    .errorType(report.getErrorType())
                    .serviceName(report.getServiceName())
                    .occurrenceCount(report.getOccurrenceCount())
                    .createdAt(report.getCreatedAt())
                    .updatedAt(report.getUpdatedAt())
                    .build());
        }
        return summaries;
    }
}