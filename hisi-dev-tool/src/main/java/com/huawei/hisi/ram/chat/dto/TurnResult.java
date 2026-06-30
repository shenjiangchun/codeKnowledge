package com.huawei.hisi.ram.chat.dto;

import java.util.List;

public record TurnResult(
        String turnId,
        String status,
        String finalText,
        List<String> reasoningSteps,
        String errorMessage
) {}
