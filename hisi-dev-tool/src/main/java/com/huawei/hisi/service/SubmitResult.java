package com.huawei.hisi.service;

/**
 * 日志分析提交结果
 * 包含报告ID和是否为新创建的标志
 *
 * 用于区分：
 * - isNew=true: 新日志，需要触发异步分析
 * - isNew=false: 重复日志，已存在报告，不需要重新分析
 */
public record SubmitResult(
    Long reportId,
    boolean isNew,
    boolean isDuplicate
) {
    /**
     * 创建新报告的结果
     */
    public static SubmitResult newReport(Long reportId) {
        return new SubmitResult(reportId, true, false);
    }

    /**
     * 重复日志的结果（返回已有报告ID）
     */
    public static SubmitResult duplicate(Long reportId) {
        return new SubmitResult(reportId, false, true);
    }

    /**
     * 提交失败的结果（reportId=null）
     */
    public static SubmitResult failed() {
        return new SubmitResult(null, false, false);
    }
}