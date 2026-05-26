package com.huawei.hisi.ram.sdk;

import java.util.Map;

/**
 * Server-side implementation of a tool registered with a session. Invoked by
 * the SDK whenever Claude emits a matching {@code tool_use} block.
 */
@FunctionalInterface
public interface ToolHandler {

    Map<String, Object> handle(String toolUseId, Map<String, Object> input);
}
