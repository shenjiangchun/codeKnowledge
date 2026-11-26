package com.huawei.hisi.ram.sdk;

/**
 * 直连模型客户端（批量/向量等非 agent 调用）用的工具定义——与 pi 的清单无关。
 * agent 流程的工具清单在 {@code com.huawei.hisi.piagent.FlowToolManifest}。
 */
public record ToolDefinition(String name, String description, String inputSchema) {
}
