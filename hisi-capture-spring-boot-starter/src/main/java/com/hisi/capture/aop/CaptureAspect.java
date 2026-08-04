package com.hisi.capture.aop;

import com.hisi.capture.context.*;
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

    /**
     * 切所有 Service/Component/Repository 的 public 方法。
     *
     * 排除 com.hisi.capture..* 自身，避免采集器内部方法（如 SizeLimiter、SilentCatchDetector 等）
     * 被 AOP 递归采集导致栈溢出与死循环。
     *
     * 注意 Spring AOP 代理局限：self-invocation（this.xxx()）不走代理，无法采集。
     */
    @Around("(@within(org.springframework.stereotype.Service) " +
            "|| @within(org.springframework.stereotype.Component) " +
            "|| @within(org.springframework.stereotype.Repository)) " +
            "&& !within(com.hisi.capture..*)")
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
            // silent_catch 检测已迁移至 CaptureControllerAdvice / CaptureUncaughtExceptionHandler
            // 与业务侧手动 API，避免在 AOP 切面中对全部方法做重复扫描。
            throw e;
        } finally {
            span.setEndMillis(System.currentTimeMillis());
            ctx.popSpan();
        }
    }
}
