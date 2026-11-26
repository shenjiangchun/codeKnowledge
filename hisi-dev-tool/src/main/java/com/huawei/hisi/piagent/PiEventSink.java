package com.huawei.hisi.piagent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.huawei.hisi.ram.model.AgentEvent;
import com.huawei.hisi.ram.model.EventType;
import com.huawei.hisi.ram.repository.AgentEventRepository;
import lombok.extern.slf4j.Slf4j;

import java.util.Map;
import java.util.function.Consumer;

/**
 * 把 pi 事件写进 {@code agent_event}，复用现有 SSE 推流与回放（前端零改动）。
 * 事件频率不高，不做节流；工具名与摘要作为 NODE_DELTA 消息，最终正文由调用方落 CHECKPOINT。
 */
@Slf4j
public class PiEventSink implements Consumer<Map<String, Object>> {

    private final AgentEventRepository events;
    private final ObjectMapper objectMapper;
    private final long sessionId;
    private final String nodeName;

    public PiEventSink(AgentEventRepository events, ObjectMapper objectMapper, long sessionId, String nodeName) {
        this.events = events;
        this.objectMapper = objectMapper;
        this.sessionId = sessionId;
        this.nodeName = nodeName;
    }

    @Override
    public void accept(Map<String, Object> event) {
        String type = String.valueOf(event.getOrDefault("type", ""));
        try {
            switch (type) {
                case "tool_execution_start" -> log("调用工具：" + toolName(event) + summaryOf(event.get("input")));
                case "tool_execution_end" -> log("工具返回：" + toolName(event) + summaryOf(event.get("output")));
                case "agent_settled" -> log("agent 完成：" + event.getOrDefault("reason", "settled"));
                default -> { }
            }
        } catch (Exception e) {
            log.warn("[PiAgent] 事件落库失败 type={}: {}", type, e.getMessage());
        }
    }

    private String toolName(Map<String, Object> event) {
        return String.valueOf(event.getOrDefault("name", event.getOrDefault("tool", "?")));
    }

    private String summaryOf(Object value) {
        if (value == null) {
            return "";
        }
        try {
            String json = objectMapper.writeValueAsString(value);
            if (json.length() <= 300) {
                return " " + json;
            }
            return " " + json.substring(0, 300) + "…";
        } catch (Exception e) {
            return "";
        }
    }

    private void log(String message) {
        try {
            events.append(AgentEvent.builder()
                    .sessionId(sessionId)
                    .type(EventType.NODE_DELTA)
                    .payload(objectMapper.writeValueAsString(Map.of("nodeName", nodeName, "message", message)))
                    .idempotencyKey("pi-" + sessionId + "-" + System.currentTimeMillis() + "-" + Math.abs(message.hashCode()))
                    .createdAt(System.currentTimeMillis() / 1000L)
                    .build());
        } catch (Exception e) {
            log.warn("[PiAgent] 日志落库失败: {}", e.getMessage());
        }
    }
}
