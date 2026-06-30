package com.huawei.hisi.ram.chat.dto;

public record SendMessageResponse(
        String turnId,
        String status,
        String errorMessage
) {}
