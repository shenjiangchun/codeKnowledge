package com.hisi.capture.exception;

import com.hisi.capture.context.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Component
public class SilentCatchDetector {

    @Autowired
    private CaptureExceptionEnricher enricher;

    /** 去重缓存：同一 entryTag + 异常 hash 5 秒内只打一次 */
    private static final ConcurrentMap<String, Long> DEDUP = new ConcurrentHashMap<>();
    private static final long DEDUP_TTL_MS = 5000L;
    private static final int MAX_DEDUP_SIZE = 10000;

    public static void detect(Span span, CaptureContext ctx) {
        if (!shouldDetect()) return;

        String key = ctx.getEntry().getEntryTag() + ":" +
                     span.getException().getClass().getName() + ":" +
                     hashStack(span.getException());
        Long last = DEDUP.get(key);
        if (last != null) {
            // 检查是否过期
            if (System.currentTimeMillis() - last < DEDUP_TTL_MS) {
                return;
            }
        }
        // 清理过大的缓存
        if (DEDUP.size() >= MAX_DEDUP_SIZE) {
            DEDUP.clear();
        }
        DEDUP.put(key, System.currentTimeMillis());

        // silent_catch：span 有异常但未冒泡（catch-return-fallback）
        // 注意：enricher 是实例方法，此处通过静态方式无法直接调用
        // 实际在 AOP 的 finally 块中会通过 Spring 容器调用
    }

    /**
     * 实例方法：由 CaptureAspect 调用，用于 enricher 注入场景
     */
    public void detectAndEnrich(Span span, CaptureContext ctx) {
        if (!shouldDetect()) return;

        String key = ctx.getEntry().getEntryTag() + ":" +
                     span.getException().getClass().getName() + ":" +
                     hashStack(span.getException());
        Long last = DEDUP.get(key);
        if (last != null && System.currentTimeMillis() - last < DEDUP_TTL_MS) {
            return;
        }
        if (DEDUP.size() >= MAX_DEDUP_SIZE) {
            DEDUP.clear();
        }
        DEDUP.put(key, System.currentTimeMillis());

        enricher.enrichAndLog(span.getException(), ctx, true);
    }

    private static boolean shouldDetect() {
        // 读配置 hisi.capture.silent-catch.enabled（决策 3 默认 true）
        String val = System.getProperty("hisi.capture.silent-catch.enabled", "true");
        return Boolean.parseBoolean(val);
    }

    private static String hashStack(Throwable ex) {
        StringBuilder sb = new StringBuilder();
        for (StackTraceElement e : ex.getStackTrace()) {
            sb.append(e.getClassName()).append(":").append(e.getLineNumber()).append("|");
        }
        return Integer.toHexString(sb.toString().hashCode());
    }
}
