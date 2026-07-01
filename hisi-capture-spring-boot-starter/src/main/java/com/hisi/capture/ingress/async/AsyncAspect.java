package com.hisi.capture.ingress.async;

import com.hisi.capture.context.*;
import com.hisi.capture.util.SizeLimiter;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.*;

@Aspect
@Component
public class AsyncAspect {

    @Autowired
    private SizeLimiter sizeLimiter;

    @Around("@annotation(org.springframework.scheduling.annotation.Async)")
    public Object captureAsync(ProceedingJoinPoint pjp) throws Throwable {
        CaptureContext ctx = CaptureContextHolder.get();
        if (ctx == null) {
            // TaskDecorator 没装上，降级
            return pjp.proceed();
        }

        // 构造 @Async 入口上下文（如果上层没有 HTTP 入口，则新建；否则复用上层 entryTag）
        if (ctx.getEntry() == null) {
            String entryTag = UUID.randomUUID().toString();
            Map<String, Object> params = new HashMap<>();
            params.put("args", sizeLimiter.limitArgs(pjp.getArgs()));
            EntryContext entry = new EntryContext(
                entryTag, EntryType.ASYNC, pjp.getSignature().toShortString(),
                params, System.currentTimeMillis());
            ctx.setEntry(entry);
        }

        return pjp.proceed();
    }
}
