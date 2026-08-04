package com.hisi.capture.exception;

import com.hisi.capture.context.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class CaptureUncaughtExceptionHandler implements Thread.UncaughtExceptionHandler {

    @Autowired
    private CaptureExceptionEnricher enricher;

    @Override
    public void uncaughtException(Thread t, Throwable e) {
        CaptureContext ctx = CaptureContextHolder.get();
        if (ctx != null) {
            enricher.enrichAndLog(e, ctx, false);
        } else {
            // 没有上下文，打原始异常
            java.util.logging.Logger.getLogger("CaptureUncaught")
                .severe("Uncaught exception in thread " + t.getName() + ": " + e);
        }
    }
}
