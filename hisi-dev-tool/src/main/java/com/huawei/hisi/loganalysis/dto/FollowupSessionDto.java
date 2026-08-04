package com.huawei.hisi.loganalysis.dto;

import java.util.List;

/**
 * Full follow-up session state for reconnection/replay.
 */
public record FollowupSessionDto(
        String sessionId,
        long reportId,
        List<FollowupMessageDto> messages,
        String status,
        long createdAt,
        long updatedAt
) {}
