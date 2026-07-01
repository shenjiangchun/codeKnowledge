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
            // 入口没装采集器（如 main 方法直调），降级
            return pjp.proceed();
        }
        if (ctx.getSpanStack().size() >= 50) {
            // 栈深保护
            return pjp.proceed();
        }

        Span span = new Span(pjp.getSignature().toLongString(), System.currentTimeMillis());
        span.setArgs(sizeLimiter.limitArgs(pjp.getArgs()));
        ctx.pushSpan(span);

        boolean escaped = false;
        try {
            Object ret = pjp.proceed();
            span.setRetVal(sizeLimiter.limitRetVal(ret));
            return ret;
        } catch (Throwable e) {
            span.setException(e);
            escaped = true;
            throw e;
        } finally {
            span.setEndMillis(System.currentTimeMillis());
            ctx.popSpan();
            // 兜底 3：silent_catch 检测
            if (span.getException() != null && !escaped && silentCatchDetector != null) {
                silentCatchDetector.detectAndEnrich(span, ctx);
            }
        }
    }
}
