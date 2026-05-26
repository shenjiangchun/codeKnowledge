package com.huawei.hisi.ram.sdk;

/**
 * Declarative description of a tool exposed to Claude.
 *
 * <p>{@code inputSchema} is a JSON Schema string forwarded verbatim to the
 * Anthropic Messages API.
 */
public record ToolDefinition(String name, String description, String inputSchema) {
}
