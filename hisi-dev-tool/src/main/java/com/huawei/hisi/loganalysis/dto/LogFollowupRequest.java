package com.huawei.hisi.loganalysis.dto;

/**
 * Request to send a follow-up question about a log analysis report.
 */
public record LogFollowupRequest(
        long reportId,
        String message
) {}
