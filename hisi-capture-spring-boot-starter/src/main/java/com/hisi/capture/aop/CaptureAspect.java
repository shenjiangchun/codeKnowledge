package com.hisi.capture.aop;

import com.hisi.capture.context.*;
import com.hisi.capture.exception.SilentCatchDetector;
import com.hisi.capture.util.SizeLimiter;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Aspect
@Component
public class CaptureAspect {

    @Autowired
    private SizeLimiter sizeLimiter;

    @Autowired(required = false)
    private SilentCatchDetector silentCatchDetector;

    /**
     * 切所有 Service/Component/Repository 的 public 方法。
     *
     * 注意 Spring AOP 代理局限：self-invocation（this.xxx()）不走代理，无法采集。
     */
    @Around("@within(org.springframework.stereotype.Service) " +
            "|| @within(org.springframework.stereotype.Component) " +
            "|| @within(org.springframework.stereotype.Repository)")
    public Object captureSpan(ProceedingJoinPoint pjp) throws Throwable {
        CaptureContext ctx = CaptureContextHolder.get();
        if (ctx == null || ctx.getEntry() == null) {
            return pjp.proceed();
        }
        if (ctx.getSpanStack().size() >= 50) {
            return pjp.proceed();
        }

        Span span = new Span(pjp.getSignature().toLongString(), System.currentTimeMillis());
        span.setArgs(sizeLimiter.limitArgs(pjp.getArgs()));
        ctx.pushSpan(span);

        try {
            Object ret = pjp.proceed();
            span.setRetVal(sizeLimiter.limitRetVal(ret));
            return ret;
        } catch (Throwable e) {
            span.setException(e);
            // 修复：在 catch 块中检测 silent_catch，因为 finally 中 escaped 始终为 true
            // 异常已被 AOP 捕获并记录在 span 中，即使重新抛出，上游仍可能静默吞掉
            if (silentCatchDetector != null) {
                silentCatchDetector.detectAndEnrich(span, ctx);
            }
            throw e;
        } finally {
            span.setEndMillis(System.currentTimeMillis());
            ctx.popSpan();
        }
    }
}
