package com.huawei.hisi.ram.chat.dto;

public record CreateSessionRequest(
        String projectPath,
        String projectName,
        String initialQuestion
) {}
