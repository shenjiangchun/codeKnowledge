package com.huawei.hisi.loganalysis.event;

import com.alibaba.fastjson2.JSON;
import com.huawei.hisi.loganalysis.websocket.LogAnalysisWebSocketHandler;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Emits real-time log analysis events to WebSocket subscribers.
 * Decouples DAG nodes from WebSocket transport.
 */
@Slf4j
@Component
public class LogAnalysisEventEmitter {

    private final LogAnalysisWebSocketHandler wsHandler;

    public LogAnalysisEventEmitter(LogAnalysisWebSocketHandler wsHandler) {
        this.wsHandler = wsHandler;
    }

    /**
     * Emit a node event to all WebSocket subscribers for the given reportId.
     */
    public void emit(LogNodeEvent event) {
        log.debug("[LogAnalysisEvent] type={} reportId={} node={}",
                event.type(), event.reportId(), event.nodeName());
        wsHandler.pushEvent(event.reportId(), JSON.toJSONString(event));
    }
}
