package com.huawei.hisi.loganalysis.dto;

/**
 * Response after submitting a follow-up question.
 */
public record LogFollowupResponse(
        String sessionId,
        String status    // "processing", "completed", "error"
) {}
