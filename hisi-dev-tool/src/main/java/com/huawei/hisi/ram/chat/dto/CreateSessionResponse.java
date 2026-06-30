package com.huawei.hisi.ram.chat.dto;

public record CreateSessionResponse(
        String sessionId,
        String projectPath,
        String projectName
) {}
