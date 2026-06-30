package com.huawei.hisi.ram.chat.dto;

import com.huawei.hisi.ram.model.EventType;

public record ChatEventDto(
        long id,
        long sessionId,
        long seq,
        EventType type,
        String payload,
        long createdAt
) {}
