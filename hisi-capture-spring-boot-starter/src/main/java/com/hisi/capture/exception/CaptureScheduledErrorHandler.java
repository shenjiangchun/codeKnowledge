package com.hisi.capture.exception;

import com.hisi.capture.context.*;
import com.hisi.capture.util.SizeLimiter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.util.ErrorHandler;

import java.util.*;

@Component
public class CaptureScheduledErrorHandler implements ErrorHandler {

    @Autowired
    private CaptureExceptionEnricher enricher;

    @Autowired
    private SizeLimiter sizeLimiter;

    @Override
    public void handleError(Throwable t) {
        CaptureContext ctx = CaptureContextHolder.get();
        if (ctx == null) {
            // @Scheduled 没经过 Filter，需要在此构造入口上下文
            String entryTag = UUID.randomUUID().toString();
            Map<String, Object> params = new HashMap<>();
            params.put("task", t.getStackTrace()[0].getClassName() + "." +
                              t.getStackTrace()[0].getMethodName());
            EntryContext entry = new EntryContext(
                entryTag, EntryType.SCHEDULED, "scheduled:" + params.get("task"),
                params, System.currentTimeMillis());
            ctx = new CaptureContext();
            ctx.setEntry(entry);
            CaptureContextHolder.set(ctx);
        }
        enricher.enrichAndLog(t, ctx, false);
        CaptureContextHolder.clear();
    }
}
