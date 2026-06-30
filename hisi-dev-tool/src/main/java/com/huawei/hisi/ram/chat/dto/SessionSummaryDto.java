package com.huawei.hisi.ram.chat.dto;

public record SessionSummaryDto(
        String sessionId,
        String projectName,
        String projectPath,
        String intent,
        String status,
        long createdAt,
        long lastActivityAt,
        long messageCount
) {}
