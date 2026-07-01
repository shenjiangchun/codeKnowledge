package com.hisi.capture.exception;

import com.hisi.capture.context.*;
import com.hisi.capture.crypto.CaptureCrypto;
import com.hisi.capture.format.CaptureFormatter;
import com.hisi.capture.util.SpanTruncator;
import org.slf4j.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class CaptureExceptionEnricher {

    private static final Logger log = LoggerFactory.getLogger(CaptureExceptionEnricher.class);

    @Autowired
    private CaptureFormatter formatter;

    @Autowired
    private CaptureCrypto crypto;

    @Autowired
    private SpanTruncator spanTruncator;

    /**
     * @param silentCatch true 表示来自兜底 3 的 silent_catch 检测
     */
    public void enrichAndLog(Throwable ex, CaptureContext ctx, boolean silentCatch) {
        try {
            // N+3 = 4：保留异常抛出点 + 3 层上游
            List<Span> kept = spanTruncator.bottomN(ctx.getSpanStack(), 4);

            // 格式化 + 加密
            String plain = formatter.format(ctx.getEntry(), kept, ctx.getFeignCalls(), ex);
            String encrypted = crypto.encrypt(plain);

            // 注入 message
            String tag = ctx.getEntry().getEntryTag();
            if (silentCatch) {
                log.warn("[SilentCatch][EntryTag={}] {} \n{}",
                    tag, ex.getMessage(), encrypted, ex);
            } else {
                log.error("[EntryTag={}] {} \n{}",
                    tag, ex.getMessage(), encrypted, ex);
            }
        } catch (Exception e) {
            // 加密失败不能吞掉原始异常
            log.error("[CaptureFailed][EntryTag={}] {}",
                ctx.getEntry().getEntryTag(), ex.getMessage(), ex);
        }
    }
}
