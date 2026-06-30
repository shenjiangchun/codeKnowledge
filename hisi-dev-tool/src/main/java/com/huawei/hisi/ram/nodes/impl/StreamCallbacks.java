package com.huawei.hisi.ram.nodes.impl;

import java.util.Map;

public interface StreamCallbacks {
    void onAssistantDelta(String deltaText);
    void onToolUseStart(String toolName, Map<String, Object> input);
    void onToolResult(String toolName, String resultContent);
    void onRoundComplete(int round, String stopReason);
}
