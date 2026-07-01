package com.hisi.capture.ingress;

import com.hisi.capture.context.EntryContext;
import com.hisi.capture.context.EntryType;

/**
 * SPI 扩展点：业务方自定义入口（gRPC / WebSocket / Netty 等）实现此接口，
 * 通过 META-INF/services 注册即可接入。
 *
 * MVP 不写实现，仅预留接口。
 */
public interface EntryIngressProvider {
    EntryType type();
    void setupEntry(EntryContext ctx);
    void clearEntry();
}
