package com.hisi.capture.aop;

import com.hisi.capture.context.*;
import com.hisi.capture.exception.CaptureExceptionEnricher;
import org.aspectj.lang.*;
import org.aspectj.lang.annotation.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * 兜底 2：业务方在 silent_catch 场景显式标注 @CaptureLog，
 * AOP @AfterThrowing 触发 enricher 打印。
 */
@Aspect
@Component
public class CaptureLogAspect {

    @Autowired
    private CaptureExceptionEnricher enricher;

    @AfterThrowing(pointcut = "@annotation(com.hisi.capture.annotation.CaptureLog)",
                   throwing = "ex")
    public void afterThrowing(JoinPoint jp, Throwable ex) {
        CaptureContext ctx = CaptureContextHolder.get();
        if (ctx != null) {
            enricher.enrichAndLog(ex, ctx, false);
        }
    }
}
