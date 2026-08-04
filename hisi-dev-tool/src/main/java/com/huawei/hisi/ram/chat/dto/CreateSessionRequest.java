package com.huawei.hisi.ram.chat.dto;

import java.util.List;

public record CreateSessionRequest(
        List<String> projectPaths,
        String projectName,
        String initialQuestion
) {}
