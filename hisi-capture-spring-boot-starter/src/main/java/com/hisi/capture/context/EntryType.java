package com.hisi.capture.context;

public enum EntryType {
    HTTP,           // OncePerRequestFilter
    SCHEDULED,      // @Scheduled
    ASYNC,          // @Async
    FEIGN_INGRESS,  // 下游 Feign 调用进入（罕见，通常 HTTP 已覆盖）
    CUSTOM          // SPI 扩展
}
