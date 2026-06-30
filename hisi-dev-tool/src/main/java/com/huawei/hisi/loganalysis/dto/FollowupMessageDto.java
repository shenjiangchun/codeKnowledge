package com.huawei.hisi.loganalysis.dto;

/**
 * A single message in a follow-up conversation.
 */
public record FollowupMessageDto(
        String role,      // "user" or "assistant"
        String content,
        long createdAt
) {}
