package com.hisi.capture.exception;

import com.hisi.capture.context.*;
import org.springframework.aop.interceptor.AsyncUncaughtExceptionHandler;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;

@Component
public class CaptureAsyncUncaughtExceptionHandler implements AsyncUncaughtExceptionHandler {

    @Autowired
    private CaptureExceptionEnricher enricher;

    @Override
    public void handleUncaughtException(Throwable ex, Method method, Object... params) {
        CaptureContext ctx = CaptureContextHolder.get();
        if (ctx != null) {
            enricher.enrichAndLog(ex, ctx, false);
        }
    }
}
