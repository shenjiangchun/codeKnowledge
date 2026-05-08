package com.huawei.hisi.service;

import com.huawei.hisi.model.LogAnalysisReport;
import com.huawei.hisi.model.LogAnalyzeRequest;
import com.huawei.hisi.model.LogAnalyzeResponse;
import com.huawei.hisi.model.LogEntry;

import java.util.List;

/**
 * 根因分析服务接口
 */
public interface RootCauseAnalysisService {

    /**
     * 分析单条日志（新增）
     *
     * @param request 日志分析请求，包含完整的日志信息
     * @return 分析响应
     */
    LogAnalyzeResponse analyzeSingleLog(LogAnalyzeRequest request);

    /**
     * 分析日志根因（保留旧接口）
     *
     * @param logs 日志列表
     * @return 分析报告
     */
    LogAnalysisReport analyze(List<LogEntry> logs);

    /**
     * 获取分析报告
     *
     * @param reportId 报告 ID
     * @return 分析报告
     */
    LogAnalysisReport getReport(Long reportId);
}