package com.hisi.capture.ingress.feign;

import com.hisi.capture.context.*;
import feign.RequestInterceptor;
import org.springframework.stereotype.Component;

/**
 * Feign 出向：在 header 注入 X-Hisi-Entry-Tag，下游服务收到后可关联回上游日志。
 *
 * 注意：中间 span 不跨进程复制（数据量太大），只传 entryTag 用于关联。
 */
@Component
public class CaptureFeignRequestInterceptor implements RequestInterceptor {

    @Override
    public void apply(feign.RequestTemplate template) {
        CaptureContext ctx = CaptureContextHolder.get();
        if (ctx != null && ctx.getEntry() != null) {
            template.header("X-Hisi-Entry-Tag", ctx.getEntry().getEntryTag());
        }
    }
}
